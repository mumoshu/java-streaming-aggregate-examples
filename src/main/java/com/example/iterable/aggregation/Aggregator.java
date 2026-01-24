package com.example.iterable.aggregation;

import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Aggregates items from an Iterable into a result with O(1) memory overhead.
 *
 * <p>This is the Iterator-based equivalent of {@link java.util.stream.Collector}.
 * Unlike Collector, this interface is designed primarily for sequential processing
 * and includes convenient default methods for common aggregation patterns.
 *
 * <p>The aggregation process consists of three phases:
 * <ol>
 *   <li><b>Initialization:</b> Create a mutable accumulator via {@link #supplier()}</li>
 *   <li><b>Accumulation:</b> Process each item via {@link #accumulator()}</li>
 *   <li><b>Finishing:</b> Transform accumulator to result via {@link #finisher()}</li>
 * </ol>
 *
 * <p>Example usage:
 * <pre>{@code
 * Aggregator<Order, StatsAccumulator, OrderStats> aggregator = ...;
 * OrderStats stats = aggregator.aggregate(orders);
 * }</pre>
 *
 * <p>Or create ad-hoc aggregators:
 * <pre>{@code
 * Aggregator<Integer, long[], Long> sumAggregator = Aggregator.of(
 *     () -> new long[1],
 *     (acc, val) -> acc[0] += val,
 *     acc -> acc[0]
 * );
 * long sum = sumAggregator.aggregate(numbers);
 * }</pre>
 *
 * @param <T> the type of input elements
 * @param <A> the mutable accumulator type (internal state)
 * @param <R> the result type of the aggregation
 */
public interface Aggregator<T, A, R> {

    /**
     * Creates a new mutable accumulator instance.
     * Called once at the start of aggregation.
     *
     * @return a supplier that creates a new accumulator
     */
    Supplier<A> supplier();

    /**
     * Incorporates a single element into the accumulator.
     * Called once per element, in encounter order.
     *
     * @return a function that adds an element to an accumulator
     */
    BiConsumer<A, T> accumulator();

    /**
     * Transforms the accumulator into the final result.
     * Called once after all elements are processed.
     *
     * @return a function that transforms the accumulator into the result
     */
    Function<A, R> finisher();

    /**
     * Combines two accumulators into one.
     *
     * <p>This method is optional and returns {@code null} by default, indicating
     * that this aggregator does not support parallel aggregation. Override this
     * method to enable potential parallel processing support.
     *
     * @return a function that combines two accumulators, or {@code null} if not supported
     */
    default BinaryOperator<A> combiner() {
        return null; // Sequential-only by default
    }

    /**
     * Aggregates all items from the given Iterable into a single result.
     *
     * <p>This is the primary method for performing aggregation. It:
     * <ol>
     *   <li>Creates a new accumulator</li>
     *   <li>Processes each item through the accumulator function</li>
     *   <li>Converts the final accumulator state to the result</li>
     * </ol>
     *
     * <p>Memory usage is O(1) if the accumulator maintains constant-size state.
     *
     * @param source the iterable to aggregate
     * @return the aggregation result
     */
    default R aggregate(Iterable<T> source) {
        A acc = supplier().get();
        BiConsumer<A, T> accFn = accumulator();
        for (T item : source) {
            accFn.accept(acc, item);
        }
        return finisher().apply(acc);
    }

    /**
     * Aggregates items from the given Iterable that match the filter predicate.
     *
     * <p>This method provides filtering capability without requiring a separate
     * filtering iterator. Items that do not match the predicate are skipped.
     *
     * <p>Example:
     * <pre>{@code
     * OrderStats completedStats = aggregator.aggregateFiltered(
     *     orders,
     *     order -> "completed".equals(order.status())
     * );
     * }</pre>
     *
     * @param source the iterable to aggregate
     * @param filter predicate to select which items to include
     * @return the aggregation result for filtered items
     */
    default R aggregateFiltered(Iterable<T> source, Predicate<T> filter) {
        A acc = supplier().get();
        BiConsumer<A, T> accFn = accumulator();
        for (T item : source) {
            if (filter.test(item)) {
                accFn.accept(acc, item);
            }
        }
        return finisher().apply(acc);
    }

    /**
     * Creates an Aggregator from functional components.
     *
     * <p>This factory method allows creating ad-hoc aggregators without
     * defining a new class. The resulting aggregator does not support
     * parallel aggregation (combiner returns null).
     *
     * <p>Example:
     * <pre>{@code
     * // Sum aggregator
     * Aggregator<Integer, long[], Long> sum = Aggregator.of(
     *     () -> new long[1],
     *     (acc, val) -> acc[0] += val,
     *     acc -> acc[0]
     * );
     *
     * // Count aggregator
     * Aggregator<Object, long[], Long> count = Aggregator.of(
     *     () -> new long[1],
     *     (acc, val) -> acc[0]++,
     *     acc -> acc[0]
     * );
     * }</pre>
     *
     * @param supplier creates a new accumulator
     * @param accumulator adds an element to the accumulator
     * @param finisher transforms the accumulator to the result
     * @param <T> the type of input elements
     * @param <A> the mutable accumulator type
     * @param <R> the result type
     * @return a new Aggregator
     */
    static <T, A, R> Aggregator<T, A, R> of(
            Supplier<A> supplier,
            BiConsumer<A, T> accumulator,
            Function<A, R> finisher) {
        return new Aggregator<>() {
            @Override
            public Supplier<A> supplier() {
                return supplier;
            }

            @Override
            public BiConsumer<A, T> accumulator() {
                return accumulator;
            }

            @Override
            public Function<A, R> finisher() {
                return finisher;
            }
        };
    }

    /**
     * Creates an Aggregator from functional components with combiner support.
     *
     * <p>This factory method allows creating ad-hoc aggregators that support
     * potential parallel aggregation through the combiner function.
     *
     * @param supplier creates a new accumulator
     * @param accumulator adds an element to the accumulator
     * @param combiner combines two accumulators
     * @param finisher transforms the accumulator to the result
     * @param <T> the type of input elements
     * @param <A> the mutable accumulator type
     * @param <R> the result type
     * @return a new Aggregator with combiner support
     */
    static <T, A, R> Aggregator<T, A, R> of(
            Supplier<A> supplier,
            BiConsumer<A, T> accumulator,
            BinaryOperator<A> combiner,
            Function<A, R> finisher) {
        return new Aggregator<>() {
            @Override
            public Supplier<A> supplier() {
                return supplier;
            }

            @Override
            public BiConsumer<A, T> accumulator() {
                return accumulator;
            }

            @Override
            public BinaryOperator<A> combiner() {
                return combiner;
            }

            @Override
            public Function<A, R> finisher() {
                return finisher;
            }
        };
    }
}
