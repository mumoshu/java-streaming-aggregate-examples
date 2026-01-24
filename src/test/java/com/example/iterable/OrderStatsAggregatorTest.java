package com.example.iterable;

import com.example.iterable.aggregation.Aggregator;
import com.example.iterable.aggregation.OrderStatsAggregator;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for OrderStatsAggregator - demonstrates O(1) memory aggregation
 * using the Iterable/Iterator pattern.
 *
 * <h2>What OrderStatsAggregator Does</h2>
 * <p>Implements {@link Aggregator} to aggregate order statistics
 * (count, sum, average) without storing individual orders in memory.</p>
 *
 * <h2>Memory Model</h2>
 * <p>Unlike collecting to a List which buffers all items, this aggregator
 * maintains only two variables:</p>
 * <ul>
 *   <li>{@code count}: Number of orders processed</li>
 *   <li>{@code sum}: Running total of order amounts</li>
 * </ul>
 * <p>Memory usage is O(1) regardless of how many orders are processed.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Using aggregate() method
 * Iterable<Order> orders = ...;
 * OrderStats stats = new OrderStatsAggregator().aggregate(orders);
 *
 * // Using factory method
 * OrderStats stats = OrderStatsAggregator.toOrderStats().aggregate(orders);
 *
 * // With filtering
 * OrderStats completedStats = new OrderStatsAggregator().aggregateFiltered(
 *     orders,
 *     order -> "completed".equals(order.status())
 * );
 * }</pre>
 *
 * <h2>Comparison with OrderStatsCollector</h2>
 * <table>
 *   <tr><th>Aspect</th><th>OrderStatsCollector</th><th>OrderStatsAggregator</th></tr>
 *   <tr><td>Interface</td><td>Collector&lt;T,A,R&gt;</td><td>Aggregator&lt;T,A,R&gt;</td></tr>
 *   <tr><td>Usage</td><td>stream.collect()</td><td>aggregator.aggregate(iterable)</td></tr>
 *   <tr><td>Memory</td><td>O(1)</td><td>O(1)</td></tr>
 * </table>
 */
class OrderStatsAggregatorTest {

    // =========================================================================
    // BASIC AGGREGATION
    // =========================================================================

    /**
     * Basic usage: aggregate order statistics from an iterable.
     *
     * <pre>{@code
     * OrderStats stats = new OrderStatsAggregator().aggregate(orders);
     * }</pre>
     */
    @Test
    @DisplayName("Should aggregate orders correctly")
    void shouldAggregateOrdersCorrectly() {
        // Given
        List<Order> orders = List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending"),
                new Order("3", 150.0, "completed")
        );

        // When
        OrderStats stats = new OrderStatsAggregator().aggregate(orders);

