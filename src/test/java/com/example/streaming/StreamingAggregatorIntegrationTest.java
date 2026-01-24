package com.example.streaming;

import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import com.example.streaming.server.SimpleOrdersServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for StreamingAggregator - demonstrates the full streaming pipeline.
 *
 * <h2>What This Tests</h2>
 * <p>These tests demonstrate how to aggregate data from paginated HTTP APIs
 * using Java Stream API without buffering all items in memory.</p>
 *
 * <h2>Key Concepts</h2>
 * <ul>
 *   <li><b>Lazy evaluation</b>: Pages are fetched only as items are consumed</li>
 *   <li><b>Memory efficiency</b>: Only one page in memory at a time (O(page_size))</li>
 *   <li><b>Stream composability</b>: filter(), map(), limit(), findFirst() all work</li>
 *   <li><b>Early termination</b>: limit() and findFirst() stop fetching pages</li>
 * </ul>
 *
 * <h2>Test Server</h2>
 * <p>Uses {@link SimpleOrdersServer} - a real HTTP server built with JDK's
 * {@code com.sun.net.httpserver.HttpServer}. No mocking frameworks needed.</p>
 *
 * <h2>Quick Start Example</h2>
 * <pre>{@code
 * // Create aggregator pointing to paginated API
 * StreamingAggregator aggregator = new StreamingAggregator(
 *     "https://api.example.com/orders"
 * );
 *
 * // Aggregate all orders - streams lazily, never buffers all items
 * OrderStats stats = aggregator.aggregateOrders();
 *
 * // Or use Stream API directly
 * double total = aggregator.streamOrders()
 *     .filter(o -> "completed".equals(o.status()))
 *     .mapToDouble(Order::amount)
 *     .sum();
 * }</pre>
 */
