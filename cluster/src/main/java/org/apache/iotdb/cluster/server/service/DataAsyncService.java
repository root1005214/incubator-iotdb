/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.cluster.server.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import org.apache.iotdb.cluster.client.async.AsyncDataClient;
import org.apache.iotdb.cluster.exception.CheckConsistencyException;
import org.apache.iotdb.cluster.exception.LeaderUnknownException;
import org.apache.iotdb.cluster.exception.ReaderNotFoundException;
import org.apache.iotdb.cluster.rpc.thrift.GetAggrResultRequest;
import org.apache.iotdb.cluster.rpc.thrift.GroupByRequest;
import org.apache.iotdb.cluster.rpc.thrift.LastQueryRequest;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.rpc.thrift.PreviousFillRequest;
import org.apache.iotdb.cluster.rpc.thrift.PullSchemaRequest;
import org.apache.iotdb.cluster.rpc.thrift.PullSchemaResp;
import org.apache.iotdb.cluster.rpc.thrift.PullSnapshotRequest;
import org.apache.iotdb.cluster.rpc.thrift.PullSnapshotResp;
import org.apache.iotdb.cluster.rpc.thrift.SendSnapshotRequest;
import org.apache.iotdb.cluster.rpc.thrift.SingleSeriesQueryRequest;
import org.apache.iotdb.cluster.rpc.thrift.TSDataService;
import org.apache.iotdb.cluster.server.handlers.forwarder.GenericForwardHandler;
import org.apache.iotdb.cluster.server.member.DataGroupMember;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataAsyncService extends BaseAsyncService implements TSDataService.AsyncIface {

  private static final Logger logger = LoggerFactory.getLogger(DataAsyncService.class);
  private DataGroupMember dataGroupMember;

  public DataAsyncService(DataGroupMember member) {
    super(member);
    this.dataGroupMember = member;
  }

  @Override
  public void sendSnapshot(SendSnapshotRequest request, AsyncMethodCallback<Void> resultHandler) {
    try {
      dataGroupMember.sendSnapshot(request);
      resultHandler.onComplete(null);
    } catch (Exception e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void pullSnapshot(PullSnapshotRequest request,
      AsyncMethodCallback<PullSnapshotResp> resultHandler) {
    PullSnapshotResp pullSnapshotResp = null;
    try {
      pullSnapshotResp = dataGroupMember.pullSnapshot(request);
    } catch (IOException e) {
      resultHandler.onError(e);
    }
    if (pullSnapshotResp == null) {
      forwardPullSnapshot(request, resultHandler);
    } else {
      resultHandler.onComplete(pullSnapshotResp);
    }
  }

  private void forwardPullSnapshot(PullSnapshotRequest request, AsyncMethodCallback resultHandler) {
    // if this node has been set readOnly, then it must have been synchronized with the leader
    // otherwise forward the request to the leader
    if (dataGroupMember.getLeader() != null) {
      logger.debug("{} forwarding a pull snapshot request to the leader {}", name,
          dataGroupMember.getLeader());
      AsyncDataClient client = (AsyncDataClient) dataGroupMember.getAsyncClient(dataGroupMember.getLeader());
      try {
        client.pullSnapshot(request, new GenericForwardHandler<>(resultHandler));
      } catch (TException e) {
        resultHandler.onError(e);
      }
    } else {
      resultHandler.onError(new LeaderUnknownException(dataGroupMember.getAllNodes()));
    }
  }

  @Override
  public void pullTimeSeriesSchema(PullSchemaRequest request,
      AsyncMethodCallback<PullSchemaResp> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.pullTimeSeriesSchema(request));
    } catch (CheckConsistencyException e) {
      // if this node cannot synchronize with the leader with in a given time, forward the
      // request to the leader
      dataGroupMember.waitLeader();
      AsyncDataClient client =
          (AsyncDataClient) dataGroupMember.getAsyncClient(dataGroupMember.getLeader());
      if (client == null) {
        resultHandler.onError(new LeaderUnknownException(dataGroupMember.getAllNodes()));
        return;
      }
      try {
        client.pullTimeSeriesSchema(request, resultHandler);
      } catch (TException e1) {
        resultHandler.onError(e1);
      }
    }
  }

  @Override
  public void querySingleSeries(SingleSeriesQueryRequest request,
      AsyncMethodCallback<Long> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.querySingleSeries(request));
    } catch (Exception e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void querySingleSeriesByTimestamp(SingleSeriesQueryRequest request,
      AsyncMethodCallback<Long> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.querySingleSeriesByTimestamp(request));
    } catch (Exception e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void endQuery(Node header, Node requester, long queryId,
      AsyncMethodCallback<Void> resultHandler) {
    try {
      dataGroupMember.endQuery(requester, queryId);
      resultHandler.onComplete(null);
    } catch (StorageEngineException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void fetchSingleSeries(Node header, long readerId,
      AsyncMethodCallback<ByteBuffer> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.fetchSingleSeries(readerId));
    } catch (ReaderNotFoundException | IOException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void fetchSingleSeriesByTimestamp(Node header, long readerId, long timestamp,
      AsyncMethodCallback<ByteBuffer> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.fetchSingleSeriesByTimestamp(readerId, timestamp));
    } catch (ReaderNotFoundException | IOException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void getAllPaths(Node header, List<String> paths,
      AsyncMethodCallback<List<String>> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.getAllPaths(paths));
    } catch (MetadataException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void getAllDevices(Node header, List<String> path,
      AsyncMethodCallback<Set<String>> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.getAllDevices(path));
    } catch (MetadataException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void getNodeList(Node header, String path, int nodeLevel,
      AsyncMethodCallback<List<String>> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.getNodeList(path, nodeLevel));
    } catch (CheckConsistencyException | MetadataException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void getChildNodePathInNextLevel(Node header, String path,
      AsyncMethodCallback<Set<String>> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.getChildNodePathInNextLevel(path));
    } catch (CheckConsistencyException | MetadataException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void getAllMeasurementSchema(Node header, ByteBuffer planBinary,
      AsyncMethodCallback<ByteBuffer> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.getAllMeasurementSchema(planBinary));
    } catch (CheckConsistencyException | IOException | MetadataException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void getAggrResult(GetAggrResultRequest request,
      AsyncMethodCallback<List<ByteBuffer>> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.getAggrResult(request));
    } catch (StorageEngineException | QueryProcessException | IOException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void getUnregisteredTimeseries(Node header, List<String> timeseriesList,
      AsyncMethodCallback<List<String>> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.getUnregisteredTimeseries(timeseriesList));
    } catch (CheckConsistencyException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void getGroupByExecutor(GroupByRequest request, AsyncMethodCallback<Long> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.getGroupByExecutor(request));
    } catch (QueryProcessException | StorageEngineException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void getGroupByResult(Node header, long executorId, long startTime, long endTime,
      AsyncMethodCallback<List<ByteBuffer>> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.getGroupByResult(executorId, startTime, endTime));
    } catch (ReaderNotFoundException | IOException | QueryProcessException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void previousFill(PreviousFillRequest request,
      AsyncMethodCallback<ByteBuffer> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.previousFill(request));
    } catch (QueryProcessException | StorageEngineException | IOException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void last(LastQueryRequest request, AsyncMethodCallback<ByteBuffer> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.last(request));
    } catch (CheckConsistencyException | QueryProcessException | IOException | StorageEngineException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void getPathCount(Node header, List<String> pathsToQuery, int level,
      AsyncMethodCallback<Integer> resultHandler) {
    try {
      resultHandler.onComplete(dataGroupMember.getPathCount(pathsToQuery, level));
    } catch (CheckConsistencyException | MetadataException e) {
      resultHandler.onError(e);
    }
  }

  @Override
  public void onSnapshotApplied(Node header, List<Integer> slots,
      AsyncMethodCallback<Boolean> resultHandler) {
    resultHandler.onComplete(dataGroupMember.onSnapshotApplied(slots));
  }
}