/*
 * Copyright 2015 Fluo authors (see AUTHORS)
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

package io.fluo.recipes.accumulo.cmds;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.fluo.api.client.FluoClient;
import io.fluo.api.client.FluoFactory;
import io.fluo.api.config.FluoConfiguration;
import io.fluo.recipes.accumulo.ops.TableOperations;
import io.fluo.recipes.common.RowRange;
import io.fluo.recipes.common.TransientRegistry;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompactTransient {

  // when run with fluo exec command, the applications fluo config will be injected
  @Inject
  private static FluoConfiguration fluoConfig;


  private static ScheduledExecutorService schedExecutor;

  private static Logger log = LoggerFactory.getLogger(CompactTransient.class);

  private static class CompactTask implements Runnable {

    private RowRange transientRange;
    private long requestedSleepTime;
    private double multiplier;

    public CompactTask(RowRange transientRange, long requestedSleepTime, double multiplier) {
      this.transientRange = transientRange;
      this.requestedSleepTime = requestedSleepTime;
      this.multiplier = multiplier;
    }

    @Override
    public void run() {

      try {
        long t1 = System.currentTimeMillis();
        TableOperations.compactTransient(fluoConfig, transientRange);
        long t2 = System.currentTimeMillis();

        long sleepTime = Math.max((long) (multiplier * (t2 - t1)), requestedSleepTime);

        if (requestedSleepTime > 0) {
          log.info("Compacted {} in {}ms sleeping {}ms", transientRange, t2 - t1, sleepTime);
          schedExecutor.schedule(new CompactTask(transientRange, requestedSleepTime, multiplier),
              sleepTime, TimeUnit.MILLISECONDS);
        } else {
          log.info("Compacted {} in {}ms", transientRange, t2 - t1);
        }


      } catch (Exception e) {
        log.warn("Compaction of " + transientRange + " failed ", e);
      }

    }



  }

  public static void main(String[] args) throws Exception {

    if ((args.length == 1 && args[0].startsWith("-h")) || (args.length > 2)) {
      System.out.println("Usage : " + CompactTransient.class.getName()
          + " [<interval> [<multiplier>]]");

      System.exit(-1);
    }

    int interval = 0;
    double multiplier = 3;

    if (args.length > 1) {
      interval = Integer.parseInt(args[0]);
      if (args.length == 2) {
        multiplier = Double.parseDouble(args[1]);
      }
    }

    if (interval > 0) {
      schedExecutor = Executors.newScheduledThreadPool(1);
    }

    List<RowRange> transientRanges;

    try (FluoClient client = FluoFactory.newClient(fluoConfig)) {
      Configuration appConfig = client.getAppConfiguration();

      TransientRegistry tr = new TransientRegistry(appConfig);

      transientRanges = tr.getTransientRanges();

      for (RowRange transientRange : transientRanges) {
        if (interval > 0) {
          schedExecutor.execute(new CompactTask(transientRange, interval * 1000, multiplier));
        } else {
          new CompactTask(transientRange, 0, 0).run();
        }
      }
    }

    if (interval > 0) {
      while (true) {
        Thread.sleep(10000);
      }
    }
  }
}
