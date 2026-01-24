package com.example.streaming;

import com.example.streaming.model.Order;
import com.example.streaming.model.Page;
import com.example.streaming.pagination.CursorBasedPagination;
import com.example.streaming.spliterator.PaginatedSpliterator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PaginatedSpliterator - the core component for lazy page fetching.
 *
 * <h2>What PaginatedSpliterator Does</h2>
 * <p>Implements {@link java.util.Spliterator} to enable lazy iteration over
 * paginated data sources using Java Stream API.</p>
 *
 * <h2>Key Behaviors</h2>
 * <ul>
 *   <li><b>Lazy fetching</b>: Pages are fetched only when items are consumed</li>
 *   <li><b>Memory efficient</b>: Only one page in memory at a time</li>
 *   <li><b>Stream compatible</b>: Works with filter(), map(), limit(), etc.</li>
 *   <li><b>No parallel support</b>: trySplit() returns null (sequential only)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create spliterator with a page fetcher function
 * Spliterator<Order> spliterator = new PaginatedSpliterator<>(
 *     cursor -> fetchPageFromApi(cursor),  // Your page fetcher
 *     new CursorBasedPagination()          // Pagination strategy
 * );
 *
 * // Convert to Stream - pages fetched lazily as items are consumed
 * Stream<Order> stream = StreamSupport.stream(spliterator, false);
 *
 * // Use standard Stream operations
 * double total = stream
 *     .filter(o -> "completed".equals(o.status()))
 *     .mapToDouble(Order::amount)
 *     .sum();
 * }</pre>
 *
 * <h2>Why Not Parallel?</h2>
 * <p>Cursor-based pagination is inherently sequential - you need the cursor
 * from page N to fetch page N+1. Network I/O is typically the bottleneck,
 * not CPU processing.</p>
 */
class PaginatedSpliteratorTest {

    // =========================================================================
    // BASIC STREAMING
    // =========================================================================

    /**
     * Demonstrates streaming items across multiple pages.
     *
     * <p>The spliterator fetches page 1, streams its items, then fetches
     * page 2, streams its items, and so on until no more pages.</p>
     */
    @Test
    @DisplayName("Should stream all items from multiple pages")
    void shouldStreamAllItemsFromMultiplePages() {
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

        PaginatedSpliterator<Order> spliterator = new PaginatedSpliterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        // When: Consume all items via stream
        List<Order> orders = StreamSupport.stream(spliterator, false)
                .collect(Collectors.toList());

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
     * Creating a Stream does NOT fetch any pages. Only when you consume
     * items (via findFirst(), collect(), forEach(), etc.) are pages fetched.</p>
     *
     * <p><b>Why this matters:</b></p>
     * <ul>
     *   <li>Early termination (limit, findFirst) avoids unnecessary fetches</li>
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

        PaginatedSpliterator<Order> spliterator = new PaginatedSpliterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        // When: Create stream - NO pages fetched yet!
        var stream = StreamSupport.stream(spliterator, false);

        // Then: Zero fetches - stream creation is lazy
        assertThat(fetchCount.get()).isEqualTo(0);

        // When: Consume first item
        var firstItem = stream.findFirst();

        // Then: Only ONE page fetched (just enough to get first item)
        assertThat(fetchCount.get()).isEqualTo(1);
        assertThat(firstItem).isPresent();
        assertThat(firstItem.get().id()).isEqualTo("1");
    }

    /**
     * Demonstrates early termination with limit().
     *
     * <p>Even if the data source has infinite pages, limit(3) ensures
     * we only fetch enough pages to get 3 items.</p>
     */
    @Test
    @DisplayName("Should support limit() for early termination")
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

        PaginatedSpliterator<Order> spliterator = new PaginatedSpliterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        // When: Only take 3 items from infinite source
        List<Order> orders = StreamSupport.stream(spliterator, false)
                .limit(3)
                .collect(Collectors.toList());

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

        PaginatedSpliterator<Order> spliterator = new PaginatedSpliterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        List<Order> orders = StreamSupport.stream(spliterator, false)
                .collect(Collectors.toList());

