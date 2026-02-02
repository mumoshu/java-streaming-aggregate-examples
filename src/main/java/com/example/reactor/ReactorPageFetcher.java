package com.example.reactor;

import com.example.streaming.client.HttpPageFetcher;
import com.example.streaming.model.Page;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive wrapper around {@link HttpPageFetcher} that returns {@link Mono}.
 *
 * <p>This class adapts the blocking HTTP client to a reactive interface by
 * executing the blocking call on a bounded elastic scheduler, which is
 * designed for blocking I/O operations.
 *
 * <p>Example usage:
 * <pre>{@code
 * ReactorPageFetcher<Order> fetcher = new ReactorPageFetcher<>(
 *     "https://api.example.com/orders",
 *     Order.class
 * );
 *
 * fetcher.fetchPage(null)
 *     .subscribe(page -> {
 *         page.items().forEach(System.out::println);
 *     });
 * }</pre>
 *
 * @param <T> the type of items in each page
 */
public class ReactorPageFetcher<T> {

    private final HttpPageFetcher<T> delegate;

    /**
     * Creates a new ReactorPageFetcher.
     *
     * @param baseUrl the base URL of the API endpoint
     * @param itemClass the class of items in each page
     */
    public ReactorPageFetcher(String baseUrl, Class<T> itemClass) {
        this.delegate = new HttpPageFetcher<>(baseUrl, itemClass);
    }

    /**
     * Creates a new ReactorPageFetcher with a custom delegate.
     *
     * @param delegate the underlying blocking page fetcher
     */
    public ReactorPageFetcher(HttpPageFetcher<T> delegate) {
        this.delegate = delegate;
    }

    /**
     * Fetches a page reactively.
     *
     * <p>The blocking HTTP call is executed on a bounded elastic scheduler,
     * which is suitable for blocking I/O operations.
     *
     * @param cursor the pagination cursor (null for first page)
     * @return Mono that emits the fetched page
     */
    public Mono<Page<T>> fetchPage(String cursor) {
        return Mono.fromCallable(() -> delegate.fetchPage(cursor))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Returns the underlying blocking page fetcher.
     *
     * @return the delegate HttpPageFetcher
     */
    public HttpPageFetcher<T> getDelegate() {
        return delegate;
    }
}
