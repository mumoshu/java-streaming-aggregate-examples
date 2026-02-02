package com.example.reactor;

import com.example.streaming.client.HttpPageFetcher;
import com.example.streaming.model.Order;
import com.example.streaming.model.OrderStats;
import com.example.streaming.model.Page;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

import java.util.function.Function;

/**
 * Main facade for reactive aggregation using Project Reactor.
 *
 * <p>This is the Reactor-based equivalent of the other aggregators. It demonstrates:
 * <ul>
 *   <li>Using {@link Flux#generate} for lazy page fetching</li>
 *   <li>Using {@link Flux#reduce} for aggregation (no recursion!)</li>
 *   <li>Backpressure support via reactive streams</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * ReactorAggregator aggregator = new ReactorAggregator("https://api.example.com/orders");
 *
 * // Aggregate all orders reactively
 * aggregator.aggregateOrders()
 *     .subscribe(stats -> System.out.println("Sum: " + stats.sum()));
 *
 * // Stream orders with backpressure
 * aggregator.streamOrders()
 *     .filter(order -> "completed".equals(order.status()))
 *     .take(10)
 *     .subscribe(System.out::println);
 *
 * // Blocking for testing
 * OrderStats stats = aggregator.aggregateOrders().block();
 * }</pre>
 *
 * <h2>Comparison with Other Variants</h2>
 * <table>
 *   <tr><th>Aspect</th><th>Async (CompletableFuture)</th><th>Reactor</th></tr>
 *   <tr><td>Aggregation</td><td>Recursive thenCompose()</td><td>reduce() operator</td></tr>
 *   <tr><td>Backpressure</td><td>No</td><td>Yes</td></tr>
 *   <tr><td>Operators</td><td>Limited</td><td>Rich (filter, map, take, etc.)</td></tr>
 * </table>
 */
public class ReactorAggregator {

    private final Function<String, Page<Order>> pageFetcher;

    /**
     * Creates an aggregator that fetches from an HTTP endpoint.
     *
     * @param baseUrl the URL of the orders API endpoint
     */
    public ReactorAggregator(String baseUrl) {
        HttpPageFetcher<Order> httpFetcher = new HttpPageFetcher<>(baseUrl, Order.class);
        this.pageFetcher = httpFetcher::fetchPage;
    }

    /**
     * Creates an aggregator with a custom page fetcher.
     * Useful for testing or when using a different data source.
     *
     * @param pageFetcher function that fetches a page given a cursor
     */
    public ReactorAggregator(Function<String, Page<Order>> pageFetcher) {
        this.pageFetcher = pageFetcher;
    }

    /**
     * Returns a Flux of all orders from the paginated API.
     *
     * <p>Uses {@link Flux#generate} to lazily fetch pages. Pages are only
     * fetched as downstream subscribers request more items. This provides
     * natural backpressure support.
     *
     * <p>Example:
     * <pre>{@code
     * aggregator.streamOrders()
     *     .filter(order -> order.amount() > 100)
     *     .take(5)  // Only fetches pages until 5 matching items found
     *     .subscribe(System.out::println);
     * }</pre>
     *
     * @return Flux of orders
     */
    public Flux<Order> streamOrders() {
        return Flux.generate(
                // Initial state: null cursor (first page)
                () -> new PaginationState(null, null, false),
                // Generator: fetch page and emit items
                (state, sink) -> emitNextItem(state, sink)
        );
    }

    /**
     * State holder for pagination during Flux.generate().
     */
    private static class PaginationState {
        String cursor;
        java.util.Iterator<Order> currentPageIterator;
        boolean finished;

        PaginationState(String cursor, java.util.Iterator<Order> iterator, boolean finished) {
            this.cursor = cursor;
            this.currentPageIterator = iterator;
            this.finished = finished;
        }
    }

    /**
     * Emits the next item from the current page, fetching a new page if needed.
     */
    private PaginationState emitNextItem(PaginationState state, SynchronousSink<Order> sink) {
        if (state.finished) {
            sink.complete();
            return state;
        }

        // If we have items in current page, emit next one
        if (state.currentPageIterator != null && state.currentPageIterator.hasNext()) {
            sink.next(state.currentPageIterator.next());
            // Check if this was the last item and no more pages
            if (!state.currentPageIterator.hasNext() && state.cursor == null) {
                state.finished = true;
            }
            return state;
        }

        // Current page exhausted, no more pages - we're done
        if (state.currentPageIterator != null && !state.currentPageIterator.hasNext() && state.cursor == null) {
            sink.complete();
            state.finished = true;
            return state;
        }

        // Need to fetch next page
        try {
            Page<Order> page = pageFetcher.apply(state.cursor);

            if (page == null || page.isEmpty()) {
                sink.complete();
                return new PaginationState(null, null, true);
            }

            state.currentPageIterator = page.items().iterator();
            state.cursor = page.hasNextPage() ? page.nextCursor() : null;

            // Emit first item from new page
            if (state.currentPageIterator.hasNext()) {
                sink.next(state.currentPageIterator.next());
                // Check if this was the last item and no more pages
                if (!state.currentPageIterator.hasNext() && state.cursor == null) {
                    state.finished = true;
                }
            } else {
                // Empty page with no more pages - complete
                sink.complete();
                state.finished = true;
            }

            return state;
        } catch (Exception e) {
            sink.error(e);
            return new PaginationState(null, null, true);
        }
    }

    /**
     * Aggregates all orders into statistics using {@code reduce()}.
     *
     * <p>This demonstrates the key advantage of Reactor over CompletableFuture:
     * aggregation uses the built-in {@code reduce()} operator instead of
     * recursive {@code thenCompose()} calls.
     *
     * <pre>{@code
     * // No recursion needed!
     * streamOrders()
     *     .reduce(OrderStats.zero(), OrderStats::accumulate)
     * }</pre>
     *
     * @return Mono that emits the aggregated statistics
     */
    public Mono<OrderStats> aggregateOrders() {
        return streamOrders()
                .reduce(OrderStats.zero(), OrderStats::accumulate);
    }

    /**
     * Aggregates only completed orders.
     *
     * @return Mono with statistics for completed orders only
     */
    public Mono<OrderStats> aggregateCompletedOrders() {
        return streamOrders()
                .filter(order -> "completed".equals(order.status()))
                .reduce(OrderStats.zero(), OrderStats::accumulate);
    }

    /**
     * Aggregates orders until the sum reaches a target amount.
     *
     * <p>Uses {@code takeWhile()} for early termination - fetching stops
     * once the target is reached.
     *
     * @param targetAmount the target sum amount
     * @return Mono with statistics of orders up to when target is reached
     */
    public Mono<OrderStats> aggregateUntilAmount(double targetAmount) {
        return streamOrders()
                .scan(OrderStats.zero(), OrderStats::accumulate)
                .takeWhile(stats -> stats.sum() < targetAmount)
                .last(OrderStats.zero());
    }

    /**
     * Collects first N orders.
     *
     * <p>Uses {@code take()} for early termination.
     *
     * @param n the number of orders to collect
     * @return Mono with the collected orders
     */
    public Mono<java.util.List<Order>> takeFirst(int n) {
        return streamOrders()
                .take(n)
                .collectList();
    }

    /**
     * Finds the first order matching a predicate.
     *
     * @param predicate the condition to match
     * @return Mono that emits the first matching order, or empty
     */
    public Mono<Order> findFirst(java.util.function.Predicate<Order> predicate) {
        return streamOrders()
                .filter(predicate)
                .next();
    }
}
