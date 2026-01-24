package com.example.iterable;

import com.example.streaming.StreamingAggregator;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import com.example.streaming.server.SimpleOrdersServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for IterableAggregator - demonstrates the full iteration-based pipeline.
 *
 * <h2>What This Tests</h2>
 * <p>These tests demonstrate how to aggregate data from paginated HTTP APIs
 * using Java Iterable/Iterator without buffering all items in memory.</p>
 *
 * <h2>Key Concepts</h2>
 * <ul>
 *   <li><b>Lazy evaluation</b>: Pages are fetched only as items are consumed</li>
 *   <li><b>Memory efficiency</b>: Only one page in memory at a time (O(page_size))</li>
 *   <li><b>For-each compatible</b>: Works with standard Java for-each loops</li>
 *   <li><b>Early termination</b>: break statement stops fetching pages</li>
 * </ul>
 *
 * <h2>Comparison with StreamingAggregator</h2>
 * <table>
 *   <tr><th>Aspect</th><th>StreamingAggregator</th><th>IterableAggregator</th></tr>
 *   <tr><td>Style</td><td>Functional</td><td>Imperative</td></tr>
 *   <tr><td>Filtering</td><td>stream.filter()</td><td>if statement in loop</td></tr>
 *   <tr><td>Early exit</td><td>limit(), takeWhile()</td><td>break statement</td></tr>
 *   <tr><td>Memory</td><td>O(page_size) + O(1)</td><td>O(page_size) + O(1)</td></tr>
 * </table>
 *
 * <h2>Test Server</h2>
 * <p>Uses {@link SimpleOrdersServer} - a real HTTP server built with JDK's
 * {@code com.sun.net.httpserver.HttpServer}. No mocking frameworks needed.</p>
 *
 * <h2>Quick Start Example</h2>
 * <pre>{@code
 * // Create aggregator pointing to paginated API
 * IterableAggregator aggregator = new IterableAggregator(
 *     "https://api.example.com/orders"
 * );
 *
 * // Aggregate all orders - iterates lazily, never buffers all items
 * OrderStats stats = aggregator.aggregateOrders();
 *
 * // Or iterate manually with for-each
 * for (Order order : aggregator.iterateOrders()) {
 *     process(order);
 *     if (shouldStop(order)) {
 *         break; // No more pages fetched
 *     }
 * }
 * }</pre>
 */
class IterableAggregatorIntegrationTest {

    private SimpleOrdersServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    // =========================================================================
    // BASIC AGGREGATION
    // =========================================================================

    /**
     * Basic usage: aggregate all orders from a paginated API.
     *
     * <p>The IterableAggregator fetches pages lazily and aggregates
     * without buffering all orders in memory.</p>
     *
     * <pre>{@code
     * IterableAggregator aggregator = new IterableAggregator(baseUrl);
     * OrderStats stats = aggregator.aggregateOrders();
     * // stats.count(), stats.sum(), stats.average()
     * }</pre>
     */
    @Test
    @DisplayName("Should aggregate all orders from paginated API")
    void shouldAggregateAllOrders() throws IOException {
        // Given: Server with 1000 orders, 100 per page (10 pages)
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        IterableAggregator aggregator = new IterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Aggregate - pages fetched lazily, only one page in memory at a time
        OrderStats stats = aggregator.aggregateOrders();

        // Then: All 1000 orders aggregated without buffering them all
        assertThat(stats.count()).isEqualTo(1000);
        assertThat(stats.sum()).isCloseTo(server.getExpectedSum(), within(0.01));
        assertThat(stats.average()).isCloseTo(server.getExpectedAverage(), within(0.01));
    }

    // =========================================================================
    // FOR-EACH ITERATION
    // =========================================================================

    /**
     * Using iterateOrders() to get a lazy Iterable for custom processing.
     *
     * <p>The returned Iterable is lazy - pages are only fetched as items
     * are consumed in the for-each loop.</p>
     */
    @Test
    @DisplayName("Should iterate orders lazily across multiple pages")
    void shouldIterateOrdersLazily() throws IOException {
        // Given: Server with 50 orders, 10 per page (5 pages)
        server = SimpleOrdersServer.create(50, 10);
        server.start();

        IterableAggregator aggregator = new IterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Iterate all orders - pages fetched lazily as we iterate
        List<Order> orders = new ArrayList<>();
        for (Order order : aggregator.iterateOrders()) {
            orders.add(order);
        }

        // Then: All 50 orders collected across 5 page fetches
        assertThat(orders).hasSize(50);
        assertThat(orders.get(0).id()).isEqualTo("order-1");
        assertThat(orders.get(49).id()).isEqualTo("order-50");
    }