class StreamingAggregatorIntegrationTest {

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
     * <p>The StreamingAggregator fetches pages lazily and aggregates
     * without buffering all orders in memory.</p>
     *
     * <pre>{@code
     * StreamingAggregator aggregator = new StreamingAggregator(baseUrl);
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

        StreamingAggregator aggregator = new StreamingAggregator(
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
    // STREAM API OPERATIONS
    // =========================================================================

    /**
     * Using streamOrders() to get a lazy Stream for custom processing.
     *
     * <p>The returned Stream is lazy - pages are only fetched as items
     * are consumed by terminal operations like collect() or forEach().</p>
     */
    @Test
    @DisplayName("Should stream orders lazily across multiple pages")
    void shouldStreamOrdersLazily() throws IOException {
        // Given: Server with 50 orders, 10 per page (5 pages)
        server = SimpleOrdersServer.create(50, 10);
        server.start();

        StreamingAggregator aggregator = new StreamingAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Stream all orders - pages fetched lazily as we iterate
        List<Order> orders = aggregator.streamOrders()
                .collect(Collectors.toList());

        // Then: All 50 orders collected across 5 page fetches
        assertThat(orders).hasSize(50);
        assertThat(orders.get(0).id()).isEqualTo("order-1");
        assertThat(orders.get(49).id()).isEqualTo("order-50");
    }

    /**
     * Early termination with limit() - stops fetching pages once limit is reached.
     *
     * <p>This is a key benefit of lazy evaluation. If you only need the first
     * N items, you don't fetch all pages.</p>
     *
     * <pre>{@code
     * // Only fetches pages until 5 items are collected
     * List<Order> first5 = aggregator.streamOrders()
     *     .limit(5)
     *     .toList();
     * }</pre>
     */
    @Test
    @DisplayName("Should support early termination with limit()")
    void shouldSupportEarlyTermination() throws IOException {
        // Given: Server with many orders (1000 orders, 100 per page)
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        StreamingAggregator aggregator = new StreamingAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Only take first 5 orders - only first page is fetched!
        List<Order> orders = aggregator.streamOrders()
                .limit(5)
                .collect(Collectors.toList());

        // Then: Only 5 orders fetched, not all 1000
        assertThat(orders).hasSize(5);
        assertThat(orders.get(0).id()).isEqualTo("order-1");
        assertThat(orders.get(4).id()).isEqualTo("order-5");
    }

    /**
     * Using filter() to process only matching orders.
     *
     * <p>Filtering happens in the stream pipeline, so only matching
     * orders are passed to downstream operations.</p>
     */
    @Test
    @DisplayName("Should filter orders in stream pipeline")
    void shouldFilterOrders() throws IOException {
        // Given: Server where every 3rd order is "completed" (index 0, 3, 6, ...)
        server = SimpleOrdersServer.create(30, 10);
        server.start();

        StreamingAggregator aggregator = new StreamingAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Filter to completed orders only
        List<Order> completedOrders = aggregator.streamCompletedOrders()
                .collect(Collectors.toList());

        // Then: Only 10 completed orders out of 30 total
        assertThat(completedOrders).hasSize(10);
        assertThat(completedOrders).allMatch(o -> "completed".equals(o.status()));
    }

    /**
     * Combining filter() with aggregation.
     *
     * <p>Filter first, then aggregate - memory usage is still O(1) for
     * the aggregation because we use OrderStatsCollector.</p>
     */
    @Test
    @DisplayName("Should aggregate filtered orders")
    void shouldAggregateFilteredOrders() throws IOException {
        server = SimpleOrdersServer.create(30, 10);
        server.start();

        StreamingAggregator aggregator = new StreamingAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Aggregate only completed orders
        OrderStats stats = aggregator.aggregateCompletedOrders();

        // Then: Stats for 10 completed orders only
        // Completed order amounts: 10, 40, 70, 100, 130, 160, 190, 220, 250, 280 = 1450
        assertThat(stats.count()).isEqualTo(10);
        assertThat(stats.sum()).isCloseTo(1450.0, within(0.01));
        assertThat(stats.average()).isCloseTo(145.0, within(0.01));
    }

    /**
     * Using findFirst() for early termination on match.
     *
     * <p>Stops fetching pages as soon as a matching order is found.</p>
     *
     * <pre>{@code
     * // Stops fetching once an order > $1000 is found
     * Optional<Order> largeOrder = aggregator.streamOrders()
     *     .filter(o -> o.amount() > 1000)
     *     .findFirst();
     * }</pre>
     */
    @Test
    @DisplayName("Should find first order matching condition")
    void shouldFindFirstMatchingOrder() throws IOException {
        server = SimpleOrdersServer.create(100, 10);
        server.start();

        StreamingAggregator aggregator = new StreamingAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Find first order with amount > 500
        // This stops fetching pages once found (order 51 is on page 6)
        var firstLargeOrder = aggregator.streamOrders()
                .filter(o -> o.amount() > 500)
                .findFirst();

        // Then: Found order-51 with amount 510
        assertThat(firstLargeOrder).isPresent();
        assertThat(firstLargeOrder.get().id()).isEqualTo("order-51");
        assertThat(firstLargeOrder.get().amount()).isEqualTo(510.0);
    }

    // =========================================================================
    // ALTERNATIVE AGGREGATION METHODS
    // =========================================================================

    /**
     * Three ways to aggregate - all produce the same result.
     *
     * <ol>
     *   <li>{@code aggregateOrders()} - uses custom OrderStatsCollector</li>
     *   <li>{@code aggregateOrdersWithTeeing()} - uses Collectors.teeing()</li>
     *   <li>{@code aggregateOrdersWithReduce()} - uses Stream.reduce()</li>
     * </ol>
     *
     * <p>All three have O(1) memory for aggregation - they don't buffer orders.</p>
     */
    @Test
    @DisplayName("Should work with different aggregation methods")
    void shouldWorkWithDifferentAggregationMethods() throws IOException {
        server = SimpleOrdersServer.create(100, 25);
        server.start();

        StreamingAggregator aggregator = new StreamingAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Use all three aggregation methods
        OrderStats statsCollector = aggregator.aggregateOrders();           // Custom collector
        OrderStats statsTeeing = aggregator.aggregateOrdersWithTeeing();    // Collectors.teeing()
        OrderStats statsReduce = aggregator.aggregateOrdersWithReduce();    // Stream.reduce()

        // Then: All produce identical results
        assertThat(statsCollector.count()).isEqualTo(100);
        assertThat(statsTeeing.count()).isEqualTo(100);
        assertThat(statsReduce.count()).isEqualTo(100);

        assertThat(statsCollector.sum()).isEqualTo(statsTeeing.sum());
        assertThat(statsTeeing.sum()).isEqualTo(statsReduce.sum());
    }

    /**
     * Using mapToDouble() for numeric stream operations.
     *
     * <pre>{@code
     * double total = aggregator.streamOrders()
     *     .mapToDouble(Order::amount)
     *     .sum();
     * }</pre>
     */
    @Test
    @DisplayName("Should map and collect order amounts")
    void shouldMapAndCollect() throws IOException {
        server = SimpleOrdersServer.create(10, 5);
        server.start();

        StreamingAggregator aggregator = new StreamingAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Use primitive stream for efficiency
        double totalAmount = aggregator.streamOrders()
                .mapToDouble(Order::amount)
                .sum();

        // Then: Sum of 10 + 20 + ... + 100 = 550
        assertThat(totalAmount).isEqualTo(550.0);
    }

    /**
     * Using count() terminal operation.
     */
    @Test
    @DisplayName("Should count orders matching condition")
    void shouldCountMatchingOrders() throws IOException {
        server = SimpleOrdersServer.create(100, 20);
        server.start();

        StreamingAggregator aggregator = new StreamingAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Count pending orders
        long pendingCount = aggregator.streamOrders()
                .filter(o -> "pending".equals(o.status()))
                .count();

        // Then: 33 pending orders (indices 1, 4, 7, ... 97)
        assertThat(pendingCount).isEqualTo(33);
    }

    // =========================================================================
    // MEMORY EFFICIENCY
    // =========================================================================

    /**
     * Demonstrates memory efficiency with large datasets.
     *
     * <p><b>Memory Model:</b></p>
     * <ul>
     *   <li>PaginatedSpliterator: O(page_size) - only one page in memory</li>
     *   <li>OrderStatsCollector: O(1) - just count and sum variables</li>
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

        StreamingAggregator aggregator = new StreamingAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Aggregate - memory stays constant at O(page_size)
        OrderStats stats = aggregator.aggregateOrders();

        // Then: Correct results despite not buffering all 10,000 orders
        assertThat(stats.count()).isEqualTo(10_000);
        assertThat(stats.sum()).isCloseTo(server.getExpectedSum(), within(0.01));
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

        StreamingAggregator aggregator = new StreamingAggregator(
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

        StreamingAggregator aggregator = new StreamingAggregator(
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

            StreamingAggregator aggregator = new StreamingAggregator(
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

            StreamingAggregator aggregator = new StreamingAggregator(
                    server.getBaseUrl() + "/orders"
            );

            List<Order> orders = aggregator.streamOrders()
                    .collect(Collectors.toList());

            assertThat(orders).hasSize(100);
        }
    }
}
