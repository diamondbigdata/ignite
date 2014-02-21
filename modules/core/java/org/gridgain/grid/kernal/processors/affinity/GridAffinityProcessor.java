// @java.file.header

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.affinity;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.affinity.*;
import org.gridgain.grid.events.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.processors.*;
import org.gridgain.grid.kernal.processors.timeout.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;

import static org.gridgain.grid.kernal.GridClosureCallMode.*;
import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.events.GridEventType.*;
import static org.gridgain.grid.kernal.processors.affinity.GridAffinityUtils.*;

/**
 * Data affinity processor.
 *
 * @author @java.author
 * @version @java.version
 */
public class GridAffinityProcessor extends GridProcessorAdapter {
    /** Affinity map cleanup delay (ms). */
    private static final long AFFINITY_MAP_CLEAN_UP_DELAY = 3000;

    /** Retries to get affinity in case of error. */
    private static final int ERROR_RETRIES = 3;

    /** Time to wait between errors (in milliseconds). */
    private static final long ERROR_WAIT = 500;

    /** Null cache name. */
    private static final String NULL_NAME = U.id8(UUID.randomUUID());

    /** Affinity map. */
    private final ConcurrentMap<String, GridFuture<GridAffinityCache>> affMap = new ConcurrentHashMap8<>();

    /** Listener. */
    private final GridLocalEventListener lsnr = new GridLocalEventListener() {
        @Override public void onEvent(GridEvent evt) {
            int evtType = evt.type();

            assert evtType == EVT_NODE_FAILED || evtType == EVT_NODE_LEFT || evtType == EVT_NODE_JOINED;

            if (affMap.isEmpty())
                return; // Skip empty affinity map.

            GridDiscoveryEvent discoEvt = (GridDiscoveryEvent)evt;

            // Clean up affinity functions if such cache no more exists.
            if (evtType == EVT_NODE_FAILED || evtType == EVT_NODE_LEFT) {
                final Collection<String> rmv = new GridLeanSet<>(affMap.keySet());

                for (Iterator<GridNode> it = ctx.discovery().allNodes().iterator(); !rmv.isEmpty() && it.hasNext(); )
                    rmv.removeAll(U.cacheNames(it.next()));

                ctx.timeout().addTimeoutObject(new GridTimeoutObjectAdapter(
                    GridUuid.fromUuid(ctx.localNodeId()), AFFINITY_MAP_CLEAN_UP_DELAY) {
                    @Override public void onTimeout() {
                        affMap.keySet().removeAll(rmv);
                    }
                });
            }

            // Cleanup outdated caches.
            for (GridFuture<GridAffinityCache> f : affMap.values()) {
                try {
                    GridAffinityCache affCache = f.get();

                    if (affCache != null)
                        affCache.cleanUpCache(discoEvt.topologyVersion());
                }
                catch (GridException e) {
                    if (log.isDebugEnabled())
                        log.debug("Failed to get affinity cache [discoEvt=" + discoEvt +
                            ", err=" + e.getMessage() + ']');
                }
            }
        }
    };

