/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.query.runtime;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.apache.helix.HelixManager;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.apache.pinot.common.exception.QueryException;
import org.apache.pinot.common.metrics.ServerMetrics;
import org.apache.pinot.common.utils.config.QueryOptionsUtils;
import org.apache.pinot.core.data.manager.InstanceDataManager;
import org.apache.pinot.core.operator.blocks.InstanceResponseBlock;
import org.apache.pinot.core.query.executor.ServerQueryExecutorV1Impl;
import org.apache.pinot.core.query.request.ServerQueryRequest;
import org.apache.pinot.query.mailbox.MailboxIdUtils;
import org.apache.pinot.query.mailbox.MailboxService;
import org.apache.pinot.query.planner.plannode.MailboxSendNode;
import org.apache.pinot.query.planner.plannode.PlanNode;
import org.apache.pinot.query.routing.MailboxMetadata;
import org.apache.pinot.query.runtime.blocks.TransferableBlock;
import org.apache.pinot.query.runtime.executor.ExecutorServiceUtils;
import org.apache.pinot.query.runtime.executor.OpChainSchedulerService;
import org.apache.pinot.query.runtime.operator.LeafStageTransferableBlockOperator;
import org.apache.pinot.query.runtime.operator.MailboxSendOperator;
import org.apache.pinot.query.runtime.operator.MultiStageOperator;
import org.apache.pinot.query.runtime.operator.OpChain;
import org.apache.pinot.query.runtime.plan.DistributedStagePlan;
import org.apache.pinot.query.runtime.plan.OpChainExecutionContext;
import org.apache.pinot.query.runtime.plan.PhysicalPlanContext;
import org.apache.pinot.query.runtime.plan.PhysicalPlanVisitor;
import org.apache.pinot.query.runtime.plan.pipeline.PipelineBreakerExecutor;
import org.apache.pinot.query.runtime.plan.pipeline.PipelineBreakerResult;
import org.apache.pinot.query.runtime.plan.server.ServerPlanRequestContext;
import org.apache.pinot.query.runtime.plan.server.ServerPlanRequestUtils;
import org.apache.pinot.spi.env.PinotConfiguration;
import org.apache.pinot.spi.metrics.PinotMetricUtils;
import org.apache.pinot.spi.utils.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * {@link QueryRunner} accepts a {@link DistributedStagePlan} and runs it.
 */
public class QueryRunner {
  private static final Logger LOGGER = LoggerFactory.getLogger(QueryRunner.class);
  private static final String PINOT_V1_SERVER_QUERY_CONFIG_PREFIX = "pinot.server.query.executor";

  // This is a temporary before merging the 2 type of executor.
  private ServerQueryExecutorV1Impl _serverExecutor;
  private HelixManager _helixManager;
  private ZkHelixPropertyStore<ZNRecord> _helixPropertyStore;
  private MailboxService _mailboxService;
  private String _hostname;
  private int _port;

  private ExecutorService _opChainExecutor;

  private OpChainSchedulerService _scheduler;

  // Join Overflow configs
  @Nullable
  private Integer _maxRowsInJoin;
  @Nullable
  private String _joinOverflowMode;

