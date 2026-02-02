package com.example.async;

import com.example.async.aggregation.AsyncOrderStatsAggregator;
import com.example.async.client.AsyncHttpPageFetcher;
import com.example.async.iterator.AsyncIterator;
import com.example.async.iterator.AsyncPaginatedIterator;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import com.example.streaming.model.Page;
import com.example.streaming.pagination.CursorBasedPagination;
import com.example.streaming.pagination.PaginationStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Main facade for async iteration-based aggregation of paginated API responses.
 *
 * <p>This is the async equivalent of
 * {@link com.example.iterable.IterableAggregator}. It demonstrates how to:
 * <ul>
 *   <li>Iterate objects lazily from a paginated HTTP API without blocking</li>
 *   <li>Aggregate results asynchronously with CompletableFuture</li>
 *   <li>Use async iteration with early termination support</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // Create aggregator
 * AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
 *     "https://api.example.com/orders"
 * );
 *
 * // Non-blocking aggregation
 * aggregator.aggregateOrdersAsync()
 *     .thenAccept(stats -> {
 *         System.out.println("Count: " + stats.count());
 *         System.out.println("Sum: " + stats.sum());
 *     });
 *
 * // Async iteration
 * aggregator.iterateOrdersAsync()
 *     .forEachAsync(order -> process(order))
 *     .thenRun(() -> System.out.println("Done"));
 *
 * // Take first N items
 * aggregator.takeFirstAsync(5)
 *     .thenAccept(orders -> orders.forEach(this::process));
 * }</pre>
 *
 * <h2>Comparison with IterableAggregator</h2>
 * <table>
 *   <tr><th>Aspect</th><th>IterableAggregator</th><th>AsyncIterableAggregator</th></tr>
 *   <tr><td>Return type</td><td>OrderStats</td><td>CompletableFuture&lt;OrderStats&gt;</td></tr>
 *   <tr><td>Blocking</td><td>Yes</td><td>No</td></tr>
 *   <tr><td>Thread usage</td><td>Blocks thread on I/O</td><td>Frees thread on I/O</td></tr>
 *   <tr><td>Early exit</td><td>break statement</td><td>cancel() + predicate</td></tr>
 *   <tr><td>Use case</td><td>Simple apps</td><td>High-concurrency servers</td></tr>
 * </table>
 */
public class AsyncIterableAggregator {

    private final Function<String, CompletableFuture<Page<Order>>> asyncPageFetcher;
    private final PaginationStrategy paginationStrategy;

    /**
     * Creates an aggregator that fetches from an HTTP endpoint asynchronously.
     *
     * @param baseUrl the URL of the orders API endpoint
     */
    public AsyncIterableAggregator(String baseUrl) {
        AsyncHttpPageFetcher<Order> httpFetcher = new AsyncHttpPageFetcher<>(baseUrl, Order.class);
        this.asyncPageFetcher = httpFetcher::fetchPageAsync;
        this.paginationStrategy = new CursorBasedPagination();
    }

    /**
     * Creates an aggregator with a custom async page fetcher.
     * Useful for testing or when using a different data source.
     *
     * @param asyncPageFetcher function that asynchronously fetches a page given a cursor
     */
    public AsyncIterableAggregator(Function<String, CompletableFuture<Page<Order>>> asyncPageFetcher) {
        this.asyncPageFetcher = asyncPageFetcher;
        this.paginationStrategy = new CursorBasedPagination();
    }

    /**
     * Creates an aggregator with custom async page fetcher and pagination strategy.
     *
     * @param asyncPageFetcher function that asynchronously fetches a page given a cursor
     * @param paginationStrategy the pagination strategy to use
     */
    public AsyncIterableAggregator(
            Function<String, CompletableFuture<Page<Order>>> asyncPageFetcher,
            PaginationStrategy paginationStrategy
    ) {
        this.asyncPageFetcher = asyncPageFetcher;
        this.paginationStrategy = paginationStrategy;
    }

    /**
     * Returns an async iterator for lazy, non-blocking iteration.
     *
     * <p>The iterator is lazy - pages are only fetched as items are consumed.
     * Each call returns a fresh iterator starting from page 1.
     *
     * <p>Example:
     * <pre>{@code
     * AsyncIterator<Order> iterator = aggregator.iterateOrdersAsync();
     * iterator.forEachAsync(order -> {
     *     process(order);
     *     if (shouldStop(order)) {
     *         iterator.cancel(); // No more pages will be fetched
     *     }
     * });
     * }</pre>
     *
     * @return async iterator of orders
     */
    public AsyncIterator<Order> iterateOrdersAsync() {
        return new AsyncPaginatedIterator<>(asyncPageFetcher, paginationStrategy);
    }

