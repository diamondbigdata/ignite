// @java.file.header

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.compute;

import org.gridgain.grid.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.spi.deployment.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.typedef.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Defines grid projection interface and monadic set of operations on a set of grid nodes.
 * <p>
 * All main grid entities such as grid and a node instances can be viewed as
 * collection of grid nodes (in case of the grid node this collection consist of only one
 * element). As such they all share the same set of operations that can be performed on a set
 * grid nodes. These operations are defined in {@link GridCompute} interface and called
 * <tt>monadic</tt> as they are equally defined on any arbitrary set of nodes.
 *
 * <h1 class="header">Nullable and Monads</h1>
 * Many methods in this interface accepts nullable parameters. Although it may seem counter intuitive
 * for some of them - it is done to promote monadic usage of this interface. Java doesn't natively support
 * concepts like <tt>Option</tt> in Scala and returning, accepting, and properly handling
 * {@code null} values is Java's way to support such monadic invocations.
 * <p>
 * All methods that accept {@code null} values (for monadic purposes) will gracefully handle it by
 * either returning a finished future, or empty collection, {@code null} value, or combination of the
 * above. Most method calls therefore can be chained without an explicit checks for {@code null}s.
 * <p>
 * The downside of this approach that inadvertent errors of passing {@code null} will not result
 * in {@link NullPointerException} and may be harder to catch.
 *
 * @author @java.author
 * @version @java.version
 */
public interface GridCompute {
    /**
     * @return TODO
     */
    public GridProjection projection();

    /**
     * Executes given closure on the node where data for provided affinity key is located. This
     * is known as affinity co-location between compute grid (a closure) and in-memory data grid
     * (value with affinity key).
     * <p>
     * This method does not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Note that class {@link GridAbsClosure} implements {@link Runnable} and class {@link GridOutClosure}
     * implements {@link Callable} interface. Note also that class {@link GridFunc} and typedefs provide rich
     * APIs and functionality for closures and predicates based processing in GridGain. While Java interfaces
     * {@link Runnable} and {@link Callable} allow for lowest common denominator for APIs - it is advisable
     * to use richer Functional Programming support provided by GridGain available in {@link org.gridgain.grid.lang}
     * package.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param cacheName Name of the cache to use for affinity co-location.
     * @param affKey Affinity key.
     * @param job Closure to affinity co-located on the node with given affinity key and execute.
     * @return Non-cancellable future of this execution.
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withResultClosure(GridBiClosure)
     * @see #withTopologySpi(String)
     * @see GridComputeJobContext#cacheName()
     * @see GridComputeJobContext#affinityKey()
     */
    public GridFuture<?> affinityRun(@Nullable String cacheName, Object affKey, Runnable job);

    /**
     * Executes given closure on the nodes where data for provided affinity keys
     * is located. This is known as affinity co-location between compute grid (a closure) and in-memory data grid
     * (value with affinity key). Note that implementation of multiple executions of the same closure will
     * be wrapped as a single task that splits into multiple {@code job}s that will be mapped to nodes
     * with provided affinity keys.
     * <p>
     * This method does not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Note that class {@link GridAbsClosure} implements {@link Runnable} and class {@link GridOutClosure}
     * implements {@link Callable} interface. Note also that class {@link GridFunc} and typedefs provide rich
     * APIs and functionality for closures and predicates based processing in GridGain. While Java interfaces
     * {@link Runnable} and {@link Callable} allow for lowest common denominator for APIs - it is advisable
     * to use richer Functional Programming support provided by GridGain available in {@link org.gridgain.grid.lang}
     * package.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param cacheName Name of the cache to use for affinity co-location.
     * @param affKeys Collection of affinity keys. All dups will be ignored. If {@code null} or
     *      empty - this method is no-op.
     * @param job Closure to execute on the node with given affinity key.
     *      Note that in case of dynamic projection this method will take a snapshot of all the
     *      nodes at the time of this call, apply all filtering predicates, if any, and if the
     *      resulting collection of nodes is empty - the exception will be thrown.
     * @return Non-cancellable future of this execution.
     * @see #affinityRun(String, Object, Runnable)
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withResultClosure(GridBiClosure)
     * @see #withTopologySpi(String)
     * @see GridComputeJobContext#cacheName()
     * @see GridComputeJobContext#affinityKey()
     */
    public GridFuture<?> affinityRun(@Nullable String cacheName, Collection<?> affKeys, Runnable job);

