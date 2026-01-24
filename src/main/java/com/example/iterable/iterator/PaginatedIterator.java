package com.example.iterable.iterator;

import com.example.streaming.model.Page;
import com.example.streaming.pagination.CursorBasedPagination;
import com.example.streaming.pagination.PaginationStrategy;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * An Iterator that lazily fetches pages from a paginated API and iterates through individual items.
 *
 * <p>This is the Iterator-based equivalent of
 * {@link com.example.streaming.spliterator.PaginatedSpliterator}. It ensures that:
 * <ul>
 *   <li>Pages are only fetched when needed (lazy evaluation)</li>
 *   <li>Only one page is held in memory at a time</li>
 *   <li>Items are returned one at a time via {@link #next()}</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * Iterator<Order> iterator = new PaginatedIterator<>(
 *     pageFetcher::fetchPage,
 *     new CursorBasedPagination()
 * );
 *
 * while (iterator.hasNext()) {
 *     Order order = iterator.next();
 *     // Process order
 * }
 * }</pre>
 *
 * <p><b>Thread Safety:</b> This class is NOT thread-safe. It must be used from a single thread.
 * Create a new iterator for each thread if concurrent iteration is needed.
 *
 * @param <T> the type of items in each page
 */
public class PaginatedIterator<T> implements Iterator<T> {

    private final Function<String, Page<T>> pageFetcher;
    private final PaginationStrategy paginationStrategy;

    private String currentCursor;
    private Iterator<T> currentPageIterator;
    private boolean finished = false;
    private boolean firstPageFetched = false;

    /**
     * Creates a new PaginatedIterator.
     *
     * @param pageFetcher function that fetches a page given a cursor
     * @param paginationStrategy strategy for handling pagination
     */
    public PaginatedIterator(
            Function<String, Page<T>> pageFetcher,
            PaginationStrategy paginationStrategy
    ) {
        this.pageFetcher = pageFetcher;
        this.paginationStrategy = paginationStrategy;
        this.currentCursor = paginationStrategy.getInitialCursor();
    }

    /**
     * Creates a PaginatedIterator with a simple page fetcher function.
     * Uses cursor-based pagination by default.
     *
     * @param pageFetcher function that fetches a page given a cursor
     */
    public PaginatedIterator(Function<String, Page<T>> pageFetcher) {
        this(pageFetcher, new CursorBasedPagination());
    }

    /**
     * Returns {@code true} if there are more items to iterate.
     *
     * <p>This method may trigger a page fetch if the current page is exhausted
     * and more pages are available. Pages are fetched lazily - only when
     * this method is called and there are no items remaining in the current page.
     *
     * @return {@code true} if there are more items
     */
    @Override
    public boolean hasNext() {
        if (finished) {
            return false;
        }

        // Ensure we have items to process
        while (!hasCurrentItem()) {
            if (!fetchNextPage()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns the next item in the iteration.
     *
     * @return the next item
     * @throws NoSuchElementException if no more items are available
     */
    @Override
    public T next() {
        if (!hasNext()) {
            throw new NoSuchElementException("No more items available");
        }
        return currentPageIterator.next();
    }

    /**
     * Checks if there's an item available in the current page iterator.
     */
    private boolean hasCurrentItem() {
        return currentPageIterator != null && currentPageIterator.hasNext();
    }

    /**
     * Fetches the next page from the API.
     * This is where the lazy loading happens - pages are only fetched when needed.
     *
     * @return true if a page was successfully fetched and has items
     */
    private boolean fetchNextPage() {
        if (finished) {
            return false;
        }

        // If we've already fetched pages and there's no next cursor, we're done
        if (firstPageFetched && currentCursor == null) {
            finished = true;
            return false;
        }

        try {
            Page<T> page = pageFetcher.apply(currentCursor);
            firstPageFetched = true;

            if (page == null || page.isEmpty()) {
                finished = true;
                return false;
            }

            // Update state for next iteration
            currentPageIterator = page.items().iterator();
            paginationStrategy.onPageFetched(page);

            if (paginationStrategy.hasMorePages(page)) {
                currentCursor = paginationStrategy.getNextCursor(page);
            } else {
                currentCursor = null;
            }

            return true;
        } catch (Exception e) {
            finished = true;
            throw new RuntimeException("Failed to fetch page with cursor: " + currentCursor, e);
        }
    }
}
