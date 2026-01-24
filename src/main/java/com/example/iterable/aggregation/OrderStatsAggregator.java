package com.example.iterable.aggregation;

import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;

import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An Aggregator that computes statistics for Order objects with O(1) memory overhead.
 *
 * <p>This is the Iterator-based equivalent of
 * {@link com.example.streaming.aggregation.OrderStatsCollector}. It processes
 * orders one at a time without buffering them in memory. Only the running
 * totals (count and sum) are kept in memory.
 *
 * <p>Example usage:
 * <pre>{@code
 * Iterable<Order> orders = ...;
 * OrderStats stats = new OrderStatsAggregator().aggregate(orders);
 * }</pre>
 *
 * <p>Or use the static factory method:
 * <pre>{@code
 * OrderStats stats = OrderStatsAggregator.toOrderStats().aggregate(orders);
 * }</pre>
 *
 * <p>With filtering:
 * <pre>{@code
 * OrderStats completedStats = new OrderStatsAggregator().aggregateFiltered(
 *     orders,
 *     order -> "completed".equals(order.status())
 * );
 * }</pre>
 */
public class OrderStatsAggregator implements Aggregator<Order, OrderStatsAggregator.Accumulator, OrderStats> {

    /**
     * Mutable accumulator class that holds the running totals.
     * Only count and sum need to be stored - average is computed at the end.
     *
     * <p>Memory usage: 16 bytes (8 for count + 8 for sum)
     */
    public static class Accumulator {
        private long count = 0;
        private double sum = 0.0;

        /**
         * Accumulates a single order into the running totals.
         *
         * @param order the order to accumulate
         */
        public void accumulate(Order order) {
            count++;
            sum += order.amount();
        }

        /**
         * Combines this accumulator with another (for potential parallel aggregation).
         *
         * @param other the other accumulator to combine with
         * @return this accumulator with combined totals
         */
        public Accumulator combine(Accumulator other) {
            this.count += other.count;
            this.sum += other.sum;
            return this;
        }

        /**
         * Converts the accumulated totals into the final OrderStats result.
         *
         * @return the computed statistics
         */
        public OrderStats finish() {
            double average = count > 0 ? sum / count : 0.0;
            return new OrderStats(count, sum, average);
        }

        /**
         * Returns the current count of accumulated orders.
         *
         * @return the count
         */
        public long getCount() {
            return count;
        }

        /**
         * Returns the current sum of order amounts.
         *
         * @return the sum
         */
        public double getSum() {
            return sum;
        }
    }

    /**
     * Returns an OrderStatsAggregator instance.
     * Convenience method for fluent API usage.
     *
     * @return a new OrderStatsAggregator
     */
    public static OrderStatsAggregator toOrderStats() {
        return new OrderStatsAggregator();
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
}