    /**
     * Executes given closure on the node where data for provided affinity key is located. This
     * is known as affinity co-location between compute grid (a closure) and in-memory data grid
     * (value with affinity key).
     * <p>
     * This method does not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param cacheName Name of the cache to use for affinity co-location.
     * @param affKey Affinity key. If {@code null} - this method is no-op.
     * @param job Closure to affinity co-located on the node with given affinity key and execute.
     *      If {@code null} - this method is no-op.
     * @return Non-cancellable closure result future.
     *      Note that in case of dynamic projection this method will take a snapshot of all the
     *      nodes at the time of this call, apply all filtering predicates, if any, and if the
     *      resulting collection of nodes is empty - the exception will be thrown.
     * @see #affinityRun(String, Object, Runnable)
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withResultClosure(GridBiClosure)
     * @see #withTopologySpi(String)
     * @see GridComputeJobContext#cacheName()
     * @see GridComputeJobContext#affinityKey()
     */
    public <R> GridFuture<R> affinityCall(@Nullable String cacheName, Object affKey, Callable<R> job);

    /**
     * Executes given closure on the nodes where data for provided affinity keys
     * is located. This is known as affinity co-location between compute grid (a closure) and in-memory data grid
     * (value with affinity key). Note that implementation of multiple executions of the same closure will
     * be wrapped as a single task that splits into multiple {@code job}s that will be mapped to nodes
     * with provided affinity keys.
     * <p>
     * Unlike its sibling method {@link #affinityCall(String, Object, Callable)} this method does
     * not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param cacheName Name of the cache to use for affinity co-location.
     * @param affKeys Collection of affinity keys. All dups will be ignored.
     * @param job Closure to execute on the node with given affinity key.
     * @return Non-cancellable future of closure results. Upon successful execution number of results
     *      will be equal to number of affinity keys provided.
     *      Note that in case of dynamic projection this method will take a snapshot of all the
     *      nodes at the time of this call, apply all filtering predicates, if any, and if the
     *      resulting collection of nodes is empty - the exception will be thrown.
     * @see #affinityRun(String, Object, Runnable)
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withResultClosure(GridBiClosure)
     * @see #withTopologySpi(String)
     * @see GridComputeJobContext#cacheName()
     * @see GridComputeJobContext#affinityKey()
     */
    public <R> GridFuture<Collection<R>> affinityCall(@Nullable String cacheName, Collection<?> affKeys,
        Callable<R> job);

    /**
     * Executes a task on the grid. For information on how task gets split into remote
     * jobs and how results are reduced back into one see {@link GridComputeTask} documentation.
     * <p>
     * If task for given name has not been deployed yet, then {@code taskName} will be
     * used as task class name to auto-deploy the task (see Grid#deployTask() method
     * for deployment algorithm).
     * <p>
     * Note that if projection is empty after applying filtering predicates, the result
     * future will finish with exception. In case of dynamic projection this method
     * will take a snapshot of all nodes in the projection, apply all filtering predicates,
     * if any, and if the resulting set of nodes is empty the returned future will
     * finish with exception.
     *
     * @param taskName Name of the task to execute. If task class has {@link GridComputeTaskName} annotation,
     *      then task is deployed under a name specified within annotation. Otherwise, full
     *      class name is used as task's name.
     * @param arg Optional argument of task execution, can be {@code null}.
     * @return Task future.
     * @see GridComputeTask for information about task execution.
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withTopologySpi(String)
     */
    public <T, R> GridComputeTaskFuture<R> execute(String taskName, @Nullable T arg);

