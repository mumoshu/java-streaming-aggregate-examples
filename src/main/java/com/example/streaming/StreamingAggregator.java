package com.example.streaming;

import com.example.streaming.aggregation.OrderStatsCollector;
import com.example.streaming.client.HttpPageFetcher;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import com.example.streaming.model.Page;
import com.example.streaming.pagination.CursorBasedPagination;
import com.example.streaming.pagination.PaginationStrategy;
import com.example.streaming.spliterator.PaginatedSpliterator;

import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Main facade for streaming aggregation of paginated API responses.
 *
 * <p>This class demonstrates how to:
 * <ul>
 *   <li>Stream objects lazily from a paginated HTTP API</li>
 *   <li>Aggregate results without buffering all items in memory</li>
 *   <li>Use Java Streams with custom Spliterators</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // With HTTP client
 * StreamingAggregator aggregator = new StreamingAggregator(
 *     "https://api.example.com/orders"
 * );
 * OrderStats stats = aggregator.aggregateOrders();
 *
 * // With custom page fetcher (for testing)
 * StreamingAggregator aggregator = new StreamingAggregator(
 *     cursor -> fetchPageFromMock(cursor)
 * );
 * OrderStats stats = aggregator.aggregateOrders();
 * }</pre>
 */
public class StreamingAggregator {

    private final Function<String, Page<Order>> pageFetcher;
    private final PaginationStrategy paginationStrategy;

    /**
     * Creates an aggregator that fetches from an HTTP endpoint.
     *
     * @param baseUrl the URL of the orders API endpoint
     */
    public StreamingAggregator(String baseUrl) {
        HttpPageFetcher<Order> httpFetcher = new HttpPageFetcher<>(baseUrl, Order.class);
        this.pageFetcher = httpFetcher::fetchPage;
        this.paginationStrategy = new CursorBasedPagination();
    }

    /**
     * Creates an aggregator with a custom page fetcher.
     * Useful for testing or when using a different data source.
     *
     * @param pageFetcher function that fetches a page given a cursor
     */
    public StreamingAggregator(Function<String, Page<Order>> pageFetcher) {
        this.pageFetcher = pageFetcher;
        this.paginationStrategy = new CursorBasedPagination();
    }

    /**
     * Creates an aggregator with custom page fetcher and pagination strategy.
     *
     * @param pageFetcher function that fetches a page given a cursor
     * @param paginationStrategy the pagination strategy to use
     */
    public StreamingAggregator(
            Function<String, Page<Order>> pageFetcher,
            PaginationStrategy paginationStrategy
    ) {
        this.pageFetcher = pageFetcher;
        this.paginationStrategy = paginationStrategy;
    }

    /**
     * Aggregates all orders from the paginated API into statistics.
     *
     * <p>This method demonstrates the complete streaming pipeline:
     * <ol>
     *   <li>Creates a lazy stream using PaginatedSpliterator</li>
     *   <li>Collects into OrderStats using OrderStatsCollector</li>
     *   <li>No intermediate buffering - items processed one at a time</li>
     * </ol>
     *
     * @return aggregated statistics (count, sum, average)
     */
    public OrderStats aggregateOrders() {
        return streamOrders()
                .collect(new OrderStatsCollector());
    }

    /**
     * Alternative aggregation using built-in Collectors.teeing().
     * Shows how to compute multiple statistics in a single pass.
     *
     * @return aggregated statistics
     */
    public OrderStats aggregateOrdersWithTeeing() {
        return streamOrders()
                .collect(Collectors.teeing(
                        Collectors.counting(),
                        Collectors.summingDouble(Order::amount),
                        (count, sum) -> new OrderStats(
                                count,
                                sum,
                                count > 0 ? sum / count : 0.0
                        )
                ));
    }

    /**
     * Aggregation using reduce() for a more functional approach.
     * Shows how OrderStats.accumulate() enables streaming aggregation.
     *
     * @return aggregated statistics
     */
    public OrderStats aggregateOrdersWithReduce() {
        return streamOrders()
                .reduce(
                        OrderStats.zero(),
                        OrderStats::accumulate,
                        OrderStats::combine
                );
    }

    /**
     * Returns a lazy stream of all orders from the paginated API.
     *
     * <p>The stream is lazy - pages are only fetched as items are consumed.
     * This allows for early termination (e.g., findFirst(), limit()).
     *
     * @return lazy stream of orders
     */
    public Stream<Order> streamOrders() {
        PaginatedSpliterator<Order> spliterator = new PaginatedSpliterator<>(
                pageFetcher,
                paginationStrategy
        );
        return StreamSupport.stream(spliterator, false);
    }

    /**
     * Returns only completed orders (demonstrating filtering in the stream).
     *
     * @return lazy stream of completed orders
     */
    public Stream<Order> streamCompletedOrders() {
        return streamOrders()
                .filter(order -> "completed".equals(order.status()));
    }

    /**
     * Aggregates only completed orders.
     *
     * @return statistics for completed orders only
     */
    public OrderStats aggregateCompletedOrders() {
        return streamCompletedOrders()
                .collect(new OrderStatsCollector());
    }

    /**
     * Demonstrates early termination - only fetches pages until condition is met.
     *
     * @param targetAmount the target sum amount
     * @return statistics of orders up to when target is reached
     */
    public OrderStats aggregateUntilAmount(double targetAmount) {
        // Using a stateful predicate to track running sum
        // Note: This is for demonstration - in production, consider a custom collector
        double[] runningSum = {0.0};

        return streamOrders()
                .takeWhile(order -> {
                    if (runningSum[0] >= targetAmount) {
                        return false;
                    }
                    runningSum[0] += order.amount();
                    return true;
                })
                .collect(new OrderStatsCollector());
    }
}
