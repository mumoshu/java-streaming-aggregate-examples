package com.example.async.aggregation;

import com.example.async.iterator.AsyncIterator;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Async version of aggregation that works with {@link AsyncIterator}.
 *
 * <p>This is the async equivalent of
 * {@link com.example.iterable.aggregation.Aggregator}. The aggregation logic
 * (supplier, accumulator, finisher) is synchronous, but the iteration over
 * elements is asynchronous.
 *
 * <p>The interface follows the same pattern as {@link java.util.stream.Collector}:
 * <ul>
 *   <li>{@code supplier()} - creates a mutable accumulator</li>
 *   <li>{@code accumulator()} - adds an element to the accumulator</li>
 *   <li>{@code finisher()} - transforms the accumulator into the final result</li>
 * </ul>
 *
 * <p>Example implementation:
 * <pre>{@code
 * AsyncAggregator<Order, Accumulator, OrderStats> aggregator = new AsyncAggregator<>() {
 *     public Supplier<Accumulator> supplier() {
 *         return Accumulator::new;
 *     }
 *     public BiConsumer<Accumulator, Order> accumulator() {
 *         return (acc, order) -> acc.add(order.amount());
 *     }
 *     public Function<Accumulator, OrderStats> finisher() {
 *         return Accumulator::toStats;
 *     }
 * };
 *
 * // Usage
 * aggregator.aggregateAsync(asyncIterator)
 *     .thenAccept(stats -> System.out.println("Sum: " + stats.sum()));
 * }</pre>
 *
 * @param <T> the type of elements to aggregate
 * @param <A> the type of the mutable accumulator
 * @param <R> the type of the final result
 */
public interface AsyncAggregator<T, A, R> {

    /**
     * Returns a supplier that creates a new mutable accumulator.
     */
    Supplier<A> supplier();

    /**
     * Returns a function that adds an element to the accumulator.
     */
    BiConsumer<A, T> accumulator();

    /**
     * Returns a function that transforms the accumulator into the final result.
     */
    Function<A, R> finisher();

    /**
     * Asynchronously aggregates all items from the async iterator.
     *
     * <p>This method recursively calls {@code nextAsync()} and accumulates
     * each element until the iterator is exhausted.
     *
     * @param source the async iterator to aggregate from
     * @return CompletableFuture that completes with the aggregated result
     */
    default CompletableFuture<R> aggregateAsync(AsyncIterator<T> source) {
        A acc = supplier().get();
        BiConsumer<A, T> accFn = accumulator();

        return aggregateRecursive(source, acc, accFn)
                .thenApply(finisher());
    }

    private CompletableFuture<A> aggregateRecursive(
            AsyncIterator<T> source,
            A acc,
            BiConsumer<A, T> accFn
    ) {
        return source.nextAsync().thenCompose(opt -> {
            if (opt.isPresent()) {
                accFn.accept(acc, opt.get());
                return aggregateRecursive(source, acc, accFn);
            }
            return CompletableFuture.completedFuture(acc);
        });
    }

    /**
     * Asynchronously aggregates items that match the given filter.
     *
     * <p>Only items for which the predicate returns {@code true} are
     * accumulated. All items are still iterated through.
     *
     * @param source the async iterator to aggregate from
     * @param filter predicate to filter items
     * @return CompletableFuture that completes with the filtered aggregation
     */
    default CompletableFuture<R> aggregateFilteredAsync(
            AsyncIterator<T> source,
            Predicate<T> filter
    ) {
        A acc = supplier().get();
        BiConsumer<A, T> accFn = accumulator();

        return aggregateFilteredRecursive(source, acc, accFn, filter)
                .thenApply(finisher());
    }

    private CompletableFuture<A> aggregateFilteredRecursive(
            AsyncIterator<T> source,
            A acc,
            BiConsumer<A, T> accFn,
            Predicate<T> filter
    ) {
        return source.nextAsync().thenCompose(opt -> {
            if (opt.isPresent()) {
                if (filter.test(opt.get())) {
                    accFn.accept(acc, opt.get());
                }
                return aggregateFilteredRecursive(source, acc, accFn, filter);
            }
            return CompletableFuture.completedFuture(acc);
        });
    }

    /**
     * Asynchronously aggregates items until a stop condition is met.
     *
     * <p>Iteration stops when the predicate returns {@code true} for the
     * current accumulator state. The iterator is cancelled to prevent
     * further network requests.
     *
     * <p>This is useful for early termination, for example aggregating
     * orders until a target sum is reached.
     *
     * @param source the async iterator to aggregate from
     * @param stopCondition predicate that returns true when aggregation should stop
     * @return CompletableFuture that completes with the partial aggregation
     */
    default CompletableFuture<R> aggregateUntilAsync(
            AsyncIterator<T> source,
            Predicate<A> stopCondition
    ) {
        A acc = supplier().get();
        BiConsumer<A, T> accFn = accumulator();

        return aggregateUntilRecursive(source, acc, accFn, stopCondition)
                .thenApply(finisher());
    }

    private CompletableFuture<A> aggregateUntilRecursive(
            AsyncIterator<T> source,
            A acc,
            BiConsumer<A, T> accFn,
            Predicate<A> stopCondition
    ) {
        if (stopCondition.test(acc)) {
            source.cancel();
            return CompletableFuture.completedFuture(acc);
        }

        return source.nextAsync().thenCompose(opt -> {
            if (opt.isPresent()) {
                accFn.accept(acc, opt.get());
                return aggregateUntilRecursive(source, acc, accFn, stopCondition);
            }
            return CompletableFuture.completedFuture(acc);
        });
    }

    /**
     * Creates a simple async aggregator from the three component functions.
     */
    static <T, A, R> AsyncAggregator<T, A, R> of(
            Supplier<A> supplier,
            BiConsumer<A, T> accumulator,
            Function<A, R> finisher
    ) {
        return new AsyncAggregator<>() {
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
}