    /**
     * Executes a task on the grid. For information on how task gets split into remote
     * jobs and how results are reduced back into one see {@link GridComputeTask} documentation.
     * <p>
     * This method is extremely useful when task class is already loaded, for example,
     * in J2EE application server environment. Since application servers already support
     * deployment and hot-redeployment, it is convenient to deploy all task related classes
     * via standard J2EE deployment and then use task classes directly.
     * <p>
     * When using this method task will be deployed automatically, so no explicit deployment
     * step is required.
     * <p>
     * Note that if projection is empty after applying filtering predicates, the result
     * future will finish with exception. In case of dynamic projection this method
     * will take a snapshot of all nodes in the projection, apply all filtering predicates,
     * if any, and if the resulting set of nodes is empty the returned future will
     * finish with exception.
     *
     * @param taskCls Class of the task to execute. If class has {@link GridComputeTaskName} annotation,
     *      then task is deployed under a name specified within annotation. Otherwise, full
     *      class name is used as task's name.
     * @param arg Optional argument of task execution, can be {@code null}.
     * @return Task future.
     * @see GridComputeTask for information about task execution.
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withTopologySpi(String)
     */
    public <T, R> GridComputeTaskFuture<R> execute(Class<? extends GridComputeTask<T, R>> taskCls, @Nullable T arg);

    /**
     * Executes a task on the grid. For information on how task gets split into remote
     * jobs and how results are reduced back into one see {@link GridComputeTask} documentation.
     * <p>
     * This method is extremely useful when task class is already loaded, for example,
     * in J2EE application server environment. Since application servers already support
     * deployment and hot-redeployment, it is convenient to deploy all task related classes
     * via standard J2EE deployment and then use task classes directly.
     * <p>
     * When using this method task will be deployed automatically, so no explicit deployment
     * step is required.
     * <p>
     * Note that if projection is empty after applying filtering predicates, the result
     * future will finish with exception. In case of dynamic projection this method
     * will take a snapshot of all nodes in the projection, apply all filtering predicates,
     * if any, and if the resulting set of nodes is empty the returned future will
     * finish with exception.
     *
     * @param task Instance of task to execute. If task class has {@link GridComputeTaskName} annotation,
     *      then task is deployed under a name specified within annotation. Otherwise, full
     *      class name is used as task's name.
     * @param arg Optional argument of task execution, can be {@code null}.
     * @return Task future.
     * @see GridComputeTask for information about task execution.
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withTopologySpi(String)
     */
    public <T, R> GridComputeTaskFuture<R> execute(GridComputeTask<T, R> task, @Nullable T arg);

    /**
     * Asynchronously executes given closure on all nodes in this projection.
     * <p>
     * This method does not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Note that class {@link GridAbsClosure} implements {@link Runnable} and class {@link GridOutClosure}
     * implements {@link Callable} interface. Note also that class {@link GridFunc} and typedefs provide rich
     * APIs and functionality for closures and predicates based processing in GridGain. While Java interfaces
     * {@link Runnable} and {@link Callable} allow for lowest common denominator for APIs - it is advisable
     * to use richer Functional Programming support provided by GridGain available in {@link org.gridgain.grid.lang}
     * package.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param job Job closure to execute.
     * @return Non-cancellable future of this execution.
     * @see #broadcast(Callable)
     * @see #broadcast(GridClosure, Object)
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withResultClosure(GridBiClosure)
     * @see #withTopologySpi(String)
     */
    public GridFuture<?> broadcast(Runnable job);

    /**
     * Asynchronously executes given closure on all nodes in this projection.
     * <p>
     * This method does not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Note that class {@link GridAbsClosure} implements {@link Runnable} and class {@link GridOutClosure}
     * implements {@link Callable} interface. Note also that class {@link GridFunc} and typedefs provide rich
     * APIs and functionality for closures and predicates based processing in GridGain. While Java interfaces
     * {@link Runnable} and {@link Callable} allow for lowest common denominator for APIs - it is advisable
     * to use richer Functional Programming support provided by GridGain available in {@link org.gridgain.grid.lang}
     * package.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param job Job closure to execute.
     * @return Non-cancellable future of this execution.
     * @see #broadcast(Runnable)
     * @see #broadcast(GridClosure, Object)
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withResultClosure(GridBiClosure)
     * @see #withTopologySpi(String)
     */
    public <R> GridFuture<Collection<R>> broadcast(Callable<R> job);

