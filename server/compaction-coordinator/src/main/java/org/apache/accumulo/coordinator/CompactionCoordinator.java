/*
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
package org.apache.accumulo.coordinator;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.clientImpl.ThriftTransportPool;
import org.apache.accumulo.core.compaction.thrift.CompactionCoordinator.Iface;
import org.apache.accumulo.core.compaction.thrift.CompactionState;
import org.apache.accumulo.core.compaction.thrift.Compactor;
import org.apache.accumulo.core.compaction.thrift.Status;
import org.apache.accumulo.core.compaction.thrift.UnknownCompactionIdException;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.metadata.TServerInstance;
import org.apache.accumulo.core.rpc.ThriftUtil;
import org.apache.accumulo.core.tabletserver.thrift.CompactionStats;
import org.apache.accumulo.core.tabletserver.thrift.TCompactionQueueSummary;
import org.apache.accumulo.core.tabletserver.thrift.TExternalCompactionJob;
import org.apache.accumulo.core.tabletserver.thrift.TabletClientService;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.core.util.HostAndPort;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.fate.util.UtilWaitThread;
import org.apache.accumulo.fate.zookeeper.ZooLock;
import org.apache.accumulo.server.AbstractServer;
import org.apache.accumulo.server.GarbageCollectionLogger;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.ServerOpts;
import org.apache.accumulo.server.compaction.ExternalCompactionId;
import org.apache.accumulo.server.compaction.ExternalCompactionUtil;
import org.apache.accumulo.server.compaction.RetryableThriftCall;
import org.apache.accumulo.server.compaction.RetryableThriftCall.RetriesExceededException;
import org.apache.accumulo.server.compaction.RetryableThriftFunction;
import org.apache.accumulo.server.manager.LiveTServerSet;
import org.apache.accumulo.server.manager.LiveTServerSet.TServerConnection;
import org.apache.accumulo.server.rpc.ServerAddress;
import org.apache.accumulo.server.rpc.TCredentialsUpdatingWrapper;
import org.apache.accumulo.server.rpc.TServerUtils;
import org.apache.accumulo.server.rpc.ThriftServerType;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompactionCoordinator extends AbstractServer
    implements org.apache.accumulo.core.compaction.thrift.CompactionCoordinator.Iface,
    LiveTServerSet.Listener {

  private static final Logger LOG = LoggerFactory.getLogger(CompactionCoordinator.class);
  private static final long TIME_BETWEEN_CHECKS = 5000;
  private static final long TSERVER_CHECK_INTERVAL = 60000;

  /* Map of external queue name -> priority -> tservers */
  private static final Map<String,TreeMap<Long,LinkedHashSet<TServerInstance>>> QUEUES =
      new HashMap<>();
  /* index of tserver to queue and priority, exists to provide O(1) lookup into QUEUES */
  private static final Map<TServerInstance,HashSet<QueueAndPriority>> INDEX =
      new ConcurrentHashMap<>();
  /* Map of compactionId to RunningCompactions */
  private static final Map<ExternalCompactionId,RunningCompaction> RUNNING =
      new ConcurrentHashMap<>();

  private final GarbageCollectionLogger gcLogger = new GarbageCollectionLogger();
  private final AccumuloConfiguration aconf;

  private ZooLock coordinatorLock;
  private LiveTServerSet tserverSet;

  protected CompactionCoordinator(ServerOpts opts, String[] args) {
    super("compaction-coordinator", opts, args);
    ServerContext context = super.getContext();
    context.setupCrypto();

    aconf = getConfiguration();
    ThreadPools.createGeneralScheduledExecutorService(aconf).scheduleWithFixedDelay(
        () -> gcLogger.logGCInfo(getConfiguration()), 0, TIME_BETWEEN_CHECKS,
        TimeUnit.MILLISECONDS);
    LOG.info("Version " + Constants.VERSION);
    LOG.info("Instance " + context.getInstanceID());
  }

  /**
   * Set up nodes and locks in ZooKeeper for this CompactionCoordinator
   *
   * @param clientAddress
   *          address of this Compactor
   * @return true if lock was acquired, else false
   * @throws KeeperException
   * @throws InterruptedException
   */
  protected boolean getCoordinatorLock(HostAndPort clientAddress)
      throws KeeperException, InterruptedException {
    LOG.info("trying to get coordinator lock");

    final String coordinatorClientAddress = ExternalCompactionUtil.getHostPortString(clientAddress);
    final String lockPath = getContext().getZooKeeperRoot() + Constants.ZCOORDINATOR_LOCK;
    final UUID zooLockUUID = UUID.randomUUID();

    CoordinatorLockWatcher coordinatorLockWatcher = new CoordinatorLockWatcher();
    coordinatorLock = new ZooLock(getContext().getSiteConfiguration(), lockPath, zooLockUUID);
    // TODO may want to wait like manager code when lock not acquired, this allows starting multiple
    // coordinators.
    return coordinatorLock.tryLock(coordinatorLockWatcher, coordinatorClientAddress.getBytes());
  }

  /**
   * Start this CompactionCoordinator thrift service to handle incoming client requests
   *
   * @return address of this CompactionCoordinator client service
   * @throws UnknownHostException
   */
  protected ServerAddress startCoordinatorClientService() throws UnknownHostException {
    Iface rpcProxy = TraceUtil.wrapService(this);
    final org.apache.accumulo.core.compaction.thrift.CompactionCoordinator.Processor<
        Iface> processor;
    if (getContext().getThriftServerType() == ThriftServerType.SASL) {
      Iface tcredProxy = TCredentialsUpdatingWrapper.service(rpcProxy, CompactionCoordinator.class,
          getConfiguration());
      processor = new org.apache.accumulo.core.compaction.thrift.CompactionCoordinator.Processor<>(
          tcredProxy);
    } else {
      processor = new org.apache.accumulo.core.compaction.thrift.CompactionCoordinator.Processor<>(
          rpcProxy);
    }
    Property maxMessageSizeProperty = (aconf.get(Property.COORDINATOR_MAX_MESSAGE_SIZE) != null
        ? Property.COORDINATOR_MAX_MESSAGE_SIZE : Property.GENERAL_MAX_MESSAGE_SIZE);
    ServerAddress sp = TServerUtils.startServer(getMetricsSystem(), getContext(), getHostname(),
        Property.COORDINATOR_CLIENTPORT, processor, this.getClass().getSimpleName(),
        "Thrift Client Server", Property.COORDINATOR_PORTSEARCH, Property.COORDINATOR_MINTHREADS,
        Property.COORDINATOR_MINTHREADS_TIMEOUT, Property.COORDINATOR_THREADCHECK,
        maxMessageSizeProperty);
    LOG.info("address = {}", sp.address);
    return sp;
  }

  @Override
  public void run() {

    ServerAddress coordinatorAddress = null;
    try {
      coordinatorAddress = startCoordinatorClientService();
    } catch (UnknownHostException e1) {
      throw new RuntimeException("Failed to start the coordinator service", e1);
    }
    final HostAndPort clientAddress = coordinatorAddress.address;

    try {
      if (!getCoordinatorLock(clientAddress)) {
        throw new RuntimeException("Unable to get Coordinator lock.");
      }
    } catch (KeeperException | InterruptedException e) {
      throw new IllegalStateException("Exception getting Coordinator lock", e);
    }

    tserverSet = new LiveTServerSet(getContext(), this);

    // TODO: On initial startup contact all running tservers to get information about the
    // compactions that are current running in external queues to populate the RUNNING map.
    // This is to handle the case where the coordinator dies or is restarted at runtime
    //
    // Alternatively, we could use the status messages in updateCompactionStatus to rebuild
    // the RUNNING map.

    tserverSet.startListeningForTabletServerChanges();

    while (true) {
      long start = System.currentTimeMillis();
      tserverSet.getCurrentServers().forEach(tsi -> {
        try {
          TabletClientService.Client client = null;
          try {
            LOG.debug("Contacting tablet server {} to get external compaction summaries",
                tsi.getHostPort());
            client = getTabletServerConnection(tsi);
            List<TCompactionQueueSummary> summaries =
                client.getCompactionQueueInfo(TraceUtil.traceInfo(), getContext().rpcCreds());
            summaries.forEach(summary -> {
              QueueAndPriority qp =
                  QueueAndPriority.get(summary.getQueue().intern(), summary.getPriority());
              synchronized (QUEUES) {
                QUEUES.computeIfAbsent(qp.getQueue(), k -> new TreeMap<>())
                    .computeIfAbsent(qp.getPriority(), k -> new LinkedHashSet<>()).add(tsi);
                INDEX.computeIfAbsent(tsi, k -> new HashSet<>()).add(qp);
              }
            });
          } finally {
            ThriftUtil.returnClient(client);
          }
        } catch (TException e) {
          LOG.warn("Error getting external compaction summaries from tablet server: {}",
              tsi.getHostAndPort(), e);
        }
      });
      long duration = (System.currentTimeMillis() - start);
      if (TSERVER_CHECK_INTERVAL - duration > 0) {
        UtilWaitThread.sleep(TSERVER_CHECK_INTERVAL - duration);
      }
    }

  }

  /**
   * Callback for the LiveTServerSet object to update current set of tablet servers, including ones
   * that were deleted and added
   *
   * @param current
   *          current set of live tservers
   * @param deleted
   *          set of tservers that were removed from current since last update
   * @param added
   *          set of tservers that were added to current since last update
   */
  @Override
  public void update(LiveTServerSet current, Set<TServerInstance> deleted,
      Set<TServerInstance> added) {

    // run() will iterate over the current and added tservers and add them to the internal
    // data structures. For tservers that are deleted, we need to remove them from QUEUES
    // and INDEX and cancel and RUNNING compactions as we currently don't have a way
    // to notify a tabletserver that a compaction has completed when the tablet is re-hosted.
    deleted.forEach(tsi -> {
      // Find any running compactions for the tserver
      final List<ExternalCompactionId> toCancel = new ArrayList<>();
      RUNNING.forEach((k, v) -> {
        if (v.getTserver().equals(tsi)) {
          toCancel.add(k);
        }
      });
      // Remove the tserver from the QUEUES and INDEX
      INDEX.get(tsi).forEach(qp -> {
        TreeMap<Long,LinkedHashSet<TServerInstance>> m = QUEUES.get(qp.getQueue());
        if (null != m) {
          LinkedHashSet<TServerInstance> tservers = m.get(qp.getPriority());
          if (null != tservers) {
            synchronized (QUEUES) {
              tservers.remove(tsi);
              INDEX.remove(tsi);
            }
          }
        }
      });
      // Cancel running compactions
      toCancel.forEach(id -> {
        try {
          cancelCompaction(id.canonical());
        } catch (TException e) {
          LOG.error("Error cancelling running compaction {} due to tserver {} removal.", id, tsi,
              e);
        }
      });
    });
  }

  /**
   * Return the next compaction job from the queue to a Compactor
   *
   * @param queueName
   *          queue
   * @param compactorAddress
   *          compactor address
   * @return compaction job
   */
  @Override
  public TExternalCompactionJob getCompactionJob(String queueName, String compactorAddress)
      throws TException {
    // CBUG need to use and check for system credentials
    LOG.debug("getCompactionJob " + queueName + " " + compactorAddress);
    String queue = queueName.intern();
    TExternalCompactionJob result = null;
    // CBUG Review synchronization on QUEUES
    synchronized (QUEUES) {
      TreeMap<Long,LinkedHashSet<TServerInstance>> m = QUEUES.get(queue);
      if (null != m && !m.isEmpty()) {
        while (result == null) {

          // m could become empty if we have contacted all tservers in this queue and
          // there are no compactions
          if (m.isEmpty()) {
            LOG.debug("No tservers found for queue {}, returning empty job to compactor {}", queue,
                compactorAddress);
            result = new TExternalCompactionJob();
            break;
          }

          // Get the first TServerInstance from the highest priority queue
          Entry<Long,LinkedHashSet<TServerInstance>> entry = m.firstEntry();
          Long priority = entry.getKey();
          LinkedHashSet<TServerInstance> tservers = entry.getValue();

          if (null == tservers || tservers.isEmpty()) {
            // Clean up the map entry when no tservers for this queue and priority
            m.remove(entry.getKey(), entry.getValue());
            continue;
          } else {
            TServerInstance tserver = tservers.iterator().next();
            LOG.debug("Found tserver {} with priority {} for queue {}", tserver.getHostAndPort(),
                priority, queue);
            // Remove the tserver from the list, we are going to run a compaction on this server
            tservers.remove(tserver);
            if (tservers.isEmpty()) {
              // Clean up the map entry when no tservers remaining for this queue and priority
              // CBUG This may be redundant as cleanup happens in the 'if' clause above
              m.remove(entry.getKey(), entry.getValue());
            }
            HashSet<QueueAndPriority> qp = INDEX.get(tserver);
            qp.remove(QueueAndPriority.get(queue, priority));
            if (qp.isEmpty()) {
              // Remove the tserver from the index
              INDEX.remove(tserver);
            }
            LOG.debug("Getting compaction for queue {} from tserver {}", queue,
                tserver.getHostAndPort());
            // Get a compaction from the tserver
            TabletClientService.Client client = null;
            try {
              client = getTabletServerConnection(tserver);
              TExternalCompactionJob job = client.reserveCompactionJob(TraceUtil.traceInfo(),
                  getContext().rpcCreds(), queue, priority, compactorAddress);
              if (null == job.getExternalCompactionId()) {
                LOG.debug("No compactions found for queue {} on tserver {}, trying next tserver",
                    queue, tserver.getHostAndPort(), compactorAddress);
                continue;
              }
              RUNNING.put(ExternalCompactionId.of(job.getExternalCompactionId()),
                  new RunningCompaction(job, compactorAddress, tserver));
              LOG.debug("Returning external job {} to {}", job.externalCompactionId,
                  compactorAddress);
              result = job;
              break;
            } catch (TException e) {
              LOG.error(
                  "Error from tserver {} while trying to reserve compaction, trying next tserver",
                  ExternalCompactionUtil.getHostPortString(tserver.getHostAndPort()), e);
            } finally {
              ThriftUtil.returnClient(client);
            }
          }
        }
      } else {
        LOG.debug("No tservers found for queue {}, returning empty job to compactor {}", queue,
            compactorAddress);
        result = new TExternalCompactionJob();
      }
    }
    return result;

  }

  protected TabletClientService.Client getTabletServerConnection(TServerInstance tserver)
      throws TTransportException {
    TServerConnection connection = tserverSet.getConnection(tserver);
    TTransport transport =
        ThriftTransportPool.getInstance().getTransport(connection.getAddress(), 0, getContext());
    return ThriftUtil.createClient(new TabletClientService.Client.Factory(), transport);
  }

  protected Compactor.Client getCompactorConnection(HostAndPort compactorAddress)
      throws TTransportException {
    TTransport transport =
        ThriftTransportPool.getInstance().getTransport(compactorAddress, 0, getContext());
    return ThriftUtil.createClient(new Compactor.Client.Factory(), transport);
  }

  /**
   * Called by the TabletServer to cancel the running compaction.
   */
  @Override
  public void cancelCompaction(String externalCompactionId) throws TException {
    LOG.info("Compaction cancel requested, id: {}", externalCompactionId);
    RunningCompaction rc = RUNNING.get(ExternalCompactionId.of(externalCompactionId));
    if (null == rc) {
      return;
    }
    if (!rc.isCompleted()) {
      HostAndPort compactor = HostAndPort.fromString(rc.getCompactorAddress());
      RetryableThriftCall<String> cancelThriftCall = new RetryableThriftCall<>(1000,
          RetryableThriftCall.MAX_WAIT_TIME, 0, new RetryableThriftFunction<String>() {
            @Override
            public String execute() throws TException {
              Compactor.Client compactorConnection = null;
              try {
                compactorConnection = getCompactorConnection(compactor);
                compactorConnection.cancel(rc.getJob().getExternalCompactionId());
                return "";
              } catch (TException e) {
                throw e;
              } finally {
                ThriftUtil.returnClient(compactorConnection);
              }
            }
          });
      try {
        cancelThriftCall.run();
      } catch (RetriesExceededException e) {
        LOG.error("Unable to contact Compactor {} to cancel running compaction {}",
            rc.getCompactorAddress(), rc.getJob(), e);
      }
    }
  }

  /**
   * TServer calls getCompactionStatus to get information about the compaction
   *
   * @param externalCompactionId
   *          id
   * @return compaction stats or null if not running
   */
  @Override
  public List<Status> getCompactionStatus(String externalCompactionId) throws TException {
    List<Status> status = new ArrayList<>();
    RunningCompaction rc = RUNNING.get(ExternalCompactionId.of(externalCompactionId));
    if (null != rc) {
      rc.getUpdates().forEach((k, v) -> {
        status.add(new Status(v.getTimestamp(), rc.getJob().getExternalCompactionId(),
            rc.getCompactorAddress(), v.getState(), v.getMessage()));
      });
    }
    return status;
  }

  /**
   * Compactor calls compactionCompleted passing in the CompactionStats
   *
   * @param job
   *          compaction job
   * @param stats
   *          compaction stats
   */
  @Override
  public void compactionCompleted(String externalCompactionId, CompactionStats stats)
      throws TException {
    LOG.info("Compaction completed, id: {}, stats: {}", externalCompactionId, stats);
    var ecid = ExternalCompactionId.of(externalCompactionId);
    RunningCompaction rc = RUNNING.get(ecid);
    if (null != rc) {
      rc.setStats(stats);
      rc.setCompleted();
    } else {
      LOG.error(
          "Compaction completed called by Compactor for {}, but no running compaction for that id.",
          externalCompactionId);
      throw new UnknownCompactionIdException();
    }
    // Attempt up to ten times to contact the TServer and notify it that the compaction has
    // completed.
    RetryableThriftCall<String> completedThriftCall = new RetryableThriftCall<>(1000,
        RetryableThriftCall.MAX_WAIT_TIME, 10, new RetryableThriftFunction<String>() {
          @Override
          public String execute() throws TException {
            TabletClientService.Client client = null;
            try {
              client = getTabletServerConnection(rc.getTserver());
              client.compactionJobFinished(TraceUtil.traceInfo(), getContext().rpcCreds(),
                  externalCompactionId, stats.fileSize, stats.entriesWritten);
              RUNNING.remove(ecid, rc);
              LOG.info("TServer {} notified of compaction {} completion",
                  rc.getTserver().getHostAndPort(), externalCompactionId);
              return "";
            } catch (TException e) {
              throw e;
            } finally {
              ThriftUtil.returnClient(client);
            }
          }
        });
    try {
      completedThriftCall.run();
    } catch (RetriesExceededException e) {
      // TODO: What happens if tserver is no longer hosting tablet? I wonder if we should not notify
      // the tserver that the compaction has finished and instead let the tserver that is hosting
      // the tablet poll for state updates. That way if the tablet is re-hosted, the tserver can
      // check
      // as part of the tablet loading process. This would also enable us to remove the running
      // compaction from RUNNING when the tserver makes the call and gets the stats.

      // TODO: If the call above fails, the RUNNING entry will be orphaned

      /**
       * One possible way to handle tserver down is to fall back to writing a completion entry to
       * the metadata table. Could be something like row=~extcomp:<uuid> family=status
       * qualifier=complete The Coordinator can periodically scan this portion of the metadata table
       * and let tablets know. For expediency could still make RPC first to let tserver know its
       * done and if that fails could fall back to writing to metadata table. The coordinator could
       * read and write to the metadata table section.
       */

    }

  }

  /**
   * Called by TabletServer to check if an external compaction has been completed.
   *
   *
   * @param externalCompactionId
   * @return CompactionStats
   * @throws UnknownCompactionIdException
   *           if compaction is not running
   */
  public CompactionStats isCompactionCompleted(String externalCompactionId) throws TException {
    var ecid = ExternalCompactionId.of(externalCompactionId);
    RunningCompaction rc = RUNNING.get(ecid);
    if (null != rc && rc.isCompleted()) {
      RUNNING.remove(ecid, rc);
      return rc.getStats();
    } else if (rc == null) {
      LOG.error(
          "isCompactionCompleted called by TServer for {}, but no running compaction for that id.",
          externalCompactionId);
      throw new UnknownCompactionIdException();
    } else {
      LOG.debug("isCompactionCompleted called by TServer for {}, but compaction is not complete.",
          externalCompactionId);
      // Return empty stats as a marker that it's not done.
      return new CompactionStats();
    }
  }

  /**
   * Compactor calls to update the status of the assigned compaction
   *
   * @param job
   *          compaction job
   * @param state
   *          compaction state
   * @param message
   *          informational message
   * @param timestamp
   *          timestamp of the message
   */
  @Override
  public void updateCompactionStatus(String externalCompactionId, CompactionState state,
      String message, long timestamp) throws TException {
    LOG.info("Compaction status update, id: {}, timestamp: {}, state: {}, message: {}",
        externalCompactionId, timestamp, state, message);
    RunningCompaction rc = RUNNING.get(ExternalCompactionId.of(externalCompactionId));
    if (null != rc) {
      rc.addUpdate(timestamp, message, state);
    } else {
      // TODO: If the Coordinator was restarted, we could use these status messages
      // to re-populate the RUNNING set. This would require the job, compactor address
      // and TServerInstance
      throw new UnknownCompactionIdException();
    }
  }

  public static void main(String[] args) throws Exception {
    try (CompactionCoordinator compactor = new CompactionCoordinator(new ServerOpts(), args)) {
      compactor.runServer();
    }
  }

}