    /**
     * Early termination with break - stops fetching pages once break is executed.
     *
     * <p>This is a key benefit of lazy evaluation. If you only need the first
     * N items, you don't fetch all pages.</p>
     *
     * <pre>{@code
     * // Only fetches pages until 5 items are collected
     * List<Order> first5 = new ArrayList<>();
     * int count = 0;
     * for (Order order : aggregator.iterateOrders()) {
     *     first5.add(order);
     *     if (++count >= 5) break;
     * }
     * }</pre>
     */
    @Test
    @DisplayName("Should support early termination with break")
    void shouldSupportEarlyTermination() throws IOException {
        // Given: Server with many orders (1000 orders, 100 per page)
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        IterableAggregator aggregator = new IterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Only take first 5 orders - only first page is fetched!
        List<Order> orders = new ArrayList<>();
        int count = 0;
        for (Order order : aggregator.iterateOrders()) {
            orders.add(order);
            if (++count >= 5) {
                break;  // Early termination
            }
        }

        // Then: Only 5 orders collected, not all 1000
        assertThat(orders).hasSize(5);
        assertThat(orders.get(0).id()).isEqualTo("order-1");
        assertThat(orders.get(4).id()).isEqualTo("order-5");
    }

    /**
     * Using if statement to filter orders during iteration.
     * Compare with stream.filter() in the Stream-based approach.
     */
    @Test
    @DisplayName("Should filter orders with if statement in loop")
    void shouldFilterOrders() throws IOException {
        // Given: Server where every 3rd order is "completed" (index 0, 3, 6, ...)
        server = SimpleOrdersServer.create(30, 10);
        server.start();

        IterableAggregator aggregator = new IterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Filter to completed orders using if statement
        List<Order> completedOrders = new ArrayList<>();
        for (Order order : aggregator.iterateOrders()) {
            if ("completed".equals(order.status())) {
                completedOrders.add(order);
            }
        }

        // Then: Only 10 completed orders out of 30 total
        assertThat(completedOrders).hasSize(10);
        assertThat(completedOrders).allMatch(o -> "completed".equals(o.status()));
    }

    /**
     * Combining filtering with aggregation using aggregateFiltered().
     */
    @Test
    @DisplayName("Should aggregate filtered orders")
    void shouldAggregateFilteredOrders() throws IOException {
        server = SimpleOrdersServer.create(30, 10);
        server.start();

        IterableAggregator aggregator = new IterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Aggregate only completed orders using aggregateCompletedOrders()
        OrderStats stats = aggregator.aggregateCompletedOrders();

        // Then: Stats for 10 completed orders only
        // Completed order amounts: 10, 40, 70, 100, 130, 160, 190, 220, 250, 280 = 1450
        assertThat(stats.count()).isEqualTo(10);
        assertThat(stats.sum()).isCloseTo(1450.0, within(0.01));
        assertThat(stats.average()).isCloseTo(145.0, within(0.01));
    }

    /**
     * Using break to find first order matching condition.
     * Compare with stream.filter().findFirst() in the Stream-based approach.
     */
    @Test
    @DisplayName("Should find first order matching condition with break")
    void shouldFindFirstMatchingOrder() throws IOException {
        server = SimpleOrdersServer.create(100, 10);
        server.start();

        IterableAggregator aggregator = new IterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Find first order with amount > 500
        Order firstLargeOrder = null;
        for (Order order : aggregator.iterateOrders()) {
            if (order.amount() > 500) {
                firstLargeOrder = order;
                break;  // Stop iteration once found
            }
        }

        // Then: Found order-51 with amount 510
        assertThat(firstLargeOrder).isNotNull();
        assertThat(firstLargeOrder.id()).isEqualTo("order-51");
        assertThat(firstLargeOrder.amount()).isEqualTo(510.0);
    }