    /**
     * Asynchronously executes given closure on all nodes in this projection.
     * <p>
     * This method does not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Note that class {@link GridAbsClosure} implements {@link Runnable} and class {@link GridOutClosure}
     * implements {@link Callable} interface. Note also that class {@link GridFunc} and typedefs provide rich
     * APIs and functionality for closures and predicates based processing in GridGain. While Java interfaces
     * {@link Runnable} and {@link Callable} allow for lowest common denominator for APIs - it is advisable
     * to use richer Functional Programming support provided by GridGain available in {@link org.gridgain.grid.lang}
     * package.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param job Job closure to execute.
     * @param arg Closure argument.
     * @return Non-cancellable future of this execution.
     * @see #broadcast(Runnable)
     * @see #broadcast(Callable)
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withResultClosure(GridBiClosure)
     * @see #withTopologySpi(String)
     */
    public <R, T> GridFuture<Collection<R>> broadcast(GridClosure<T, R> job, @Nullable T arg);

    /**
     * Asynchronously executes given closure on this projection.
     * <p>
     * This method does not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Note that class {@link GridAbsClosure} implements {@link Runnable} and class {@link GridOutClosure}
     * implements {@link Callable} interface. Note also that class {@link GridFunc} and typedefs provide rich
     * APIs and functionality for closures and predicates based processing in GridGain. While Java interfaces
     * {@link Runnable} and {@link Callable} allow for lowest common denominator for APIs - it is advisable
     * to use richer Functional Programming support provided by GridGain available in {@link org.gridgain.grid.lang}
     * package.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param job Job closure to execute.
     * @return Non-cancellable future of this execution.
     * @see PN
     * @see #call(Callable)
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withResultClosure(GridBiClosure)
     * @see #withTopologySpi(String)
     */
    public GridFuture<?> run(Runnable job);

    /**
     * Asynchronously executes given closures on this projection.
     * <p>
     * This method does not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Note that class {@link GridAbsClosure} implements {@link Runnable} and class {@link GridOutClosure}
     * implements {@link Callable} interface. Note also that class {@link GridFunc} and typedefs provide rich
     * APIs and functionality for closures and predicates based processing in GridGain. While Java interfaces
     * {@link Runnable} and {@link Callable} allow for lowest common denominator for APIs - it is advisable
     * to use richer Functional Programming support provided by GridGain available in {@link org.gridgain.grid.lang}
     * package.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param jobs Job closures to execute.
     * @return Non-cancellable future of this execution.
     * @see PN
     */
    public GridFuture<?> run(Collection<? extends Runnable> jobs);

    /**
     * Asynchronously executes given closure on this projection.
     * <p>
     * This method does not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Note that class {@link GridAbsClosure} implements {@link Runnable} and class {@link GridOutClosure}
     * implements {@link Callable} interface. Note also that class {@link GridFunc} and typedefs provide rich
     * APIs and functionality for closures and predicates based processing in GridGain. While Java interfaces
     * {@link Runnable} and {@link Callable} allow for lowest common denominator for APIs - it is advisable
     * to use richer Functional Programming support provided by GridGain available in {@link org.gridgain.grid.lang}
     * package.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param job Closure to invoke.
     * @return Non-cancellable closure result future.
     * @see PN
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withTopologySpi(String)
     */
    public <R> GridFuture<R> call(Callable<R> job);

    /**
     * Asynchronously executes given closures on this projection.
     * <p>
     * This method does not block and returns immediately with future. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Note that class {@link GridAbsClosure} implements {@link Runnable} and class {@link GridOutClosure}
     * implements {@link Callable} interface. Note also that class {@link GridFunc} and typedefs provide rich
     * APIs and functionality for closures and predicates based processing in GridGain. While Java interfaces
     * {@link Runnable} and {@link Callable} allow for lowest common denominator for APIs - it is advisable
     * to use richer Functional Programming support provided by GridGain available in {@link org.gridgain.grid.lang}
     * package.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param jobs Closures to invoke.
     * @return Future collection of closure results. Order is undefined.
     * @see PN
     * @see #call(Callable)
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withTopologySpi(String)
     */
    public <R> GridFuture<Collection<R>> call(Collection<? extends Callable<R>> jobs);

