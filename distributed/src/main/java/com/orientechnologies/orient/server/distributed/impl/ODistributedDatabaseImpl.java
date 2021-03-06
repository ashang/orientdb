/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.server.distributed.impl;

import com.orientechnologies.common.concur.OOfflineNodeException;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.util.OCallable;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.command.OCommandDistributedReplicateRequest;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.exception.OSecurityAccessException;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OLogSequenceNumber;
import com.orientechnologies.orient.server.distributed.*;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog.DIRECTION;
import com.orientechnologies.orient.server.distributed.task.OAbstractRemoteTask;
import com.orientechnologies.orient.server.distributed.task.OAbstractReplicatedTask;
import com.orientechnologies.orient.server.distributed.task.ORemoteTask;
import com.orientechnologies.orient.server.hazelcast.OHazelcastPlugin;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;

/**
 * Distributed database implementation. There is one instance per database. Each node creates own instance to talk with each others.
 *
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 */
public class ODistributedDatabaseImpl implements ODistributedDatabase {

  public static final String                                                    DISTRIBUTED_SYNC_JSON_FILENAME = "distributed-sync.json";

  private static final String                                                   NODE_LOCK_PREFIX               = "orientdb.reqlock.";
  private static final HashSet<Integer>                                         ALL_QUEUES                     = new HashSet<Integer>();
  protected final ODistributedAbstractPlugin                                    manager;
  protected final ODistributedMessageServiceImpl                                msgService;
  protected final String                                                        databaseName;
  protected final Lock                                                          requestLock;
  protected ODistributedSyncConfiguration                                       syncConfiguration;
  protected ConcurrentHashMap<ORID, ODistributedRequestId>                      lockManager                    = new ConcurrentHashMap<ORID, ODistributedRequestId>(
      256);
  protected ConcurrentHashMap<ODistributedRequestId, ODistributedTxContextImpl> activeTxContexts               = new ConcurrentHashMap<ODistributedRequestId, ODistributedTxContextImpl>(
      64);
  protected final List<ODistributedWorker>                                      workerThreads                  = new ArrayList<ODistributedWorker>();
  protected AtomicBoolean                                                       status                         = new AtomicBoolean(
      false);
  private String                                                                localNodeName;

  private Map<String, OLogSequenceNumber>                                       lastLSN                        = new ConcurrentHashMap<String, OLogSequenceNumber>();
  private long                                                                  lastLSNWrittenOnDisk           = 0l;

  public ODistributedDatabaseImpl(final OHazelcastPlugin manager, final ODistributedMessageServiceImpl msgService,
      final String iDatabaseName) {
    this.manager = manager;
    this.msgService = msgService;
    this.databaseName = iDatabaseName;
    this.localNodeName = manager.getLocalNodeName();

    this.requestLock = manager.getHazelcastInstance().getLock(NODE_LOCK_PREFIX + iDatabaseName);

    startAcceptingRequests();

    checkLocalNodeInConfiguration();
  }

  public OLogSequenceNumber getLastLSN(final String server) {
    if (server == null)
      return null;
    return lastLSN.get(server);
  }

  public long getLastLSNWrittenOnDisk() {
    return lastLSNWrittenOnDisk;
  }

  /**
   * Distributed requests against the available workers by using one queue per worker. This guarantee the sequence of the operations
   * against the same record cluster.
   */
  public void processRequest(final ODistributedRequest request) {
    final ORemoteTask task = request.getTask();

    if (task instanceof OAbstractReplicatedTask) {
      final OLogSequenceNumber taskLastLSN = ((OAbstractReplicatedTask) task).getLastLSN();
      final OLogSequenceNumber lastLSN = getLastLSN(task.getNodeSource());

      if (taskLastLSN != null && lastLSN != null && taskLastLSN.compareTo(lastLSN) < 0) {
        // SKIP REQUEST BECAUSE CONTAINS AN OLD LSN
        ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE,
            "Skipped request %s on database '%s' because LSN %s < current LSN %s", request, databaseName, taskLastLSN, lastLSN);
        return;
      }
    }

    final int[] partitionKeys = task.getPartitionKey();

