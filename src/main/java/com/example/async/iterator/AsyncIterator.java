package com.example.async.iterator;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * An asynchronous iterator that returns items wrapped in CompletableFuture.
 *
 * <p>Unlike {@link java.util.Iterator} which blocks on {@code hasNext()}/{@code next()},
 * this interface returns CompletableFutures that complete when the data is available.
 * This enables non-blocking iteration over paginated API responses.
 *
 * <p>Usage patterns:
 * <pre>{@code
 * AsyncIterator<Order> iterator = ...;
 *
 * // Pattern 1: Recursive async iteration
 * iterator.nextAsync()
 *     .thenCompose(optOrder -> {
 *         if (optOrder.isPresent()) {
 *             process(optOrder.get());
 *             return iterator.nextAsync();
 *         }
 *         return CompletableFuture.completedFuture(Optional.empty());
 *     });
 *
 * // Pattern 2: Using forEachAsync helper
 * iterator.forEachAsync(order -> process(order))
 *     .thenRun(() -> System.out.println("Done"));
 *
 * // Pattern 3: Blocking wait (for testing or simple cases)
 * Optional<Order> first = iterator.nextAsync().join();
 * }</pre>
 *
 * <h2>Comparison with Iterator</h2>
 * <table>
 *   <tr><th>Aspect</th><th>Iterator</th><th>AsyncIterator</th></tr>
 *   <tr><td>Blocking</td><td>Yes</td><td>No</td></tr>
 *   <tr><td>Check for more</td><td>hasNext()</td><td>nextAsync() returns Optional.empty()</td></tr>
 *   <tr><td>Get next</td><td>next()</td><td>nextAsync().join().get()</td></tr>
 *   <tr><td>Early exit</td><td>break</td><td>cancel()</td></tr>
 * </table>
 *
 * @param <T> the type of elements
 */
public interface AsyncIterator<T> {

    /**
     * Asynchronously retrieves the next element.
     *
     * <p>The returned CompletableFuture completes with:
     * <ul>
     *   <li>{@code Optional.of(element)} if an element is available</li>
     *   <li>{@code Optional.empty()} if iteration is complete or cancelled</li>
     *   <li>Exceptionally if an error occurs during fetching</li>
     * </ul>
     *
     * <p>This method may trigger an async page fetch if the current page
     * is exhausted and more pages are available.
     *
     * @return a CompletableFuture that completes with the next element or empty
     */
    CompletableFuture<Optional<T>> nextAsync();

    /**
     * Asynchronously applies an action to each remaining element.
     *
     * <p>This is a convenience method that recursively calls {@code nextAsync()}
     * and applies the action to each element until iteration is complete.
     *
     * <p>Example:
     * <pre>{@code
     * iterator.forEachAsync(order -> System.out.println(order.id()))
     *     .thenRun(() -> System.out.println("All orders processed"));
     * }</pre>
     *
     * @param action the action to apply to each element
     * @return a CompletableFuture that completes when all elements are processed
     */
    default CompletableFuture<Void> forEachAsync(Consumer<T> action) {
        return nextAsync().thenCompose(opt -> {
            if (opt.isPresent()) {
                action.accept(opt.get());
                return forEachAsync(action);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Cancels the iteration, releasing any resources.
     *
     * <p>After cancellation:
     * <ul>
     *   <li>Future calls to {@code nextAsync()} complete with {@code Optional.empty()}</li>
     *   <li>No more network requests are made</li>
     *   <li>{@code isCancelled()} returns {@code true}</li>
     * </ul>
     *
     * <p>This is useful for early termination when you've found what you need
     * or want to stop processing based on some condition.
     */
    void cancel();

    /**
     * Returns {@code true} if the iteration has been cancelled.
     *
     * @return {@code true} if cancelled, {@code false} otherwise
     */
    boolean isCancelled();
}
