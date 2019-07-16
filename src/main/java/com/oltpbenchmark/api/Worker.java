/******************************************************************************
 *  Copyright 2015 by OLTPBenchmark Project                                   *
 *                                                                            *
 *  Licensed under the Apache License, Version 2.0 (the "License");           *
 *  you may not use this file except in compliance with the License.          *
 *  You may obtain a copy of the License at                                   *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 *  Unless required by applicable law or agreed to in writing, software       *
 *  distributed under the License is distributed on an "AS IS" BASIS,         *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 *  See the License for the specific language governing permissions and       *
 *  limitations under the License.                                            *
 ******************************************************************************/

package com.oltpbenchmark.api;

import com.oltpbenchmark.*;
import com.oltpbenchmark.api.Procedure.UserAbortException;
import com.oltpbenchmark.catalog.Catalog;
import com.oltpbenchmark.types.DatabaseType;
import com.oltpbenchmark.types.State;
import com.oltpbenchmark.types.TransactionStatus;
import com.oltpbenchmark.util.Histogram;
import com.oltpbenchmark.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class Worker<T extends BenchmarkModule> implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(Worker.class);

    private static final int MAX_RETRY_COUNT = 3;

    private WorkloadState wrkldState;
    private LatencyRecord latencies;
    private Statement currStatement;

    // Interval requests used by the monitor
    private AtomicInteger intervalRequests = new AtomicInteger(0);

    private final int id;
    private final T benchmarkModule;
    protected final WorkloadConfiguration wrkld;
    protected final TransactionTypes transactionTypes;
    protected final Map<TransactionType, Procedure> procedures = new HashMap<>();
    protected final Map<String, Procedure> name_procedures = new HashMap<>();
    protected final Map<Class<? extends Procedure>, Procedure> class_procedures = new HashMap<>();

    private final Histogram<TransactionType> txnSuccess = new Histogram<>();
    private final Histogram<TransactionType> txnAbort = new Histogram<>();
    private final Histogram<TransactionType> txnRetry = new Histogram<>();
    private final Histogram<TransactionType> txnErrors = new Histogram<>();
    private final Map<TransactionType, Histogram<String>> txnAbortMessages = new HashMap<>();

    private boolean seenDone = false;

    public Worker(T benchmarkModule, int id) {
        this.id = id;
        this.benchmarkModule = benchmarkModule;
        this.wrkld = this.benchmarkModule.getWorkloadConfiguration();
        this.wrkldState = this.wrkld.getWorkloadState();
        this.currStatement = null;
        this.transactionTypes = this.wrkld.getTransTypes();

        // Generate all the Procedures that we're going to need
        this.procedures.putAll(this.benchmarkModule.getProcedures());
        for (Entry<TransactionType, Procedure> e : this.procedures.entrySet()) {
            Procedure proc = e.getValue();
            this.name_procedures.put(e.getKey().getName(), proc);
            this.class_procedures.put(proc.getClass(), proc);
            // e.getValue().generateAllPreparedStatements(this.conn);
        } // FOR
    }

    /**
     * Get the BenchmarkModule managing this Worker
     */
    public final T getBenchmarkModule() {
        return (this.benchmarkModule);
    }

    /**
     * Get the unique thread id for this worker
     */
    public final int getId() {
        return this.id;
    }

    @Override
    public String toString() {
        return String.format("%s<%03d>", this.getClass().getSimpleName(), this.getId());
    }

    /**
     * Get the the total number of workers in this benchmark invocation
     */
    public final int getNumWorkers() {
        return (this.benchmarkModule.getWorkloadConfiguration().getTerminals());
    }

    public final WorkloadConfiguration getWorkloadConfiguration() {
        return (this.benchmarkModule.getWorkloadConfiguration());
    }

    public final Catalog getCatalog() {
        return (this.benchmarkModule.getCatalog());
    }

    public final Random rng() {
        return (this.benchmarkModule.rng());
    }


    public final int getRequests() {
        return latencies.size();
    }

    public final int getAndResetIntervalRequests() {
        return intervalRequests.getAndSet(0);
    }

    public final Iterable<LatencyRecord.Sample> getLatencyRecords() {
        return latencies;
    }

    public final Procedure getProcedure(TransactionType type) {
        return (this.procedures.get(type));
    }

    @Deprecated
    public final Procedure getProcedure(String name) {
        return (this.name_procedures.get(name));
    }


    public final <P extends Procedure> P getProcedure(Class<P> procClass) {
        return (P) (this.class_procedures.get(procClass));
    }

    public final Histogram<TransactionType> getTransactionSuccessHistogram() {
        return (this.txnSuccess);
    }

    public final Histogram<TransactionType> getTransactionRetryHistogram() {
        return (this.txnRetry);
    }

    public final Histogram<TransactionType> getTransactionAbortHistogram() {
        return (this.txnAbort);
    }

    public final Histogram<TransactionType> getTransactionErrorHistogram() {
        return (this.txnErrors);
    }

    public final Map<TransactionType, Histogram<String>> getTransactionAbortMessageHistogram() {
        return (this.txnAbortMessages);
    }

    synchronized public void setCurrStatement(Statement s) {
        this.currStatement = s;
    }

    /**
     * Stop executing the current statement.
     */
    synchronized public void cancelStatement() {
        try {
            if (this.currStatement != null) {
                this.currStatement.cancel();
            }
        } catch (SQLException e) {
            LOG.error("Failed to cancel statement: {}", e.getMessage());
        }
    }

    @Override
    public final void run() {
        Thread t = Thread.currentThread();
        SubmittedProcedure pieceOfWork;
        t.setName(this.toString());

        // In case of reuse reset the measurements
        latencies = new LatencyRecord(wrkldState.getTestStartNs());

        // Invoke the initialize callback
        try {
            this.initialize();
        } catch (Throwable ex) {
            throw new RuntimeException("Unexpected error when initializing " + this, ex);
        }

        // wait for start
        wrkldState.blockForStart();
        State preState, postState;
        Phase phase;

        TransactionType invalidTT = TransactionType.INVALID;


        work:
        while (true) {

            // PART 1: Init and check if done

            preState = wrkldState.getGlobalState();
            phase = this.wrkldState.getCurrentPhase();

            switch (preState) {
                case DONE:
                    if (!seenDone) {
                        // This is the first time we have observed that the
                        // test is done notify the global test state, then
                        // continue applying load
                        seenDone = true;
                        wrkldState.signalDone();
                        break work;
                    }
                    break;
                default:
                    // Do nothing
            }

            // PART 2: Wait for work

            // Sleep if there's nothing to do.
            wrkldState.stayAwake();
            phase = this.wrkldState.getCurrentPhase();
            if (phase == null) {
                continue work;
            }

            // Grab some work and update the state, in case it changed while we
            // waited.
            pieceOfWork = wrkldState.fetchWork();
            preState = wrkldState.getGlobalState();

            phase = this.wrkldState.getCurrentPhase();
            if (phase == null) {
                continue work;
            }

            switch (preState) {
                case DONE:
                case EXIT:
                case LATENCY_COMPLETE:
                    // Once a latency run is complete, we wait until the next
                    // phase or until DONE.
                    continue work;
                default:
                    // Do nothing
            }

            // PART 3: Execute work

            // TODO: Measuring latency when not rate limited is ... a little
            // weird because if you add more simultaneous clients, you will
            // increase latency (queue delay) but we do this anyway since it is
            // useful sometimes

            long start = pieceOfWork.getStartTime();

            TransactionType type = invalidTT;
            try {
                type = doWork(preState == State.MEASURE, pieceOfWork);
            } catch (IndexOutOfBoundsException e) {
                if (phase.isThroughputRun()) {
                    LOG.error("Thread tried executing disabled phase!");
                    throw e;
                }
                if (phase.id == this.wrkldState.getCurrentPhase().id) {
                    switch (preState) {
                        case WARMUP:
                            // Don't quit yet: we haven't even begun!
                            phase.resetSerial();
                            break;
                        case COLD_QUERY:
                        case MEASURE:
                            // The serial phase is over. Finish the run early.
                            wrkldState.signalLatencyComplete();
                            LOG.info("[Serial] Serial execution of all" + " transactions complete.");
                            break;
                        default:
                            throw e;
                    }
                }
            }

            // PART 4: Record results

            long end = System.nanoTime();
            postState = wrkldState.getGlobalState();

            switch (postState) {
                case MEASURE:
                    // Non-serial measurement. Only measure if the state both
                    // before and after was MEASURE, and the phase hasn't
                    // changed, otherwise we're recording results for a query
                    // that either started during the warmup phase or ended
                    // after the timer went off.
                    if (preState == State.MEASURE && type != null && this.wrkldState.getCurrentPhase().id == phase.id) {
                        latencies.addLatency(type.getId(), start, end, this.id, phase.id);
                        intervalRequests.incrementAndGet();
                    }
                    if (phase.isLatencyRun()) {
                        this.wrkldState.startColdQuery();
                    }
                    break;
                case COLD_QUERY:
                    // No recording for cold runs, but next time we will since
                    // it'll be a hot run.
                    if (preState == State.COLD_QUERY) {
                        this.wrkldState.startHotQuery();
                    }
                    break;
                default:
                    // Do nothing
            }

            wrkldState.finishedWork();
        }

        LOG.debug("worker calling teardown");

        tearDown(false);
    }

    /**
     * Called in a loop in the thread to exercise the system under test. Each
     * implementing worker should return the TransactionType handle that was
     * executed.
     *
     * @param llr
     */
    protected final TransactionType doWork(boolean measure, SubmittedProcedure pieceOfWork) {
        TransactionStatus status = TransactionStatus.RETRY;

        final DatabaseType dbType = wrkld.getDBType();
        final boolean recordAbortMessages = wrkld.getRecordAbortMessages();
        final TransactionType next = transactionTypes.getType(pieceOfWork.getType());

        try (Connection conn = benchmarkModule.getConnection()) {

            if (!conn.getAutoCommit()) {
                LOG.warn("autocommit is already false at beginning of work.  this is a problem");
            }

            conn.setAutoCommit(false);

            if (this.wrkld.getDBType().shouldUseTransactions()) {
                conn.setTransactionIsolation(this.wrkld.getIsolationMode());
            }

            // lets add a max retry loop

            int retryCount = 0;

            while (retryCount < MAX_RETRY_COUNT && status == TransactionStatus.RETRY && this.wrkldState.getGlobalState() != State.DONE) {


                Savepoint savepoint = null;

                // For Postgres, we have to create a savepoint in order to rollback a user aborted transaction
                if (dbType == DatabaseType.POSTGRES) {
                    LOG.debug("setting savepoint");
                    savepoint = conn.setSavepoint();
                } else if (dbType == DatabaseType.COCKROACHDB) {
                    // For cockroach, a savepoint must be created with a specific name in order to rollback
                    LOG.debug("setting savepoint COCKROACH_RESTART");
                    savepoint = conn.setSavepoint("cockroach_restart");
                }


                try {

                    if (LOG.isDebugEnabled()) {
                        LOG.debug(String.format("%s %s attempting...", this, next));
                    }

                    status = this.executeWork(conn, next);

                    if (LOG.isDebugEnabled()) {
                        LOG.debug(String.format("%s %s completed with status [%s]...", this, next, status.name()));
                    }

                    if (savepoint != null) {

                        if (LOG.isDebugEnabled()) {
                            LOG.debug(String.format("%s %s releasing savepoint...", this, next));
                        }

                        conn.releaseSavepoint(savepoint);
                    }

                    if (LOG.isDebugEnabled()) {
                        LOG.debug(String.format("%s %s committing...", this, next));
                    }

                    conn.commit();

                    // User Abort Handling
                    // These are not errors
                } catch (UserAbortException ex) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("{} Aborted", next, ex);
                    }

                    /* PAVLO */
                    if (recordAbortMessages) {
                        Histogram<String> error_h = this.txnAbortMessages.get(next);
                        if (error_h == null) {
                            error_h = new Histogram<>();
                            this.txnAbortMessages.put(next, error_h);
                        }
                        error_h.put(StringUtil.abbrv(ex.getMessage(), 20));
                    }

                    if (savepoint != null) {
                        conn.rollback(savepoint);
                    } else {
                        conn.rollback();
                    }

                    status = TransactionStatus.USER_ABORTED;
                    break;

                    // Database System Specific Exception Handling
                } catch (SQLException ex) {
                    // TODO: Handle acceptable error codes for every DBMS
                    LOG.warn(String.format("%s thrown when executing '%s' on '%s' " + "[Message='%s', ErrorCode='%d', SQLState='%s']", ex.getClass().getSimpleName(), next, this.toString(), ex.getMessage(), ex.getErrorCode(), ex.getSQLState()), ex);

                    this.txnErrors.put(next);

                    if (this.wrkld.getDBType().shouldUseTransactions()) {
                        if (savepoint != null) {
                            conn.rollback(savepoint);
                        } else {
                            conn.rollback();
                        }
                    }

                    if (ex.getSQLState() == null) {
                        continue;
                        // ------------------
                        // MYSQL
                        // ------------------
                    } else if (ex.getErrorCode() == 1213 && ex.getSQLState().equals("40001")) {
                        // MySQLTransactionRollbackException
                        continue;
                    } else if (ex.getErrorCode() == 1205 && ex.getSQLState().equals("41000")) {
                        // MySQL Lock timeout
                        continue;

                        // ------------------
                        // SQL SERVER
                        // ------------------
                    } else if (ex.getErrorCode() == 1205 && ex.getSQLState().equals("40001")) {
                        // SQLServerException Deadlock
                        continue;

                        // ------------------
                        // POSTGRES
                        // ------------------
                    } else if (ex.getErrorCode() == 0 && ex.getSQLState() != null && ex.getSQLState().equals("40001")) {
                        // Postgres serialization
                        LOG.warn("calling PG/CRDB retry...");
                        continue;
                    } else if (ex.getErrorCode() == 0 && ex.getSQLState() != null && ex.getSQLState().equals("53200")) {
                        // Postgres OOM error
                        throw ex;
                    } else if (ex.getErrorCode() == 0 && ex.getSQLState() != null && ex.getSQLState().equals("XX000")) {
                        // Postgres no pinned buffers available
                        throw ex;

                        // ------------------
                        // ORACLE
                        // ------------------
                    } else if (ex.getErrorCode() == 8177 && ex.getSQLState().equals("72000")) {
                        // ORA-08177: Oracle Serialization
                        continue;

                        // ------------------
                        // DB2
                        // ------------------
                    } else if (ex.getErrorCode() == -911 && ex.getSQLState().equals("40001")) {
                        // DB2Exception Deadlock
                        continue;
                    } else if ((ex.getErrorCode() == 0 && ex.getSQLState().equals("57014")) || (ex.getErrorCode() == -952 && ex.getSQLState().equals("57014"))) {
                        // Query cancelled by benchmark because we changed
                        // state. That's fine! We expected/caused this.
                        status = TransactionStatus.RETRY_DIFFERENT;
                        continue;
                    } else if (ex.getErrorCode() == 0 && ex.getSQLState().equals("02000")) {
                        // No results returned. That's okay, we can proceed to
                        // a different query. But we should send out a warning,
                        // too, since this is unusual.
                        status = TransactionStatus.RETRY_DIFFERENT;
                        continue;

                        // ------------------
                        // UNKNOWN!
                        // ------------------
                    } else {
                        // UNKNOWN: In this case .. Retry as well!
                        LOG.warn("The DBMS rejected the transaction without an error code", ex);
                        continue;
                        // FIXME Disable this for now
                        // throw ex;
                    }
                    // Assertion Error
                } catch (Error ex) {
                    LOG.error("Fatal error when invoking {}", next, ex);
                    throw ex;
                    // Random Error
                } catch (Exception ex) {
                    LOG.error("Fatal error when invoking {}", next, ex);
                    throw new RuntimeException(ex);

                } finally {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(String.format("%s %s Result: %s", this, next, status));
                    }

                    switch (status) {
                        case SUCCESS:
                            this.txnSuccess.put(next);
                            break;
                        case RETRY_DIFFERENT:
                            this.txnRetry.put(next);
                            return null;
                        case USER_ABORTED:
                            this.txnAbort.put(next);
                            break;
                        case RETRY:
                            retryCount++;

                            if (retryCount >= MAX_RETRY_COUNT) {
                                LOG.warn(String.format("%s %s retry count exceeded for transaction: %s", this, next, status));
                            } else {
                                LOG.warn(String.format("%s %s retry transaction iteration %d: %s", this, next, retryCount, status));
                            }

                            continue;
                        default:

                    } // SWITCH
                }

            }

            if (conn.getAutoCommit()) {
                LOG.warn("autocommit is already true at end of work.  this is a problem");
            }

            conn.setAutoCommit(true);
        } catch (SQLException ex) {
            String msg = String.format("Unexpected fatal, error in '%s' when executing '%s' [%s]", this, next, dbType);

            throw new RuntimeException(msg, ex);
        }

        return (next);
    }

    /**
     * Optional callback that can be used to initialize the Worker right before
     * the benchmark execution begins
     */
    protected void initialize() {
        // The default is to do nothing
    }

    /**
     * Invoke a single transaction for the given TransactionType
     *
     * @param conn
     * @param txnType
     * @return TODO
     * @throws UserAbortException TODO
     * @throws SQLException       TODO
     */
    protected abstract TransactionStatus executeWork(Connection conn, TransactionType txnType) throws UserAbortException, SQLException;

    /**
     * Called at the end of the test to do any clean up that may be required.
     *
     * @param error TODO
     */
    public void tearDown(boolean error) {

    }

    public void initializeState() {
        this.wrkldState = this.wrkld.getWorkloadState();
    }
}