  /**
   * Initializes the query executor.
   * <p>Should be called only once and before calling any other method.
   */
  public void init(PinotConfiguration config, InstanceDataManager instanceDataManager, HelixManager helixManager,
      ServerMetrics serverMetrics) {
    String instanceName = config.getProperty(CommonConstants.MultiStageQueryRunner.KEY_OF_QUERY_RUNNER_HOSTNAME);
    _hostname = instanceName.startsWith(CommonConstants.Helix.PREFIX_OF_SERVER_INSTANCE) ? instanceName.substring(
        CommonConstants.Helix.SERVER_INSTANCE_PREFIX_LENGTH) : instanceName;
    _port = config.getProperty(CommonConstants.MultiStageQueryRunner.KEY_OF_QUERY_RUNNER_PORT,
        CommonConstants.MultiStageQueryRunner.DEFAULT_QUERY_RUNNER_PORT);
    _helixManager = helixManager;
    // Set Join Overflow configs
    _joinOverflowMode = config.getProperty(CommonConstants.MultiStageQueryRunner.KEY_OF_JOIN_OVERFLOW_MODE);
    _maxRowsInJoin =
        config.containsKey(CommonConstants.MultiStageQueryRunner.KEY_OF_MAX_ROWS_IN_JOIN) ? Integer.parseInt(
            config.getProperty(CommonConstants.MultiStageQueryRunner.KEY_OF_MAX_ROWS_IN_JOIN)) : null;

    try {
      //TODO: make this configurable
      _opChainExecutor = ExecutorServiceUtils.create(config, "pinot.query.runner.opchain",
          "op_chain_worker_on_" + _port + "_port");
      _scheduler = new OpChainSchedulerService(getOpChainExecutorService());
      _mailboxService = new MailboxService(_hostname, _port, config);
      _serverExecutor = new ServerQueryExecutorV1Impl();
      _serverExecutor.init(config.subset(PINOT_V1_SERVER_QUERY_CONFIG_PREFIX), instanceDataManager, serverMetrics);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void start()
      throws TimeoutException {
    _helixPropertyStore = _helixManager.getHelixPropertyStore();
    _mailboxService.start();
    _serverExecutor.start();
  }

  public void shutDown()
      throws TimeoutException {
    _serverExecutor.shutDown();
    _mailboxService.shutdown();
    ExecutorServiceUtils.close(_opChainExecutor);
  }

  /**
   * Execute a {@link DistributedStagePlan}.
   *
   * <p>This execution entry point should be asynchronously called by the request handler and caller should not wait
   * for results/exceptions.</p>
   */
  public void processQuery(DistributedStagePlan distributedStagePlan, Map<String, String> requestMetadataMap) {
    long requestId = Long.parseLong(requestMetadataMap.get(CommonConstants.Query.Request.MetadataKeys.REQUEST_ID));
    long timeoutMs = Long.parseLong(requestMetadataMap.get(CommonConstants.Broker.Request.QueryOptionKey.TIMEOUT_MS));
    boolean isTraceEnabled =
        Boolean.parseBoolean(requestMetadataMap.getOrDefault(CommonConstants.Broker.Request.TRACE, "false"));
    long deadlineMs = System.currentTimeMillis() + timeoutMs;

    // run pre-stage execution for all pipeline breakers
    PipelineBreakerResult pipelineBreakerResult =
        PipelineBreakerExecutor.executePipelineBreakers(_scheduler, _mailboxService, distributedStagePlan, deadlineMs,
            requestId, isTraceEnabled);

    // Send error block to all the receivers if pipeline breaker fails
    if (pipelineBreakerResult != null && pipelineBreakerResult.getErrorBlock() != null) {
      TransferableBlock errorBlock = pipelineBreakerResult.getErrorBlock();
      LOGGER.error("Error executing pipeline breaker for request: {}, stage: {}, sending error block: {}", requestId,
          distributedStagePlan.getStageId(), errorBlock.getDataBlock().getExceptions());
      int receiverStageId = ((MailboxSendNode) distributedStagePlan.getStageRoot()).getReceiverStageId();
      MailboxMetadata mailboxMetadata = distributedStagePlan.getStageMetadata().getWorkerMetadataList()
          .get(distributedStagePlan.getServer().workerId()).getMailBoxInfosMap().get(receiverStageId);
      List<String> mailboxIds = MailboxIdUtils.toMailboxIds(requestId, mailboxMetadata);
      for (int i = 0; i < mailboxIds.size(); i++) {
        try {
          _mailboxService.getSendingMailbox(mailboxMetadata.getVirtualAddress(i).hostname(),
              mailboxMetadata.getVirtualAddress(i).port(), mailboxIds.get(i), deadlineMs).send(errorBlock);
        } catch (TimeoutException e) {
          LOGGER.warn("Timed out sending error block to mailbox: {} for request: {}, stage: {}", mailboxIds.get(i),
              requestId, distributedStagePlan.getStageId(), e);
        } catch (Exception e) {
          LOGGER.error("Caught exception sending error block to mailbox: {} for request: {}, stage: {}",
              mailboxIds.get(i), requestId, distributedStagePlan.getStageId(), e);
        }
      }
      return;
    }

    // Set Join Overflow configs to StageMetadata from request
    setJoinOverflowConfigs(distributedStagePlan, requestMetadataMap);

    // run OpChain
    OpChain opChain;
    if (DistributedStagePlan.isLeafStage(distributedStagePlan)) {
      opChain = compileLeafStage(requestId, distributedStagePlan, requestMetadataMap, pipelineBreakerResult, deadlineMs,
          isTraceEnabled);
    } else {
      opChain = compileIntermediateStage(requestId, distributedStagePlan, requestMetadataMap, pipelineBreakerResult,
          deadlineMs, isTraceEnabled);
    }
    _scheduler.register(opChain);
  }

  private void setJoinOverflowConfigs(DistributedStagePlan distributedStagePlan,
      Map<String, String> requestMetadataMap) {
    String joinOverflowMode = QueryOptionsUtils.getJoinOverflowMode(requestMetadataMap);
    if (joinOverflowMode != null) {
      distributedStagePlan.getStageMetadata().getCustomProperties()
          .put(CommonConstants.Broker.Request.QueryOptionKey.JOIN_OVERFLOW_MODE, joinOverflowMode);
    } else if (_joinOverflowMode != null) {
      distributedStagePlan.getStageMetadata().getCustomProperties()
          .put(CommonConstants.Broker.Request.QueryOptionKey.JOIN_OVERFLOW_MODE, _joinOverflowMode);
    }

    Integer maxRowsInJoin = QueryOptionsUtils.getMaxRowsInJoin(requestMetadataMap);
    if (maxRowsInJoin != null) {
      distributedStagePlan.getStageMetadata().getCustomProperties()
          .put(CommonConstants.Broker.Request.QueryOptionKey.MAX_ROWS_IN_JOIN, String.valueOf(maxRowsInJoin));
    } else if (_maxRowsInJoin != null) {
      distributedStagePlan.getStageMetadata().getCustomProperties()
          .put(CommonConstants.Broker.Request.QueryOptionKey.MAX_ROWS_IN_JOIN, String.valueOf(_maxRowsInJoin));
    }
  }

  public void cancel(long requestId) {
    _scheduler.cancel(requestId);
  }

  @VisibleForTesting
  public ExecutorService getOpChainExecutorService() {
    return _opChainExecutor;
  }

  private OpChain compileIntermediateStage(long requestId, DistributedStagePlan distributedStagePlan,
      Map<String, String> requestMetadataMap, PipelineBreakerResult pipelineBreakerResult, long deadlineMs,
      boolean isTraceEnabled) {
    PlanNode stageRoot = distributedStagePlan.getStageRoot();
    OpChainExecutionContext opChainContext = new OpChainExecutionContext(_mailboxService, requestId,
        stageRoot.getPlanFragmentId(), distributedStagePlan.getServer(), deadlineMs,
        distributedStagePlan.getStageMetadata(), pipelineBreakerResult, isTraceEnabled);
    return PhysicalPlanVisitor.walkPlanNode(stageRoot,
        new PhysicalPlanContext(opChainContext, pipelineBreakerResult));
  }

  private OpChain compileLeafStage(long requestId, DistributedStagePlan distributedStagePlan,
      Map<String, String> requestMetadataMap, PipelineBreakerResult pipelineBreakerResult, long deadlineMs,
      boolean isTraceEnabled) {
    OpChainExecutionContext opChainContext = new OpChainExecutionContext(_mailboxService, requestId,
        distributedStagePlan.getStageId(), distributedStagePlan.getServer(), deadlineMs,
        distributedStagePlan.getStageMetadata(), pipelineBreakerResult, isTraceEnabled);
    PhysicalPlanContext planContext = new PhysicalPlanContext(opChainContext, pipelineBreakerResult);
    List<ServerPlanRequestContext> serverPlanRequestContexts = ServerPlanRequestUtils.constructServerQueryRequests(
        planContext, distributedStagePlan, requestMetadataMap, _helixPropertyStore);
    List<ServerQueryRequest> serverQueryRequests = new ArrayList<>(serverPlanRequestContexts.size());
    for (ServerPlanRequestContext requestContext : serverPlanRequestContexts) {
      serverQueryRequests.add(new ServerQueryRequest(requestContext.getInstanceRequest(),
          new ServerMetrics(PinotMetricUtils.getPinotMetricsRegistry()), System.currentTimeMillis()));
    }
    MailboxSendNode sendNode = (MailboxSendNode) distributedStagePlan.getStageRoot();
    OpChainExecutionContext opChainExecutionContext = new OpChainExecutionContext(planContext);
    MultiStageOperator leafStageOperator =
        new LeafStageTransferableBlockOperator(opChainExecutionContext, this::processServerQueryRequest,
            serverQueryRequests, sendNode.getDataSchema());
    MailboxSendOperator mailboxSendOperator =
        new MailboxSendOperator(opChainExecutionContext, leafStageOperator, sendNode.getDistributionType(),
            sendNode.getPartitionKeySelector(), sendNode.getCollationKeys(), sendNode.getCollationDirections(),
            sendNode.isSortOnSender(), sendNode.getReceiverStageId());
    return new OpChain(opChainExecutionContext, mailboxSendOperator, Collections.emptyList());
  }

  private InstanceResponseBlock processServerQueryRequest(ServerQueryRequest request) {
    InstanceResponseBlock result;
    try {
      result = _serverExecutor.execute(request, getOpChainExecutorService());
    } catch (Exception e) {
      InstanceResponseBlock errorResponse = new InstanceResponseBlock();
      errorResponse.getExceptions().put(QueryException.QUERY_EXECUTION_ERROR_CODE,
          e.getMessage() + QueryException.getTruncatedStackTrace(e));
      result = errorResponse;
    }
    return result;
  }
}
