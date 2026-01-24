package com.example.streaming.spliterator;

import com.example.streaming.model.Page;
import com.example.streaming.pagination.PaginationStrategy;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A Spliterator that lazily fetches pages from a paginated API and streams individual items.
 *
 * <p>This is the core component for streaming aggregation. It ensures that:
 * <ul>
 *   <li>Pages are only fetched when needed (lazy evaluation)</li>
 *   <li>Only one page is held in memory at a time</li>
 *   <li>Items are processed one at a time via {@link #tryAdvance}</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * Spliterator<Order> spliterator = new PaginatedSpliterator<>(
 *     pageFetcher::fetchPage,
 *     new CursorBasedPagination()
 * );
 *
 * Stream<Order> orderStream = StreamSupport.stream(spliterator, false);
 * }</pre>
 *
 * @param <T> the type of items in each page
 */
public class PaginatedSpliterator<T> implements Spliterator<T> {

    private final Function<String, Page<T>> pageFetcher;
    private final PaginationStrategy paginationStrategy;

    private String currentCursor;
    private Iterator<T> currentPageIterator;
    private boolean finished = false;
    private boolean firstPageFetched = false;

    /**
     * Creates a new PaginatedSpliterator.
     *
     * @param pageFetcher function that fetches a page given a cursor
     * @param paginationStrategy strategy for handling pagination
     */
    public PaginatedSpliterator(
            Function<String, Page<T>> pageFetcher,
            PaginationStrategy paginationStrategy
    ) {
        this.pageFetcher = pageFetcher;
        this.paginationStrategy = paginationStrategy;
        this.currentCursor = paginationStrategy.getInitialCursor();
    }

    /**
     * Creates a PaginatedSpliterator with a simple page fetcher function.
     * Uses cursor-based pagination by default.
     *
     * @param pageFetcher function that fetches a page given a cursor
     */
    public PaginatedSpliterator(Function<String, Page<T>> pageFetcher) {
        this(pageFetcher, new com.example.streaming.pagination.CursorBasedPagination());
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        if (finished) {
            return false;
        }

        // Ensure we have items to process
        while (!hasCurrentItem()) {
            if (!fetchNextPage()) {
                return false;
            }
        }

        // Process the next item
        T item = currentPageIterator.next();
        action.accept(item);
        return true;
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

    /**
     * Returns null because parallel streams are not supported for paginated APIs.
     *
     * <p><b>Why parallel is not supported:</b></p>
     * <ul>
     *   <li><b>Pages must be fetched sequentially</b> - cursor-based pagination
     *       requires knowing the previous cursor to fetch the next page</li>
     *   <li><b>Network I/O is the bottleneck</b> - parallelizing CPU work doesn't
     *       help when waiting for HTTP responses</li>
     *   <li><b>Order matters</b> - many aggregations depend on processing order</li>
     * </ul>
     *
     * <p>Note: While this Spliterator cannot be parallelized, downstream collectors
     * like {@link com.example.streaming.aggregation.OrderStatsCollector} still
     * support parallel streams when used with in-memory data sources.</p>
     *
     * @return null (splitting not supported)
     */
    @Override
    public Spliterator<T> trySplit() {
        return null;
    }

    /**
     * Returns an estimate of the number of remaining elements.
     * Since we don't know the total count, we return MAX_VALUE.
     */
    @Override
    public long estimateSize() {
        return Long.MAX_VALUE;
    }

    /**
     * Returns the characteristics of this Spliterator.
     * <ul>
     *   <li>ORDERED: Elements are processed in order</li>
     *   <li>NONNULL: Elements are never null</li>
     *   <li>IMMUTABLE: The underlying data source is not modified</li>
     * </ul>
     *
     * Note: NOT SIZED because we don't know the total count upfront.
     */
    @Override
    public int characteristics() {
        return ORDERED | NONNULL | IMMUTABLE;
    }
}