    /**
     * Executes given jobs on this projection.
     * <p>
     * This method will block until the execution is complete. All default SPI implementations
     * configured for this grid instance will be used (i.e. failover, load balancing, collision
     * resolution, etc.).
     * Note that if you need greater control on any aspects of Java code execution on the grid
     * you should implement {@link GridComputeTask} which will provide you with full control over the execution.
     * <p>
     * Here's a general example of the Java method that takes a text message and calculates its length
     * by splitting it by spaces, calculating the length of each word on individual (remote) grid node
     * and then summing (reducing) results from all nodes to produce the final length of the input string
     * using function APIs, typedefs, and execution closures on the grid:
     * <pre name="code" class="java">
     * public static int length(final String msg) throws GridException {
     *     return GridGain.grid().call(SPREAD, F.yield(msg.split(" "), F.cInvoke("length")), F.sumIntReducer());
     * }
     * </pre>
     * <p>
     * Note that class {@link GridAbsClosure} implements {@link Runnable} and class {@link GridOutClosure}
     * implements {@link Callable} interface. Note also that class {@link GridFunc} and typedefs provide rich
     * APIs and functionality for closures and predicates based processing in GridGain. While Java interfaces
     * {@link Runnable} and {@link Callable} allow for lowest common denominator for APIs - it is advisable
     * to use richer Functional Programming support provided by GridGain available in {@link org.gridgain.grid.lang}
     * package.
     * <p>
     * Notice that {@link Runnable} and {@link Callable} implementations must support serialization as required
     * by the configured marshaller. For example, JDK marshaller will require that implementations would
     * be serializable. Other marshallers, e.g. JBoss marshaller, may not have this limitation. Please consult
     * with specific marshaller implementation for the details. Note that all closures and predicates in
     * {@link org.gridgain.grid.lang} package are serializable and can be freely used in the distributed
     * context with all marshallers currently shipped with GridGain.
     *
     * @param jobs Closures to executes.
     * @param rdc Result reducing closure.
     * @return Value produced by reducing closure.
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withResultClosure(GridBiClosure)
     * @see #withTopologySpi(String)
     */
    public <R1, R2> GridFuture<R2> call(Collection<? extends Callable<R1>> jobs, GridReducer<R1, R2> rdc);

    /**
     * Runs job producing result with given argument on this projection.
     * <p>
     * This method doesn't block and immediately returns with future of execution.
     *
     * @param job Job to run.
     * @param arg Job argument.
     * @return Non-cancellable closure result future.
     * @see #call(Callable)
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withResultClosure(GridBiClosure)
     * @see #withTopologySpi(String)
     */
    public <R, T> GridFuture<R> apply(GridClosure<T, R> job, @Nullable T arg);

    /**
     * Runs job taking argument and producing result on this projection with given
     * collection of arguments. The job is sequentially executed on every single
     * argument from the collection so that number of actual executions will be
     * equal to size of collection of arguments.
     * <p>
     * This method doesn't block and immediately returns with future of execution.
     *
     * @param job Job to run.
     * @param args Job arguments (closure free variables).
     * @return Future of job results collection.
     * @see #call(Callable)
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withTopologySpi(String)
     */
    public <T, R> GridFuture<Collection<R>> apply(GridClosure<T, R> job, @Nullable Collection<? extends T> args);

    /**
     * Runs jobs taking argument and producing result on this projection with given
     * collection of arguments. The job is sequentially executed on every single argument
     * from the collection so that number of actual executions will be equal to size of
     * collection of arguments. Then method reduces these job results to a single
     * execution result using provided reducer. See {@link GridReducer} for reducer details.
     *
     * @param job Job to run.
     * @param args Job arguments.
     * @param rdc Job result reducer.
     * @return Result reduced from job results with given reducer.
     * @see #withCheckpointSpi(String)
     * @see #withFailoverSpi(String)
     * @see #withName(String)
     * @see #withResultClosure(GridBiClosure)
     * @see #withTopologySpi(String)
     */
    public <R1, R2, T> GridFuture<R2> apply(GridClosure<T, R1> job, @Nullable Collection<? extends T> args,
        GridReducer<R1, R2> rdc);

    /**
     * Creates new {@link ExecutorService} which will execute all submitted
     * {@link Callable} and {@link Runnable} tasks on this projection. This essentially
     * creates a <b><i>Distributed Thread Pool</i</b> that can be used as a drop-in
     * replacement for local thread pools to gain easy distributed processing
     * capabilities.
     * <p>
     * User may run {@link Callable} and {@link Runnable} tasks
     * just like normally with {@link ExecutorService java.util.ExecutorService}.
     * <p>
     * The typical Java example could be:
     * <pre name="code" class="java">
     * ...
     * ExecutorService exec = grid.executor();
     *
     * Future&lt;String&gt; fut = exec.submit(new MyCallable());
     * ...
     * String res = fut.get();
     * ...
     * </pre>
     *
     * @return {@code ExecutorService} which delegates all calls to grid.
     */
    public ExecutorService executor();

