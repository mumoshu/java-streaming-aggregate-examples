package com.example.streaming;

import com.example.streaming.aggregation.OrderStatsCollector;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for OrderStatsCollector - demonstrates O(1) memory aggregation.
 *
 * <h2>What OrderStatsCollector Does</h2>
 * <p>Implements {@link java.util.stream.Collector} to aggregate order statistics
 * (count, sum, average) without storing individual orders in memory.</p>
 *
 * <h2>Memory Model</h2>
 * <p>Unlike {@code Collectors.toList()} which buffers all items, this collector
 * maintains only two variables:</p>
 * <ul>
 *   <li>{@code count}: Number of orders processed</li>
 *   <li>{@code sum}: Running total of order amounts</li>
 * </ul>
 * <p>Memory usage is O(1) regardless of how many orders are processed.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Using constructor
 * OrderStats stats = orders.stream()
 *     .collect(new OrderStatsCollector());
 *
 * // Using factory method (recommended)
 * OrderStats stats = orders.stream()
 *     .collect(OrderStatsCollector.toOrderStats());
 *
 * // Access results
 * long count = stats.count();      // Total orders
 * double sum = stats.sum();        // Sum of amounts
 * double avg = stats.average();    // Average amount
 * }</pre>
 *
 * <h2>Parallel Stream Support</h2>
 * <p>Unlike {@link com.example.streaming.spliterator.PaginatedSpliterator}
 * which cannot be parallelized, OrderStatsCollector fully supports parallel
 * streams via its {@code combiner()} method that merges partial results.</p>
 *
 * <h2>Collector Contract</h2>
 * <p>Implements the four Collector functions:</p>
 * <ul>
 *   <li>{@code supplier()}: Creates new Accumulator</li>
 *   <li>{@code accumulator()}: Adds one order to the running totals</li>
 *   <li>{@code combiner()}: Merges two accumulators (for parallel streams)</li>
 *   <li>{@code finisher()}: Converts Accumulator to final OrderStats</li>
 * </ul>
 */
class OrderStatsCollectorTest {

    // =========================================================================
    // BASIC AGGREGATION
    // =========================================================================

    /**
     * Basic usage: collect order statistics from a stream.
     *
     * <pre>{@code
     * OrderStats stats = orders.stream()
     *     .collect(new OrderStatsCollector());
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
        OrderStats stats = orders.stream()
                .collect(new OrderStatsCollector());

        // Then
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.sum()).isEqualTo(450.0);
        assertThat(stats.average()).isEqualTo(150.0);
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    /**
     * Handles empty streams gracefully - returns zero for all statistics.
     */
    @Test
    @DisplayName("Should handle empty stream")
    void shouldHandleEmptyStream() {
        // When
        OrderStats stats = Stream.<Order>empty()
                .collect(new OrderStatsCollector());

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
        OrderStats stats = Stream.of(new Order("1", 42.5, "completed"))
                .collect(new OrderStatsCollector());

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
     * OrderStats stats = orders.stream()
     *     .collect(OrderStatsCollector.toOrderStats());
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
        OrderStats stats = orders.stream()
                .collect(OrderStatsCollector.toOrderStats());

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
     * // BAD: O(n) memory - stores all orders
     * List<Order> all = orders.collect(Collectors.toList());
     * double sum = all.stream().mapToDouble(Order::amount).sum();
     *
     * // GOOD: O(1) memory - only stores count and sum
     * OrderStats stats = orders.collect(new OrderStatsCollector());
     * }</pre>
     */
    @Test
    @DisplayName("Should aggregate large number of orders efficiently")
    void shouldAggregateLargeNumberOfOrders() {
        // Given: 100,000 orders
        int count = 100_000;

        // When
        OrderStats stats = IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Order("order-" + i, i * 1.0, "completed"))
                .collect(new OrderStatsCollector());