    // =========================================================================
    // ALTERNATIVE AGGREGATION METHODS
    // =========================================================================

    /**
     * Two ways to aggregate - both produce the same result.
     *
     * <ol>
     *   <li>{@code aggregateOrders()} - uses OrderStatsAggregator</li>
     *   <li>{@code aggregateOrdersManually()} - uses explicit for-each loop</li>
     * </ol>
     *
     * <p>Both have O(1) memory for aggregation - they don't buffer orders.</p>
     */
    @Test
    @DisplayName("Should work with different aggregation methods")
    void shouldWorkWithDifferentAggregationMethods() throws IOException {
        server = SimpleOrdersServer.create(100, 25);
        server.start();

        IterableAggregator aggregator = new IterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Use both aggregation methods
        OrderStats statsAggregator = aggregator.aggregateOrders();        // Using Aggregator
        OrderStats statsManual = aggregator.aggregateOrdersManually();    // Using for-each

        // Then: Both produce identical results
        assertThat(statsAggregator.count()).isEqualTo(100);
        assertThat(statsManual.count()).isEqualTo(100);

        assertThat(statsAggregator.sum()).isEqualTo(statsManual.sum());
        assertThat(statsAggregator.average()).isEqualTo(statsManual.average());
    }

    // =========================================================================
    // COMPARISON WITH STREAM-BASED APPROACH
    // =========================================================================

    /**
     * Demonstrates that both approaches produce identical results.
     *
     * <p>This test verifies that StreamingAggregator and IterableAggregator
     * produce the same results when processing the same data.</p>
     */
    @Test
    @DisplayName("Should produce same results as StreamingAggregator")
    void shouldProduceSameResultsAsStreamingAggregator() throws IOException {
        server = SimpleOrdersServer.create(100, 25);
        server.start();

        String url = server.getBaseUrl() + "/orders";

        // When: Use both approaches
        StreamingAggregator streamingAggregator = new StreamingAggregator(url);
        IterableAggregator iterableAggregator = new IterableAggregator(url);

        OrderStats streamStats = streamingAggregator.aggregateOrders();
        OrderStats iterableStats = iterableAggregator.aggregateOrders();

        // Then: Identical results
        assertThat(iterableStats.count()).isEqualTo(streamStats.count());
        assertThat(iterableStats.sum()).isCloseTo(streamStats.sum(), within(0.001));
        assertThat(iterableStats.average()).isCloseTo(streamStats.average(), within(0.001));
    }

    /**
     * Demonstrates that filtered aggregation produces same results.
     */
    @Test
    @DisplayName("Should produce same filtered results as StreamingAggregator")
    void shouldProduceSameFilteredResultsAsStreamingAggregator() throws IOException {
        server = SimpleOrdersServer.create(30, 10);
        server.start();

        String url = server.getBaseUrl() + "/orders";

        // When: Use both approaches for filtered aggregation
        StreamingAggregator streamingAggregator = new StreamingAggregator(url);
        IterableAggregator iterableAggregator = new IterableAggregator(url);

        OrderStats streamStats = streamingAggregator.aggregateCompletedOrders();
        OrderStats iterableStats = iterableAggregator.aggregateCompletedOrders();

        // Then: Identical results
        assertThat(iterableStats.count()).isEqualTo(streamStats.count());
        assertThat(iterableStats.sum()).isCloseTo(streamStats.sum(), within(0.001));
        assertThat(iterableStats.average()).isCloseTo(streamStats.average(), within(0.001));
    }

    // =========================================================================
    // MEMORY EFFICIENCY
    // =========================================================================

    /**
     * Demonstrates memory efficiency with large datasets.
     *
     * <p><b>Memory Model:</b></p>
     * <ul>
     *   <li>PaginatedIterator: O(page_size) - only one page in memory</li>
     *   <li>OrderStatsAggregator: O(1) - just count and sum variables</li>
     *   <li>Total: O(page_size) regardless of total orders</li>
     * </ul>
     *
     * <p>With 10,000 orders and page size 100, memory usage is ~100 Order objects,
     * not 10,000.</p>
     */
    @Test
    @DisplayName("Should demonstrate memory efficiency with large dataset")
    void shouldDemonstrateMemoryEfficiency() throws IOException {
        // Given: Large dataset - 10,000 orders across 100 pages
        server = SimpleOrdersServer.create(10_000, 100);
        server.start();

        IterableAggregator aggregator = new IterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Aggregate - memory stays constant at O(page_size)
        OrderStats stats = aggregator.aggregateOrders();

        // Then: Correct results despite not buffering all 10,000 orders
        assertThat(stats.count()).isEqualTo(10_000);
        assertThat(stats.sum()).isCloseTo(server.getExpectedSum(), within(0.01));
    }