    /**
     * Gets task future based on session ID. If task execution was started on local node and this
     * projection includes local node then the future for this task will be returned.
     *
     * @param sesId Session ID for task execution.
     * @param <R> Task result type.
     * @return Task future if task was started on this node and this node belongs to this projection,
     *      or {@code null} otherwise.
     */
    @Nullable public <R> GridComputeTaskFuture<R> taskFuture(GridUuid sesId);

    /**
     * Cancels task with the given ID, if it currently running inside this projection.
     *
     * @param sesId Task session ID. If {@code null} - this method is no-op.
     * @throws GridException If task cancellation failed.
     */
    public void cancelTask(@Nullable GridUuid sesId) throws GridException;

    /**
     * Cancels job with the given ID, if it currently running inside this projection.
     *
     * @param jobId Job ID. If {@code null} - this method is no-op.
     * @throws GridException If task cancellation failed.
     */
    public void cancelJob(GridUuid jobId) throws GridException;

    /**
     * Sets task name for the next executed task on this projection in the <b>current thread</b>.
     * When task starts execution name is reset, so one name is used only once.
     * <p>
     * You may use this method to set task name when you cannot use
     * {@link GridComputeTaskName} annotation.
     * <p>
     * Here is an example.
     * <pre name="code" class="java">
     * GridGain.grid().withName("MyTask").call(
     *     BROADCAST,
     *     new CAX() {
     *         &#64;Override public void applyx() throws GridException {
     *             System.out.println("Hello!");
     *         }
     *     }
     * );
     * </pre>
     *
     * @param taskName Task name.
     * @return Grid projection ({@code this}).
     */
    public GridCompute withName(@Nullable String taskName);

    /**
     * Sets task timeout for the next executed task on this projection in the <b>current thread</b>.
     * When task starts timeout is reset, so one timeout is used only once.
     * <p>
     * Here is an example.
     * <pre name="code" class="java">
     * GridGain.grid().withTimeout(10000).call(
     *     BROADCAST,
     *     new CAX() {
     *         &#64;Override public void applyx() throws GridException {
     *             System.out.println("Hello!");
     *         }
     *     }
     * );
     * </pre>
     *
     * @param timeout Task timeout in milliseconds.
     * @return Grid projection ({@code this}).
     */
    public GridCompute withTimeout(long timeout);

    /**
     * Sets custom ad-hoc implementation for {@link GridComputeTask#result(GridComputeJobResult, List)} method for the
     * next executed closure on this projection in the <b>current thread</b>.
     * When closure starts execution the ad-hoc implementation set here is reset - so it is valid only
     * for one execution from the current thread.
     * <p>
     * Note that this method makes sense <b>only for closure executions</b>, i.e. the case where GridGain
     * automatically converts closure to a grid task. If a user provided task is executed this ad-hoc
     * closure is ignored.
     * <p>
     * This particular useful when you need to disable, for example, failover on specific closure
     * execution like in the following example (note that {@code X.NO_FAILOVER} provides
     * built-in closure that effectively disables failover logic):
     * <pre name="code" class="java">
     * GridGain.grid().withResultClosure(X.NO_FAILOVER).call(
     *     BROADCAST,
     *     new CAX() {
     *         &#64;Override public void applyx() throws GridException {
     *             System.out.println("Hello!");
     *         }
     *     }
     * );
     * </pre>
     *
     * @param res Ad-hoc implementation for {@link GridComputeTask#result(GridComputeJobResult, List)} method.
     * @return Grid projection ({@code this}).
     * @see X#NO_FAILOVER
     */
    public GridCompute withResultClosure(@Nullable GridBiClosure<GridComputeJobResult, List<GridComputeJobResult>,
        GridComputeJobResultPolicy> res);

