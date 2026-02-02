package com.example.async;

import com.example.async.iterator.AsyncIterator;
import com.example.iterable.IterableAggregator;
import com.example.streaming.StreamingAggregator;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import com.example.streaming.server.SimpleOrdersServer;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for AsyncIterableAggregator - demonstrates the full async pipeline.
 *
 * <h2>What This Tests</h2>
 * <p>These tests demonstrate how to aggregate data from paginated HTTP APIs
 * using async iteration with CompletableFuture for non-blocking I/O.</p>
 *
 * <h2>Key Concepts</h2>
 * <ul>
 *   <li><b>Non-blocking</b>: Thread is freed during HTTP I/O operations</li>
 *   <li><b>Lazy evaluation</b>: Pages are fetched only as items are consumed</li>
 *   <li><b>Memory efficiency</b>: Only one page in memory at a time (O(page_size))</li>
 *   <li><b>Cancellation</b>: cancel() stops fetching pages</li>
 * </ul>
 *
 * <h2>Comparison with Other Variants</h2>
 * <table>
 *   <tr><th>Aspect</th><th>Streaming</th><th>Iterable</th><th>Async</th></tr>
 *   <tr><td>Blocking</td><td>Yes</td><td>Yes</td><td>No</td></tr>
 *   <tr><td>Style</td><td>Functional</td><td>Imperative</td><td>Future-based</td></tr>
 *   <tr><td>Filtering</td><td>stream.filter()</td><td>if statement</td><td>predicate param</td></tr>
 *   <tr><td>Early exit</td><td>limit()</td><td>break</td><td>cancel()</td></tr>
 *   <tr><td>Thread usage</td><td>Blocks</td><td>Blocks</td><td>Non-blocking</td></tr>
 * </table>
 */
class AsyncIterableAggregatorIntegrationTest {

    private SimpleOrdersServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    // =========================================================================
    // BASIC ASYNC AGGREGATION
    // =========================================================================

    @Test
    @DisplayName("Should aggregate all orders from paginated API asynchronously")
    void shouldAggregateAllOrdersAsync() throws Exception {
        // Given: Server with 1000 orders, 100 per page (10 pages)
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Aggregate asynchronously - non-blocking
        CompletableFuture<OrderStats> future = aggregator.aggregateOrdersAsync();

        // Wait for completion (in real code, you'd chain with thenAccept)
        OrderStats stats = future.get(30, TimeUnit.SECONDS);

        // Then: All 1000 orders aggregated
        assertThat(stats.count()).isEqualTo(1000);
        assertThat(stats.sum()).isCloseTo(server.getExpectedSum(), within(0.01));
        assertThat(stats.average()).isCloseTo(server.getExpectedAverage(), within(0.01));
    }

    // =========================================================================
    // ASYNC ITERATION
    // =========================================================================

    @Test
    @DisplayName("Should iterate orders lazily and asynchronously")
    void shouldIterateOrdersAsync() throws Exception {
        // Given: Server with 50 orders, 10 per page
        server = SimpleOrdersServer.create(50, 10);
        server.start();

        AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Iterate asynchronously using forEachAsync
        List<Order> orders = new ArrayList<>();
        aggregator.iterateOrdersAsync()
                .forEachAsync(orders::add)
                .get(30, TimeUnit.SECONDS);

        // Then: All 50 orders collected
        assertThat(orders).hasSize(50);
        assertThat(orders.get(0).id()).isEqualTo("order-1");
        assertThat(orders.get(49).id()).isEqualTo("order-50");
    }

    @Test
    @DisplayName("Should support takeFirstAsync for limiting results")
    void shouldSupportTakeFirst() throws Exception {
        // Given: Server with many orders
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Take first 5 orders asynchronously
        List<Order> orders = aggregator.takeFirstAsync(5)
                .get(30, TimeUnit.SECONDS);

        // Then: Only 5 orders collected
        assertThat(orders).hasSize(5);
        assertThat(orders.get(0).id()).isEqualTo("order-1");
        assertThat(orders.get(4).id()).isEqualTo("order-5");
    }

    @Test
    @DisplayName("Should support cancellation for early termination")
    void shouldSupportCancellation() throws Exception {
        // Given: Server with many orders
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        AsyncIterator<Order> iterator = aggregator.iterateOrdersAsync();

        // When: Take 3 items then cancel
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Optional<Order> opt = iterator.nextAsync().get(10, TimeUnit.SECONDS);
            opt.ifPresent(orders::add);
        }
        iterator.cancel();

        // Then: Only 3 orders collected
        assertThat(orders).hasSize(3);
        assertThat(iterator.isCancelled()).isTrue();

