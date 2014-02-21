// @java.file.header

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.dataload;

import org.gridgain.grid.*;
import org.gridgain.grid.dataload.*;
import org.gridgain.grid.events.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.managers.communication.*;
import org.gridgain.grid.kernal.managers.deployment.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.tostring.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.events.GridEventType.*;
import static org.gridgain.grid.kernal.GridTopic.*;
import static org.gridgain.grid.kernal.managers.communication.GridIoPolicy.*;

/**
 * Data loader implementation.
 *
 * @author @java.author
 * @version @java.version
 */
public class GridDataLoaderImpl<K, V> implements GridDataLoader<K, V>, Delayed {
    /** Cache updater. */
    private GridDataLoadCacheUpdater<K, V> updater = GridDataLoadCacheUpdaters.single();

    /** */
    private byte[] updaterBytes;

    /** Max remap count before issuing an error. */
    private static final int MAX_REMAP_CNT = 32;

    /** Log reference. */
    private static final AtomicReference<GridLogger> logRef = new AtomicReference<>();

    /** Cache name ({@code null} for default cache). */
    private String cacheName;

    /** Per-node buffer size. */
    @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
    private int bufSize = DFLT_PER_NODE_BUFFER_SIZE;

    /** */
    private int parallelOps = DFLT_MAX_PARALLEL_OPS;

    /** */
    private long autoFlushFreq;

    /** Mapping. */
    @GridToStringInclude
    private ConcurrentMap<UUID, Buffer> bufMappings = new ConcurrentHashMap8<>();

    /** Logger. */
    private GridLogger log;

    /** Discovery listener. */
    private final GridLocalEventListener discoLsnr;

    /** Context. */
    private final GridKernalContext ctx;

    /** Communication topic for responses. */
    private final Object topic;

    /** */
    private byte[] topicBytes;

    /** {@code True} if data loader has been cancelled. */
    private volatile boolean cancelled;

    /** Active futures of this data loader. */
    @GridToStringInclude
    private final Collection<GridFuture<?>> activeFuts = new GridConcurrentHashSet<>();

    /** Closure to remove from active futures. */
    @GridToStringExclude
    private final GridInClosure<GridFuture<?>> rmvActiveFut = new GridInClosure<GridFuture<?>>() {
        @Override public void apply(GridFuture<?> t) {
            boolean rmv = activeFuts.remove(t);

            assert rmv;
        }
    };

    /** Job peer deploy aware. */
    private volatile GridPeerDeployAware jobPda;

    /** Deployment class. */
    private Class<?> depCls;

    /** Future to track loading finish. */
    private final GridFutureAdapter<?> fut;

    /** Busy lock. */
    private final GridSpinBusyLock busyLock = new GridSpinBusyLock();

    /** Closed flag. */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** */
    private volatile long lastFlushTime = U.currentTimeMillis();

    /** */
    private final DelayQueue<GridDataLoaderImpl<K, V>> flushQ;

