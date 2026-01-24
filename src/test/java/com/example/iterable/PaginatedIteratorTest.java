package com.example.iterable;

import com.example.iterable.iterator.PaginatedIterator;
import com.example.streaming.model.Order;
import com.example.streaming.model.Page;
import com.example.streaming.pagination.CursorBasedPagination;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PaginatedIterator - the core component for lazy page fetching
 * using the Iterable/Iterator pattern.
 *
 * <h2>What PaginatedIterator Does</h2>
 * <p>Implements {@link java.util.Iterator} to enable lazy iteration over
 * paginated data sources using standard Java for-each loops.</p>
 *
 * <h2>Key Behaviors</h2>
 * <ul>
 *   <li><b>Lazy fetching</b>: Pages are fetched only when items are consumed</li>
 *   <li><b>Memory efficient</b>: Only one page in memory at a time</li>
 *   <li><b>For-each compatible</b>: Works with standard Java for-each loops</li>
 *   <li><b>Early termination</b>: Use break to stop fetching more pages</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create iterator with a page fetcher function
 * Iterator<Order> iterator = new PaginatedIterator<>(
 *     cursor -> fetchPageFromApi(cursor),  // Your page fetcher
 *     new CursorBasedPagination()          // Pagination strategy
 * );
 *
 * // Iterate using while loop
 * while (iterator.hasNext()) {
 *     Order order = iterator.next();
 *     // Process order
 * }
 *
 * // Or with LazyPaginatedIterable, use for-each
 * for (Order order : new LazyPaginatedIterable<>(pageFetcher)) {
 *     process(order);
 *     if (shouldStop(order)) {
 *         break; // No more pages fetched
 *     }
 * }
 * }</pre>
 *
 * <h2>Comparison with PaginatedSpliterator</h2>
 * <table>
 *   <tr><th>Aspect</th><th>PaginatedSpliterator</th><th>PaginatedIterator</th></tr>
 *   <tr><td>Interface</td><td>Spliterator&lt;T&gt;</td><td>Iterator&lt;T&gt;</td></tr>
 *   <tr><td>Lazy loading</td><td>tryAdvance()</td><td>hasNext()/next()</td></tr>
 *   <tr><td>Early exit</td><td>limit(), takeWhile()</td><td>break statement</td></tr>
 *   <tr><td>Style</td><td>Functional</td><td>Imperative</td></tr>
 * </table>
 */
class PaginatedIteratorTest {

    // =========================================================================
    // BASIC ITERATION
    // =========================================================================

    /**
     * Demonstrates iterating items across multiple pages.
     *
     * <p>The iterator fetches page 1, returns its items, then fetches
     * page 2, returns its items, and so on until no more pages.</p>
     */
    @Test
    @DisplayName("Should iterate all items from multiple pages")
    void shouldIterateAllItemsFromMultiplePages() {
        // Given: 3 pages with 2 items each
        Function<String, Page<Order>> pageFetcher = createMockFetcher(
                List.of(
                        Page.of(List.of(
                                new Order("1", 100.0, "completed"),
                                new Order("2", 200.0, "pending")
                        ), "cursor-1"),   // First page, has next cursor
                        Page.of(List.of(
                                new Order("3", 300.0, "completed"),
                                new Order("4", 400.0, "pending")
                        ), "cursor-2"),   // Second page, has next cursor
                        Page.last(List.of(
                                new Order("5", 500.0, "completed"),
                                new Order("6", 600.0, "pending")
                        ))                // Last page, no next cursor
                )
        );

        PaginatedIterator<Order> iterator = new PaginatedIterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        // When: Consume all items via iteration
        List<Order> orders = new ArrayList<>();
        while (iterator.hasNext()) {
            orders.add(iterator.next());
        }

        // Then: All 6 items from 3 pages
        assertThat(orders).hasSize(6);
        assertThat(orders.get(0).id()).isEqualTo("1");
        assertThat(orders.get(5).id()).isEqualTo("6");
    }

    // =========================================================================
    // LAZY EVALUATION - THE KEY FEATURE
    // =========================================================================

