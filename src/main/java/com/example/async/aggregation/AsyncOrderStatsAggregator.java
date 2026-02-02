package com.example.async.aggregation;

import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An AsyncAggregator that computes statistics for Order objects with O(1) memory overhead.
 *
 * <p>This is the async equivalent of
 * {@link com.example.iterable.aggregation.OrderStatsAggregator}. It processes
 * orders one at a time without buffering them in memory. Only the running
 * totals (count and sum) are kept in memory.
 *
 * <p>Example usage:
 * <pre>{@code
 * AsyncIterator<Order> orders = ...;
 * CompletableFuture<OrderStats> statsFuture =
 *     new AsyncOrderStatsAggregator().aggregateAsync(orders);
 *
 * statsFuture.thenAccept(stats -> {
 *     System.out.println("Count: " + stats.count());
 *     System.out.println("Sum: " + stats.sum());
 *     System.out.println("Average: " + stats.average());
 * });
 * }</pre>
 *
 * <p>With filtering:
 * <pre>{@code
 * CompletableFuture<OrderStats> completedStats =
 *     new AsyncOrderStatsAggregator().aggregateFilteredAsync(
 *         orders,
 *         order -> "completed".equals(order.status())
 *     );
 * }</pre>
 *
 * <p>With early termination:
 * <pre>{@code
 * CompletableFuture<OrderStats> partialStats =
 *     new AsyncOrderStatsAggregator().aggregateUntilAsync(
 *         orders,
 *         acc -> acc.getSum() >= 10000.0  // Stop when sum reaches target
 *     );
 * }</pre>
 */
public class AsyncOrderStatsAggregator implements AsyncAggregator<Order, AsyncOrderStatsAggregator.Accumulator, OrderStats> {

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
     * Returns an AsyncOrderStatsAggregator instance.
     * Convenience method for fluent API usage.
     *
     * @return a new AsyncOrderStatsAggregator
     */
    public static AsyncOrderStatsAggregator toOrderStats() {
        return new AsyncOrderStatsAggregator();
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
    public Function<Accumulator, OrderStats> finisher() {
        return Accumulator::finish;
    }
}