        // And: Future calls return empty
        Optional<Order> afterCancel = iterator.nextAsync().get(1, TimeUnit.SECONDS);
        assertThat(afterCancel).isEmpty();
    }

    // =========================================================================
    // FILTERED AGGREGATION
    // =========================================================================

    @Test
    @DisplayName("Should aggregate only completed orders asynchronously")
    void shouldAggregateCompletedOrdersAsync() throws Exception {
        server = SimpleOrdersServer.create(30, 10);
        server.start();

        AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Aggregate only completed orders
        OrderStats stats = aggregator.aggregateCompletedOrdersAsync()
                .get(30, TimeUnit.SECONDS);

        // Then: Stats for 10 completed orders only
        assertThat(stats.count()).isEqualTo(10);
        assertThat(stats.sum()).isCloseTo(1450.0, within(0.01));
        assertThat(stats.average()).isCloseTo(145.0, within(0.01));
    }

    @Test
    @DisplayName("Should support findFirstAsync for conditional search")
    void shouldSupportFindFirstAsync() throws Exception {
        server = SimpleOrdersServer.create(100, 10);
        server.start();

        AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Find first order with amount > 500
        Optional<Order> result = aggregator.findFirstAsync(order -> order.amount() > 500)
                .get(30, TimeUnit.SECONDS);

        // Then: Found order-51 with amount 510
        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("order-51");
        assertThat(result.get().amount()).isEqualTo(510.0);
    }

    // =========================================================================
    // EARLY TERMINATION
    // =========================================================================

    @Test
    @DisplayName("Should support aggregateUntilAmount for early termination")
    void shouldSupportAggregateUntilAmount() throws Exception {
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Aggregate until sum reaches 500
        OrderStats stats = aggregator.aggregateUntilAmountAsync(500.0)
                .get(30, TimeUnit.SECONDS);

        // Then: Should have processed orders until sum >= 500
        assertThat(stats.sum()).isGreaterThanOrEqualTo(500.0);
        assertThat(stats.count()).isLessThan(1000);
    }

    // =========================================================================
    // COMPARISON WITH SYNC VARIANTS
    // =========================================================================

    @Test
    @DisplayName("Should produce same results as IterableAggregator")
    void shouldProduceSameResultsAsIterableAggregator() throws Exception {
        server = SimpleOrdersServer.create(100, 25);
        server.start();

        String url = server.getBaseUrl() + "/orders";

        // When: Use both approaches
        IterableAggregator iterableAggregator = new IterableAggregator(url);
        AsyncIterableAggregator asyncAggregator = new AsyncIterableAggregator(url);

        OrderStats syncStats = iterableAggregator.aggregateOrders();
        OrderStats asyncStats = asyncAggregator.aggregateOrdersAsync()
                .get(30, TimeUnit.SECONDS);

        // Then: Identical results
        assertThat(asyncStats.count()).isEqualTo(syncStats.count());
        assertThat(asyncStats.sum()).isCloseTo(syncStats.sum(), within(0.001));
        assertThat(asyncStats.average()).isCloseTo(syncStats.average(), within(0.001));
    }

    @Test
    @DisplayName("Should produce same results as StreamingAggregator")
    void shouldProduceSameResultsAsStreamingAggregator() throws Exception {
        server = SimpleOrdersServer.create(100, 25);
        server.start();

        String url = server.getBaseUrl() + "/orders";

        // When: Use both approaches
        StreamingAggregator streamingAggregator = new StreamingAggregator(url);
        AsyncIterableAggregator asyncAggregator = new AsyncIterableAggregator(url);

        OrderStats streamStats = streamingAggregator.aggregateOrders();
        OrderStats asyncStats = asyncAggregator.aggregateOrdersAsync()
                .get(30, TimeUnit.SECONDS);

        // Then: Identical results
        assertThat(asyncStats.count()).isEqualTo(streamStats.count());
        assertThat(asyncStats.sum()).isCloseTo(streamStats.sum(), within(0.001));
        assertThat(asyncStats.average()).isCloseTo(streamStats.average(), within(0.001));
    }

    @Test
    @DisplayName("Should produce same filtered results as sync variants")
    void shouldProduceSameFilteredResults() throws Exception {
        server = SimpleOrdersServer.create(30, 10);
        server.start();

        String url = server.getBaseUrl() + "/orders";

        // When: Compare filtered aggregation
        IterableAggregator iterableAggregator = new IterableAggregator(url);
        AsyncIterableAggregator asyncAggregator = new AsyncIterableAggregator(url);

        OrderStats syncStats = iterableAggregator.aggregateCompletedOrders();
        OrderStats asyncStats = asyncAggregator.aggregateCompletedOrdersAsync()
                .get(30, TimeUnit.SECONDS);

        // Then: Identical results
        assertThat(asyncStats.count()).isEqualTo(syncStats.count());
        assertThat(asyncStats.sum()).isCloseTo(syncStats.sum(), within(0.001));
        assertThat(asyncStats.average()).isCloseTo(syncStats.average(), within(0.001));
    }

    // =========================================================================
    // MEMORY EFFICIENCY
    // =========================================================================

    @Test
    @DisplayName("Should demonstrate memory efficiency with large dataset")
    void shouldDemonstrateMemoryEfficiency() throws Exception {
        // Given: Large dataset - 10,000 orders across 100 pages
        server = SimpleOrdersServer.create(10_000, 100);
        server.start();

        AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // When: Aggregate asynchronously
        OrderStats stats = aggregator.aggregateOrdersAsync()
                .get(60, TimeUnit.SECONDS);

        // Then: Correct results despite not buffering all 10,000 orders
        assertThat(stats.count()).isEqualTo(10_000);
        assertThat(stats.sum()).isCloseTo(server.getExpectedSum(), within(0.01));
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle single page response")
        void shouldHandleSinglePage() throws Exception {
            server = SimpleOrdersServer.create(5, 100);
            server.start();

            AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                    server.getBaseUrl() + "/orders"
            );

            OrderStats stats = aggregator.aggregateOrdersAsync()
                    .get(10, TimeUnit.SECONDS);

            assertThat(stats.count()).isEqualTo(5);
            assertThat(stats.sum()).isEqualTo(150.0);
            assertThat(stats.average()).isEqualTo(30.0);
        }

        @Test
        @DisplayName("Should handle empty response")
        void shouldHandleEmptyResponse() throws Exception {
            server = SimpleOrdersServer.create(0, 100);
            server.start();

            AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                    server.getBaseUrl() + "/orders"
            );

            OrderStats stats = aggregator.aggregateOrdersAsync()
                    .get(10, TimeUnit.SECONDS);

            assertThat(stats.count()).isEqualTo(0);
            assertThat(stats.sum()).isEqualTo(0.0);
            assertThat(stats.average()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should handle page size of 1")
        void shouldHandlePageSizeOfOne() throws Exception {
            server = SimpleOrdersServer.create(10, 1);
            server.start();

            AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                    server.getBaseUrl() + "/orders"
            );

            OrderStats stats = aggregator.aggregateOrdersAsync()
                    .get(30, TimeUnit.SECONDS);

            assertThat(stats.count()).isEqualTo(10);
            assertThat(stats.sum()).isEqualTo(550.0);
        }

        @Test
        @DisplayName("Should handle findFirst with no match")
        void shouldHandleFindFirstNoMatch() throws Exception {
            server = SimpleOrdersServer.create(10, 10);
            server.start();

            AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                    server.getBaseUrl() + "/orders"
            );

            // Order amounts are 10, 20, ..., 100 - none exceed 1000
            Optional<Order> result = aggregator.findFirstAsync(order -> order.amount() > 1000)
                    .get(10, TimeUnit.SECONDS);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should handle takeFirst with fewer items available")
        void shouldHandleTakeFirstWithFewerItems() throws Exception {
            server = SimpleOrdersServer.create(3, 10);
            server.start();

            AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                    server.getBaseUrl() + "/orders"
            );

            // Request 10 but only 3 available
            List<Order> orders = aggregator.takeFirstAsync(10)
                    .get(10, TimeUnit.SECONDS);

            assertThat(orders).hasSize(3);
        }
    }

    // =========================================================================
    // ASYNC CHAINING PATTERNS
    // =========================================================================

    @Test
    @DisplayName("Should support async chaining with thenCompose")
    void shouldSupportAsyncChaining() throws Exception {
        server = SimpleOrdersServer.create(100, 25);
        server.start();

        AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        // Demonstrate async chaining pattern
        String result = aggregator.aggregateOrdersAsync()
                .thenApply(stats -> "Processed " + stats.count() + " orders totaling $" + stats.sum())
                .get(30, TimeUnit.SECONDS);

        assertThat(result).contains("Processed 100 orders");
    }

    @Test
    @DisplayName("Should support processAllOrdersAsync for side effects")
    void shouldSupportProcessAllOrdersAsync() throws Exception {
        server = SimpleOrdersServer.create(10, 5);
        server.start();

        AsyncIterableAggregator aggregator = new AsyncIterableAggregator(
                server.getBaseUrl() + "/orders"
        );

        List<String> processedIds = new ArrayList<>();

        aggregator.processAllOrdersAsync(order -> processedIds.add(order.id()))
                .get(10, TimeUnit.SECONDS);

        assertThat(processedIds).hasSize(10);
        assertThat(processedIds).containsExactly(
                "order-1", "order-2", "order-3", "order-4", "order-5",
                "order-6", "order-7", "order-8", "order-9", "order-10"
        );
    }
}