    /**
     * Sets failover SPI for the next executed task on this projection in the <b>current thread</b>.
     * When task starts execution the failover SPI set here is reset - so it is valid only
     * for one execution from the current thread.
     * <p>
     * You may use this method to set specific failover SPI when you cannot use
     * {@link GridComputeTaskSpis} annotation.
     * <p>
     * Here is an example.
     * <pre name="code" class="java">
     * GridGain.grid().withFailoverSpi("MyFailoverSpi").call(
     *     BROADCAST,
     *     new CAX() {
     *         &#64;Override public void applyx() throws GridException {
     *             System.out.println("Hello!");
     *         }
     *     }
     * );
     * </pre>
     *
     * @param spiName Failover SPI name to use.
     * @return Grid projection ({@code this}).
     * @see GridComputeTaskSpis
     */
    public GridCompute withFailoverSpi(@Nullable String spiName);

    /**
     * Sets checkpoint SPI for the next executed task on this projection in the <b>current thread</b>.
     * When task starts execution the checkpoint SPI set here is reset - so it is valid only
     * for one execution from the current thread.
     * <p>
     * You may use this method to set specific checkpoint SPI when you cannot use
     * {@link GridComputeTaskSpis} annotation.
     * <p>
     * Here is an example.
     * <pre name="code" class="java">
     * GridGain.grid().withCheckpointSpi("MyCheckpointSpi").call(
     *     BROADCAST,
     *     new CAX() {
     *         &#64;Override public void applyx() throws GridException {
     *             System.out.println("Hello!");
     *         }
     *     }
     * );
     * </pre>
     *
     * @param spiName Checkpoint SPI name to use.
     * @return Grid projection ({@code this}).
     * @see GridComputeTaskSpis
     */
    public GridCompute withCheckpointSpi(@Nullable String spiName);

    /**
     * Sets load balancing SPI for the next executed task on this projection in the <b>current thread</b>.
     * When task starts execution the load balancing SPI set here is reset - so it is valid only
     * for one execution from the current thread.
     * <p>
     * You may use this method to set specific load balancing SPI when you cannot use
     * {@link GridComputeTaskSpis} annotation.
     * <p>
     * Here is an example.
     * <pre name="code" class="java">
     * GridGain.grid().withLoadBalancingSpi("MyLoadBalancingSpi").call(
     *     BROADCAST,
     *     new CAX() {
     *         &#64;Override public void applyx() throws GridException {
     *             System.out.println("Hello!");
     *         }
     *     }
     * );
     * </pre>
     *
     * @param spiName Load balancing SPI name to use.
     * @return Grid projection ({@code this}).
     * @see GridComputeTaskSpis
     */
    public GridCompute withLoadBalancingSpi(@Nullable String spiName);

    /**
     * Sets topology SPI for the next executed task on this projection in the <b>current thread</b>.
     * When task starts execution the topology SPI set here is reset - so it is valid only
     * for one execution from the current thread.
     * <p>
     * You may use this method to set specific topology SPI when you cannot use
     * {@link GridComputeTaskSpis} annotation.
     * <p>
     * Here is an example.
     * <pre name="code" class="java">
     * GridGain.grid().withTopologySpi("MyTopologySpi").call(
     *     BROADCAST,
     *     new CAX() {
     *         &#64;Override public void applyx() throws GridException {
     *             System.out.println("Hello!");
     *         }
     *     }
     * );
     * </pre>
     *
     * @param spiName Checkpoint SPI name to use.
     * @return Grid projection ({@code this}).
     * @see GridComputeTaskSpis
     */
    public GridCompute withTopologySpi(@Nullable String spiName);

    /**
     * Enables task session attributes and checkpoints for the next executed task on this projection in the
     * <b>current thread</b>. When task starts execution the flag here is reset - so it is valid only
     * for one execution from the current thread.
     * <p>
     * You may use this method to enable session attributes when you cannot use
     * {@link GridComputeTaskSessionFullSupport} annotation.
     * <p>
     * Here is an example.
     * <pre name="code" class="java">
     * GridGain.grid().withSessionAttributesEnabled().call(
     *     BROADCAST,
     *     new CAX() {
     *         &#64;GridTaskSessionResource
     *         private GridComputeTaskSession ses;
     *
     *         &#64;Override public void applyx() throws GridException {
     *             ses.setAttribute("test", "val");
     *         }
     *     }
     * );
     * </pre>
     *
     * @return Grid projection ({@code this}).
     * @see GridComputeTaskSessionFullSupport
     */
    public GridCompute withSessionFullSupport();

