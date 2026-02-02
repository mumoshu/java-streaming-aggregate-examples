package com.example.async;

import com.example.async.iterator.AsyncIterator;
import com.example.async.iterator.AsyncPaginatedIterator;
import com.example.streaming.model.Order;
import com.example.streaming.model.Page;
import com.example.streaming.pagination.CursorBasedPagination;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for AsyncPaginatedIterator - the core component for async lazy page fetching.
 *
 * <h2>What AsyncPaginatedIterator Does</h2>
 * <p>Implements {@link AsyncIterator} to enable non-blocking, lazy iteration over
 * paginated data sources using CompletableFuture.</p>
 *
 * <h2>Key Behaviors</h2>
 * <ul>
 *   <li><b>Async fetching</b>: Pages are fetched asynchronously without blocking</li>
 *   <li><b>Lazy evaluation</b>: No pages fetched until nextAsync() is called</li>
 *   <li><b>Memory efficient</b>: Only one page in memory at a time</li>
 *   <li><b>Cancellation</b>: Use cancel() to stop fetching more pages</li>
 * </ul>
 *
 * <h2>Comparison with PaginatedIterator</h2>
 * <table>
 *   <tr><th>Aspect</th><th>PaginatedIterator</th><th>AsyncPaginatedIterator</th></tr>
 *   <tr><td>Blocking</td><td>Yes</td><td>No</td></tr>
 *   <tr><td>Get next</td><td>next()</td><td>nextAsync().join()</td></tr>
 *   <tr><td>Early exit</td><td>break</td><td>cancel()</td></tr>
 *   <tr><td>Thread usage</td><td>Blocks thread</td><td>Frees thread on I/O</td></tr>
 * </table>
 */
class AsyncPaginatedIteratorTest {

    // =========================================================================
    // BASIC ASYNC ITERATION
    // =========================================================================

    @Test
    @DisplayName("Should iterate all items from multiple pages asynchronously")
    void shouldIterateAllItemsAsynchronously() {
        // Given: 3 pages with 2 items each
        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = createMockAsyncFetcher(
                List.of(
                        Page.of(List.of(
                                new Order("1", 100.0, "completed"),
                                new Order("2", 200.0, "pending")
                        ), "cursor-1"),
                        Page.of(List.of(
                                new Order("3", 300.0, "completed"),
                                new Order("4", 400.0, "pending")
                        ), "cursor-2"),
                        Page.last(List.of(
                                new Order("5", 500.0, "completed"),
                                new Order("6", 600.0, "pending")
                        ))
                )
        );

        AsyncPaginatedIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        // When: Consume all items via async iteration
        List<Order> orders = new ArrayList<>();
        iterator.forEachAsync(orders::add).join();

        // Then: All 6 items from 3 pages
        assertThat(orders).hasSize(6);
        assertThat(orders.get(0).id()).isEqualTo("1");
        assertThat(orders.get(5).id()).isEqualTo("6");
    }

    // =========================================================================
    // LAZY EVALUATION - THE KEY FEATURE
    // =========================================================================

    @Test
    @DisplayName("Should lazily fetch pages only when nextAsync() is called")
    void shouldLazilyFetchPages() {
        // Given: Track fetch count to prove laziness
        AtomicInteger fetchCount = new AtomicInteger(0);

        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = cursor -> {
            fetchCount.incrementAndGet();
            Page<Order> page;
            if (cursor == null) {
                page = Page.of(List.of(
                        new Order("1", 100.0, "completed"),
                        new Order("2", 200.0, "pending")
                ), "cursor-1");
            } else {
                page = Page.last(List.of(
                        new Order("3", 300.0, "completed")
                ));
            }
            return CompletableFuture.completedFuture(page);
        };

        AsyncPaginatedIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        // Then: Zero fetches - iterator creation is lazy
        assertThat(fetchCount.get()).isEqualTo(0);

        // When: First nextAsync()
        Optional<Order> first = iterator.nextAsync().join();

        // Then: First page fetched only when needed
        assertThat(first).isPresent();
        assertThat(fetchCount.get()).isEqualTo(1);
        assertThat(first.get().id()).isEqualTo("1");

        // When: Second nextAsync() - still on first page
        Optional<Order> second = iterator.nextAsync().join();

        // Then: Still only one fetch (first page has 2 items)
        assertThat(fetchCount.get()).isEqualTo(1);
        assertThat(second.get().id()).isEqualTo("2");
    }

