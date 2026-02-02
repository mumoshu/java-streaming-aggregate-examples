package com.example.reactor;

import com.example.iterable.IterableAggregator;
import com.example.streaming.StreamingAggregator;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import com.example.streaming.server.SimpleOrdersServer;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for ReactorAggregator.
 *
 * <h2>What This Tests</h2>
 * <p>Demonstrates reactive aggregation using Project Reactor's Flux with
 * the {@code reduce()} operator instead of recursion.</p>
 *
 * <h2>Key Concepts</h2>
 * <ul>
 *   <li><b>Flux.generate()</b>: Lazy page fetching with state</li>
 *   <li><b>reduce()</b>: Aggregation without recursion</li>
 *   <li><b>Backpressure</b>: Subscriber controls the pace</li>
 *   <li><b>Operators</b>: filter(), take(), takeWhile() for early termination</li>
 * </ul>
 */
class ReactorAggregatorIntegrationTest {

    private SimpleOrdersServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    // =========================================================================
    // BASIC AGGREGATION WITH REDUCE
    // =========================================================================

    @Test
    @DisplayName("Should aggregate all orders using reduce()")
    void shouldAggregateAllOrdersWithReduce() throws Exception {
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        ReactorAggregator aggregator = new ReactorAggregator(
                server.getBaseUrl() + "/orders"
        );

        // Using reduce() - no recursion!
        OrderStats stats = aggregator.aggregateOrders().block();

        assertThat(stats.count()).isEqualTo(1000);
        assertThat(stats.sum()).isCloseTo(server.getExpectedSum(), within(0.01));
        assertThat(stats.average()).isCloseTo(server.getExpectedAverage(), within(0.01));
    }

    // =========================================================================
    // FLUX STREAMING
    // =========================================================================

    @Test
    @DisplayName("Should stream orders lazily with Flux")
    void shouldStreamOrdersWithFlux() throws Exception {
        server = SimpleOrdersServer.create(50, 10);
        server.start();

        ReactorAggregator aggregator = new ReactorAggregator(
                server.getBaseUrl() + "/orders"
        );

        List<Order> orders = aggregator.streamOrders()
                .collectList()
                .block();

        assertThat(orders).hasSize(50);
        assertThat(orders.get(0).id()).isEqualTo("order-1");
        assertThat(orders.get(49).id()).isEqualTo("order-50");
    }

    @Test
    @DisplayName("Should support take() for early termination")
    void shouldSupportTakeForEarlyTermination() throws Exception {
        server = SimpleOrdersServer.create(1000, 100);
        server.start();

        ReactorAggregator aggregator = new ReactorAggregator(
                server.getBaseUrl() + "/orders"
        );

        List<Order> orders = aggregator.takeFirst(5).block();

        assertThat(orders).hasSize(5);
        assertThat(orders.get(0).id()).isEqualTo("order-1");
        assertThat(orders.get(4).id()).isEqualTo("order-5");
    }

    @Test
    @DisplayName("Should support filter() for conditional processing")
    void shouldSupportFilterOperator() throws Exception {
        server = SimpleOrdersServer.create(30, 10);
        server.start();

        ReactorAggregator aggregator = new ReactorAggregator(
                server.getBaseUrl() + "/orders"
        );

        OrderStats stats = aggregator.aggregateCompletedOrders().block();

        // Every 3rd order is completed (indices 0, 3, 6, ...)
        assertThat(stats.count()).isEqualTo(10);
        assertThat(stats.sum()).isCloseTo(1450.0, within(0.01));
    }

    @Test
    @DisplayName("Should support findFirst() for search")
    void shouldSupportFindFirst() throws Exception {
        server = SimpleOrdersServer.create(100, 10);
        server.start();

        ReactorAggregator aggregator = new ReactorAggregator(
                server.getBaseUrl() + "/orders"
        );

        Order found = aggregator.findFirst(order -> order.amount() > 500).block();

        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo("order-51");
        assertThat(found.amount()).isEqualTo(510.0);
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
        ReactorAggregator reactorAggregator = new ReactorAggregator(url);

        OrderStats streamStats = streamingAggregator.aggregateOrders();
        OrderStats reactorStats = reactorAggregator.aggregateOrders().block();

        assertThat(reactorStats.count()).isEqualTo(streamStats.count());
        assertThat(reactorStats.sum()).isCloseTo(streamStats.sum(), within(0.001));
        assertThat(reactorStats.average()).isCloseTo(streamStats.average(), within(0.001));
    }