        // Then
        assertThat(stats.count()).isEqualTo(count);
        // Sum of 1 + 2 + ... + n = n * (n + 1) / 2
        double expectedSum = count * (count + 1.0) / 2.0;
        assertThat(stats.sum()).isCloseTo(expectedSum, within(0.001));
        assertThat(stats.average()).isCloseTo(expectedSum / count, within(0.001));
    }

    // =========================================================================
    // PARALLEL STREAM SUPPORT
    // =========================================================================

    /**
     * Demonstrates parallel stream support via the combiner() method.
     *
     * <p>When using {@code parallelStream()}, the stream is split into chunks,
     * each processed by a separate thread with its own Accumulator. The
     * {@code combiner()} method merges these partial results.</p>
     *
     * <p><b>How it works:</b></p>
     * <ol>
     *   <li>Stream is split into N chunks (one per CPU core)</li>
     *   <li>Each chunk gets its own Accumulator via {@code supplier()}</li>
     *   <li>Each thread accumulates its chunk via {@code accumulator()}</li>
     *   <li>Partial results are merged via {@code combiner()}</li>
     *   <li>Final result computed via {@code finisher()}</li>
     * </ol>
     *
     * <p><b>Note:</b> This differs from PaginatedSpliterator which cannot
     * be parallelized because pages must be fetched sequentially.</p>
     */
    @Test
    @DisplayName("Should support parallel streams")
    void shouldSupportParallelStreams() {
        // Given: Orders to process in parallel
        List<Order> orders = IntStream.rangeClosed(1, 10_000)
                .mapToObj(i -> new Order("order-" + i, i * 1.0, "completed"))
                .toList();

        // When: Process in parallel
        OrderStats parallelStats = orders.parallelStream()
                .collect(new OrderStatsCollector());

        // And: Process sequentially for comparison
        OrderStats sequentialStats = orders.stream()
                .collect(new OrderStatsCollector());

        // Then: Results should be identical
        assertThat(parallelStats.count()).isEqualTo(sequentialStats.count());
        assertThat(parallelStats.sum()).isCloseTo(sequentialStats.sum(), within(0.001));
        assertThat(parallelStats.average()).isCloseTo(sequentialStats.average(), within(0.001));
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
        OrderStatsCollector.Accumulator acc = new OrderStatsCollector.Accumulator();

        // When
        acc.accumulate(new Order("1", 100.0, "completed"));

        // Then
        OrderStats stats = acc.finish();
        assertThat(stats.count()).isEqualTo(1);
        assertThat(stats.sum()).isEqualTo(100.0);
        assertThat(stats.average()).isEqualTo(100.0);
    }

    /**
     * Tests combining two Accumulators - essential for parallel stream support.
     *
     * <p>When parallel streams are used, each thread has its own Accumulator.
     * The combine() method merges them into a single result.</p>
     */
    @Test
    @DisplayName("Accumulator should combine two accumulators")
    void accumulatorShouldCombine() {
        // Given
        OrderStatsCollector.Accumulator acc1 = new OrderStatsCollector.Accumulator();
        acc1.accumulate(new Order("1", 100.0, "completed"));
        acc1.accumulate(new Order("2", 200.0, "pending"));

        OrderStatsCollector.Accumulator acc2 = new OrderStatsCollector.Accumulator();
        acc2.accumulate(new Order("3", 300.0, "completed"));

        // When
        OrderStatsCollector.Accumulator combined = acc1.combine(acc2);

        // Then
        OrderStats stats = combined.finish();
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.sum()).isEqualTo(600.0);
        assertThat(stats.average()).isEqualTo(200.0);
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
        OrderStats stats = orders.stream()
                .collect(new OrderStatsCollector());

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
        OrderStats stats = orders.stream()
                .collect(new OrderStatsCollector());

        // Then
        assertThat(stats.count()).isEqualTo(3);
        assertThat(stats.sum()).isEqualTo(250.0);
        assertThat(stats.average()).isCloseTo(83.333, within(0.001));
    }

    // =========================================================================
    // STREAM PIPELINE COMPATIBILITY
    // =========================================================================

    /**
     * Works seamlessly with filter() in the stream pipeline.
     *
     * <p>Filter first, then aggregate - a common pattern for computing
     * statistics on a subset of orders.</p>
     *
     * <pre>{@code
     * OrderStats completedStats = orders.stream()
     *     .filter(o -> "completed".equals(o.status()))
     *     .collect(OrderStatsCollector.toOrderStats());
     * }</pre>
     */
    @Test
    @DisplayName("Should work with filtered stream")
    void shouldWorkWithFilteredStream() {
        // Given
        List<Order> orders = List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending"),
                new Order("3", 150.0, "completed"),
                new Order("4", 300.0, "cancelled")
        );

        // When: Only aggregate completed orders
        OrderStats stats = orders.stream()
                .filter(o -> "completed".equals(o.status()))
                .collect(new OrderStatsCollector());

        // Then
        assertThat(stats.count()).isEqualTo(2);
        assertThat(stats.sum()).isEqualTo(250.0);
        assertThat(stats.average()).isEqualTo(125.0);
    }
}