    /**
     * Explicitly deploys given grid task on the local node. Upon completion of this method,
     * a task can immediately be executed on the grid, considering that all participating
     * remote nodes also have this task deployed. If peer-class-loading is enabled
     * (see {@link GridConfiguration#isPeerClassLoadingEnabled()}), then other nodes
     * will automatically deploy task upon execution request from the originating node without
     * having to manually deploy it.
     * <p>
     * Another way of class deployment which is supported is deployment from local class path.
     * Class from local class path has a priority over P2P deployed.
     * Following describes task class deployment:
     * <ul>
     * <li> If peer class loading is enabled (see {@link GridConfiguration#isPeerClassLoadingEnabled()})
     * <ul> Task class loaded from local class path if it is not defined as P2P loaded
     *      (see {@link GridConfiguration#getPeerClassLoadingLocalClassPathExclude()}).</ul>
     * <ul> If there is no task class in local class path or task class needs to be peer loaded
     *      it is downloaded from task originating node using provided class loader.</ul>
     * </li>
     * <li> If peer class loading is disabled (see {@link GridConfiguration#isPeerClassLoadingEnabled()})
     * <ul> Check that task class was deployed (either as GAR or explicitly) and use it.</ul>
     * <ul> If task class was not deployed then we try to find it in local class path by task
     *      name. Task name should correspond task class name.</ul>
     * <ul> If task has custom name (that does not correspond task class name) and this
     *      task was not deployed before then exception will be thrown.</ul>
     * </li>
     * </ul>
     * <p>
     * Note that this is an alternative deployment method additionally to deployment SPI that
     * provides more formal method of deploying a task, e.g. deployment of GAR files and/or URI-based
     * deployment. See {@link GridDeploymentSpi} for detailed information about grid task deployment.
     * <p>
     * Note that class can be deployed multiple times on remote nodes, i.e. re-deployed. GridGain
     * maintains internal version of deployment for each instance of deployment (analogous to
     * class and class loader in Java). Execution happens always on the latest deployed instance
     * (latest that is on the node where execution request is originated). This allows a very
     * convenient development model when a developer can execute a task on the grid from IDE,
     * then realize that he made a mistake, stop his node in IDE, fix mistake and re-execute the
     * task. Grid will automatically detect that task got renewed and redeploy it on all remote
     * nodes upon execution.
     * <p>
     * This method has no effect if the class passed in was already deployed. Implementation
     * checks for this condition and returns immediately.
     *
     * @param taskCls Task class to deploy. If task class has {@link GridComputeTaskName} annotation,
     *      then task will be deployed under a name specified within annotation. Otherwise, full
     *      class name will be used as task's name.
     * @param clsLdr Task resources/classes class loader. This class loader is in charge
     *      of loading all necessary resources.
     * @throws GridException If task is invalid and cannot be deployed.
     * @see GridDeploymentSpi
     */
    public void localDeployTask(Class<? extends GridComputeTask> taskCls, ClassLoader clsLdr) throws GridException;

    /**
     * Gets map of all locally deployed tasks keyed by their task name satisfying all given predicates.
     * If no tasks were locally deployed, then empty map is returned. If no predicates provided - all
     * locally deployed tasks, if any, will be returned.
     *
     * @return Map of locally deployed tasks keyed by their task name.
     */
    public Map<String, Class<? extends GridComputeTask<?, ?>>> localTasks();

    /**
     * Makes the best attempt to undeploy a task from the projection. Note that this
     * method returns immediately and does not wait until the task will actually be
     * undeployed on every node.
     * <p>
     * Note that GridGain maintains internal versions for grid tasks in case of redeployment.
     * This method will attempt to undeploy all versions on the grid task with
     * given name.
     *
     * @param taskName Name of the task to undeploy. If task class has {@link GridComputeTaskName} annotation,
     *      then task was deployed under a name specified within annotation. Otherwise, full
     *      class name should be used as task's name.
     * @throws GridException Thrown if undeploy failed.
     * // TODO: change current behavior from the whole grid to current projection.
     */
    public void undeployTask(String taskName) throws GridException;
}
