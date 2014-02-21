// @java.file.header

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.examples.hpc.continuation.fibonacci;

import org.gridgain.grid.*;
import org.gridgain.grid.compute.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.resources.*;
import org.jetbrains.annotations.*;

import java.math.*;
import java.util.*;

/**
 * This example demonstrates how to use continuation feature of GridGain by
 * performing the distributed recursive calculation of {@code 'Fibonacci'}
 * numbers on the grid. Continuations
 * functionality is exposed via {@link org.gridgain.grid.compute.GridComputeJobContext#holdcc()} and
 * {@link org.gridgain.grid.compute.GridComputeJobContext#callcc()} method calls in {@link FibonacciClosure} class.
 * <p>
 * This is a powerful design pattern which allows for creation of fully distributively recursive
 * (a.k.a. nested) tasks or closures with continuations. This example also shows
 * usage of {@code 'continuations'}, which allows us to wait for results from remote nodes
 * without blocking threads.
 * <p>
 * Note that because this example utilizes local node storage via {@link GridNodeLocalMap},
 * it gets faster if you execute it multiple times, as the more you execute it,
 * the more values it will be cached on remote nodes.
 * <p>
 * <h1 class="header">Starting Remote Nodes</h1>
 * To try this example you should (but don't have to) start remote grid instances.
 * You can start as many as you like by executing the following script:
 * <pre class="snippet">{GRIDGAIN_HOME}/bin/ggstart.{bat|sh} examples/config/example-default.xml</pre>
 * Once remote instances are started, you can execute this example from
 * Eclipse, IntelliJ IDEA, or NetBeans (and any other Java IDE) by simply hitting run
 * button. You will see that all nodes discover each other and
 * some of the nodes will participate in task execution (check node
 * output).
 *
 * @author @java.author
 * @version @java.version
 */
public final class GridFibonacciContinuationExample {
    /**
     * This example recursively calculates {@code 'Fibonacci'} numbers on the grid. This is
     * a powerful design pattern which allows for creation of distributively recursive
     * tasks or closures with {@code 'continuations'}.
     * <p>
     * Note that because this example utilizes local node storage via {@link GridNodeLocalMap},
     * it gets faster if you execute it multiple times, as the more you execute it,
     * the more values it will be cached on remote nodes.
     *
     * @param args Command line arguments, none required but if provided
     *      first one should point to the Spring XML configuration file. See
     *      <tt>"examples/config/"</tt> for configuration file examples.
     * @throws GridException If example execution failed.
     */
    public static void main(String[] args) throws GridException {
        try (Grid g = GridGain.start("examples/config/example-default.xml")) {
            long N = 100;

            final UUID exampleNodeId = g.localNode().id();

            // Filter to exclude this node from execution.
            final GridPredicate<GridNode> nodeFilter = new GridPredicate<GridNode>() {
                @Override public boolean apply(GridNode n) {
                    // Give preference to remote nodes.
                    return g.forRemotes().nodes().isEmpty() || !n.id().equals(exampleNodeId);
                }
            };

            long start = System.currentTimeMillis();

            BigInteger fib = g.forPredicate(nodeFilter).compute().apply(new FibonacciClosure(nodeFilter), N).get();

            long duration = System.currentTimeMillis() - start;

            System.out.println(">>>");
            System.out.println(">>> Finished executing Fibonacci for '" + N + "' in " + duration + " ms.");
            System.out.println(">>> Fibonacci sequence for input number '" + N + "' is '" + fib + "'.");
            System.out.println(">>> If you re-run this example w/o stopping remote nodes - the performance will");
            System.out.println(">>> increase since intermediate results are pre-cache on remote nodes.");
            System.out.println(">>> You should see prints out every recursive Fibonacci execution on grid nodes.");
            System.out.println(">>> Check remote nodes for output.");
            System.out.println(">>>");
        }
    }

    /**
     * Closure to execute.
     */
    private static class FibonacciClosure extends GridClosure<Long, BigInteger> {
        /** Future for spawned task. */
        private GridFuture<BigInteger> fut1;

        /** Future for spawned task. */
        private GridFuture<BigInteger>fut2;

        /** Auto-inject job context. */
        @GridJobContextResource
        private GridComputeJobContext jobCtx;

        /** Grid. */
        @GridInstanceResource
        private Grid g;

        /** Predicate. */
        private final GridPredicate<GridNode> nodeFilter;

        /**
         * @param nodeFilter Predicate to filter nodes.
         */
        FibonacciClosure(GridPredicate<GridNode> nodeFilter) {
            this.nodeFilter = nodeFilter;
        }

        /** {@inheritDoc} */
        @Nullable @Override public BigInteger apply(Long n) {
            if (fut1 == null || fut2 == null) {
                System.out.println(">>> Starting fibonacci execution for number: " + n);

                // Make sure n is not negative.
                n = Math.abs(n);

                if (n <= 2)
                    return n == 0 ? BigInteger.ZERO : BigInteger.ONE;

                // Node-local storage.
                GridNodeLocalMap<Long, GridFuture<BigInteger>> store = g.nodeLocalMap();

                // Check if value is cached in node-local store first.
                fut1 = store.get(n - 1);
                fut2 = store.get(n - 2);

                GridProjection p = g.forPredicate(nodeFilter);

                // If future is not cached in node-local store, cache it.
                // Recursive grid execution.
                if (fut1 == null)
                    fut1 = store.addIfAbsent(n - 1, p.compute().apply(new FibonacciClosure(nodeFilter), n - 1));

                // If future is not cached in node-local store, cache it.
                if (fut2 == null)
                    fut2 = store.addIfAbsent(n - 2, p.compute().apply(new FibonacciClosure(nodeFilter), n - 2));

                // If futures are not done, then wait asynchronously for the result
                if (!fut1.isDone() || !fut2.isDone()) {
                    GridInClosure<GridFuture<BigInteger>> lsnr = new GridInClosure<GridFuture<BigInteger>>() {
                        @Override public void apply(GridFuture<BigInteger> f) {
                            // If both futures are done, resume the continuation.
                            if (fut1.isDone() && fut2.isDone())
                                // CONTINUATION:
                                // =============
                                // Resume suspended job execution.
                                jobCtx.callcc();
                        }
                    };

                    // CONTINUATION:
                    // =============
                    // Hold (suspend) job execution.
                    // It will be resumed in listener above via 'callcc()' call
                    // once both futures are done.
                    jobCtx.holdcc();

                    // Attach the same listener to both futures.
                    fut1.listenAsync(lsnr);
                    fut2.listenAsync(lsnr);

                    return null;
                }
            }

            assert fut1.isDone() && fut2.isDone();

            // Return cached results.
            try {
                return fut1.get().add(fut2.get());
            }
            catch (GridException e) {
                throw new GridRuntimeException(e);
            }
        }
    }
}
