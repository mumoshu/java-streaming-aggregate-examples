package com.example.iterable;

import com.example.iterable.aggregation.OrderStatsAggregator;
import com.example.streaming.client.HttpPageFetcher;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import com.example.streaming.model.Page;
import com.example.streaming.pagination.CursorBasedPagination;
import com.example.streaming.pagination.PaginationStrategy;

import java.util.function.Function;

/**
 * Main facade for iterable-based aggregation of paginated API responses.
 *
 * <p>This is the Iterator-based equivalent of
 * {@link com.example.streaming.StreamingAggregator}. It demonstrates how to:
 * <ul>
 *   <li>Iterate objects lazily from a paginated HTTP API</li>
 *   <li>Aggregate results without buffering all items in memory</li>
 *   <li>Use Java Iterable with custom Iterators</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // With HTTP client
 * IterableAggregator aggregator = new IterableAggregator(
 *     "https://api.example.com/orders"
 * );
 * OrderStats stats = aggregator.aggregateOrders();
 *
 * // Manual iteration
 * for (Order order : aggregator.iterateOrders()) {
 *     process(order);
 * }
 *
 * // With filtering
 * OrderStats completedStats = aggregator.aggregateCompletedOrders();
 *
 * // With early termination
 * OrderStats partialStats = aggregator.aggregateUntilAmount(10000.0);
 * }</pre>
 *
 * <h2>Comparison with StreamingAggregator</h2>
 * <table>
 *   <tr><th>Aspect</th><th>StreamingAggregator</th><th>IterableAggregator</th></tr>
 *   <tr><td>Style</td><td>Functional/declarative</td><td>Imperative/explicit</td></tr>
 *   <tr><td>Filtering</td><td>stream.filter()</td><td>if statement in loop</td></tr>
 *   <tr><td>Early exit</td><td>limit(), takeWhile()</td><td>break statement</td></tr>
 *   <tr><td>Memory</td><td>O(page_size) + O(1)</td><td>O(page_size) + O(1)</td></tr>
 * </table>
 */
public class IterableAggregator {

    private final LazyPaginatedIterable<Order> orders;

    /**
     * Creates an aggregator that fetches from an HTTP endpoint.
     *
     * @param baseUrl the URL of the orders API endpoint
     */
    public IterableAggregator(String baseUrl) {
        HttpPageFetcher<Order> httpFetcher = new HttpPageFetcher<>(baseUrl, Order.class);
        this.orders = new LazyPaginatedIterable<>(
                httpFetcher::fetchPage,
                new CursorBasedPagination()
        );
    }

    /**
     * Creates an aggregator with a custom page fetcher.
     * Useful for testing or when using a different data source.
     *
     * @param pageFetcher function that fetches a page given a cursor
     */
    public IterableAggregator(Function<String, Page<Order>> pageFetcher) {
        this.orders = new LazyPaginatedIterable<>(
                pageFetcher,
                new CursorBasedPagination()
        );
    }

    /**
     * Creates an aggregator with custom page fetcher and pagination strategy.
     *
     * @param pageFetcher function that fetches a page given a cursor
     * @param paginationStrategy the pagination strategy to use
     */
    public IterableAggregator(
            Function<String, Page<Order>> pageFetcher,
            PaginationStrategy paginationStrategy
    ) {
        this.orders = new LazyPaginatedIterable<>(pageFetcher, paginationStrategy);
    }

    /**
     * Returns a lazy Iterable of all orders from the paginated API.
     *
     * <p>The iterable is lazy - pages are only fetched as items are consumed.
     * Each call to {@code iterator()} returns a fresh iterator starting from page 1.
     *
     * <p>Example:
     * <pre>{@code
     * for (Order order : aggregator.iterateOrders()) {
     *     process(order);
     *     if (shouldStop(order)) {
     *         break; // No more pages will be fetched
     *     }
     * }
     * }</pre>
     *
     * @return lazy iterable of orders
     */
    public Iterable<Order> iterateOrders() {
        return orders;
    }

    /**
     * Aggregates all orders from the paginated API into statistics.
     *
     * <p>This method demonstrates the complete iteration-based aggregation:
     * <ol>
     *   <li>Creates a lazy iterable using LazyPaginatedIterable</li>
     *   <li>Aggregates using OrderStatsAggregator</li>
     *   <li>No intermediate buffering - items processed one at a time</li>
     * </ol>
     *
     * @return aggregated statistics (count, sum, average)
     */
    public OrderStats aggregateOrders() {
        return new OrderStatsAggregator().aggregate(orders);
    }

    /**
     * Aggregates orders using a manual for-each loop.
     *
     * <p>This method demonstrates the imperative approach without using
     * the Aggregator abstraction. Useful for educational comparison.
     *
     * @return aggregated statistics
     */
    public OrderStats aggregateOrdersManually() {
        long count = 0;
        double sum = 0.0;

        for (Order order : orders) {
            count++;
            sum += order.amount();
        }

        double average = count > 0 ? sum / count : 0.0;
        return new OrderStats(count, sum, average);
    }

    /**
     * Aggregates only completed orders.
     *
     * <p>Demonstrates filtering with the {@code aggregateFiltered} method.
     * Compare with {@link com.example.streaming.StreamingAggregator#aggregateCompletedOrders()}
     * which uses {@code stream.filter()}.
     *
     * @return statistics for completed orders only
     */
    public OrderStats aggregateCompletedOrders() {
        return new OrderStatsAggregator().aggregateFiltered(
                orders,
                order -> "completed".equals(order.status())
        );
    }

    /**
     * Aggregates completed orders using explicit if statement.
     *
     * <p>Alternative to aggregateCompletedOrders() showing the imperative approach
     * with explicit filtering in the loop.
     *
     * @return statistics for completed orders only
     */
    public OrderStats aggregateCompletedOrdersManually() {
        OrderStatsAggregator.Accumulator acc = new OrderStatsAggregator.Accumulator();

        for (Order order : orders) {
            if ("completed".equals(order.status())) {
                acc.accumulate(order);
            }
        }

        return acc.finish();
    }

    /**
     * Demonstrates early termination - only fetches pages until condition is met.
     *
     * <p>Uses a {@code break} statement to stop iteration when the target amount
     * is reached. No additional pages will be fetched after breaking.
     *
     * <p>Compare with {@link com.example.streaming.StreamingAggregator#aggregateUntilAmount(double)}
     * which uses {@code takeWhile()}.
     *
     * @param targetAmount the target sum amount
     * @return statistics of orders up to when target is reached
     */
    public OrderStats aggregateUntilAmount(double targetAmount) {
        OrderStatsAggregator.Accumulator acc = new OrderStatsAggregator.Accumulator();

        for (Order order : orders) {
            acc.accumulate(order);
            if (acc.getSum() >= targetAmount) {
                break; // Early termination - no more pages fetched
            }
        }

        return acc.finish();
    }
}
