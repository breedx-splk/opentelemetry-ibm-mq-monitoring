/*
 * Copyright Splunk Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.splunk.ibm.mq.opentelemetry;

import com.splunk.ibm.mq.WMQMonitor;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("Usage: Main <config-file>");
      System.exit(1);
    }

    try {
      Main.class.getClassLoader().loadClass("com.ibm.mq.headers.MQDataException");
    } catch (ClassNotFoundException e) {
      System.err.println("IBM MQ jar is missing from classpath.");
      System.exit(1);
    }

    String configFile = args[0];

    ConfigWrapper config = ConfigWrapper.parse(configFile);

    Thread.UncaughtExceptionHandler handler =
        (t, e) -> logger.error("Unhandled exception in thread pool", e);
    logger.debug("Initializing thread pool with {} threads", config.getNumberOfThreads());
    ScheduledExecutorService service =
        Executors.newScheduledThreadPool(
            config.getNumberOfThreads(),
            r -> {
              Thread thread = new Thread(r);
              thread.setUncaughtExceptionHandler(handler);
              return thread;
            });

    Config.configureSecurity(config);
    Config.setUpSSLConnection(config.getSslConnection());

    run(config, service);
  }

  public static void run(ConfigWrapper config, final ScheduledExecutorService service) {

    AutoConfiguredOpenTelemetrySdk sdk =
        AutoConfiguredOpenTelemetrySdk.builder()
            .addMeterProviderCustomizer(
                (builder, configProps) -> builder.setResource(Resource.empty()))
            .build();

    OpenTelemetrySdk otel = sdk.getOpenTelemetrySdk();

    run(config, service, otel);
  }

  @VisibleForTesting
  public static void run(
      ConfigWrapper config, ScheduledExecutorService service, OpenTelemetry otel) {
    MeterProvider meterProvider = otel.getMeterProvider();

    Runtime.getRuntime().addShutdownHook(new Thread(service::shutdown));
    WMQMonitor monitor = new WMQMonitor(config, service, meterProvider.get("websphere/mq"));
    service.scheduleAtFixedRate(
        monitor::run,
        config.getTaskInitialDelaySeconds(),
        config.getTaskDelaySeconds(),
        TimeUnit.SECONDS);
  }
}
