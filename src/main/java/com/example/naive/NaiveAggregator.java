package com.example.naive;

import com.example.streaming.client.HttpPageFetcher;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import com.example.streaming.model.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Naive implementation that buffers ALL orders in memory before aggregation.
 *
 * <p><strong>WARNING:</strong> This is an anti-pattern! This class exists only
 * to demonstrate the memory problem that streaming approaches solve.
 *
 * <p>Memory complexity: O(total_items) - memory grows with total dataset size
 *
 * <p>Problems with this approach:
 * <ul>
 *   <li>OutOfMemoryError with large datasets</li>
 *   <li>Wasted memory even for moderate datasets</li>
 *   <li>Must wait for all pages before processing starts</li>
 *   <li>Cannot do early termination efficiently</li>
 * </ul>
 *
 * <p>Example of what NOT to do:
 * <pre>{@code
 * // Bad: O(total_items) memory - buffers everything
 * List<Order> allOrders = new ArrayList<>();
 * while (hasMorePages) {
 *     Page<Order> page = fetchPage(cursor);
 *     allOrders.addAll(page.items());  // Memory grows with each page!
 *     cursor = page.nextCursor();
 * }
 * double sum = allOrders.stream().mapToDouble(Order::amount).sum();
 * }</pre>
 *
 * <p>Compare with streaming approaches in other packages that use O(page_size) memory.
 *
 * @see com.example.streaming.StreamingAggregator
 * @see com.example.iterable.IterableAggregator
 */
public class NaiveAggregator {

    private final Function<String, Page<Order>> pageFetcher;

    /**
     * Creates an aggregator that fetches from an HTTP endpoint.
     *
     * @param baseUrl the URL of the orders API endpoint
     */
    public NaiveAggregator(String baseUrl) {
        HttpPageFetcher<Order> httpFetcher = new HttpPageFetcher<>(baseUrl, Order.class);
        this.pageFetcher = httpFetcher::fetchPage;
    }

    /**
     * Creates an aggregator with a custom page fetcher.
     *
     * @param pageFetcher function that fetches a page given a cursor
     */
    public NaiveAggregator(Function<String, Page<Order>> pageFetcher) {
        this.pageFetcher = pageFetcher;
    }

    /**
     * Fetches ALL orders into memory, then computes statistics.
     *
     * <p><strong>WARNING:</strong> This buffers all orders in memory!
     * Memory usage = O(total_items).
     *
     * <p>This method demonstrates the naive approach that should be avoided:
     * <ol>
     *   <li>Fetch all pages sequentially</li>
     *   <li>Buffer every order in a List (memory grows!)</li>
     *   <li>Only after ALL orders are fetched, compute statistics</li>
     * </ol>
     *
     * @return aggregated statistics
     */
    public OrderStats aggregateOrders() {
        // Step 1: Buffer ALL orders in memory (BAD!)
        List<Order> allOrders = fetchAllOrders();

        // Step 2: Compute statistics only after everything is buffered
        long count = allOrders.size();
        double sum = allOrders.stream()
                .mapToDouble(Order::amount)
                .sum();
        double average = count > 0 ? sum / count : 0.0;

        return new OrderStats(count, sum, average);
    }

    /**
     * Fetches ALL orders from ALL pages into a single List.
     *
     * <p><strong>WARNING:</strong> This is the problematic code pattern!
     * Memory grows with each page fetched.
     *
     * @return list containing ALL orders (entire dataset in memory)
     */
    public List<Order> fetchAllOrders() {
        List<Order> allOrders = new ArrayList<>();
        String cursor = null;

        while (true) {
            Page<Order> page = pageFetcher.apply(cursor);

            // Memory grows here with each page!
            allOrders.addAll(page.items());

            if (!page.hasNextPage()) {
                break;
            }
            cursor = page.nextCursor();
        }

        return allOrders;
    }

    /**
     * Returns the count of ALL orders (requires fetching everything first).
     *
     * <p>Demonstrates another problem: even simple operations require
     * buffering all data with the naive approach.
     *
     * @return total number of orders
     */
    public long countOrders() {
        return fetchAllOrders().size();
    }

    /**
     * Filters orders and returns matching ones.
     *
     * <p>With the naive approach, filtering still requires loading
     * everything into memory first, then filtering.
     *
     * @return list of completed orders (still O(total_items) memory)
     */
    public List<Order> getCompletedOrders() {
        return fetchAllOrders().stream()
                .filter(order -> "completed".equals(order.status()))
                .toList();
    }
}