    /**
     * Demonstrates that pages are fetched ONLY when items are consumed.
     *
     * <p>This is the most important test - it proves lazy evaluation.
     * Creating an Iterator does NOT fetch any pages. Only when you call
     * hasNext() are pages fetched as needed.</p>
     *
     * <p><b>Why this matters:</b></p>
     * <ul>
     *   <li>Early termination (break) avoids unnecessary fetches</li>
     *   <li>No wasted network calls for data you don't need</li>
     *   <li>Memory stays bounded even for infinite data sources</li>
     * </ul>
     */
    @Test
    @DisplayName("Should lazily fetch pages only when needed")
    void shouldLazilyFetchPages() {
        // Given: Track fetch count to prove laziness
        AtomicInteger fetchCount = new AtomicInteger(0);

        Function<String, Page<Order>> pageFetcher = cursor -> {
            fetchCount.incrementAndGet();  // Count each fetch
            if (cursor == null) {
                return Page.of(List.of(
                        new Order("1", 100.0, "completed"),
                        new Order("2", 200.0, "pending")
                ), "cursor-1");
            } else {
                return Page.last(List.of(
                        new Order("3", 300.0, "completed")
                ));
            }
        };

        PaginatedIterator<Order> iterator = new PaginatedIterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        // Then: Zero fetches - iterator creation is lazy
        assertThat(fetchCount.get()).isEqualTo(0);

        // When: Check first hasNext()
        boolean hasFirst = iterator.hasNext();

        // Then: First page fetched only when needed
        assertThat(hasFirst).isTrue();
        assertThat(fetchCount.get()).isEqualTo(1);

        // When: Get first item
        Order firstItem = iterator.next();

        // Then: Still only one fetch (first page has 2 items)
        assertThat(fetchCount.get()).isEqualTo(1);
        assertThat(firstItem.id()).isEqualTo("1");
    }

    /**
     * Demonstrates early termination with break statement.
     *
     * <p>Even if the data source has infinite pages, breaking out of
     * the loop ensures we only fetch the pages we actually consume.</p>
     */
    @Test
    @DisplayName("Should support early termination with break")
    void shouldSupportEarlyTermination() {
        // Given: Infinite pages (returns cursor forever)
        AtomicInteger fetchCount = new AtomicInteger(0);

        Function<String, Page<Order>> pageFetcher = cursor -> {
            int page = fetchCount.incrementAndGet();
            // Each page has 1 item and always has a next cursor (infinite)
            return Page.of(List.of(
                    new Order("order-" + page, page * 100.0, "completed")
            ), "cursor-" + page);
        };

        LazyPaginatedIterable<Order> iterable = new LazyPaginatedIterable<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        // When: Only take 3 items from infinite source using break
        List<Order> orders = new ArrayList<>();
        int count = 0;
        for (Order order : iterable) {
            orders.add(order);
            count++;
            if (count >= 3) {
                break;  // Early termination
            }
        }

        // Then: Only 3 pages fetched (not infinite!)
        assertThat(orders).hasSize(3);
        assertThat(fetchCount.get()).isEqualTo(3);
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Test
    @DisplayName("Should handle empty first page")
    void shouldHandleEmptyFirstPage() {
        Function<String, Page<Order>> pageFetcher = cursor -> Page.empty();

        PaginatedIterator<Order> iterator = new PaginatedIterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        assertThat(iterator.hasNext()).isFalse();
    }

    @Test
    @DisplayName("Should handle single page without next cursor")
    void shouldHandleSinglePage() {
        // Given: Single page with no next cursor (hasMore = false)
        Function<String, Page<Order>> pageFetcher = cursor -> Page.last(List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending")
        ));

        PaginatedIterator<Order> iterator = new PaginatedIterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        List<Order> orders = new ArrayList<>();
        while (iterator.hasNext()) {
            orders.add(iterator.next());
        }

        assertThat(orders).hasSize(2);
    }

    @Test
    @DisplayName("Should throw NoSuchElementException when exhausted")
    void shouldThrowWhenExhausted() {
        Function<String, Page<Order>> pageFetcher = cursor -> Page.last(List.of(
                new Order("1", 100.0, "completed")
        ));

        PaginatedIterator<Order> iterator = new PaginatedIterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        // Consume the only item
        iterator.hasNext();
        iterator.next();

        // Verify exhausted
        assertThat(iterator.hasNext()).isFalse();

        // Should throw when trying to get more
        assertThatThrownBy(() -> iterator.next())
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("No more items");
    }