    // =========================================================================
    // CANCELLATION - ASYNC EARLY TERMINATION
    // =========================================================================

    @Test
    @DisplayName("Should support cancellation for early termination")
    void shouldSupportCancellation() {
        // Given: Infinite pages (returns cursor forever)
        AtomicInteger fetchCount = new AtomicInteger(0);

        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = cursor -> {
            int page = fetchCount.incrementAndGet();
            return CompletableFuture.completedFuture(
                    Page.of(List.of(
                            new Order("order-" + page, page * 100.0, "completed")
                    ), "cursor-" + page)
            );
        };

        AsyncIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        // When: Take 3 items then cancel
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Optional<Order> opt = iterator.nextAsync().join();
            opt.ifPresent(orders::add);
        }
        iterator.cancel();

        // Then: Only 3 pages fetched
        assertThat(orders).hasSize(3);
        assertThat(fetchCount.get()).isEqualTo(3);
        assertThat(iterator.isCancelled()).isTrue();

        // And: Future calls return empty
        Optional<Order> afterCancel = iterator.nextAsync().join();
        assertThat(afterCancel).isEmpty();
    }

    @Test
    @DisplayName("Should not fetch more pages after cancellation")
    void shouldNotFetchAfterCancellation() {
        AtomicInteger fetchCount = new AtomicInteger(0);

        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = cursor -> {
            fetchCount.incrementAndGet();
            return CompletableFuture.completedFuture(
                    Page.of(List.of(new Order("1", 100.0, "completed")), "next")
            );
        };

        AsyncIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        // Fetch one item
        iterator.nextAsync().join();
        assertThat(fetchCount.get()).isEqualTo(1);

        // Cancel
        iterator.cancel();

        // Try to fetch more
        iterator.nextAsync().join();
        iterator.nextAsync().join();

        // No additional fetches
        assertThat(fetchCount.get()).isEqualTo(1);
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Test
    @DisplayName("Should handle empty first page")
    void shouldHandleEmptyFirstPage() {
        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = cursor ->
                CompletableFuture.completedFuture(Page.empty());

        AsyncPaginatedIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        Optional<Order> result = iterator.nextAsync().join();
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should handle single page without next cursor")
    void shouldHandleSinglePage() {
        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = cursor ->
                CompletableFuture.completedFuture(Page.last(List.of(
                        new Order("1", 100.0, "completed"),
                        new Order("2", 200.0, "pending")
                )));

        AsyncPaginatedIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        List<Order> orders = new ArrayList<>();
        iterator.forEachAsync(orders::add).join();

        assertThat(orders).hasSize(2);
    }

    @Test
    @DisplayName("Should return empty after all items consumed")
    void shouldReturnEmptyWhenExhausted() {
        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = cursor ->
                CompletableFuture.completedFuture(Page.last(List.of(
                        new Order("1", 100.0, "completed")
                )));

        AsyncPaginatedIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        // Consume the only item
        Optional<Order> first = iterator.nextAsync().join();
        assertThat(first).isPresent();

        // Next call returns empty
        Optional<Order> second = iterator.nextAsync().join();
        assertThat(second).isEmpty();

        // Subsequent calls also return empty
        Optional<Order> third = iterator.nextAsync().join();
        assertThat(third).isEmpty();
    }

    // =========================================================================
    // ERROR HANDLING
    // =========================================================================

    @Test
    @DisplayName("Should propagate exceptions from async page fetcher")
    void shouldPropagateExceptions() {
        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = cursor ->
                CompletableFuture.failedFuture(new RuntimeException("Network error"));

        AsyncPaginatedIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        assertThatThrownBy(() -> iterator.nextAsync().join())
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Network error");
    }

    @Test
    @DisplayName("Should be finished after error")
    void shouldBeFinishedAfterError() {
        AtomicInteger fetchCount = new AtomicInteger(0);

        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = cursor -> {
            if (fetchCount.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(new RuntimeException("First fetch fails"));
            }
            return CompletableFuture.completedFuture(
                    Page.last(List.of(new Order("1", 100.0, "completed")))
            );
        };

        AsyncPaginatedIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        // First nextAsync() fails
        try {
            iterator.nextAsync().join();
        } catch (Exception e) {
            // Expected
        }

        // Iterator is now finished
        assertThat(iterator.isFinished()).isTrue();

        // Subsequent calls return empty
        Optional<Order> result = iterator.nextAsync().join();
        assertThat(result).isEmpty();
    }

    // =========================================================================
    // FOREACH ASYNC
    // =========================================================================

    @Test
    @DisplayName("forEachAsync should process all items")
    void forEachAsyncShouldProcessAllItems() {
        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = createMockAsyncFetcher(
                List.of(
                        Page.of(List.of(
                                new Order("1", 100.0, "completed"),
                                new Order("2", 200.0, "pending")
                        ), "cursor-1"),
                        Page.last(List.of(
                                new Order("3", 300.0, "completed")
                        ))
                )
        );

        AsyncIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        List<String> ids = new ArrayList<>();
        iterator.forEachAsync(order -> ids.add(order.id())).join();

        assertThat(ids).containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("forEachAsync should complete immediately for empty iterator")
    void forEachAsyncShouldCompleteForEmpty() {
        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = cursor ->
                CompletableFuture.completedFuture(Page.empty());

        AsyncIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        List<Order> orders = new ArrayList<>();
        iterator.forEachAsync(orders::add).join();

        assertThat(orders).isEmpty();
    }

    // =========================================================================
    // CROSS-PAGE ITERATION
    // =========================================================================

    @Test
    @DisplayName("Should correctly transition between pages")
    void shouldCorrectlyTransitionBetweenPages() {
        AtomicInteger fetchCount = new AtomicInteger(0);

        Function<String, CompletableFuture<Page<Order>>> asyncFetcher = cursor -> {
            int page = fetchCount.incrementAndGet();
            if (page == 1) {
                return CompletableFuture.completedFuture(Page.of(
                        List.of(new Order("1", 100.0, "completed")),
                        "cursor-1"
                ));
            } else if (page == 2) {
                return CompletableFuture.completedFuture(Page.of(
                        List.of(new Order("2", 200.0, "completed")),
                        "cursor-2"
                ));
            } else {
                return CompletableFuture.completedFuture(Page.last(
                        List.of(new Order("3", 300.0, "completed"))
                ));
            }
        };

        AsyncIterator<Order> iterator = new AsyncPaginatedIterator<>(
                asyncFetcher,
                new CursorBasedPagination()
        );

        // Consume all items one by one
        Optional<Order> first = iterator.nextAsync().join();
        assertThat(first.get().id()).isEqualTo("1");
        assertThat(fetchCount.get()).isEqualTo(1);

        Optional<Order> second = iterator.nextAsync().join();
        assertThat(second.get().id()).isEqualTo("2");
        assertThat(fetchCount.get()).isEqualTo(2);

        Optional<Order> third = iterator.nextAsync().join();
        assertThat(third.get().id()).isEqualTo("3");
        assertThat(fetchCount.get()).isEqualTo(3);

        Optional<Order> fourth = iterator.nextAsync().join();
        assertThat(fourth).isEmpty();
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private Function<String, CompletableFuture<Page<Order>>> createMockAsyncFetcher(
            List<Page<Order>> pages
    ) {
        AtomicInteger pageIndex = new AtomicInteger(0);
        return cursor -> {
            int index = pageIndex.getAndIncrement();
            if (index < pages.size()) {
                return CompletableFuture.completedFuture(pages.get(index));
            }
            return CompletableFuture.completedFuture(Page.empty());
        };
    }
}