    /**
     * @param ctx Context.
     */
    public GridAffinityProcessor(GridKernalContext ctx) {
        super(ctx);
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart() throws GridException {
        ctx.event().addLocalEventListener(lsnr, EVT_NODE_FAILED, EVT_NODE_LEFT, EVT_NODE_JOINED);
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop(boolean cancel) {
        if (ctx != null && ctx.event() != null)
            ctx.event().removeLocalEventListener(lsnr);
    }

    /**
     * Maps keys to nodes for given cache.
     *
     * @param cacheName Cache name.
     * @param keys Keys to map.
     * @return Map of nodes to keys.
     * @throws GridException If failed.
     */
    public <K> Map<GridNode, Collection<K>> mapKeysToNodes(@Nullable String cacheName,
        @Nullable Collection<? extends K> keys) throws GridException {
        return keysToNodes(cacheName, keys);
    }

    /**
     * Maps keys to nodes on default cache.
     *
     * @param keys Keys to map.
     * @return Map of nodes to keys.
     * @throws GridException If failed.
     */
    public <K> Map<GridNode, Collection<K>> mapKeysToNodes(@Nullable Collection<? extends K> keys)
        throws GridException {
        return keysToNodes(null, keys);
    }

    /**
     * Maps single key to a node.
     *
     * @param cacheName Cache name.
     * @param key Key to map.
     * @return Picked node.
     * @throws GridException If failed.
     */
    @Nullable public <K> GridNode mapKeyToNode(@Nullable String cacheName, K key) throws GridException {
        Map<GridNode, Collection<K>> map = keysToNodes(cacheName, F.asList(key));

        return map != null ? F.first(map.keySet()) : null;
    }

    /**
     * Maps single key to a node on default cache.
     *
     * @param key Key to map.
     * @return Picked node.
     * @throws GridException If failed.
     */
    @Nullable public <K> GridNode mapKeyToNode(K key) throws GridException {
        Map<GridNode, Collection<K>> map = keysToNodes(null, F.asList(key));

        return map != null ? F.first(map.keySet()) : null;
    }

    /**
     * Gets affinity key for cache key.
     *
     * @param cacheName Cache name.
     * @param key Cache key.
     * @return Affinity key.
     * @throws GridException In case of error.
     */
    @SuppressWarnings("unchecked")
    @Nullable public Object affinityKey(@Nullable String cacheName, @Nullable Object key) throws GridException {
        if (key == null)
            return null;

        GridAffinityCache affCache = affinityCache(cacheName);

        return affCache != null ? affCache.affinityKey(key) : null;
    }

    /**
     * @param cacheName Cache name.
     * @return Non-null cache name.
     */
    private String maskNull(@Nullable String cacheName) {
        return cacheName == null ? NULL_NAME : cacheName;
    }

    /**
     * @param cacheName Cache name.
     * @param keys Keys.
     * @return Affinity map.
     * @throws GridException If failed.
     */
    private <K> Map<GridNode, Collection<K>> keysToNodes(@Nullable final String cacheName,
        Collection<? extends K> keys) throws GridException {
        if (F.isEmpty(keys))
            return Collections.emptyMap();

        GridNode loc = ctx.discovery().localNode();

        if (U.hasCache(loc, cacheName) && ctx.cache().cache(cacheName).configuration().getCacheMode() == LOCAL)
            return F.asMap(loc, (Collection<K>)keys);

        GridAffinityCache affCache = affinityCache(cacheName);

        return affCache != null ? affinityMap(affCache, keys) : Collections.<GridNode, Collection<K>>emptyMap();
    }

    /**
     * @param cacheName Cache name.
     * @return Affinity cache.
     * @throws GridException In case of error.
     */
    @SuppressWarnings("ErrorNotRethrown")
    private <K> GridAffinityCache affinityCache(@Nullable final String cacheName)
        throws GridException {
        GridFuture <GridAffinityCache> fut = affMap.get(maskNull(cacheName));

        if (fut != null)
            return fut.get();

        GridNode loc = ctx.discovery().localNode();

        // Check local node.
        if (U.hasCache(loc, cacheName)) {
            GridCache<K, ?> cache = ctx.cache().cache(cacheName);

            GridCacheAffinity a = cache.configuration().getAffinity();
            GridCacheAffinityMapper m = cache.configuration().getAffinityMapper();
            GridAffinityCache affCache = new GridAffinityCache(ctx, cacheName, a, m);

            GridFuture<GridAffinityCache> old = affMap.putIfAbsent(maskNull(cacheName),
                new GridFinishedFuture<>(ctx, affCache));

            if (old != null)
                affCache = old.get();

            return affCache;
        }

        Collection<GridNode> cacheNodes = F.view(
            ctx.discovery().remoteNodes(),
            new P1<GridNode>() {
                @Override public boolean apply(GridNode n) {
                    return U.hasCache(n, cacheName);
                }
            });

        if (F.isEmpty(cacheNodes))
            return null;

        GridFutureAdapter<GridAffinityCache> fut0 = new GridFutureAdapter<>();

        GridFuture<GridAffinityCache> old = affMap.putIfAbsent(maskNull(cacheName), fut0);

        if (old != null)
            return old.get();

        int max = ERROR_RETRIES;
        int cnt = 0;

        Iterator<GridNode> it = cacheNodes.iterator();

        // We are here because affinity has not been fetched yet, or cache mode is LOCAL.
        while (true) {
            cnt++;

            if (!it.hasNext())
                it = cacheNodes.iterator();

            // Double check since we deal with dynamic view.
            if (!it.hasNext())
                // Exception will be caught in this method.
                throw new GridException("No cache nodes in topology for cache name: " + cacheName);

            GridNode n = it.next();

            GridCacheMode mode = U.cacheMode(n, cacheName);

            assert mode != null;

            // Map all keys to a single node, if the cache mode is LOCAL.
            if (mode == LOCAL) {
                fut0.onDone(new GridException("Failed to map keys for LOCAL cache."));

                // Will throw exception.
                fut0.get();
            }

            try {
                // Resolve cache context for remote node.
                // Set affinity function before counting down on latch.
                fut0.onDone(affinityFromNode(cacheName, n));

                break;
            }
            catch (GridException e) {
                if (log.isDebugEnabled())
                    log.debug("Failed to get affinity from node (will retry) [cache=" + cacheName +
                        ", node=" + U.toShortString(n) + ", msg=" + e.getMessage() + ']');

                if (cnt < max) {
                    U.sleep(ERROR_WAIT);

                    continue;
                }

                affMap.remove(maskNull(cacheName), fut0);

                fut0.onDone(new GridException("Failed to get affinity mapping from node: " + n, e));
            }
            catch (RuntimeException | Error e) {
                fut0.onDone(new GridException("Failed to get affinity mapping from node: " + n, e));
            }
        }

        return fut0.get();
    }

    /**
     * Requests {@link GridCacheAffinity} and {@link GridCacheAffinityMapper} from remote node.
     *
     * @param cacheName Name of cache on which affinity is requested.
     * @param n Node from which affinity is requested.
     * @return Affinity cached function.
     * @throws GridException If either local or remote node cannot get deployment for affinity objects.
     */
    private GridAffinityCache affinityFromNode(@Nullable String cacheName, GridNode n)
        throws GridException {
        GridTuple3<GridAffinityMessage, GridAffinityMessage, GridException> t = ctx.closure()
            .callAsyncNoFailover(BALANCE, affinityJob(cacheName), F.asList(n), true/*system pool*/).get();

        // Throw exception if remote node failed to deploy result.
        GridException err = t.get3();

        if (err != null)
            throw err;

        GridCacheAffinityMapper m = (GridCacheAffinityMapper)unmarshall(ctx, n.id(), t.get1());
        GridCacheAffinity a = (GridCacheAffinity)unmarshall(ctx, n.id(), t.get2());

        assert a != null;
        assert m != null;

        // Bring to initial state.
        a.reset();
        m.reset();

        return new GridAffinityCache(ctx, cacheName, a, m);
    }

    /**
     * @param aff Affinity function.
     * @param keys Keys.
     * @return Affinity map.
     * @throws GridException If failed.
     */
    @SuppressWarnings({"unchecked"})
    private <K> Map<GridNode, Collection<K>> affinityMap(GridAffinityCache aff, Collection<? extends K> keys)
        throws GridException {
        assert aff != null;
        assert !F.isEmpty(keys);

        long topVer = ctx.discovery().topologyVersion();

        try {
            if (keys.size() == 1)
                return Collections.singletonMap(primary(aff, F.first(keys), topVer), (Collection<K>)keys);

            Map<GridNode, Collection<K>> map = new GridLeanMap<>();

            for (K k : keys) {
                GridNode n = primary(aff, k, topVer);

                Collection<K> mapped = map.get(n);

                if (mapped == null)
                    map.put(n, mapped = new LinkedList<>());

                mapped.add(k);
            }

            return map;
        }
        catch (GridRuntimeException e) {
            // Affinity calculation may lead to GridRuntimeException if no cache nodes found for pair cacheName+topVer.
            throw new GridException("Failed to get affinity map for keys: " + keys, e);
        }
    }

    /**
     * Get primary node for cached key.
     *
     * @param aff Affinity function.
     * @param key Key to check.
     * @param topVer Topology version.
     * @return Primary node for given key.
     * @throws GridException In case of error.
     */
    private <K> GridNode primary(GridAffinityCache aff, K key, long topVer) throws GridException {
        Collection<GridNode> nodes = aff.nodes(aff.partition(key), topVer);

        if (F.isEmpty(nodes))
            throw new GridException("Failed to get affinity nodes [aff=" + aff + ", key=" + key +
                ", topVer=" + topVer + ']');

        return nodes.iterator().next();
    }

    /** {@inheritDoc} */
    @Override public void printMemoryStats() {
        X.println(">>>");
        X.println(">>> Affinity processor memory stats [grid=" + ctx.gridName() + ']');
        X.println(">>>   affMapSize: " + affMap.size());
    }
}