        assertThat(orders).isEmpty();
    }

    @Test
    @DisplayName("Should handle single page without next cursor")
    void shouldHandleSinglePage() {
        // Given: Single page with no next cursor (hasMore = false)
        Function<String, Page<Order>> pageFetcher = cursor -> Page.last(List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending")
        ));

        PaginatedSpliterator<Order> spliterator = new PaginatedSpliterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        List<Order> orders = StreamSupport.stream(spliterator, false)
                .collect(Collectors.toList());

        assertThat(orders).hasSize(2);
    }

    // =========================================================================
    // ERROR HANDLING
    // =========================================================================

    /**
     * Demonstrates exception propagation from the page fetcher.
     *
     * <p>Network errors, parsing errors, etc. are wrapped and propagated
     * to the stream consumer.</p>
     */
    @Test
    @DisplayName("Should propagate exceptions from page fetcher")
    void shouldPropagateExceptions() {
        Function<String, Page<Order>> pageFetcher = cursor -> {
            throw new RuntimeException("Network error");
        };

        PaginatedSpliterator<Order> spliterator = new PaginatedSpliterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        assertThatThrownBy(() ->
                StreamSupport.stream(spliterator, false)
                        .collect(Collectors.toList())
        ).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to fetch page");
    }

    // =========================================================================
    // SPLITERATOR CHARACTERISTICS
    // =========================================================================

    /**
     * Verifies Spliterator characteristics.
     *
     * <ul>
     *   <li>ORDERED: Items come in page order</li>
     *   <li>NONNULL: No null items</li>
     *   <li>IMMUTABLE: Source data doesn't change</li>
     *   <li>NOT SIZED: Total count unknown upfront</li>
     * </ul>
     */
    @Test
    @DisplayName("Should report correct characteristics")
    void shouldReportCorrectCharacteristics() {
        PaginatedSpliterator<Order> spliterator = new PaginatedSpliterator<>(
                cursor -> Page.empty(),
                new CursorBasedPagination()
        );

        // These characteristics are set
        assertThat(spliterator.hasCharacteristics(java.util.Spliterator.ORDERED)).isTrue();
        assertThat(spliterator.hasCharacteristics(java.util.Spliterator.NONNULL)).isTrue();
        assertThat(spliterator.hasCharacteristics(java.util.Spliterator.IMMUTABLE)).isTrue();

        // NOT sized - we don't know total count
        assertThat(spliterator.hasCharacteristics(java.util.Spliterator.SIZED)).isFalse();
    }

    /**
     * Demonstrates that parallel streams are NOT supported.
     *
     * <p>trySplit() returns null because cursor-based pagination is
     * inherently sequential - you need cursor N to get page N+1.</p>
     */
    @Test
    @DisplayName("Should not support splitting (no parallel)")
    void shouldNotSupportSplitting() {
        PaginatedSpliterator<Order> spliterator = new PaginatedSpliterator<>(
                cursor -> Page.last(List.of(new Order("1", 100.0, "completed"))),
                new CursorBasedPagination()
        );

        // trySplit returns null - no parallel processing possible
        var split = spliterator.trySplit();

        assertThat(split).isNull();
    }

    // =========================================================================
    // STREAM PIPELINE COMPATIBILITY
    // =========================================================================

    /**
     * Demonstrates filter() works in the stream pipeline.
     */
    @Test
    @DisplayName("Should support filtering in stream pipeline")
    void shouldSupportFiltering() {
        Function<String, Page<Order>> pageFetcher = cursor -> Page.last(List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending"),
                new Order("3", 300.0, "completed")
        ));

        PaginatedSpliterator<Order> spliterator = new PaginatedSpliterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        List<Order> completedOrders = StreamSupport.stream(spliterator, false)
                .filter(order -> "completed".equals(order.status()))
                .collect(Collectors.toList());

        assertThat(completedOrders).hasSize(2);
        assertThat(completedOrders).allMatch(o -> "completed".equals(o.status()));
    }

    /**
     * Demonstrates map() works in the stream pipeline.
     */
    @Test
    @DisplayName("Should support mapping in stream pipeline")
    void shouldSupportMapping() {
        Function<String, Page<Order>> pageFetcher = cursor -> Page.last(List.of(
                new Order("1", 100.0, "completed"),
                new Order("2", 200.0, "pending")
        ));

        PaginatedSpliterator<Order> spliterator = new PaginatedSpliterator<>(
                pageFetcher,
                new CursorBasedPagination()
        );

        List<Double> amounts = StreamSupport.stream(spliterator, false)
                .map(Order::amount)
                .collect(Collectors.toList());

        assertThat(amounts).containsExactly(100.0, 200.0);
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
