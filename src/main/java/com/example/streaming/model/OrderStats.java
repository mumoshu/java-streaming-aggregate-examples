package com.example.streaming.model;

/**
 * Aggregation result for order statistics.
 * Contains count, sum, and average of order amounts.
 *
 * This class is designed for streaming aggregation - it can accumulate
 * values one at a time without buffering all orders.
 */
public record OrderStats(
        long count,
        double sum,
        double average
) {
    /**
     * Creates an empty OrderStats for use as initial accumulator value.
     */
    public static OrderStats zero() {
        return new OrderStats(0, 0.0, 0.0);
    }

    /**
     * Accumulates a single order amount into the statistics.
     * This is the key method for streaming aggregation - it processes
     * one value at a time without needing to buffer.
     *
     * @param amount the order amount to accumulate
     * @return new OrderStats with updated values
     */
    public OrderStats accumulate(double amount) {
        long newCount = count + 1;
        double newSum = sum + amount;
        double newAverage = newSum / newCount;
        return new OrderStats(newCount, newSum, newAverage);
    }

    /**
     * Accumulates an Order into the statistics.
     *
     * @param order the order to accumulate
     * @return new OrderStats with updated values
     */
    public OrderStats accumulate(Order order) {
        return accumulate(order.amount());
    }

    /**
     * Combines two OrderStats for parallel processing.
     *
     * @param other the other stats to combine with
     * @return new OrderStats with combined values
     */
    public OrderStats combine(OrderStats other) {
        long newCount = this.count + other.count;
        double newSum = this.sum + other.sum;
        double newAverage = newCount > 0 ? newSum / newCount : 0.0;
        return new OrderStats(newCount, newSum, newAverage);
    }
}