    // if (ODistributedServerLog.isDebugEnabled())
    ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE, "Request %s on database '%s' partitionKeys=%s", request,
        databaseName, Arrays.toString(partitionKeys));

    if (partitionKeys.length > 1 || partitionKeys[0] < 0) {

      final Set<Integer> involvedWorkerQueues;
      if (partitionKeys.length > 1)
        involvedWorkerQueues = getInvolvedQueuesByPartitionKeys(partitionKeys);
      else
        // LOCK ALL THE QUEUES
        involvedWorkerQueues = ALL_QUEUES;

      // if (ODistributedServerLog.isDebugEnabled())
      ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE, "Request %s on database '%s' involvedQueues=%s",
          request, databaseName, involvedWorkerQueues);

      if (involvedWorkerQueues.size() == 1)
        // JUST ONE QUEUE INVOLVED: PROCESS IT IMMEDIATELY
        processRequest(involvedWorkerQueues.iterator().next(), request);
      else {
        // INVOLVING MULTIPLE QUEUES

        // if (ODistributedServerLog.isDebugEnabled())
        ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE,
            "Request %s on database '%s' waiting for all the previous requests to be completed", request, databaseName);

        // WAIT ALL THE INVOLVED QUEUES ARE FREE AND SYNCHORIZED
        final CountDownLatch syncLatch = new CountDownLatch(involvedWorkerQueues.size());
        final ODistributedRequest syncRequest = new ODistributedRequest(manager.getTaskFactory(), request.getId().getNodeId(), -1,
            databaseName, new OSynchronizedTaskWrapper(syncLatch));
        for (int queue : involvedWorkerQueues)
          workerThreads.get(queue).processRequest(syncRequest);

        try {
          syncLatch.await();
        } catch (InterruptedException e) {
          // IGNORE
        }

        // PUT THE TASK TO EXECUTE ONLY IN THE FIRST QUEUE AND PUT WAIT-FOR TASKS IN THE OTHERS. WHEN THE REAL TASK IS EXECUTED,
        // ALL THE OTHER TASKS WILL RETURN, SO THE QUEUES WILL BE BUSY DURING THE EXECUTION OF THE TASK. THIS AVOID CONCURRENT
        // EXECUTION FOR THE SAME PARTITION
        final CountDownLatch queueLatch = new CountDownLatch(1);

        int i = 0;
        for (int queue : involvedWorkerQueues) {
          final ODistributedRequest req;
          if (i++ == 0) {
            // USE THE FIRST QUEUE TO PROCESS THE REQUEST
            final String senderNodeName = manager.getNodeNameById(request.getId().getNodeId());
            request.setTask(new OSynchronizedTaskWrapper(queueLatch, senderNodeName, task));
            req = request;
          } else
            req = new ODistributedRequest(manager.getTaskFactory(), request.getId().getNodeId(), -1, databaseName,
                new OWaitForTask(queueLatch));

          workerThreads.get(queue).processRequest(req);
        }
      }
    } else {
      processRequest(partitionKeys[0], request);
    }
  }

  protected Set<Integer> getInvolvedQueuesByPartitionKeys(final int[] partitionKeys) {
    final Set<Integer> involvedWorkerQueues = new HashSet<Integer>(partitionKeys.length);
    for (int pk : partitionKeys) {
      if (pk >= 0)
        involvedWorkerQueues.add(pk % workerThreads.size());
    }
    return involvedWorkerQueues;
  }

  protected void processRequest(final int partitionKey, final ODistributedRequest request) {
    if (workerThreads.isEmpty())
      throw new ODistributedException("There are no worker threads to process request " + request);

    final int partition = partitionKey % workerThreads.size();

    ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE,
        "Request %s on database '%s' dispatched to the worker %d", request, databaseName, partition);

    workerThreads.get(partition).processRequest(request);
  }

  @Override
  public ODistributedResponse send2Nodes(final ODistributedRequest iRequest, final Collection<String> iClusterNames,
      Collection<String> iNodes, final ODistributedRequest.EXECUTION_MODE iExecutionMode, final Object localResult,
      final OCallable<Void, ODistributedRequestId> iAfterSentCallback) {
    boolean afterSendCallBackCalled = false;
    try {
      checkForServerOnline(iRequest);

      final String databaseName = iRequest.getDatabaseName();

      if (iNodes.isEmpty()) {
        ODistributedServerLog.error(this, localNodeName, null, DIRECTION.OUT, "No nodes configured for database '%s' request: %s",
            databaseName, iRequest);
        throw new ODistributedException("No nodes configured for partition '" + databaseName + "' request: " + iRequest);
      }

      final ODistributedConfiguration cfg = manager.getDatabaseConfiguration(databaseName);

      final ORemoteTask task = iRequest.getTask();

      final boolean checkNodesAreOnline = task.isNodeOnlineRequired();

      final Set<String> nodesConcurToTheQuorum = manager.getDistributedStrategy().getNodesConcurInQuorum(manager, cfg, iRequest,
          iNodes, localResult);

      // AFTER COMPUTED THE QUORUM, REMOVE THE OFFLINE NODES TO HAVE THE LIST OF REAL AVAILABLE NODES
      final int availableNodes = manager.getAvailableNodes(iNodes, databaseName);

      final int expectedResponses = localResult != null ? availableNodes + 1 : availableNodes;

      final int quorum = calculateQuorum(task.getQuorumType(), iClusterNames, cfg, expectedResponses, nodesConcurToTheQuorum.size(),
          checkNodesAreOnline, localNodeName);

      final boolean groupByResponse;
      if (task.getResultStrategy() == OAbstractRemoteTask.RESULT_STRATEGY.UNION) {
        groupByResponse = false;
      } else {
        groupByResponse = true;
      }

      final boolean waitLocalNode = waitForLocalNode(cfg, iClusterNames, iNodes);

      // CREATE THE RESPONSE MANAGER
      final ODistributedResponseManager currentResponseMgr = new ODistributedResponseManager(manager, iRequest, iNodes,
          nodesConcurToTheQuorum, expectedResponses, quorum, waitLocalNode, task.getSynchronousTimeout(expectedResponses),
          task.getTotalTimeout(availableNodes), groupByResponse);

      if (localResult != null)
        // COLLECT LOCAL RESULT
        currentResponseMgr.setLocalResult(localNodeName, (Serializable) localResult);

      // SORT THE NODE TO GUARANTEE THE SAME ORDER OF DELIVERY
      if (!(iNodes instanceof List))
        iNodes = new ArrayList<String>(iNodes);
      Collections.sort((List<String>) iNodes);

      msgService.registerRequest(iRequest.getId().getMessageId(), currentResponseMgr);

      if (ODistributedServerLog.isDebugEnabled())
        ODistributedServerLog.debug(this, localNodeName, iNodes.toString(), DIRECTION.OUT, "Sending request %s...", iRequest);

      for (String node : iNodes) {
        // CATCH ANY EXCEPTION LOG IT AND IGNORE TO CONTINUE SENDING REQUESTS TO OTHER NODES
        try {
          final ORemoteServerController remoteServer = manager.getRemoteServer(node);

          remoteServer.sendRequest(iRequest);

        } catch (Throwable e) {
          String reason = e.getMessage();
          if (e instanceof ODistributedException && e.getCause() instanceof IOException) {
            // CONNECTION ERROR: REMOVE THE CONNECTION
            reason = e.getCause().getMessage();
            manager.closeRemoteServer(node);

          } else if (e instanceof OSecurityAccessException) {
            // THE CONNECTION COULD BE STALE, CREATE A NEW ONE AND RETRY
            manager.closeRemoteServer(node);
            try {
              final ORemoteServerController remoteServer = manager.getRemoteServer(node);
              remoteServer.sendRequest(iRequest);
              continue;

            } catch (Throwable ex) {
              // IGNORE IT BECAUSE MANAGED BELOW
            }
          }

          if (!manager.isNodeAvailable(node))
            // NODE IS NOT AVAILABLE
            ODistributedServerLog.debug(this, localNodeName, node, ODistributedServerLog.DIRECTION.OUT,
                "Error on sending distributed request %s. The target node is not available. Active nodes: %s", e, iRequest,
                manager.getAvailableNodeNames(databaseName));
          else
            ODistributedServerLog.error(this, localNodeName, node, ODistributedServerLog.DIRECTION.OUT,
                "Error on sending distributed request %s (%s). Active nodes: %s", iRequest, reason,
                manager.getAvailableNodeNames(databaseName));
        }
      }

      if (ODistributedServerLog.isDebugEnabled())
        ODistributedServerLog.debug(this, localNodeName, iNodes.toString(), DIRECTION.OUT, "Sent request %s", iRequest);

      Orient.instance().getProfiler().updateCounter("distributed.db." + databaseName + ".msgSent",
          "Number of replication messages sent from current node", +1, "distributed.db.*.msgSent");

      afterSendCallBackCalled = true;

      if (iAfterSentCallback != null)
        iAfterSentCallback.call(iRequest.getId());

      if (iExecutionMode == ODistributedRequest.EXECUTION_MODE.RESPONSE)
        return waitForResponse(iRequest, currentResponseMgr);

      return null;

    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw OException.wrapException(new ODistributedException("Error on executing distributed request (" + iRequest
          + ") against database '" + databaseName + (iClusterNames != null ? "." + iClusterNames : "") + "' to nodes " + iNodes),
          e);
    } finally {
      if (iAfterSentCallback != null && !afterSendCallBackCalled)
        iAfterSentCallback.call(iRequest.getId());
    }
  }

  @Override
  public void setOnline() {
    if (status.compareAndSet(false, true)) {
      ODistributedServerLog.info(this, localNodeName, null, DIRECTION.NONE, "Publishing ONLINE status for database %s.%s...",
          localNodeName, databaseName);

      // SET THE NODE.DB AS ONLINE
      manager.setDatabaseStatus(localNodeName, databaseName, ODistributedServerManager.DB_STATUS.ONLINE);
    }
  }

  @Override
  public ODistributedRequestId lockRecord(final OIdentifiable iRecord, final ODistributedRequestId iRequestId) {
    final ORID rid = iRecord.getIdentity();
    if (!rid.isPersistent())
      // TEMPORARY RECORD
      return null;

    final ODistributedRequestId oldReqId = lockManager.putIfAbsent(rid, iRequestId);

    final boolean locked = oldReqId == null;

    if (!locked) {
      if (iRequestId.equals(oldReqId)) {
        // SAME ID, ALREADY LOCKED
        ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE,
            "Distributed transaction: %s locked record %s in database '%s' owned by %s", iRequestId, iRecord, databaseName,
            iRequestId);
        return null;
      }
    }

    if (ODistributedServerLog.isDebugEnabled())
      if (locked)
        ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE,
            "Distributed transaction: %s locked record %s in database '%s'", iRequestId, iRecord, databaseName);
      else
        ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE,
            "Distributed transaction: %s cannot lock record %s in database '%s' owned by %s", iRequestId, iRecord, databaseName,
            oldReqId);

    return oldReqId;
  }

  @Override
  public void unlockRecord(final OIdentifiable iRecord, final ODistributedRequestId requestId) {
    if (requestId == null)
      return;

    final ODistributedRequestId owner = lockManager.remove(iRecord.getIdentity());

    if (ODistributedServerLog.isDebugEnabled())
      ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE,
          "Distributed transaction: %s unlocked record %s in database '%s' (owner=%s)", requestId, iRecord, databaseName, owner);
  }

  @Override
  public ODistributedTxContext registerTxContext(final ODistributedRequestId reqId) {
    ODistributedTxContextImpl ctx = new ODistributedTxContextImpl(this, reqId);

    final ODistributedTxContextImpl prevCtx = activeTxContexts.putIfAbsent(reqId, ctx);
    if (prevCtx != null) {
      // ALREADY EXISTENT
      ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE,
          "Distributed transaction: repeating request %s in database '%s'", reqId, databaseName);
      ctx = prevCtx;
    } else
      // REGISTERED
      ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE,
          "Distributed transaction: registered request %s in database '%s'", reqId, databaseName);

    return ctx;
  }

  @Override
  public ODistributedTxContext popTxContext(final ODistributedRequestId requestId) {
    final ODistributedTxContext ctx = activeTxContexts.remove(requestId);
    ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE,
        "Distributed transaction: pop request %s for database %s -> %s", requestId, databaseName, ctx);
    return ctx;
  }

  @Override
  public ODistributedServerManager getManager() {
    return manager;
  }

  public boolean exists() {
    try {
      manager.getServerInstance().getStoragePath(databaseName);
      return true;
    } catch (OConfigurationException e) {
      return false;
    }
  }

  public ODistributedSyncConfiguration getSyncConfiguration() {
    if (syncConfiguration == null) {
      final String path = manager.getServerInstance().getDatabaseDirectory() + databaseName + "/" + DISTRIBUTED_SYNC_JSON_FILENAME;
      final File cfgFile = new File(path);
      try {
        syncConfiguration = new ODistributedSyncConfiguration(cfgFile);
      } catch (IOException e) {
        throw new ODistributedException("Cannot open database sync configuration file: " + cfgFile);
      }
    }

    return syncConfiguration;
  }

  @Override
  public void handleUnreachableNode(final int iNodeId) {
    if (iNodeId < 0)
      return;

    int rollbacks = 0;
    int tasks = 0;

    final ODatabaseDocumentTx database = getDatabaseInstance();
    try {
      final Iterator<ODistributedTxContextImpl> pendingReqIterator = activeTxContexts.values().iterator();
      while (pendingReqIterator.hasNext()) {
        final ODistributedTxContextImpl pReq = pendingReqIterator.next();
        if (pReq != null && pReq.getReqId().getNodeId() == iNodeId) {
          tasks += pReq.rollback(database);
          rollbacks++;
          pReq.destroy();
          pendingReqIterator.remove();
        }
      }
    } finally {
      database.close();
    }

    if (ODistributedServerLog.isDebugEnabled())
      ODistributedServerLog.debug(this, localNodeName, null, DIRECTION.NONE,
          "Distributed transaction: rolled back %d transactions (%d total operations) in database '%s' owned by server '%s'",
          rollbacks, tasks, databaseName, manager.getNodeNameById(iNodeId));
  }

  @Override
  public String getDatabaseName() {
    return databaseName;
  }

  @Override
  public ODatabaseDocumentTx getDatabaseInstance() {
    return manager.getServerInstance().openDatabase(databaseName, "internal", "internal", null, true);
  }

  public void shutdown() {
    // SEND THE SHUTDOWN TO ALL THE WORKER THREADS
    for (ODistributedWorker workerThread : workerThreads) {
      if (workerThread != null)
        workerThread.shutdown();
    }

    // WAIT A BIT FOR PROPER SHUTDOWN
    for (ODistributedWorker workerThread : workerThreads) {
      if (workerThread != null) {
        try {
          workerThread.join(2000);
        } catch (InterruptedException e) {
        }
      }
    }
    workerThreads.clear();

    for (String server : lastLSN.keySet()) {
      try {
        saveLSNTable(server);
      } catch (IOException e) {
        // IGNORE IT
      }
    }
    lastLSN.clear();

    ODistributedServerLog.info(this, localNodeName, null, DIRECTION.NONE,
        "Shutting down distributed database manager '%s'. Pending objects: txs=%d locks=%d", databaseName, activeTxContexts.size(),
        lockManager.size());

    lockManager.clear();
    activeTxContexts.clear();

  }

  protected void checkForServerOnline(final ODistributedRequest iRequest) throws ODistributedException {
    final ODistributedServerManager.NODE_STATUS srvStatus = manager.getNodeStatus();
    if (srvStatus == ODistributedServerManager.NODE_STATUS.OFFLINE
        || srvStatus == ODistributedServerManager.NODE_STATUS.SHUTTINGDOWN) {
      ODistributedServerLog.error(this, localNodeName, null, DIRECTION.OUT,
          "Local server is not online (status='%s'). Request %s will be ignored", srvStatus, iRequest);
      throw new OOfflineNodeException(
          "Local server is not online (status='" + srvStatus + "'). Request " + iRequest + " will be ignored");
    }
  }

  protected boolean waitForLocalNode(final ODistributedConfiguration cfg, final Collection<String> iClusterNames,
      final Collection<String> iNodes) {
    boolean waitLocalNode = false;
    if (iNodes.contains(localNodeName))
      if (iClusterNames == null || iClusterNames.isEmpty()) {
        // DEFAULT CLUSTER (*)
        if (cfg.isReadYourWrites(null))
          waitLocalNode = true;
      } else
        // BROWSE FOR ALL CLUSTER TO GET THE FIRST 'waitLocalNode'
        for (String clName : iClusterNames) {
        if (cfg.isReadYourWrites(clName)) {
        waitLocalNode = true;
        break;
        }
        }
    return waitLocalNode;
  }

  protected int calculateQuorum(final OCommandDistributedReplicateRequest.QUORUM_TYPE quorumType, Collection<String> clusterNames,
      final ODistributedConfiguration cfg, final int allAvailableNodes, final int masterAvailableNodes,
      final boolean checkNodesAreOnline, final String localNodeName) {

    int quorum = 1;

    if (clusterNames == null || clusterNames.isEmpty()) {
      clusterNames = new ArrayList<String>(1);
      clusterNames.add(null);
    }

    for (String cluster : clusterNames) {
      int clusterQuorum = 0;
      switch (quorumType) {
      case NONE:
        // IGNORE IT
        break;
      case READ:
        clusterQuorum = cfg.getReadQuorum(cluster, allAvailableNodes, localNodeName);
        break;
      case WRITE:
        clusterQuorum = cfg.getWriteQuorum(cluster, masterAvailableNodes, localNodeName);
        break;
      case ALL:
        clusterQuorum = allAvailableNodes;
        break;
      }

      quorum = Math.max(quorum, clusterQuorum);
    }

    if (quorum < 0)
      quorum = 0;

    if (checkNodesAreOnline && quorum > allAvailableNodes)
      throw new ODistributedException(
          "Quorum (" + quorum + ") cannot be reached because it is major than available nodes (" + allAvailableNodes + ")");

    return quorum;
  }

  protected ODistributedResponse waitForResponse(final ODistributedRequest iRequest,
      final ODistributedResponseManager currentResponseMgr) throws InterruptedException {
    final long beginTime = System.currentTimeMillis();

    // WAIT FOR THE MINIMUM SYNCHRONOUS RESPONSES (QUORUM)
    if (!currentResponseMgr.waitForSynchronousResponses()) {
      final long elapsed = System.currentTimeMillis() - beginTime;

      if (elapsed > currentResponseMgr.getSynchTimeout()) {

        ODistributedServerLog.warn(this, localNodeName, null, DIRECTION.IN,
            "Timeout (%dms) on waiting for synchronous responses from nodes=%s responsesSoFar=%s request=(%s)", elapsed,
            currentResponseMgr.getExpectedNodes(), currentResponseMgr.getRespondingNodes(), iRequest);
      }
    }

    return currentResponseMgr.getFinalResponse();
  }

  protected void checkLocalNodeInConfiguration() {
    manager.executeInDistributedDatabaseLock(databaseName, 0, new OCallable<Void, ODistributedConfiguration>() {
      @Override
      public Void call(final ODistributedConfiguration cfg) {
        // GET LAST VERSION IN LOCK
        final List<String> foundPartition = cfg.addNewNodeInServerList(localNodeName);
        if (foundPartition != null) {
          manager.setDatabaseStatus(localNodeName, databaseName, ODistributedServerManager.DB_STATUS.SYNCHRONIZING);

          ODistributedServerLog.info(this, localNodeName, null, DIRECTION.NONE, "Adding node '%s' in partition: %s db=%s v=%d",
              localNodeName, databaseName, foundPartition, cfg.getVersion());
        }
        return null;
      }
    });
  }

  protected String getLocalNodeName() {
    return localNodeName;
  }

  private void startAcceptingRequests() {
    // START ALL THE WORKER THREADS (CONFIGURABLE)
    final int totalWorkers = OGlobalConfiguration.DISTRIBUTED_DB_WORKERTHREADS.getValueAsInteger();
    if (totalWorkers < 1)
      throw new ODistributedException("Cannot create configured distributed workers (" + totalWorkers + ")");

    for (int i = 0; i < totalWorkers; ++i) {
      final ODistributedWorker workerThread = new ODistributedWorker(this, databaseName, i);
      workerThreads.add(workerThread);
      workerThread.start();

      ALL_QUEUES.add(i);
    }
  }

  public void setLSN(final String sourceNodeName, final OLogSequenceNumber taskLastLSN) throws IOException {
    if (taskLastLSN == null)
      return;

    lastLSN.put(sourceNodeName, taskLastLSN);

    if (System.currentTimeMillis() - lastLSNWrittenOnDisk > 2000) {
      saveLSNTable(sourceNodeName);
    }
  }

  protected void saveLSNTable(final String sourceNodeName) throws IOException {
    final OLogSequenceNumber storedLSN = lastLSN.get(sourceNodeName);

    getSyncConfiguration().setLSN(sourceNodeName, storedLSN);

    ODistributedServerLog.debug(this, localNodeName, sourceNodeName, DIRECTION.NONE, "Updating LSN table to the value %s",
        storedLSN);

    lastLSNWrittenOnDisk = System.currentTimeMillis();
  }
}
