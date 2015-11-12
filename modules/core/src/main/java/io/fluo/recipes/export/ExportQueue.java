/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.recipes.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.hash.Hashing;
import io.fluo.api.client.TransactionBase;
import io.fluo.api.config.FluoConfiguration;
import io.fluo.api.config.ObserverConfiguration;
import io.fluo.api.data.Bytes;
import io.fluo.recipes.common.Pirtos;
import io.fluo.recipes.common.RowRange;
import io.fluo.recipes.common.TransientRegistry;
import io.fluo.recipes.serialization.SimpleSerializer;
import org.apache.commons.configuration.Configuration;

public class ExportQueue<K, V> {

  private int numBuckets;
  private SimpleSerializer serializer;
  private String queueId;

  // usage hint : could be created once in an observers init method
  // usage hint : maybe have a queue for each type of data being exported???
  // maybe less queues are
  // more efficient though because more batching at export time??
  ExportQueue(Options opts, SimpleSerializer serializer) throws Exception {
    // TODO sanity check key type based on type params
    // TODO defer creating classes until needed.. so that its not done during Fluo init
    this.queueId = opts.queueId;
    this.numBuckets = opts.numBuckets;
    this.serializer = serializer;
  }

  public void add(TransactionBase tx, K key, V value) {
    addAll(tx, Collections.singleton(new Export<K, V>(key, value)).iterator());
  }

  public void addAll(TransactionBase tx, Iterator<Export<K, V>> exports) {

    Set<Integer> bucketsNotified = new HashSet<>();
    while (exports.hasNext()) {
      Export<K, V> export = exports.next();

      byte[] k = serializer.serialize(export.getKey());
      byte[] v = serializer.serialize(export.getValue());

      int hash = Hashing.murmur3_32().hashBytes(k).asInt();
      int bucketId = Math.abs(hash % numBuckets);

      ExportBucket bucket = new ExportBucket(tx, queueId, bucketId);
      bucket.add(tx.getStartTimestamp(), k, v);

      if (!bucketsNotified.contains(bucketId)) {
        bucket.notifyExportObserver();
        bucketsNotified.add(bucketId);
      }
    }
  }

  public static <K2, V2> ExportQueue<K2, V2> getInstance(String exportQueueId,
      Configuration appConfig) {
    Options opts = new Options(exportQueueId, appConfig);
    try {
      return new ExportQueue<K2, V2>(opts, SimpleSerializer.getInstance(appConfig));
    } catch (Exception e) {
      // TODO
      throw new RuntimeException(e);
    }
  }

  /**
   * Call this method before initializing Fluo.
   *
   * @param fluoConfig The configuration that will be used to initialize fluo.
   */
  public static Pirtos configure(FluoConfiguration fluoConfig, Options opts) {
    Configuration appConfig = fluoConfig.getAppConfiguration();
    opts.save(appConfig);

    fluoConfig.addObserver(new ObserverConfiguration(ExportObserver.class.getName())
        .setParameters(Collections.singletonMap("queueId", opts.queueId)));

    List<Bytes> splits = new ArrayList<>();

    Bytes exportRangeStart = Bytes.of(opts.queueId + ":");
    Bytes exportRangeStop = Bytes.of(opts.queueId + ";");

    splits.add(exportRangeStart);
    splits.add(exportRangeStop);

    new TransientRegistry(fluoConfig.getAppConfiguration()).addTransientRange("exportQueue."
        + opts.queueId, new RowRange(exportRangeStart, exportRangeStop));

    Pirtos pirtos = new Pirtos();
    pirtos.setSplits(splits);

    return pirtos;
  }

  public static class Options {

    private static final String PREFIX = "recipes.exportQueue.";
    private static final long DEFAULT_BUFFER_SIZE = 1 << 22;

    int numBuckets;
    private Long bufferSize;

    String keyType;
    String valueType;
    String exporterType;
    String queueId;

    Options(String queueId, Configuration appConfig) {
      this.queueId = queueId;

      this.numBuckets = appConfig.getInt(PREFIX + queueId + ".buckets");
      this.exporterType = appConfig.getString(PREFIX + queueId + ".exporter");
      this.keyType = appConfig.getString(PREFIX + queueId + ".key");
      this.valueType = appConfig.getString(PREFIX + queueId + ".val");
      this.bufferSize = appConfig.getLong(PREFIX + queueId + ".bufferSize", DEFAULT_BUFFER_SIZE);
    }

    public Options(String queueId, String exporterType, String keyType, String valueType,
        int buckets) {
      Preconditions.checkArgument(buckets > 0);

      this.queueId = queueId;
      this.numBuckets = buckets;
      this.exporterType = exporterType;
      this.keyType = keyType;
      this.valueType = valueType;
    }


    public <K, V> Options(String queueId, Class<? extends Exporter<K, V>> exporter,
        Class<K> keyType, Class<V> valueType, int buckets) {
      this(queueId, exporter.getName(), keyType.getName(), valueType.getName(), buckets);
    }

    /**
     * Sets a limit on the amount of serialized updates to read into memory. Additional memory will
     * be used to actually deserialize and process the updates. This limit does not account for
     * object overhead in java, which can be significant.
     *
     * <p>
     * The way memory read is calculated is by summing the length of serialized key and value byte
     * arrays. Once this sum exceeds the configured memory limit, no more export key values are
     * processed in the current transaction. When not everything is processed, the observer
     * processing exports will notify itself causing another transaction to continue processing
     * later.
     */
    public Options setBufferSize(long bufferSize) {
      Preconditions.checkArgument(bufferSize > 0, "Buffer size must be positive");
      this.bufferSize = bufferSize;
      return this;
    }

    long getBufferSize() {
      if (bufferSize == null) {
        return DEFAULT_BUFFER_SIZE;
      }

      return bufferSize;
    }

    void save(Configuration appConfig) {
      appConfig.setProperty(PREFIX + queueId + ".buckets", numBuckets + "");
      appConfig.setProperty(PREFIX + queueId + ".exporter", exporterType + "");
      appConfig.setProperty(PREFIX + queueId + ".key", keyType);
      appConfig.setProperty(PREFIX + queueId + ".val", valueType);

      if (bufferSize != null) {
        appConfig.setProperty(PREFIX + queueId + ".bufferSize", bufferSize);
      }
    }
  }
}