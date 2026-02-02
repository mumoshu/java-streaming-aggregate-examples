package com.example.virtualthreads;

import com.example.iterable.IterableAggregator;
import com.example.streaming.StreamingAggregator;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import com.example.streaming.server.SimpleOrdersServer;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for VirtualThreadAggregator.
 *
 * <h2>What This Tests</h2>
 * <p>Demonstrates using Java 21+ virtual threads for concurrent aggregation
 * with simple blocking code that scales efficiently.</p>
 *
 * <h2>Key Concepts</h2>
 * <ul>
 *   <li><b>Virtual Threads</b>: Lightweight threads for blocking I/O</li>
 *   <li><b>Simple Code</b>: No callbacks or reactive operators</li>
 *   <li><b>Concurrent Aggregations</b>: Multiple URLs processed in parallel</li>
 *   <li><b>Reuse</b>: Delegates to existing IterableAggregator</li>
 * </ul>
 */
class VirtualThreadAggregatorIntegrationTest {

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

    @Test
    @DisplayName("Should aggregate all orders asynchronously on virtual thread")
    void shouldAggregateAllOrdersAsync() throws Exception {
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        VirtualThreadAggregator aggregator = new VirtualThreadAggregator(
                server.getBaseUrl() + "/orders"
        );

        CompletableFuture<OrderStats> future = aggregator.aggregateOrdersAsync();
        OrderStats stats = future.get(30, TimeUnit.SECONDS);

        assertThat(stats.count()).isEqualTo(1000);
        assertThat(stats.sum()).isCloseTo(server.getExpectedSum(), within(0.01));
        assertThat(stats.average()).isCloseTo(server.getExpectedAverage(), within(0.01));
    }

    @Test
    @DisplayName("Should aggregate orders synchronously")
    void shouldAggregateOrdersSynchronously() throws Exception {
        server = SimpleOrdersServer.create(100, 25);
        server.start();

        VirtualThreadAggregator aggregator = new VirtualThreadAggregator(
                server.getBaseUrl() + "/orders"
        );

        // Run synchronously (useful when already on a virtual thread)
        OrderStats stats = aggregator.aggregateOrders();

        assertThat(stats.count()).isEqualTo(100);
        assertThat(stats.sum()).isCloseTo(server.getExpectedSum(), within(0.01));
    }

    // =========================================================================
    // FILTERING
    // =========================================================================

    @Test
    @DisplayName("Should aggregate filtered orders on virtual thread")
    void shouldAggregateFilteredOrders() throws Exception {
        server = SimpleOrdersServer.create(30, 10);
        server.start();

        VirtualThreadAggregator aggregator = new VirtualThreadAggregator(
                server.getBaseUrl() + "/orders"
        );

        CompletableFuture<OrderStats> future = aggregator.aggregateFilteredAsync(
                order -> "completed".equals(order.status())
        );
        OrderStats stats = future.get(30, TimeUnit.SECONDS);

        // Every 3rd order is completed (indices 0, 3, 6, ...)
        assertThat(stats.count()).isEqualTo(10);
        assertThat(stats.sum()).isCloseTo(1450.0, within(0.01));
    }

    @Test
    @DisplayName("Should find first matching order on virtual thread")
    void shouldFindFirstMatchingOrder() throws Exception {
        server = SimpleOrdersServer.create(100, 10);
        server.start();

        VirtualThreadAggregator aggregator = new VirtualThreadAggregator(
                server.getBaseUrl() + "/orders"
        );

        CompletableFuture<Order> future = aggregator.findFirstAsync(
                order -> order.amount() > 500
        );
        Order found = future.get(30, TimeUnit.SECONDS);

        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo("order-51");
        assertThat(found.amount()).isEqualTo(510.0);
    }

    // =========================================================================
    // CONCURRENT AGGREGATIONS
    // =========================================================================

