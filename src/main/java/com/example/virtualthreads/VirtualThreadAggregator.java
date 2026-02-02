package com.example.virtualthreads;

import com.example.iterable.IterableAggregator;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * Aggregator that uses Java 21+ virtual threads for concurrent operations.
 *
 * <p>This variant demonstrates how virtual threads allow blocking I/O code to scale
 * without the complexity of async/reactive programming. It reuses the blocking
 * {@link IterableAggregator} but runs operations on virtual threads.
 *
 * <p>Example usage:
 * <pre>{@code
 * VirtualThreadAggregator aggregator = new VirtualThreadAggregator(
 *     "https://api.example.com/orders"
 * );
 *
 * // Single aggregation on virtual thread
 * CompletableFuture<OrderStats> future = aggregator.aggregateOrdersAsync();
 * OrderStats stats = future.join();
 *
 * // Multiple concurrent aggregations - each on its own virtual thread
 * List<String> urls = List.of(
 *     "https://api1.example.com/orders",
 *     "https://api2.example.com/orders"
 * );
 * List<OrderStats> results = VirtualThreadAggregator.aggregateMultiple(urls);
 * }</pre>
 *
 * <h2>Why Virtual Threads?</h2>
 * <ul>
 *   <li><b>Simple blocking code</b>: Write straightforward blocking I/O without callbacks</li>
 *   <li><b>Scalable</b>: Millions of virtual threads can exist concurrently</li>
 *   <li><b>Cheap</b>: Virtual threads are lightweight (~1KB vs ~1MB for platform threads)</li>
 *   <li><b>No recursion needed</b>: Unlike CompletableFuture chains, just use loops</li>
 * </ul>
 *
 * <h2>Comparison with Other Variants</h2>
 * <table>
 *   <tr><th>Aspect</th><th>Async (CompletableFuture)</th><th>Reactor</th><th>Virtual Threads</th></tr>
 *   <tr><td>Code style</td><td>Callback chains</td><td>Operators</td><td>Blocking (simple)</td></tr>
 *   <tr><td>Aggregation</td><td>Recursive thenCompose()</td><td>reduce()</td><td>Loop</td></tr>
 *   <tr><td>Thread usage</td><td>Platform threads</td><td>Few threads</td><td>Virtual threads</td></tr>
 *   <tr><td>Java version</td><td>8+</td><td>8+</td><td>21+</td></tr>
 * </table>
 */
public class VirtualThreadAggregator {

    private final IterableAggregator delegate;
    private final String baseUrl;

    /**
     * Creates an aggregator that fetches from an HTTP endpoint.
     *
     * @param baseUrl the URL of the orders API endpoint
     */
    public VirtualThreadAggregator(String baseUrl) {
        this.baseUrl = baseUrl;
        this.delegate = new IterableAggregator(baseUrl);
    }

    /**
     * Aggregates all orders asynchronously on a virtual thread.
     *
     * <p>The actual aggregation uses blocking I/O, but runs on a virtual thread
     * so it doesn't block platform threads.
     *
     * @return CompletableFuture that completes with the aggregated statistics
     */
    public CompletableFuture<OrderStats> aggregateOrdersAsync() {
        return CompletableFuture.supplyAsync(
                delegate::aggregateOrders,
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    /**
     * Aggregates orders synchronously (blocking).
     *
     * <p>This is the same as {@link IterableAggregator#aggregateOrders()}.
     * Use this when already running on a virtual thread.
     *
     * @return the aggregated statistics
     */
    public OrderStats aggregateOrders() {
        return delegate.aggregateOrders();
    }

    /**
     * Iterates over all orders.
     *
     * <p>Use this when already running on a virtual thread.
     *
     * @return iterable of orders
     */
    public Iterable<Order> iterateOrders() {
        return delegate.iterateOrders();
    }

    /**
     * Aggregates only orders matching a predicate.
     *
     * @param predicate the filter condition
     * @return CompletableFuture with statistics for matching orders
     */
    public CompletableFuture<OrderStats> aggregateFilteredAsync(Predicate<Order> predicate) {
        return CompletableFuture.supplyAsync(
                () -> {
                    OrderStats stats = OrderStats.zero();
                    for (Order order : delegate.iterateOrders()) {
                        if (predicate.test(order)) {
                            stats = stats.accumulate(order);
                        }
                    }
                    return stats;
                },
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    /**
     * Finds the first order matching a predicate.
     *
     * @param predicate the condition to match
     * @return CompletableFuture that completes with the first match, or null if none found
     */
    public CompletableFuture<Order> findFirstAsync(Predicate<Order> predicate) {
        return CompletableFuture.supplyAsync(
                () -> {
                    for (Order order : delegate.iterateOrders()) {
                        if (predicate.test(order)) {
                            return order;
                        }
                    }
                    return null;
                },
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    /**
     * Aggregates from multiple URLs concurrently, each on its own virtual thread.
     *
     * <p>This demonstrates the power of virtual threads: we can spawn thousands
     * of concurrent aggregations without worrying about thread pool sizing.
     *
     * <pre>{@code
     * List<String> urls = List.of(
     *     "https://api1.example.com/orders",
     *     "https://api2.example.com/orders",
     *     "https://api3.example.com/orders"
     * );
     * List<OrderStats> results = VirtualThreadAggregator.aggregateMultiple(urls);
     * }</pre>
     *
     * @param urls list of API endpoint URLs
     * @return list of aggregated statistics, one per URL
     * @throws RuntimeException if any aggregation fails
     */
    public static List<OrderStats> aggregateMultiple(List<String> urls) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<OrderStats>> futures = urls.stream()
                    .map(url -> executor.submit(() -> new IterableAggregator(url).aggregateOrders()))
                    .toList();

            return futures.stream()
                    .map(VirtualThreadAggregator::getFutureResult)
                    .toList();
        }
    }

    /**
     * Aggregates from multiple URLs concurrently and combines results.
     *
     * @param urls list of API endpoint URLs
     * @return combined statistics from all URLs
     */
    public static OrderStats aggregateMultipleCombined(List<String> urls) {
        return aggregateMultiple(urls).stream()
                .reduce(OrderStats.zero(), OrderStats::combine);
    }

    /**
     * Runs a task on a virtual thread and waits for completion.
     *
     * <p>Utility method for running blocking code on a virtual thread.
     *
     * @param task the task to run
     * @param <T> the result type
     * @return the task result
     */
    public static <T> T runOnVirtualThread(java.util.concurrent.Callable<T> task) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for virtual thread", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Virtual thread task failed", e.getCause());
        }
    }

    private static OrderStats getFutureResult(Future<OrderStats> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for result", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Aggregation failed", e.getCause());
        }
    }

    /**
     * Returns the base URL of this aggregator.
     *
     * @return the base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }
}
