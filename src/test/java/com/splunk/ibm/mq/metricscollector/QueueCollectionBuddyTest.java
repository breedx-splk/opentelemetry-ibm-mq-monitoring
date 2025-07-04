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
package com.splunk.ibm.mq.metricscollector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.CMQCFC;
import com.ibm.mq.headers.pcf.PCFMessage;
import com.ibm.mq.headers.pcf.PCFMessageAgent;
import com.splunk.ibm.mq.config.QueueManager;
import com.splunk.ibm.mq.metrics.MetricsConfig;
import com.splunk.ibm.mq.opentelemetry.ConfigWrapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class QueueCollectionBuddyTest {
  @RegisterExtension
  static final OpenTelemetryExtension otelTesting = OpenTelemetryExtension.create();

  QueueCollectionBuddy classUnderTest;
  QueueManager queueManager;
  MetricsCollectorContext collectorContext;
  Meter meter;
  @Mock private PCFMessageAgent pcfMessageAgent;

  @BeforeEach
  void setup() throws Exception {
    ConfigWrapper config = ConfigWrapper.parse("src/test/resources/conf/config.yml");
    ObjectMapper mapper = new ObjectMapper();
    queueManager = mapper.convertValue(config.getQueueManagers().get(0), QueueManager.class);
    meter = otelTesting.getOpenTelemetry().getMeter("opentelemetry.io/mq");
    collectorContext =
        new MetricsCollectorContext(queueManager, pcfMessageAgent, null, new MetricsConfig(config));
  }

  @Test
  void testProcessPCFRequestAndPublishQMetricsForInquireQStatusCmd() throws Exception {
    QueueCollectorSharedState sharedState = new QueueCollectorSharedState();
    sharedState.putQueueType("AMQ.5AF1608820C7D76E", "local-transmission");
    sharedState.putQueueType("DEV.DEAD.LETTER.QUEUE", "local-transmission");
    sharedState.putQueueType("DEV.QUEUE.1", "local-transmission");
    PCFMessage request = createPCFRequestForInquireQStatusCmd();
    when(pcfMessageAgent.send(request)).thenReturn(createPCFResponseForInquireQStatusCmd());

    classUnderTest = new QueueCollectionBuddy(meter, sharedState);
    classUnderTest.processPCFRequestAndPublishQMetrics(
        collectorContext, request, "*", InquireQStatusCmdCollector.ATTRIBUTES);

    Map<String, Map<String, Long>> expectedValues =
        new HashMap<String, Map<String, Long>>() {
          {
            put(
                "DEV.DEAD.LETTER.QUEUE",
                new HashMap<String, Long>() {
                  {
                    put("mq.oldest.msg.age", -1L);
                    put("mq.uncommitted.messages", 0L);
                    put("mq.onqtime.1", -1L);
                    put("mq.onqtime.2", -1L);
                    put("mq.queue.depth", 0L);
                  }
                });
            put(
                "DEV.QUEUE.1",
                new HashMap<String, Long>() {
                  {
                    put("mq.oldest.msg.age", -1L);
                    put("mq.uncommitted.messages", 10L);
                    put("mq.onqtime.1", -1L);
                    put("mq.onqtime.2", -1L);
                    put("mq.queue.depth", 1L);
                  }
                });
          }
        };

    for (MetricData metric : otelTesting.getMetrics()) {
      for (LongPointData d : metric.getLongGaugeData().getPoints()) {
        String queueName = d.getAttributes().get(AttributeKey.stringKey("queue.name"));
        Long expectedValue = expectedValues.get(queueName).remove(metric.getName());
        assertThat(d.getValue()).isEqualTo(expectedValue);
      }
    }

    for (Map<String, Long> metrics : expectedValues.values()) {
      assertThat(metrics).isEmpty();
    }
  }

  @Test
  void testProcessPCFRequestAndPublishQMetricsForInquireQCmd() throws Exception {
    PCFMessage request = createPCFRequestForInquireQCmd();
    when(pcfMessageAgent.send(request)).thenReturn(createPCFResponseForInquireQCmd());
    classUnderTest = new QueueCollectionBuddy(meter, new QueueCollectorSharedState());
    classUnderTest.processPCFRequestAndPublishQMetrics(
        collectorContext, request, "*", InquireQCmdCollector.ATTRIBUTES);

    Map<String, Map<String, Long>> expectedValues =
        new HashMap<String, Map<String, Long>>() {
          {
            put(
                "DEV.DEAD.LETTER.QUEUE",
                new HashMap<String, Long>() {
                  {
                    put("mq.queue.depth", 2L);
                    put("mq.max.queue.depth", 5000L);
                    put("mq.open.input.count", 2L);
                    put("mq.open.output.count", 2L);
                  }
                });
            put(
                "DEV.QUEUE.1",
                new HashMap<String, Long>() {
                  {
                    put("mq.queue.depth", 3L);
                    put("mq.max.queue.depth", 5000L);
                    put("mq.open.input.count", 3L);
                    put("mq.open.output.count", 3L);
                  }
                });
          }
        };

    for (MetricData metric : otelTesting.getMetrics()) {
      for (LongPointData d : metric.getLongGaugeData().getPoints()) {
        String queueName = d.getAttributes().get(AttributeKey.stringKey("queue.name"));
        Long expectedValue = expectedValues.get(queueName).remove(metric.getName());
        assertThat(d.getValue()).isEqualTo(expectedValue);
      }
    }

    for (Map<String, Long> metrics : expectedValues.values()) {
      assertThat(metrics).isEmpty();
    }
  }

  @Test
  void testProcessPCFRequestAndPublishQMetricsForResetQStatsCmd() throws Exception {
    QueueCollectorSharedState sharedState = new QueueCollectorSharedState();
    sharedState.putQueueType("AMQ.5AF1608820C7D76E", "local-transmission");
    sharedState.putQueueType("DEV.DEAD.LETTER.QUEUE", "local-transmission");
    sharedState.putQueueType("DEV.QUEUE.1", "local-transmission");
    PCFMessage request = createPCFRequestForResetQStatsCmd();
    when(pcfMessageAgent.send(request)).thenReturn(createPCFResponseForResetQStatsCmd());
    classUnderTest = new QueueCollectionBuddy(meter, sharedState);
    classUnderTest.processPCFRequestAndPublishQMetrics(
        collectorContext, request, "*", ResetQStatsCmdCollector.ATTRIBUTES);

    for (MetricData metric : otelTesting.getMetrics()) {
      Iterator<LongPointData> iterator = metric.getLongGaugeData().getPoints().iterator();
      if (metric.getName().equals("mq.high.queue.depth")) {
        assertThat(iterator.next().getValue()).isEqualTo(10);
      } else if (metric.getName().equals("mq.message.deq.count")) {
        assertThat(iterator.next().getValue()).isEqualTo(0);
      } else if (metric.getName().equals("mq.message.enq.count")) {
        assertThat(iterator.next().getValue()).isEqualTo(3);
      }
    }
  }

  /*
      PCFMessage:
      MQCFH [type: 1, strucLength: 36, version: 1, command: 41 (MQCMD_INQUIRE_Q_STATUS), msgSeqNumber: 1, control: 1, compCode: 0, reason: 0, parameterCount: 2]
      MQCFST [type: 4, strucLength: 24, parameter: 2016 (MQCA_Q_NAME), codedCharSetId: 0, stringLength: 1, string: *]
      MQCFIL [type: 5, strucLength: 32, parameter: 1026 (MQIACF_Q_STATUS_ATTRS), count: 4, values: {2016, 1226, 1227, 1027}]
  */
  private PCFMessage createPCFRequestForInquireQStatusCmd() {
    PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_Q_STATUS);
    request.addParameter(CMQC.MQCA_Q_NAME, "*");
    request.addParameter(CMQCFC.MQIACF_Q_STATUS_ATTRS, new int[] {2016, 1226, 1227, 1027});
    return request;
  }

  /*
      0 = {PCFMessage@6026} "PCFMessage:
      MQCFH [type: 2, strucLength: 36, version: 2, command: 41 (MQCMD_INQUIRE_Q_STATUS), msgSeqNumber: 1, control: 0, compCode: 0, reason: 0, parameterCount: 6]
      MQCFST [type: 4, strucLength: 68, parameter: 2016 (MQCA_Q_NAME), codedCharSetId: 819, stringLength: 48, string: AMQ.5AF1608820C7D76E                            ]
      MQCFIN [type: 3, strucLength: 16, parameter: 1103 (MQIACF_Q_STATUS_TYPE), value: 1105]
      MQCFIN [type: 3, strucLength: 16, parameter: 3 (MQIA_CURRENT_Q_DEPTH), value: 12]
      MQCFIN [type: 3, strucLength: 16, parameter: 1227 (MQIACF_OLDEST_MSG_AGE), value: -1]
      MQCFIL [type: 5, strucLength: 24, parameter: 1226 (MQIACF_Q_TIME_INDICATOR), count: 2, values: {-1, -1}]
      MQCFIN [type: 3, strucLength: 16, parameter: 1027 (MQIACF_UNCOMMITTED_MSGS), value: 0]"

      1 = {PCFMessage@6029} "PCFMessage:
      MQCFH [type: 2, strucLength: 36, version: 2, command: 41 (MQCMD_INQUIRE_Q_STATUS), msgSeqNumber: 2, control: 0, compCode: 0, reason: 0, parameterCount: 6]
      MQCFST [type: 4, strucLength: 68, parameter: 2016 (MQCA_Q_NAME), codedCharSetId: 819, stringLength: 48, string: DEV.DEAD.LETTER.QUEUE                           ]
      MQCFIN [type: 3, strucLength: 16, parameter: 1103 (MQIACF_Q_STATUS_TYPE), value: 1105]
      MQCFIN [type: 3, strucLength: 16, parameter: 3 (MQIA_CURRENT_Q_DEPTH), value: 0]
      MQCFIN [type: 3, strucLength: 16, parameter: 1227 (MQIACF_OLDEST_MSG_AGE), value: -1]
      MQCFIL [type: 5, strucLength: 24, parameter: 1226 (MQIACF_Q_TIME_INDICATOR), count: 2, values: {-1, -1}]
      MQCFIN [type: 3, strucLength: 16, parameter: 1027 (MQIACF_UNCOMMITTED_MSGS), value: 0]"

      2 = {PCFMessage@6030} "PCFMessage:
      MQCFH [type: 2, strucLength: 36, version: 2, command: 41 (MQCMD_INQUIRE_Q_STATUS), msgSeqNumber: 3, control: 0, compCode: 0, reason: 0, parameterCount: 6]
      MQCFST [type: 4, strucLength: 68, parameter: 2016 (MQCA_Q_NAME), codedCharSetId: 819, stringLength: 48, string: DEV.QUEUE.1                                     ]
      MQCFIN [type: 3, strucLength: 16, parameter: 1103 (MQIACF_Q_STATUS_TYPE), value: 1105]
      MQCFIN [type: 3, strucLength: 16, parameter: 3 (MQIA_CURRENT_Q_DEPTH), value: 1]
      MQCFIN [type: 3, strucLength: 16, parameter: 1227 (MQIACF_OLDEST_MSG_AGE), value: -1]
      MQCFIL [type: 5, strucLength: 24, parameter: 1226 (MQIACF_Q_TIME_INDICATOR), count: 2, values: {-1, -1}]
      MQCFIN [type: 3, strucLength: 16, parameter: 1027 (MQIACF_UNCOMMITTED_MSGS), value: 0]"
  */
  private PCFMessage[] createPCFResponseForInquireQStatusCmd() {
    PCFMessage response1 = new PCFMessage(2, CMQCFC.MQCMD_INQUIRE_Q_STATUS, 1, false);
    response1.addParameter(CMQC.MQCA_Q_NAME, "AMQ.5AF1608820C7D76E");
    response1.addParameter(CMQCFC.MQIACF_Q_STATUS_TYPE, 1105);
    response1.addParameter(CMQC.MQIA_CURRENT_Q_DEPTH, 12);
    response1.addParameter(CMQCFC.MQIACF_OLDEST_MSG_AGE, -1);
    response1.addParameter(CMQCFC.MQIACF_Q_TIME_INDICATOR, new int[] {-1, -1});
    response1.addParameter(CMQCFC.MQIACF_UNCOMMITTED_MSGS, 0);

    PCFMessage response2 = new PCFMessage(2, CMQCFC.MQCMD_INQUIRE_Q_STATUS, 2, false);
    response2.addParameter(CMQC.MQCA_Q_NAME, "DEV.DEAD.LETTER.QUEUE");
    response2.addParameter(CMQCFC.MQIACF_Q_STATUS_TYPE, 1105);
    response2.addParameter(CMQC.MQIA_CURRENT_Q_DEPTH, 0);
    response2.addParameter(CMQCFC.MQIACF_OLDEST_MSG_AGE, -1);
    response2.addParameter(CMQCFC.MQIACF_Q_TIME_INDICATOR, new int[] {-1, -1});
    response2.addParameter(CMQCFC.MQIACF_UNCOMMITTED_MSGS, 0);

    PCFMessage response3 = new PCFMessage(2, CMQCFC.MQCMD_INQUIRE_Q_STATUS, 1, false);
    response3.addParameter(CMQC.MQCA_Q_NAME, "DEV.QUEUE.1");
    response3.addParameter(CMQCFC.MQIACF_Q_STATUS_TYPE, 1105);
    response3.addParameter(CMQC.MQIA_CURRENT_Q_DEPTH, 1);
    response3.addParameter(CMQCFC.MQIACF_OLDEST_MSG_AGE, -1);
    response3.addParameter(CMQCFC.MQIACF_Q_TIME_INDICATOR, new int[] {-1, -1});
    response3.addParameter(CMQCFC.MQIACF_UNCOMMITTED_MSGS, 10);

    return new PCFMessage[] {response1, response2, response3};
  }

  /*
     PCFMessage:
     MQCFH [type: 1, strucLength: 36, version: 1, command: 13 (MQCMD_INQUIRE_Q), msgSeqNumber: 1, control: 1, compCode: 0, reason: 0, parameterCount: 3]
     MQCFST [type: 4, strucLength: 24, parameter: 2016 (MQCA_Q_NAME), codedCharSetId: 0, stringLength: 1, string: *]
     MQCFIN [type: 3, strucLength: 16, parameter: 20 (MQIA_Q_TYPE), value: 1001]
     MQCFIL [type: 5, strucLength: 36, parameter: 1002 (MQIACF_Q_ATTRS), count: 5, values: {2016, 15, 3, 17, 18}]
  */
  private PCFMessage createPCFRequestForInquireQCmd() {
    PCFMessage request = new PCFMessage(CMQCFC.MQCMD_INQUIRE_Q);
    request.addParameter(CMQC.MQCA_Q_NAME, "*");
    request.addParameter(CMQC.MQIA_Q_TYPE, CMQC.MQQT_ALL);
    request.addParameter(CMQCFC.MQIACF_Q_ATTRS, new int[] {2016, 15, 3, 17, 18});
    return request;
  }

  /*
     0 = {PCFMessage@6059} "PCFMessage:
     MQCFH [type: 2, strucLength: 36, version: 1, command: 13 (MQCMD_INQUIRE_Q), msgSeqNumber: 1, control: 0, compCode: 0, reason: 0, parameterCount: 6]
     MQCFST [type: 4, strucLength: 68, parameter: 2016 (MQCA_Q_NAME), codedCharSetId: 819, stringLength: 48, string: AMQ.5AF1608820C76D80                            ]
     MQCFIN [type: 3, strucLength: 16, parameter: 20 (MQIA_Q_TYPE), value: 1]
     MQCFIN [type: 3, strucLength: 16, parameter: 3 (MQIA_CURRENT_Q_DEPTH), value: 0]
     MQCFIN [type: 3, strucLength: 16, parameter: 17 (MQIA_OPEN_INPUT_COUNT), value: 1]
     MQCFIN [type: 3, strucLength: 16, parameter: 15 (MQIA_MAX_Q_DEPTH), value: 5000]
     MQCFIN [type: 3, strucLength: 16, parameter: 18 (MQIA_OPEN_OUTPUT_COUNT), value: 1]"

     1 = {PCFMessage@6060} "PCFMessage:
     MQCFH [type: 2, strucLength: 36, version: 1, command: 13 (MQCMD_INQUIRE_Q), msgSeqNumber: 2, control: 0, compCode: 0, reason: 0, parameterCount: 6]
     MQCFST [type: 4, strucLength: 68, parameter: 2016 (MQCA_Q_NAME), codedCharSetId: 819, stringLength: 48, string: DEV.DEAD.LETTER.QUEUE                           ]
     MQCFIN [type: 3, strucLength: 16, parameter: 20 (MQIA_Q_TYPE), value: 1]
     MQCFIN [type: 3, strucLength: 16, parameter: 3 (MQIA_CURRENT_Q_DEPTH), value: 0]
     MQCFIN [type: 3, strucLength: 16, parameter: 17 (MQIA_OPEN_INPUT_COUNT), value: 0]
     MQCFIN [type: 3, strucLength: 16, parameter: 15 (MQIA_MAX_Q_DEPTH), value: 5000]
     MQCFIN [type: 3, strucLength: 16, parameter: 18 (MQIA_OPEN_OUTPUT_COUNT), value: 0]"

     2 = {PCFMessage@6061} "PCFMessage:
     MQCFH [type: 2, strucLength: 36, version: 1, command: 13 (MQCMD_INQUIRE_Q), msgSeqNumber: 3, control: 0, compCode: 0, reason: 0, parameterCount: 6]
     MQCFST [type: 4, strucLength: 68, parameter: 2016 (MQCA_Q_NAME), codedCharSetId: 819, stringLength: 48, string: DEV.QUEUE.1                                     ]
     MQCFIN [type: 3, strucLength: 16, parameter: 20 (MQIA_Q_TYPE), value: 1]
     MQCFIN [type: 3, strucLength: 16, parameter: 3 (MQIA_CURRENT_Q_DEPTH), value: 0]
     MQCFIN [type: 3, strucLength: 16, parameter: 17 (MQIA_OPEN_INPUT_COUNT), value: 0]
     MQCFIN [type: 3, strucLength: 16, parameter: 15 (MQIA_MAX_Q_DEPTH), value: 5000]
     MQCFIN [type: 3, strucLength: 16, parameter: 18 (MQIA_OPEN_OUTPUT_COUNT), value: 0]"
  */

  private PCFMessage[] createPCFResponseForInquireQCmd() {
    PCFMessage response1 = new PCFMessage(2, CMQCFC.MQCMD_INQUIRE_Q, 1, false);
    response1.addParameter(CMQC.MQCA_Q_NAME, "AMQ.5AF1608820C76D80");
    response1.addParameter(CMQC.MQIA_Q_TYPE, 1);
    response1.addParameter(CMQC.MQIA_CURRENT_Q_DEPTH, 1);
    response1.addParameter(CMQC.MQIA_OPEN_INPUT_COUNT, 1);
    response1.addParameter(CMQC.MQIA_MAX_Q_DEPTH, 5000);
    response1.addParameter(CMQC.MQIA_OPEN_OUTPUT_COUNT, 1);
    response1.addParameter(CMQC.MQIA_USAGE, CMQC.MQUS_NORMAL);

    PCFMessage response2 = new PCFMessage(2, CMQCFC.MQCMD_INQUIRE_Q, 2, false);
    response2.addParameter(CMQC.MQCA_Q_NAME, "DEV.DEAD.LETTER.QUEUE");
    response2.addParameter(CMQC.MQIA_Q_TYPE, 1);
    response2.addParameter(CMQC.MQIA_CURRENT_Q_DEPTH, 2);
    response2.addParameter(CMQC.MQIA_OPEN_INPUT_COUNT, 2);
    response2.addParameter(CMQC.MQIA_MAX_Q_DEPTH, 5000);
    response2.addParameter(CMQC.MQIA_OPEN_OUTPUT_COUNT, 2);
    response2.addParameter(CMQC.MQIA_USAGE, CMQC.MQUS_TRANSMISSION);

    PCFMessage response3 = new PCFMessage(2, CMQCFC.MQCMD_INQUIRE_Q, 3, false);
    response3.addParameter(CMQC.MQCA_Q_NAME, "DEV.QUEUE.1");
    response3.addParameter(CMQC.MQIA_Q_TYPE, 1);
    response3.addParameter(CMQC.MQIA_CURRENT_Q_DEPTH, 3);
    response3.addParameter(CMQC.MQIA_OPEN_INPUT_COUNT, 3);
    response3.addParameter(CMQC.MQIA_MAX_Q_DEPTH, 5000);
    response3.addParameter(CMQC.MQIA_OPEN_OUTPUT_COUNT, 3);
    response3.addParameter(CMQC.MQIA_USAGE, CMQC.MQUS_TRANSMISSION);

    return new PCFMessage[] {response1, response2, response3};
  }

  /*
     PCFMessage:
     MQCFH [type: 1, strucLength: 36, version: 1, command: 17 (MQCMD_RESET_Q_STATS), msgSeqNumber: 1, control: 1, compCode: 0, reason: 0, parameterCount: 1]
     MQCFST [type: 4, strucLength: 24, parameter: 2016 (MQCA_Q_NAME), codedCharSetId: 0, stringLength: 1, string: *]
  */
  private PCFMessage createPCFRequestForResetQStatsCmd() {
    PCFMessage request = new PCFMessage(CMQCFC.MQCMD_RESET_Q_STATS);
    request.addParameter(CMQC.MQCA_Q_NAME, "*");
    return request;
  }

  /*
     0 = {PCFMessage@6144} "PCFMessage:
     MQCFH [type: 2, strucLength: 36, version: 1, command: 17 (MQCMD_RESET_Q_STATS), msgSeqNumber: 1, control: 0, compCode: 0, reason: 0, parameterCount: 5]
     MQCFST [type: 4, strucLength: 68, parameter: 2016 (MQCA_Q_NAME), codedCharSetId: 819, stringLength: 48, string: DEV.DEAD.LETTER.QUEUE                           ]
     MQCFIN [type: 3, strucLength: 16, parameter: 37 (MQIA_MSG_ENQ_COUNT), value: 0]
     MQCFIN [type: 3, strucLength: 16, parameter: 38 (MQIA_MSG_DEQ_COUNT), value: 0]
     MQCFIN [type: 3, strucLength: 16, parameter: 36 (MQIA_HIGH_Q_DEPTH), value: 0]
     MQCFIN [type: 3, strucLength: 16, parameter: 35 (MQIA_TIME_SINCE_RESET), value: 65]"
  */
  private PCFMessage[] createPCFResponseForResetQStatsCmd() {
    PCFMessage response1 = new PCFMessage(2, CMQCFC.MQCMD_RESET_Q_STATS, 1, false);
    response1.addParameter(CMQC.MQCA_Q_NAME, "DEV.DEAD.LETTER.QUEUE");
    response1.addParameter(CMQC.MQIA_MSG_ENQ_COUNT, 3);
    response1.addParameter(CMQC.MQIA_MSG_DEQ_COUNT, 0);
    response1.addParameter(CMQC.MQIA_HIGH_Q_DEPTH, 10);
    response1.addParameter(CMQC.MQIA_TIME_SINCE_RESET, 65);

    return new PCFMessage[] {response1};
  }
}