    @Test
    @DisplayName("Should aggregate multiple URLs concurrently")
    void shouldAggregateMultipleUrlsConcurrently() throws Exception {
        // Create multiple servers
        SimpleOrdersServer server1 = SimpleOrdersServer.create(50, 10);
        SimpleOrdersServer server2 = SimpleOrdersServer.create(100, 25);
        SimpleOrdersServer server3 = SimpleOrdersServer.create(75, 15);

        server1.start();
        server2.start();
        server3.start();

        try {
            List<String> urls = List.of(
                    server1.getBaseUrl() + "/orders",
                    server2.getBaseUrl() + "/orders",
                    server3.getBaseUrl() + "/orders"
            );

            List<OrderStats> results = VirtualThreadAggregator.aggregateMultiple(urls);

            assertThat(results).hasSize(3);
            assertThat(results.get(0).count()).isEqualTo(50);
            assertThat(results.get(1).count()).isEqualTo(100);
            assertThat(results.get(2).count()).isEqualTo(75);
        } finally {
            server1.close();
            server2.close();
            server3.close();
        }
    }

    @Test
    @DisplayName("Should combine results from multiple URLs")
    void shouldCombineResultsFromMultipleUrls() throws Exception {
        SimpleOrdersServer server1 = SimpleOrdersServer.create(50, 10);
        SimpleOrdersServer server2 = SimpleOrdersServer.create(100, 25);

        server1.start();
        server2.start();

        try {
            List<String> urls = List.of(
                    server1.getBaseUrl() + "/orders",
                    server2.getBaseUrl() + "/orders"
            );

            OrderStats combined = VirtualThreadAggregator.aggregateMultipleCombined(urls);

            assertThat(combined.count()).isEqualTo(150);
            assertThat(combined.sum()).isCloseTo(
                    server1.getExpectedSum() + server2.getExpectedSum(),
                    within(0.01)
            );
        } finally {
            server1.close();
            server2.close();
        }
    }

    // =========================================================================
    // COMPARISON WITH OTHER VARIANTS
    // =========================================================================

    @Test
    @DisplayName("Should produce same results as StreamingAggregator")
    void shouldProduceSameResultsAsStreamingAggregator() throws Exception {
        server = SimpleOrdersServer.create(100, 25);
        server.start();

        String url = server.getBaseUrl() + "/orders";

        StreamingAggregator streamingAggregator = new StreamingAggregator(url);
        VirtualThreadAggregator virtualThreadAggregator = new VirtualThreadAggregator(url);

        OrderStats streamStats = streamingAggregator.aggregateOrders();
        OrderStats vtStats = virtualThreadAggregator.aggregateOrders();

        assertThat(vtStats.count()).isEqualTo(streamStats.count());
        assertThat(vtStats.sum()).isCloseTo(streamStats.sum(), within(0.001));
        assertThat(vtStats.average()).isCloseTo(streamStats.average(), within(0.001));
    }

    @Test
    @DisplayName("Should produce same results as IterableAggregator")
    void shouldProduceSameResultsAsIterableAggregator() throws Exception {
        server = SimpleOrdersServer.create(100, 25);
        server.start();

        String url = server.getBaseUrl() + "/orders";

        IterableAggregator iterableAggregator = new IterableAggregator(url);
        VirtualThreadAggregator virtualThreadAggregator = new VirtualThreadAggregator(url);

        OrderStats iterableStats = iterableAggregator.aggregateOrders();
        OrderStats vtStats = virtualThreadAggregator.aggregateOrders();

        assertThat(vtStats.count()).isEqualTo(iterableStats.count());
        assertThat(vtStats.sum()).isCloseTo(iterableStats.sum(), within(0.001));
        assertThat(vtStats.average()).isCloseTo(iterableStats.average(), within(0.001));
    }

    // =========================================================================
    // ITERATION
    // =========================================================================

