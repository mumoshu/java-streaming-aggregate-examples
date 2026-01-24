package com.example.streaming.aggregation;

import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

/**
 * A custom Collector that aggregates Order objects into OrderStats.
 *
 * <p>This collector is designed for streaming aggregation - it processes
 * orders one at a time without buffering them in memory. Only the running
 * totals (count and sum) are kept in memory.
 *
 * <p>Example usage:
 * <pre>{@code
 * Stream<Order> orders = ...;
 * OrderStats stats = orders.collect(new OrderStatsCollector());
 * }</pre>
 *
 * <p>Or use the static factory method:
 * <pre>{@code
 * OrderStats stats = orders.collect(OrderStatsCollector.toOrderStats());
 * }</pre>
 */
public class OrderStatsCollector implements Collector<Order, OrderStatsCollector.Accumulator, OrderStats> {

    /**
     * Mutable accumulator class that holds the running totals.
     * Only count and sum need to be stored - average is computed at the end.
     */
    public static class Accumulator {
        private long count = 0;
        private double sum = 0.0;

        /**
         * Accumulates a single order into the running totals.
         */
        public void accumulate(Order order) {
            count++;
            sum += order.amount();
        }

        /**
         * Combines this accumulator with another (for parallel streams).
         */
        public Accumulator combine(Accumulator other) {
            this.count += other.count;
            this.sum += other.sum;
            return this;
        }

        /**
         * Converts the accumulated totals into the final OrderStats result.
         */
        public OrderStats finish() {
            double average = count > 0 ? sum / count : 0.0;
            return new OrderStats(count, sum, average);
        }
    }

    /**
     * Returns a Collector that aggregates orders into statistics.
     * Convenience method for use with Stream.collect().
     */
    public static OrderStatsCollector toOrderStats() {
        return new OrderStatsCollector();
    }

    @Override
    public Supplier<Accumulator> supplier() {
        return Accumulator::new;
    }

    @Override
    public BiConsumer<Accumulator, Order> accumulator() {
        return Accumulator::accumulate;
    }

    @Override
    public BinaryOperator<Accumulator> combiner() {
        return Accumulator::combine;
    }

    @Override
    public Function<Accumulator, OrderStats> finisher() {
        return Accumulator::finish;
    }

    @Override
    public Set<Characteristics> characteristics() {
        // UNORDERED: The accumulation is commutative (order doesn't matter for sum/count)
        // This allows for potential parallel optimizations
        return Set.of(Characteristics.UNORDERED);
    }
}