    /**
     * Aggregates all orders from the paginated API asynchronously.
     *
     * <p>This method demonstrates the complete async aggregation flow:
     * <ol>
     *   <li>Creates a lazy async iterator using AsyncPaginatedIterator</li>
     *   <li>Aggregates using AsyncOrderStatsAggregator</li>
     *   <li>No intermediate buffering - items processed one at a time</li>
     *   <li>Thread is freed during network I/O</li>
     * </ol>
     *
     * @return CompletableFuture that completes with aggregated statistics
     */
    public CompletableFuture<OrderStats> aggregateOrdersAsync() {
        return new AsyncOrderStatsAggregator().aggregateAsync(iterateOrdersAsync());
    }

    /**
     * Aggregates only completed orders asynchronously.
     *
     * <p>Demonstrates async filtering with the {@code aggregateFilteredAsync} method.
     *
     * @return CompletableFuture with statistics for completed orders only
     */
    public CompletableFuture<OrderStats> aggregateCompletedOrdersAsync() {
        return new AsyncOrderStatsAggregator().aggregateFilteredAsync(
                iterateOrdersAsync(),
                order -> "completed".equals(order.status())
        );
    }

    /**
     * Aggregates orders until the sum reaches a target amount.
     *
     * <p>Demonstrates async early termination - fetching stops when the
     * target is reached, preventing unnecessary network requests.
     *
     * @param targetAmount the target sum amount
     * @return CompletableFuture with statistics of orders up to when target is reached
     */
    public CompletableFuture<OrderStats> aggregateUntilAmountAsync(double targetAmount) {
        return new AsyncOrderStatsAggregator().aggregateUntilAsync(
                iterateOrdersAsync(),
                acc -> acc.getSum() >= targetAmount
        );
    }

    /**
     * Processes all orders with a custom async action.
     *
     * @param action the action to apply to each order
     * @return CompletableFuture that completes when all orders are processed
     */
    public CompletableFuture<Void> processAllOrdersAsync(Consumer<Order> action) {
        return iterateOrdersAsync().forEachAsync(action);
    }

    /**
     * Collects first N orders asynchronously.
     *
     * <p>This is the async equivalent of {@code stream.limit(n).collect()}.
     * Fetching stops after N items are collected.
     *
     * @param n the number of orders to collect
     * @return CompletableFuture that completes with the collected orders
     */
    public CompletableFuture<List<Order>> takeFirstAsync(int n) {
        List<Order> result = new ArrayList<>();
        AsyncIterator<Order> iterator = iterateOrdersAsync();

        return takeRecursive(iterator, result, n);
    }

    private CompletableFuture<List<Order>> takeRecursive(
            AsyncIterator<Order> iterator,
            List<Order> result,
            int remaining
    ) {
        if (remaining <= 0) {
            iterator.cancel();
            return CompletableFuture.completedFuture(result);
        }

        return iterator.nextAsync().thenCompose(opt -> {
            if (opt.isPresent()) {
                result.add(opt.get());
                return takeRecursive(iterator, result, remaining - 1);
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    /**
     * Finds the first order matching a predicate asynchronously.
     *
     * <p>This is the async equivalent of {@code stream.filter(p).findFirst()}.
     * Fetching stops when a match is found.
     *
     * @param predicate the condition to match
     * @return CompletableFuture that completes with the first matching order, or empty
     */
    public CompletableFuture<java.util.Optional<Order>> findFirstAsync(
            java.util.function.Predicate<Order> predicate
    ) {
        AsyncIterator<Order> iterator = iterateOrdersAsync();
        return findFirstRecursive(iterator, predicate);
    }

    private CompletableFuture<java.util.Optional<Order>> findFirstRecursive(
            AsyncIterator<Order> iterator,
            java.util.function.Predicate<Order> predicate
    ) {
        return iterator.nextAsync().thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(java.util.Optional.empty());
            }

            Order order = opt.get();
            if (predicate.test(order)) {
                iterator.cancel();
                return CompletableFuture.completedFuture(java.util.Optional.of(order));
            }

            return findFirstRecursive(iterator, predicate);
        });
    }
}