        // Then
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.sum()).isEqualTo(450.0);
        assertThat(stats.average()).isEqualTo(150.0);
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    /**
     * Handles empty iterables gracefully - returns zero for all statistics.
     */
    @Test
    @DisplayName("Should handle empty iterable")
    void shouldHandleEmptyIterable() {
        // When
        OrderStats stats = new OrderStatsAggregator().aggregate(Collections.emptyList());

        // Then
        assertThat(stats.count()).isEqualTo(0);
        assertThat(stats.sum()).isEqualTo(0.0);
        assertThat(stats.average()).isEqualTo(0.0);
    }

    /**
     * Works correctly with a single order.
     */
    @Test
    @DisplayName("Should handle single order")
    void shouldHandleSingleOrder() {
        // When
        OrderStats stats = new OrderStatsAggregator().aggregate(
                List.of(new Order("1", 42.5, "completed"))
        );

        // Then
        assertThat(stats.count()).isEqualTo(1);
        assertThat(stats.sum()).isEqualTo(42.5);
        assertThat(stats.average()).isEqualTo(42.5);
    }

    // =========================================================================
    // FACTORY METHOD
    // =========================================================================

    /**
     * Recommended usage: use the static factory method for cleaner code.
     *
     * <pre>{@code
     * OrderStats stats = OrderStatsAggregator.toOrderStats().aggregate(orders);
     * }</pre>
     */
    @Test
    @DisplayName("Should work with toOrderStats() factory method")
    void shouldWorkWithFactoryMethod() {
        // Given
        List<Order> orders = List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending")
        );

        // When
        OrderStats stats = OrderStatsAggregator.toOrderStats().aggregate(orders);

        // Then
        assertThat(stats.count()).isEqualTo(2);
        assertThat(stats.sum()).isEqualTo(300.0);
        assertThat(stats.average()).isEqualTo(150.0);
    }

    // =========================================================================
    // MEMORY EFFICIENCY - THE KEY BENEFIT
    // =========================================================================

    /**
     * Demonstrates O(1) memory usage with large datasets.
     *
     * <p>This test processes 100,000 orders but only ever stores
     * two numbers in memory: count and sum. No intermediate List is created.</p>
     *
     * <p><b>Compare with naive approach:</b></p>
     * <pre>{@code
     * // BAD: O(n) memory - stores all orders in a list first
     * List<Order> all = new ArrayList<>();
     * for (Order order : orders) {
     *     all.add(order);
     * }
     * double sum = 0;
     * for (Order order : all) {
     *     sum += order.amount();
     * }
     *
     * // GOOD: O(1) memory - only stores count and sum
     * OrderStats stats = new OrderStatsAggregator().aggregate(orders);
     * }</pre>
     */
    @Test
    @DisplayName("Should aggregate large number of orders efficiently")
    void shouldAggregateLargeNumberOfOrders() {
        // Given: 100,000 orders
        int count = 100_000;
        List<Order> orders = IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Order("order-" + i, i * 1.0, "completed"))
                .toList();

        // When
        OrderStats stats = new OrderStatsAggregator().aggregate(orders);

        // Then
        assertThat(stats.count()).isEqualTo(count);
        // Sum of 1 + 2 + ... + n = n * (n + 1) / 2
        double expectedSum = count * (count + 1.0) / 2.0;
        assertThat(stats.sum()).isCloseTo(expectedSum, within(0.001));
        assertThat(stats.average()).isCloseTo(expectedSum / count, within(0.001));
    }

    // =========================================================================
    // FILTERED AGGREGATION
    // =========================================================================

    /**
     * Demonstrates aggregateFiltered() for conditional aggregation.
     *
     * <p>Compare with Stream approach:</p>
     * <pre>{@code
     * // Stream approach
     * OrderStats stats = orders.stream()
     *     .filter(o -> "completed".equals(o.status()))
     *     .collect(new OrderStatsCollector());
     *
     * // Iterable approach
     * OrderStats stats = new OrderStatsAggregator().aggregateFiltered(
     *     orders,
     *     order -> "completed".equals(order.status())
     * );
     * }</pre>
     */
    @Test
    @DisplayName("Should support filtered aggregation")
    void shouldSupportFilteredAggregation() {
        // Given
        List<Order> orders = List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending"),
                new Order("3", 150.0, "completed"),
                new Order("4", 300.0, "cancelled")
        );

        // When: Only aggregate completed orders
        OrderStats stats = new OrderStatsAggregator().aggregateFiltered(
                orders,
                order -> "completed".equals(order.status())
        );

        // Then
        assertThat(stats.count()).isEqualTo(2);
        assertThat(stats.sum()).isEqualTo(250.0);
        assertThat(stats.average()).isEqualTo(125.0);
    }

    // =========================================================================
    // ACCUMULATOR INTERNALS
    // =========================================================================

    /**
     * Tests the Accumulator class directly - the mutable container for partial results.
     *
     * <p>The Accumulator holds running totals as orders are processed:</p>
     * <pre>{@code
     * Accumulator acc = new Accumulator();
     * acc.accumulate(order1);  // count=1, sum=100
     * acc.accumulate(order2);  // count=2, sum=300
     * OrderStats stats = acc.finish();  // count=2, sum=300, avg=150
     * }</pre>
     */
    @Test
    @DisplayName("Accumulator should accumulate single order")
    void accumulatorShouldAccumulateSingleOrder() {
        // Given
        OrderStatsAggregator.Accumulator acc = new OrderStatsAggregator.Accumulator();

        // When
        acc.accumulate(new Order("1", 100.0, "completed"));

        // Then
        OrderStats stats = acc.finish();
        assertThat(stats.count()).isEqualTo(1);
        assertThat(stats.sum()).isEqualTo(100.0);
        assertThat(stats.average()).isEqualTo(100.0);
    }

    /**
     * Tests combining two Accumulators - useful for potential parallel processing.
     */
    @Test
    @DisplayName("Accumulator should combine two accumulators")
    void accumulatorShouldCombine() {
        // Given
        OrderStatsAggregator.Accumulator acc1 = new OrderStatsAggregator.Accumulator();
        acc1.accumulate(new Order("1", 100.0, "completed"));
        acc1.accumulate(new Order("2", 200.0, "pending"));

        OrderStatsAggregator.Accumulator acc2 = new OrderStatsAggregator.Accumulator();
        acc2.accumulate(new Order("3", 300.0, "completed"));

        // When
        OrderStatsAggregator.Accumulator combined = acc1.combine(acc2);

        // Then
        OrderStats stats = combined.finish();
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.sum()).isEqualTo(600.0);
        assertThat(stats.average()).isEqualTo(200.0);
    }

    /**
     * Tests the getters on Accumulator for early termination scenarios.
     */
    @Test
    @DisplayName("Accumulator should provide getters for running totals")
    void accumulatorShouldProvideGetters() {
        // Given
        OrderStatsAggregator.Accumulator acc = new OrderStatsAggregator.Accumulator();
        acc.accumulate(new Order("1", 100.0, "completed"));
        acc.accumulate(new Order("2", 200.0, "pending"));

        // Then
        assertThat(acc.getCount()).isEqualTo(2);
        assertThat(acc.getSum()).isEqualTo(300.0);
    }

    // =========================================================================
    // SPECIAL VALUES
    // =========================================================================

    /**
     * Handles zero-amount orders correctly.
     */
    @Test
    @DisplayName("Should handle orders with zero amounts")
    void shouldHandleZeroAmounts() {
        // Given
        List<Order> orders = List.of(
                new Order("1", 0.0, "completed"),
                new Order("2", 100.0, "pending"),
                new Order("3", 0.0, "completed")
        );

        // When
        OrderStats stats = new OrderStatsAggregator().aggregate(orders);

        // Then
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.sum()).isEqualTo(100.0);
        assertThat(stats.average()).isCloseTo(33.333, within(0.001));
    }

    /**
     * Handles negative amounts (e.g., refunds) correctly.
     */
    @Test
    @DisplayName("Should handle negative amounts")
    void shouldHandleNegativeAmounts() {
        // Given: Refunds as negative amounts
        List<Order> orders = List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", -50.0, "refunded"),
                new Order("3", 200.0, "completed")
        );

        // When
        OrderStats stats = new OrderStatsAggregator().aggregate(orders);

        // Then
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.sum()).isEqualTo(250.0);
        assertThat(stats.average()).isCloseTo(83.333, within(0.001));
    }

    // =========================================================================
    // AGGREGATOR INTERFACE FEATURES
    // =========================================================================

    /**
     * Tests the Aggregator.of() static factory method for ad-hoc aggregators.
     */
    @Test
    @DisplayName("Should support ad-hoc aggregators via Aggregator.of()")
    void shouldSupportAdHocAggregators() {
        // Given: Create a sum-only aggregator using Aggregator.of()
        Aggregator<Order, double[], Double> sumAggregator = Aggregator.of(
                () -> new double[1],
                (acc, order) -> acc[0] += order.amount(),
                acc -> acc[0]
        );

        List<Order> orders = List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending")
        );

        // When
        Double sum = sumAggregator.aggregate(orders);

        // Then
        assertThat(sum).isEqualTo(300.0);
    }

    /**
     * Tests the Aggregator.of() with combiner for potential parallel support.
     */
    @Test
    @DisplayName("Should support ad-hoc aggregators with combiner")
    void shouldSupportAdHocAggregatorsWithCombiner() {
        // Given: Create a count aggregator with combiner
        Aggregator<Order, long[], Long> countAggregator = Aggregator.of(
                () -> new long[1],
                (acc, order) -> acc[0]++,
                (acc1, acc2) -> { acc1[0] += acc2[0]; return acc1; },
                acc -> acc[0]
        );

        List<Order> orders = List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending"),
                new Order("3", 150.0, "completed")
        );

        // When
        Long count = countAggregator.aggregate(orders);

        // Then
        assertThat(count).isEqualTo(3);
    }

    // =========================================================================
    // COMPARISON WITH FOR-EACH LOOP
    // =========================================================================

    /**
     * Shows equivalence between Aggregator and manual for-each loop.
     * Both produce the same result with O(1) memory.
     */
    @Test
    @DisplayName("Should produce same result as manual for-each loop")
    void shouldProduceSameResultAsManualLoop() {
        // Given
        List<Order> orders = List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending"),
                new Order("3", 150.0, "completed")
        );

        // When: Using Aggregator
        OrderStats aggregatorStats = new OrderStatsAggregator().aggregate(orders);

        // When: Using manual for-each loop
        long manualCount = 0;
        double manualSum = 0.0;
        for (Order order : orders) {
            manualCount++;
            manualSum += order.amount();
        }
        double manualAverage = manualCount > 0 ? manualSum / manualCount : 0.0;

        // Then: Results should be identical
        assertThat(aggregatorStats.count()).isEqualTo(manualCount);
        assertThat(aggregatorStats.sum()).isEqualTo(manualSum);
        assertThat(aggregatorStats.average()).isEqualTo(manualAverage);
    }
}