    /**
     * @param ctx Grid kernal context.
     * @param cacheName Cache name.
     * @param flushQ Flush queue.
     */
    public GridDataLoaderImpl(final GridKernalContext ctx, @Nullable final String cacheName,
        DelayQueue<GridDataLoaderImpl<K, V>> flushQ) {
        assert ctx != null;

        this.ctx = ctx;
        this.cacheName = cacheName;
        this.flushQ = flushQ;

        log = U.logger(ctx, logRef, GridDataLoaderImpl.class);

        discoLsnr = new GridLocalEventListener() {
            @Override public void onEvent(GridEvent evt) {
                assert evt.type() == EVT_NODE_FAILED || evt.type() == EVT_NODE_LEFT;

                GridDiscoveryEvent discoEvt = (GridDiscoveryEvent)evt;

                UUID id = discoEvt.eventNodeId();

                // Remap regular mappings.
                final Buffer buf = bufMappings.remove(id);

                if (buf != null) {
                    // Only async notification is possible since
                    // discovery thread may be trapped otherwise.
                    ctx.closure().callLocalSafe(
                        new Callable<Object>() {
                            @Override public Object call() throws Exception {
                                buf.onNodeLeft();

                                return null;
                            }
                        },
                        true /* system pool */
                    );
                }
            }
        };

        ctx.event().addLocalEventListener(discoLsnr, EVT_NODE_FAILED, EVT_NODE_LEFT);

        // Generate unique topic for this loader.
        topic = TOPIC_DATALOAD.topic(GridUuid.fromUuid(ctx.localNodeId()));

        ctx.io().addMessageListener(topic, new GridMessageListener() {
            @Override public void onMessage(UUID nodeId, Object msg) {
                assert msg instanceof GridDataLoadResponse;

                GridDataLoadResponse res = (GridDataLoadResponse)msg;

                if (log.isDebugEnabled())
                    log.debug("Received data load response: " + res);

                Buffer buf = bufMappings.get(nodeId);

                if (buf != null)
                    buf.onResponse(res);

                else if (log.isDebugEnabled())
                    log.debug("Ignoring response since node has left [nodeId=" + nodeId + ", ");
            }
        });

        if (log.isDebugEnabled())
            log.debug("Added response listener within topic: " + topic);

        fut = new GridDataLoaderFuture(ctx, this);
    }

    /**
     * Enters busy lock.
     */
    private void enterBusy() {
        if (!busyLock.enterBusy())
            throw new IllegalStateException("Data loader has been closed.");
    }