    /**
     * Demonstrates aggregateUntilAmount() for early termination.
     */
    @Test
    @DisplayName("Should support aggregateUntilAmount for early termination")
    void shouldSupportAggregateUntilAmount() throws IOException {
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        IterableAggregator aggregator = new IterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Aggregate until sum reaches 500
        OrderStats stats = aggregator.aggregateUntilAmount(500.0);

        // Then: Should have processed orders until sum >= 500
        // Orders: 10, 20, 30, 40, 50, 60, 70, 80, 90, 100 = 550 (10 orders)
        assertThat(stats.sum()).isGreaterThanOrEqualTo(500.0);
        assertThat(stats.count()).isLessThan(1000); // Didn't process all orders
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Test
    @DisplayName("Should handle single page response")
    void shouldHandleSinglePage() throws IOException {
        // Given: Fewer orders than page size (no pagination needed)
        server = SimpleOrdersServer.create(5, 100);
        server.start();

        IterableAggregator aggregator = new IterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        OrderStats stats = aggregator.aggregateOrders();

        assertThat(stats.count()).isEqualTo(5);
        assertThat(stats.sum()).isEqualTo(150.0); // 10 + 20 + 30 + 40 + 50
        assertThat(stats.average()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("Should handle empty response")
    void shouldHandleEmptyResponse() throws IOException {
        // Given: No orders
        server = SimpleOrdersServer.create(0, 100);
        server.start();

        IterableAggregator aggregator = new IterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        OrderStats stats = aggregator.aggregateOrders();

        assertThat(stats.count()).isEqualTo(0);
        assertThat(stats.sum()).isEqualTo(0.0);
        assertThat(stats.average()).isEqualTo(0.0);
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle page size of 1")
        void shouldHandlePageSizeOfOne() throws IOException {
            // Given: Each page has only 1 item (10 pages for 10 orders)
            server = SimpleOrdersServer.create(10, 1);
            server.start();

            IterableAggregator aggregator = new IterableAggregator(
                    server.getBaseUrl() + "/orders"
            );

            OrderStats stats = aggregator.aggregateOrders();

            assertThat(stats.count()).isEqualTo(10);
            assertThat(stats.sum()).isEqualTo(550.0);
        }

        @Test
        @DisplayName("Should handle exact page boundary")
        void shouldHandleExactPageBoundary() throws IOException {
            // Given: Orders exactly fit into pages (no partial last page)
            server = SimpleOrdersServer.create(100, 25);
            server.start();

            IterableAggregator aggregator = new IterableAggregator(
                    server.getBaseUrl() + "/orders"
            );

            List<Order> orders = new ArrayList<>();
            for (Order order : aggregator.iterateOrders()) {
                orders.add(order);
            }

            assertThat(orders).hasSize(100);
        }

        @Test
        @DisplayName("Should allow multiple iterations over same iterable")
        void shouldAllowMultipleIterations() throws IOException {
            server = SimpleOrdersServer.create(10, 5);
            server.start();

            IterableAggregator aggregator = new IterableAggregator(
                    server.getBaseUrl() + "/orders"
            );

            Iterable<Order> orders = aggregator.iterateOrders();

            // First iteration
            List<Order> firstPass = new ArrayList<>();
            for (Order order : orders) {
                firstPass.add(order);
            }

            // Second iteration (should start fresh)
            List<Order> secondPass = new ArrayList<>();
            for (Order order : orders) {
                secondPass.add(order);
            }

            // Both iterations got all items
            assertThat(firstPass).hasSize(10);
            assertThat(secondPass).hasSize(10);
            assertThat(firstPass.get(0).id()).isEqualTo(secondPass.get(0).id());
        }
    }
}
