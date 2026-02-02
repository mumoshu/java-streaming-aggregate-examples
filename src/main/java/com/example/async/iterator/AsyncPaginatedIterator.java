package com.example.async.iterator;

import com.example.streaming.model.Page;
import com.example.streaming.pagination.CursorBasedPagination;
import com.example.streaming.pagination.PaginationStrategy;

import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * An AsyncIterator that lazily fetches pages from a paginated API using async HTTP calls.
 *
 * <p>This is the async equivalent of {@link com.example.iterable.iterator.PaginatedIterator}.
 * Key differences:
 * <ul>
 *   <li>Page fetching is non-blocking (returns CompletableFuture)</li>
 *   <li>{@code nextAsync()} returns immediately, completing later when data arrives</li>
 *   <li>Supports cancellation to stop async operations</li>
 *   <li>Thread-safe state management via atomic references</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * AsyncPaginatedIterator<Order> iterator = new AsyncPaginatedIterator<>(
 *     cursor -> httpClient.fetchPageAsync(cursor),
 *     new CursorBasedPagination()
 * );
 *
 * // Non-blocking iteration
 * iterator.forEachAsync(order -> process(order))
 *     .thenRun(() -> System.out.println("Done"));
 *
 * // Or with manual control
 * iterator.nextAsync()
 *     .thenAccept(opt -> opt.ifPresent(this::processFirst));
 * }</pre>
 *
 * <p><b>Thread Safety:</b> This class uses atomic operations for state management,
 * making it safe for concurrent {@code nextAsync()} calls from different async
 * continuations. However, the typical usage is sequential async composition.
 *
 * @param <T> the type of items in each page
 */
public class AsyncPaginatedIterator<T> implements AsyncIterator<T> {

    private final Function<String, CompletableFuture<Page<T>>> asyncPageFetcher;
    private final PaginationStrategy paginationStrategy;

    private final AtomicReference<String> currentCursor;
    private final AtomicReference<Iterator<T>> currentPageIterator;
    private final AtomicBoolean finished;
    private final AtomicBoolean cancelled;
    private final AtomicBoolean firstPageFetched;

    /**
     * Creates a new AsyncPaginatedIterator.
     *
     * @param asyncPageFetcher function that asynchronously fetches a page given a cursor
     * @param paginationStrategy strategy for handling pagination
     */
    public AsyncPaginatedIterator(
            Function<String, CompletableFuture<Page<T>>> asyncPageFetcher,
            PaginationStrategy paginationStrategy
    ) {
        this.asyncPageFetcher = asyncPageFetcher;
        this.paginationStrategy = paginationStrategy;
        this.currentCursor = new AtomicReference<>(paginationStrategy.getInitialCursor());
        this.currentPageIterator = new AtomicReference<>(null);
        this.finished = new AtomicBoolean(false);
        this.cancelled = new AtomicBoolean(false);
        this.firstPageFetched = new AtomicBoolean(false);
    }

    /**
     * Creates an AsyncPaginatedIterator with cursor-based pagination.
     *
     * @param asyncPageFetcher function that asynchronously fetches a page given a cursor
     */
    public AsyncPaginatedIterator(Function<String, CompletableFuture<Page<T>>> asyncPageFetcher) {
        this(asyncPageFetcher, new CursorBasedPagination());
    }

    @Override
    public CompletableFuture<Optional<T>> nextAsync() {
        if (cancelled.get() || finished.get()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // Try to get from current page first
        Iterator<T> pageIter = currentPageIterator.get();
        if (pageIter != null && pageIter.hasNext()) {
            return CompletableFuture.completedFuture(Optional.of(pageIter.next()));
        }

        // Need to fetch next page asynchronously
        return fetchNextPageAsync().thenApply(fetched -> {
            if (!fetched) {
                return Optional.empty();
            }
            Iterator<T> newPageIter = currentPageIterator.get();
            if (newPageIter != null && newPageIter.hasNext()) {
                return Optional.of(newPageIter.next());
            }
            return Optional.empty();
        });
    }

    /**
     * Asynchronously fetches the next page from the API.
     *
     * @return CompletableFuture that completes with true if a page was fetched, false if done
     */
    private CompletableFuture<Boolean> fetchNextPageAsync() {
        if (cancelled.get() || finished.get()) {
            return CompletableFuture.completedFuture(false);
        }

        String cursor = currentCursor.get();

        // If we've fetched before and there's no next cursor, we're done
        if (firstPageFetched.get() && cursor == null) {
            finished.set(true);
            return CompletableFuture.completedFuture(false);
        }

        return asyncPageFetcher.apply(cursor)
                .thenApply(page -> {
                    if (cancelled.get()) {
                        return false;
                    }

                    firstPageFetched.set(true);

                    if (page == null || page.isEmpty()) {
                        finished.set(true);
                        return false;
                    }

                    currentPageIterator.set(page.items().iterator());

                    if (paginationStrategy.hasMorePages(page)) {
                        currentCursor.set(paginationStrategy.getNextCursor(page));
                    } else {
                        currentCursor.set(null);
                    }

                    return true;
                })
                .exceptionally(ex -> {
                    finished.set(true);
                    throw new RuntimeException(
                            "Failed to fetch page with cursor: " + cursor, ex);
                });
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        finished.set(true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Returns true if iteration has finished (either completed or cancelled).
     *
     * @return true if no more items will be returned
     */
    public boolean isFinished() {
        return finished.get();
    }
}