    // =========================================================================
    // ERROR HANDLING
    // =========================================================================

    /**
     * Demonstrates exception propagation from the page fetcher.
     *
     * <p>Network errors, parsing errors, etc. are wrapped and propagated
     * to the iterator consumer.</p>
     */
    @Test
    @DisplayName("Should propagate exceptions from page fetcher")
    void shouldPropagateExceptions() {
        Function<String, Page<Order>> pageFetcher = cursor -> {
            throw new RuntimeException("Network error");
        };

        PaginatedIterator<Order> iterator = new PaginatedIterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        assertThatThrownBy(() -> iterator.hasNext())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to fetch page");
    }

    /**
     * Demonstrates that after an error, the iterator is finished.
     */
    @Test
    @DisplayName("Should be finished after error")
    void shouldBeFinishedAfterError() {
        AtomicInteger fetchCount = new AtomicInteger(0);

        Function<String, Page<Order>> pageFetcher = cursor -> {
            if (fetchCount.incrementAndGet() == 1) {
                throw new RuntimeException("First fetch fails");
            }
            return Page.last(List.of(new Order("1", 100.0, "completed")));
        };

        PaginatedIterator<Order> iterator = new PaginatedIterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        // First hasNext() throws
        try {
            iterator.hasNext();
        } catch (RuntimeException e) {
            // Expected
        }

        // Subsequent hasNext() returns false (iterator is finished)
        assertThat(iterator.hasNext()).isFalse();
    }

    // =========================================================================
    // ITERABLE REUSABILITY
    // =========================================================================

    /**
     * Demonstrates that LazyPaginatedIterable can be iterated multiple times.
     * Each iteration starts fresh from page 1.
     */
    @Test
    @DisplayName("LazyPaginatedIterable should be reusable")
    void iterableShouldBeReusable() {
        AtomicInteger fetchCount = new AtomicInteger(0);

        Function<String, Page<Order>> pageFetcher = cursor -> {
            fetchCount.incrementAndGet();
            return Page.last(List.of(
                    new Order("1", 100.0, "completed"),
                    new Order("2", 200.0, "pending")
            ));
        };

        LazyPaginatedIterable<Order> iterable = new LazyPaginatedIterable<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        // First iteration
        List<Order> firstPass = new ArrayList<>();
        for (Order order : iterable) {
            firstPass.add(order);
        }

        // Second iteration (fresh start)
        List<Order> secondPass = new ArrayList<>();
        for (Order order : iterable) {
            secondPass.add(order);
        }

        // Both iterations got all items
        assertThat(firstPass).hasSize(2);
        assertThat(secondPass).hasSize(2);

        // Two iterations means two page fetches
        assertThat(fetchCount.get()).isEqualTo(2);
    }

    // =========================================================================
    // FILTERING IN LOOP
    // =========================================================================

    /**
     * Demonstrates filtering with if statement in loop.
     * Compare with stream.filter() in the Stream-based approach.
     */
    @Test
    @DisplayName("Should support filtering with if statement")
    void shouldSupportFilteringWithIf() {
        Function<String, Page<Order>> pageFetcher = cursor -> Page.last(List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending"),
                new Order("3", 300.0, "completed")
        ));

        LazyPaginatedIterable<Order> iterable = new LazyPaginatedIterable<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        List<Order> completedOrders = new ArrayList<>();
        for (Order order : iterable) {
            if ("completed".equals(order.status())) {
                completedOrders.add(order);
            }
        }

        assertThat(completedOrders).hasSize(2);
        assertThat(completedOrders).allMatch(o -> "completed".equals(o.status()));
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    /**
     * Creates a mock page fetcher from a predefined list of pages.
     * Useful for testing without a real HTTP server.
     */
    private Function<String, Page<Order>> createMockFetcher(List<Page<Order>> pages) {
        AtomicInteger pageIndex = new AtomicInteger(0);
        return cursor -> {
            int index = pageIndex.getAndIncrement();
            if (index < pages.size()) {
                return pages.get(index);
            }
            return Page.empty();
        };
    }
}