    @Test
    @DisplayName("Should iterate orders with for-each")
    void shouldIterateOrdersWithForEach() throws Exception {
        server = SimpleOrdersServer.create(50, 10);
        server.start();

        VirtualThreadAggregator aggregator = new VirtualThreadAggregator(
                server.getBaseUrl() + "/orders"
        );

        List<String> ids = new ArrayList<>();
        for (Order order : aggregator.iterateOrders()) {
            ids.add(order.id());
        }

        assertThat(ids).hasSize(50);
        assertThat(ids.get(0)).isEqualTo("order-1");
        assertThat(ids.get(49)).isEqualTo("order-50");
    }

    @Test
    @DisplayName("Should support early termination with break")
    void shouldSupportEarlyTerminationWithBreak() throws Exception {
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        VirtualThreadAggregator aggregator = new VirtualThreadAggregator(
                server.getBaseUrl() + "/orders"
        );

        List<Order> collected = new ArrayList<>();
        for (Order order : aggregator.iterateOrders()) {
            collected.add(order);
            if (collected.size() >= 5) {
                break;
            }
        }

        assertThat(collected).hasSize(5);
        assertThat(collected.get(0).id()).isEqualTo("order-1");
        assertThat(collected.get(4).id()).isEqualTo("order-5");
    }

    // =========================================================================
    // UTILITY METHODS
    // =========================================================================

    @Test
    @DisplayName("Should run task on virtual thread")
    void shouldRunTaskOnVirtualThread() throws Exception {
        server = SimpleOrdersServer.create(50, 10);
        server.start();

        String url = server.getBaseUrl() + "/orders";

        OrderStats stats = VirtualThreadAggregator.runOnVirtualThread(() -> {
            IterableAggregator aggregator = new IterableAggregator(url);
            return aggregator.aggregateOrders();
        });

        assertThat(stats.count()).isEqualTo(50);
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

            VirtualThreadAggregator aggregator = new VirtualThreadAggregator(
                    server.getBaseUrl() + "/orders"
            );

            OrderStats stats = aggregator.aggregateOrdersAsync().get(30, TimeUnit.SECONDS);

            assertThat(stats.count()).isEqualTo(5);
            assertThat(stats.sum()).isEqualTo(150.0);
            assertThat(stats.average()).isEqualTo(30.0);
        }

        @Test
        @DisplayName("Should handle empty response")
        void shouldHandleEmptyResponse() throws Exception {
            server = SimpleOrdersServer.create(0, 100);
            server.start();

            VirtualThreadAggregator aggregator = new VirtualThreadAggregator(
                    server.getBaseUrl() + "/orders"
            );

            OrderStats stats = aggregator.aggregateOrdersAsync().get(30, TimeUnit.SECONDS);

            assertThat(stats.count()).isEqualTo(0);
            assertThat(stats.sum()).isEqualTo(0.0);
            assertThat(stats.average()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should handle findFirst with no match")
        void shouldHandleFindFirstNoMatch() throws Exception {
            server = SimpleOrdersServer.create(10, 10);
            server.start();

            VirtualThreadAggregator aggregator = new VirtualThreadAggregator(
                    server.getBaseUrl() + "/orders"
            );

            Order result = aggregator.findFirstAsync(order -> order.amount() > 1000)
                    .get(30, TimeUnit.SECONDS);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should handle empty URL list")
        void shouldHandleEmptyUrlList() {
            List<OrderStats> results = VirtualThreadAggregator.aggregateMultiple(List.of());

            assertThat(results).isEmpty();
        }
    }

    // =========================================================================
    // VIRTUAL THREAD VERIFICATION
    // =========================================================================

    @Test
    @DisplayName("Should actually run on virtual thread")
    void shouldActuallyRunOnVirtualThread() throws Exception {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> Thread.currentThread().isVirtual(),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        );

        Boolean isVirtual = future.get(5, TimeUnit.SECONDS);

        assertThat(isVirtual).isTrue();
    }
}