    /**
     * Leaves busy lock.
     */
    private void leaveBusy() {
        busyLock.leaveBusy();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> future() {
        return fut;
    }

    /** {@inheritDoc} */
    @Override public void deployClass(Class<?> depCls) {
        this.depCls = depCls;
    }

    /** {@inheritDoc} */
    @Override public void updater(GridDataLoadCacheUpdater<K, V> updater) {
        A.notNull(updater, "updater");

        this.updater = updater;
    }

    /** {@inheritDoc} */
    @Override @Nullable public String cacheName() {
        return cacheName;
    }

    /** {@inheritDoc} */
    @Override public int perNodeBufferSize() {
        return bufSize;
    }

    /** {@inheritDoc} */
    @Override public void perNodeBufferSize(int bufSize) {
        A.ensure(bufSize > 0, "bufSize > 0");

        this.bufSize = bufSize;
    }

    /** {@inheritDoc} */
    @Override public int perNodeParallelLoadOperations() {
        return parallelOps;
    }

    /** {@inheritDoc} */
    @Override public void perNodeParallelLoadOperations(int parallelOps) {
        this.parallelOps = parallelOps;
    }

    /** {@inheritDoc} */
    @Override public long autoFlushFrequency() {
        return autoFlushFreq;
    }

    /** {@inheritDoc} */
    @Override public void autoFlushFrequency(long autoFlushFreq) {
        A.ensure(autoFlushFreq >= 0, "autoFlushFreq >= 0");

        long old = this.autoFlushFreq;

        if (autoFlushFreq != old) {
            this.autoFlushFreq = autoFlushFreq;

            if (autoFlushFreq != 0 && old == 0)
                flushQ.add(this);
            else if (autoFlushFreq == 0 && old != 0)
                flushQ.remove(this);
        }
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> addData(Collection<? extends GridDataLoadEntry<K, V>> entries) {
        A.ensure(!F.isEmpty(entries), "entries can not be empty");

        enterBusy();

        try {
            GridFutureAdapter<Object> resFut = new GridFutureAdapter<>(ctx);

            activeFuts.add(resFut);

            resFut.listenAsync(rmvActiveFut);

            Collection<K> keys = new GridConcurrentHashSet<>(entries.size(), 1.0f, 16);

            try {
                for (GridDataLoadEntry<K, V> entry : entries)
                    keys.add(entry.key());
            }
            catch (GridException e) {
                resFut.onDone(e);

                return resFut;
            }

            load0(entries, resFut, keys, 0);

            return resFut;
        }
        finally {
            leaveBusy();
        }
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> addData(GridDataLoadEntry<K, V> entry) throws GridException, GridInterruptedException,
        IllegalStateException {
        A.notNull(entry, "entry");

        return addData(F.asList(entry));
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> addData(K key, V val) throws GridException, GridInterruptedException,
        IllegalStateException {
        A.notNull(key, "key");

        return addData(new GridDataLoadEntryAdapter<>(key, val));
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> removeData(K key) throws GridException, GridInterruptedException,
        IllegalStateException {
        return addData(key, null);
    }

    /**
     * @param entries Entries.
     * @param resFut Result future.
     * @param activeKeys Active keys.
     * @param remaps Remaps count.
     */
    private void load0(
        Collection<? extends GridDataLoadEntry<K, V>> entries,
        final GridFutureAdapter<Object> resFut,
        final Collection<K> activeKeys,
        final int remaps
    ) {
        assert entries != null;

        if (remaps >= MAX_REMAP_CNT) {
            resFut.onDone(new GridException("Failed to finish operation (too many remaps): " + remaps));

            return;
        }

        Map<GridNode, Collection<GridDataLoadEntry<K, V>>> mappings = new HashMap<>();

        boolean initPda = ctx.deploy().enabled() && jobPda == null;

        for (GridDataLoadEntry<K, V> entry : entries) {
            GridNode node;

            try {
                K key = entry.key();

                assert key != null;

                if (initPda) {
                    jobPda = new DataLoaderPda(key, entry.value(), updater);

                    initPda = false;
                }

                node = ctx.affinity().mapKeyToNode(cacheName, key);
            }
            catch (GridException e) {
                resFut.onDone(e);

                return;
            }

            if (node == null) {
                resFut.onDone(new GridTopologyException("Failed to map key to node " +
                    "(no nodes with cache found in topology) [infos=" + entries.size() +
                    ", cacheName=" + cacheName + ']'));

                return;
            }

            Collection<GridDataLoadEntry<K, V>> col = mappings.get(node);

            if (col == null)
                mappings.put(node, col = new ArrayList<>());

            col.add(entry);
        }

        for (final Map.Entry<GridNode, Collection<GridDataLoadEntry<K, V>>> e : mappings.entrySet()) {
            final UUID nodeId = e.getKey().id();

            Buffer buf = bufMappings.get(nodeId);

            if (buf == null) {
                Buffer old = bufMappings.putIfAbsent(nodeId, buf = new Buffer(e.getKey()));

                if (old != null)
                    buf = old;
            }

            final Collection<GridDataLoadEntry<K, V>> entriesForNode = e.getValue();

            GridInClosure<GridFuture<?>> lsnr = new GridInClosure<GridFuture<?>>() {
                @Override public void apply(GridFuture<?> t) {
                    try {
                        t.get();

                        for (GridDataLoadEntry<K, V> e : entriesForNode)
                            activeKeys.remove(e.key());

                        if (activeKeys.isEmpty())
                            resFut.onDone();
                    }
                    catch (GridException e1) {
                        if (log.isDebugEnabled())
                            log.debug("Future finished with error [nodeId=" + nodeId + ", err=" + e1 + ']');

                        if (cancelled) {
                            resFut.onDone(new GridException("Data loader has been cancelled: " +
                                GridDataLoaderImpl.this, e1));
                        }
                        else
                            load0(entriesForNode, resFut, activeKeys, remaps + 1);
                    }
                }
            };

            GridFutureAdapter<?> f;

            try {
                f = buf.update(entriesForNode, lsnr);
            }
            catch (GridInterruptedException e1) {
                resFut.onDone(e1);

                return;
            }

            if (ctx.discovery().node(nodeId) == null) {
                if (bufMappings.remove(nodeId, buf))
                    buf.onNodeLeft();

                if (f != null)
                    f.onDone(new GridTopologyException("Failed to wait for request completion " +
                        "(node has left): " + nodeId));
            }
        }
    }

    /**
     * Performs flush.
     *
     * @throws GridException If failed.
     */
    private void doFlush() throws GridException {
        lastFlushTime = U.currentTimeMillis();

        List<GridFuture> activeFuts0 = null;

        int doneCnt = 0;

        for (GridFuture<?> f : activeFuts) {
            if (!f.isDone()) {
                if (activeFuts0 == null)
                    activeFuts0 = new ArrayList<>((int)(activeFuts.size() * 1.2));

                activeFuts0.add(f);
            }
            else {
                f.get();

                doneCnt++;
            }
        }

        if (activeFuts0 == null || activeFuts0.isEmpty())
            return;

        while (true) {
            Queue<GridFuture<?>> q = null;

            for (Buffer buf : bufMappings.values()) {
                GridFuture<?> flushFut = buf.flush();

                if (flushFut != null) {
                    if (q == null)
                        q = new ArrayDeque<>(bufMappings.size() * 2);

                    q.add(flushFut);
                }
            }

            if (q != null) {
                assert !q.isEmpty();

                boolean err = false;

                for (GridFuture fut = q.poll(); fut != null; fut = q.poll()) {
                    try {
                        fut.get();
                    }
                    catch (GridException e) {
                        if (log.isDebugEnabled())
                            log.debug("Failed to flush buffer: " + e);

                        err = true;
                    }
                }

                if (err)
                    // Remaps needed - flush buffers.
                    continue;
            }

            doneCnt = 0;

            for (int i = 0; i < activeFuts0.size(); i++) {
                GridFuture f = activeFuts0.get(i);

                if (f == null)
                    doneCnt++;
                else if (f.isDone()) {
                    f.get();

                    doneCnt++;

                    activeFuts0.set(i, null);
                }
                else
                    break;
            }

            if (doneCnt == activeFuts0.size())
                return;
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    @Override public void flush() throws GridException {
        enterBusy();

        try {
            doFlush();
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * Flushes every internal buffer if buffer was flushed before passed in
     * threshold.
     * <p>
     * Does not wait for result and does not fail on errors assuming that this method
     * should be called periodically.
     */
    @Override public void tryFlush() throws GridInterruptedException {
        if (!busyLock.enterBusy())
            return;

        try {
            for (Buffer buf : bufMappings.values())
                buf.flush();

            lastFlushTime = U.currentTimeMillis();
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param cancel {@code True} to close with cancellation.
     * @throws GridException If failed.
     */
    @Override public void close(boolean cancel) throws GridException {
        if (!closed.compareAndSet(false, true))
            return;

        busyLock.block();

        if (log.isDebugEnabled())
            log.debug("Closing data loader [ldr=" + this + ", cancel=" + cancel + ']');

        GridException e = null;

        try {
            // Assuming that no methods are called on this loader after this method is called.
            if (cancel) {
                cancelled = true;

                for (Buffer buf : bufMappings.values())
                    buf.cancelAll();
            }
            else
                doFlush();

            ctx.event().removeLocalEventListener(discoLsnr);

            ctx.io().removeMessageListener(topic);
        }
        catch (GridException e0) {
            e = e0;
        }

        fut.onDone(null, e);

        if (e != null)
            throw e;
    }

    /**
     * @return {@code true} If the loader is closed.
     */
    boolean isClosed() {
        return fut.isDone();
    }

    /** {@inheritDoc} */
    @Override public void close() throws GridException {
        close(false);
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridDataLoaderImpl.class, this);
    }

    /** {@inheritDoc} */
    @Override public long getDelay(TimeUnit unit) {
        return unit.convert(nextFlushTime() - U.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * @return Next flush time.
     */
    private long nextFlushTime() {
        return lastFlushTime + autoFlushFreq;
    }

    /** {@inheritDoc} */
    @Override public int compareTo(Delayed o) {
        return nextFlushTime() > ((GridDataLoaderImpl)o).nextFlushTime() ? 1 : -1;
    }

    /**
     *
     */
    private class Buffer {
        /** Node. */
        private final GridNode node;

        /** Active futures. */
        private final Collection<GridFuture<Object>> locFuts;

        /** Buffered entries. */
        private List<GridDataLoadEntry<K, V>> entries;

        /** */
        @GridToStringExclude
        private GridFutureAdapter<Object> curFut;

        /** Local node flag. */
        private final boolean isLocNode;

        /** ID generator. */
        private final AtomicLong idGen = new AtomicLong();

        /** Active futures. */
        private final ConcurrentMap<Long, GridFutureAdapter<Object>> reqs;

        /** */
        private final Semaphore sem;

        /** Closure to signal on task finish. */
        @GridToStringExclude
        private final GridInClosure<GridFuture<Object>> signalC = new GridInClosure<GridFuture<Object>>() {
            @Override public void apply(GridFuture<Object> t) {
                signalTaskFinished(t);
            }
        };

        /**
         * @param node Node.
         */
        Buffer(GridNode node) {
            assert node != null;

            this.node = node;

            locFuts = new GridConcurrentHashSet<>();
            reqs = new ConcurrentHashMap8<>();

            // Cache local node flag.
            isLocNode = node.equals(ctx.discovery().localNode());

            entries = newEntries();
            curFut = new GridFutureAdapter<>(ctx);
            curFut.listenAsync(signalC);

            sem = new Semaphore(parallelOps);
        }

        /**
         * @param newEntries Infos.
         * @param lsnr Listener for the operation future.
         * @throws GridInterruptedException If failed.
         * @return Future for operation.
         */
        @Nullable GridFutureAdapter<?> update(Iterable<GridDataLoadEntry<K, V>> newEntries,
            GridInClosure<GridFuture<?>> lsnr) throws GridInterruptedException {
            List<GridDataLoadEntry<K, V>> entries0 = null;
            GridFutureAdapter<Object> curFut0;

            synchronized (this) {
                curFut0 = curFut;

                curFut0.listenAsync(lsnr);

                for (GridDataLoadEntry<K, V> entry : newEntries)
                    entries.add(entry);

                if (entries.size() >= bufSize) {
                    entries0 = entries;

                    entries = newEntries();
                    curFut = new GridFutureAdapter<>(ctx);
                    curFut.listenAsync(signalC);
                }
            }

            if (entries0 != null) {
                submit(entries0, curFut0);

                if (cancelled)
                    curFut0.onDone(new GridException("Data loader has been cancelled: " + GridDataLoaderImpl.this));
            }

            return curFut0;
        }

        /**
         * @return Fresh collection with some space for outgrowth.
         */
        private List<GridDataLoadEntry<K, V>> newEntries() {
            return new ArrayList<>((int)(bufSize * 1.2));
        }

        /**
         * @return Future if any submitted.
         *
         * @throws GridInterruptedException If thread has been interrupted.
         */
        @Nullable GridFuture<?> flush() throws GridInterruptedException {
            List<GridDataLoadEntry<K, V>> entries0 = null;
            GridFutureAdapter<Object> curFut0 = null;

            synchronized (this) {
                if (!entries.isEmpty()) {
                    entries0 = entries;
                    curFut0 = curFut;

                    entries = newEntries();
                    curFut = new GridFutureAdapter<>(ctx);
                    curFut.listenAsync(signalC);
                }
            }

            if (entries0 != null)
                submit(entries0, curFut0);

            // Create compound future for this flush.
            GridCompoundFuture<Object, Object> res = null;

            for (GridFuture<Object> f : locFuts) {
                if (res == null)
                    res = new GridCompoundFuture<>(ctx);

                res.add(f);
            }

            for (GridFuture<Object> f : reqs.values()) {
                if (res == null)
                    res = new GridCompoundFuture<>(ctx);

                res.add(f);
            }

            if (res != null)
                res.markInitialized();

            return res;
        }

        /**
         * Increments active tasks count.
         *
         * @throws GridInterruptedException If thread has been interrupted.
         */
        private void incrementActiveTasks() throws GridInterruptedException {
            U.acquire(sem);
        }

        /**
         * @param f Future that finished.
         */
        private void signalTaskFinished(GridFuture<Object> f) {
            assert f != null;

            sem.release();
        }

        /**
         * @param entries Entries to submit.
         * @param curFut Current future.
         * @throws GridInterruptedException If interrupted.
         */
        private void submit(final List<GridDataLoadEntry<K, V>> entries, final GridFutureAdapter<Object> curFut)
            throws GridInterruptedException {
            assert entries != null;
            assert !entries.isEmpty();
            assert curFut != null;

            incrementActiveTasks();

            GridFuture<Object> fut;
            if (isLocNode) {
                fut = ctx.closure().callLocalSafe(
                    new GridDataLoadUpdateJob<>(ctx, log, cacheName, entries, false, updater), false);

                locFuts.add(fut);

                fut.listenAsync(new GridInClosure<GridFuture<Object>>() {
                    @Override public void apply(GridFuture<Object> t) {
                        try {
                            boolean rmv = locFuts.remove(t);

                            assert rmv;

                            curFut.onDone(t.get());
                        }
                        catch (GridException e) {
                            curFut.onDone(e);
                        }
                    }
                });
            }
            else {
                byte[] entriesBytes;

                try {
                    entriesBytes = ctx.config().getMarshaller().marshal(entries);

                    if (updaterBytes == null) {
                        assert updater != null;

                        updaterBytes = ctx.config().getMarshaller().marshal(updater);
                    }

                    if (topicBytes == null)
                        topicBytes = ctx.config().getMarshaller().marshal(topic);
                }
                catch (GridException e) {
                    U.error(log, "Failed to marshal (request will not be sent).", e);

                    return;
                }

                GridDeployment dep = null;

                GridPeerDeployAware jobPda0 = jobPda;

                if (ctx.deploy().enabled()) {
                    try {
                        dep = ctx.deploy().deploy(jobPda0.deployClass(), jobPda0.classLoader());
                    }
                    catch (GridException e) {
                        U.error(log, "Failed to deploy class (request will not be sent): " + jobPda0.deployClass(), e);

                        return;
                    }

                    if (dep == null)
                        U.warn(log, "Failed to deploy class (request will be sent): " + jobPda0.deployClass());
                }

                long reqId = idGen.incrementAndGet();

                fut = curFut;

                reqs.put(reqId, (GridFutureAdapter<Object>)fut);

                GridDataLoadRequest<Object, Object> req = new GridDataLoadRequest<>(
                    reqId,
                    topicBytes,
                    cacheName,
                    updaterBytes,
                    entriesBytes,
                    true,
                    dep != null ? dep.deployMode() : null,
                    dep != null ? jobPda0.deployClass().getName() : null,
                    dep != null ? dep.userVersion() : null,
                    dep != null ? dep.participants() : null,
                    dep != null ? dep.classLoaderId() : null,
                    dep == null);

                try {
                    ctx.io().send(node, TOPIC_DATALOAD, req, PUBLIC_POOL);

                    if (log.isDebugEnabled())
                        log.debug("Sent request to node [nodeId=" + node.id() + ", req=" + req + ']');
                }
                catch (GridException e) {
                    if (ctx.discovery().alive(node) && ctx.discovery().pingNode(node.id()))
                        ((GridFutureAdapter<Object>)fut).onDone(e);
                    else
                        ((GridFutureAdapter<Object>)fut).onDone(new GridTopologyException("Failed to send " +
                            "request (node has left): " + node.id()));
                }
            }
        }

        /**
         *
         */
        void onNodeLeft() {
            assert !isLocNode;
            assert bufMappings.get(node.id()) != this;

            if (log.isDebugEnabled())
                log.debug("Forcibly completing futures (node has left): " + node.id());

            Exception e = new GridTopologyException("Failed to wait for request completion " +
                "(node has left): " + node.id());

            for (GridFutureAdapter<Object> f : reqs.values())
                f.onDone(e);

            // Make sure to complete current future.
            GridFutureAdapter<Object> curFut0;

            synchronized (this) {
                curFut0 = curFut;
            }

            curFut0.onDone(e);
        }

        /**
         * @param res Response.
         */
        void onResponse(GridDataLoadResponse res) {
            if (log.isDebugEnabled())
                log.debug("Received data load response: " + res);

            GridFutureAdapter<?> f = reqs.remove(res.requestId());

            if (f == null) {
                if (log.isDebugEnabled())
                    log.debug("Future for request has not been found: " + res.requestId());

                return;
            }

            Throwable err = null;

            byte[] errBytes = res.errorBytes();

            if (errBytes != null) {
                try {
                    err = ctx.config().getMarshaller().unmarshal(errBytes, jobPda.classLoader());
                }
                catch (GridException e) {
                    f.onDone(null, new GridException("Failed to unmarshal response.", e));

                    return;
                }
            }

            f.onDone(null, err);

            if (log.isDebugEnabled())
                log.debug("Finished future [fut=" + f + ", reqId=" + res.requestId() + ", err=" + err + ']');
        }

        /**
         *
         */
        void cancelAll() {
            GridException err = new GridException("Data loader has been cancelled: " + GridDataLoaderImpl.this);

            for (GridFuture<?> f : locFuts) {
                try {
                    f.cancel();
                }
                catch (GridException e) {
                    U.error(log, "Failed to cancel mini-future.", e);
                }
            }

            for (GridFutureAdapter<?> f : reqs.values())
                f.onDone(err);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            int size;

            synchronized (this) {
                size = entries.size();
            }

            return S.toString(Buffer.class, this,
                "entriesCnt", size,
                "locFutsSize", locFuts.size(),
                "reqsSize", reqs.size());
        }
    }

    /**
     * Data loader peer-deploy aware.
     */
    private class DataLoaderPda implements GridPeerDeployAware {
        /** Deploy class. */
        private Class<?> cls;

        /** Class loader. */
        private ClassLoader ldr;

        /** Collection of objects to detect deploy class and class loader. */
        private Collection<Object> objs;

        /**
         * Constructs data loader peer-deploy aware.
         *
         * @param objs Collection of objects to detect deploy class and class loader.
         */
        private DataLoaderPda(Object... objs) {
            this.objs = Arrays.asList(objs);
        }

        /** {@inheritDoc} */
        @Override public Class<?> deployClass() {
            if (cls == null) {
                Class<?> cls0 = null;

                if (depCls != null)
                    cls0 = depCls;
                else {
                    for (Iterator<Object> it = objs.iterator(); (cls0 == null || U.isJdk(cls0)) && it.hasNext();) {
                        Object o = it.next();

                        if (o != null)
                            cls0 = U.detectClass(o);
                    }

                    if (cls0 == null || U.isJdk(cls0))
                        cls0 = GridDataLoaderImpl.class;
                }

                assert cls0 != null : "Failed to detect deploy class [objs=" + objs + ']';

                cls = cls0;
            }

            return cls;
        }

        /** {@inheritDoc} */
        @Override public ClassLoader classLoader() {
            if (ldr == null) {
                ClassLoader ldr0 = deployClass().getClassLoader();

                // Safety.
                if (ldr0 == null)
                    ldr0 = U.gridClassLoader();

                assert ldr0 != null : "Failed to detect classloader [objs=" + objs + ']';

                ldr = ldr0;
            }

            return ldr;
        }
    }
}