    @Test
    @DisplayName("Should produce same results as IterableAggregator")
    void shouldProduceSameResultsAsIterableAggregator() throws Exception {
        server = SimpleOrdersServer.create(100, 25);
        server.start();

        String url = server.getBaseUrl() + "/orders";

        IterableAggregator iterableAggregator = new IterableAggregator(url);
        ReactorAggregator reactorAggregator = new ReactorAggregator(url);

        OrderStats iterableStats = iterableAggregator.aggregateOrders();
        OrderStats reactorStats = reactorAggregator.aggregateOrders().block();

        assertThat(reactorStats.count()).isEqualTo(iterableStats.count());
        assertThat(reactorStats.sum()).isCloseTo(iterableStats.sum(), within(0.001));
        assertThat(reactorStats.average()).isCloseTo(iterableStats.average(), within(0.001));
    }

    // =========================================================================
    // MEMORY EFFICIENCY
    // =========================================================================

    @Test
    @DisplayName("Should demonstrate memory efficiency with large dataset")
    void shouldDemonstrateMemoryEfficiency() throws Exception {
        server = SimpleOrdersServer.create(10_000, 100);
        server.start();

        ReactorAggregator aggregator = new ReactorAggregator(
                server.getBaseUrl() + "/orders"
        );

        OrderStats stats = aggregator.aggregateOrders().block();

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

            ReactorAggregator aggregator = new ReactorAggregator(
                    server.getBaseUrl() + "/orders"
            );

            OrderStats stats = aggregator.aggregateOrders().block();

            assertThat(stats.count()).isEqualTo(5);
            assertThat(stats.sum()).isEqualTo(150.0);
            assertThat(stats.average()).isEqualTo(30.0);
        }

        @Test
        @DisplayName("Should handle empty response")
        void shouldHandleEmptyResponse() throws Exception {
            server = SimpleOrdersServer.create(0, 100);
            server.start();

            ReactorAggregator aggregator = new ReactorAggregator(
                    server.getBaseUrl() + "/orders"
            );

            OrderStats stats = aggregator.aggregateOrders().block();

            assertThat(stats.count()).isEqualTo(0);
            assertThat(stats.sum()).isEqualTo(0.0);
            assertThat(stats.average()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should handle page size of 1")
        void shouldHandlePageSizeOfOne() throws Exception {
            server = SimpleOrdersServer.create(10, 1);
            server.start();

            ReactorAggregator aggregator = new ReactorAggregator(
                    server.getBaseUrl() + "/orders"
            );

            OrderStats stats = aggregator.aggregateOrders().block();

            assertThat(stats.count()).isEqualTo(10);
            assertThat(stats.sum()).isEqualTo(550.0);
        }

        @Test
        @DisplayName("Should handle findFirst with no match")
        void shouldHandleFindFirstNoMatch() throws Exception {
            server = SimpleOrdersServer.create(10, 10);
            server.start();

            ReactorAggregator aggregator = new ReactorAggregator(
                    server.getBaseUrl() + "/orders"
            );

            Order result = aggregator.findFirst(order -> order.amount() > 1000).block();

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should handle takeFirst with fewer items available")
        void shouldHandleTakeFirstWithFewerItems() throws Exception {
            server = SimpleOrdersServer.create(3, 10);
            server.start();

            ReactorAggregator aggregator = new ReactorAggregator(
                    server.getBaseUrl() + "/orders"
            );

            List<Order> orders = aggregator.takeFirst(10).block();

            assertThat(orders).hasSize(3);
        }
    }

    // =========================================================================
    // REACTOR-SPECIFIC FEATURES
    // =========================================================================

    @Test
    @DisplayName("Should support Flux operators like map and doOnNext")
    void shouldSupportFluxOperators() throws Exception {
        server = SimpleOrdersServer.create(10, 5);
        server.start();

        ReactorAggregator aggregator = new ReactorAggregator(
                server.getBaseUrl() + "/orders"
        );

        List<String> ids = new ArrayList<>();

        aggregator.streamOrders()
                .doOnNext(order -> ids.add(order.id()))
                .map(Order::amount)
                .reduce(0.0, Double::sum)
                .block();

        assertThat(ids).hasSize(10);
        assertThat(ids).containsExactly(
                "order-1", "order-2", "order-3", "order-4", "order-5",
                "order-6", "order-7", "order-8", "order-9", "order-10"
        );
    }
}
