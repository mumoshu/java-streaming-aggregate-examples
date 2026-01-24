package com.example.iterable;

import com.example.iterable.iterator.PaginatedIterator;
import com.example.streaming.model.Page;
import com.example.streaming.pagination.CursorBasedPagination;
import com.example.streaming.pagination.PaginationStrategy;

import java.util.Iterator;
import java.util.function.Function;

/**
 * A lazy Iterable that fetches pages on demand.
 *
 * <p>This class wraps a page fetcher function and provides an {@link Iterable} interface
 * that creates fresh {@link PaginatedIterator} instances on each call to {@link #iterator()}.
 *
 * <p><b>Reusability:</b> This Iterable can be iterated multiple times. Each call to
 * {@link #iterator()} returns a fresh iterator that starts from the first page.
 *
 * <p><b>Side effects:</b> Be aware that iterating multiple times will make multiple
 * HTTP requests to the underlying API.
 *
 * <p>Example usage:
 * <pre>{@code
 * LazyPaginatedIterable<Order> orders = new LazyPaginatedIterable<>(
 *     pageFetcher::fetchPage,
 *     new CursorBasedPagination()
 * );
 *
 * // First iteration
 * for (Order order : orders) {
 *     process(order);
 * }
 *
 * // Second iteration (starts fresh from page 1)
 * for (Order order : orders) {
 *     processAgain(order);
 * }
 * }</pre>
 *
 * <p><b>Thread Safety:</b> This class is thread-safe for creating iterators.
 * Each thread gets its own iterator instance. However, individual iterators
 * are NOT thread-safe and should not be shared between threads.
 *
 * @param <T> the type of items in each page
 */
public class LazyPaginatedIterable<T> implements Iterable<T> {

    private final Function<String, Page<T>> pageFetcher;
    private final PaginationStrategy paginationStrategy;

    /**
     * Creates a new LazyPaginatedIterable.
     *
     * @param pageFetcher function that fetches a page given a cursor
     * @param paginationStrategy strategy for handling pagination
     */
    public LazyPaginatedIterable(
            Function<String, Page<T>> pageFetcher,
            PaginationStrategy paginationStrategy
    ) {
        this.pageFetcher = pageFetcher;
        this.paginationStrategy = paginationStrategy;
    }

    /**
     * Creates a LazyPaginatedIterable with default cursor-based pagination.
     *
     * @param pageFetcher function that fetches a page given a cursor
     */
    public LazyPaginatedIterable(Function<String, Page<T>> pageFetcher) {
        this(pageFetcher, new CursorBasedPagination());
    }

    /**
     * Returns a fresh iterator that starts from the first page.
     *
     * <p>Each call to this method creates a new {@link PaginatedIterator} instance,
     * allowing the same Iterable to be iterated multiple times. Note that each
     * iteration will make fresh HTTP requests to fetch pages.
     *
     * @return a new iterator starting from the first page
     */
    @Override
    public Iterator<T> iterator() {
        return new PaginatedIterator<>(pageFetcher, paginationStrategy);
    }
}
