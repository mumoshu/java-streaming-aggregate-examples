package com.example.async.client;

import com.example.streaming.model.Page;
import com.example.streaming.pagination.PaginationStrategy;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Async HTTP client for fetching paginated API responses.
 * Uses {@link HttpClient#sendAsync} for non-blocking I/O.
 *
 * <p>This is the async equivalent of
 * {@link com.example.streaming.client.HttpPageFetcher}. Key differences:
 * <ul>
 *   <li>{@code fetchPageAsync()} returns CompletableFuture instead of blocking</li>
 *   <li>Retry logic uses CompletableFuture composition</li>
 *   <li>Async delays use {@code CompletableFuture.delayedExecutor()}</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * AsyncHttpPageFetcher<Order> fetcher = new AsyncHttpPageFetcher<>(
 *     "https://api.example.com/orders",
 *     Order.class
 * );
 *
 * // Non-blocking fetch
 * fetcher.fetchPageAsync(null)
 *     .thenAccept(page -> {
 *         for (Order order : page.items()) {
 *             process(order);
 *         }
 *     });
 *
 * // Use with AsyncPaginatedIterator
 * AsyncPaginatedIterator<Order> iterator = new AsyncPaginatedIterator<>(
 *     fetcher::fetchPageAsync,
 *     new CursorBasedPagination()
 * );
 * }</pre>
 *
 * @param <T> the type of items in each page
 */
public class AsyncHttpPageFetcher<T> {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final JavaType pageType;
    private final PaginationStrategy paginationStrategy;
    private final RetryConfig retryConfig;

    /**
     * Creates a new AsyncHttpPageFetcher with default configuration.
     *
     * @param baseUrl the base URL of the API endpoint
     * @param itemClass the class of items in each page
     */
    public AsyncHttpPageFetcher(String baseUrl, Class<T> itemClass) {
        this(baseUrl, itemClass, new CursorBasedPaginationStrategy(), RetryConfig.defaults());
    }

    /**
     * Creates a new AsyncHttpPageFetcher with custom configuration.
     */
    public AsyncHttpPageFetcher(
            String baseUrl,
            Class<T> itemClass,
            PaginationStrategy paginationStrategy,
            RetryConfig retryConfig
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.baseUrl = baseUrl;
        this.pageType = objectMapper.getTypeFactory()
                .constructParametricType(Page.class, itemClass);
        this.paginationStrategy = paginationStrategy;
        this.retryConfig = retryConfig;
    }

    /**
     * Asynchronously fetches a page from the API with the given cursor.
     * Includes retry logic with exponential backoff.
     *
     * @param cursor the pagination cursor (null for first page)
     * @return CompletableFuture that completes with the fetched page
     */
    public CompletableFuture<Page<T>> fetchPageAsync(String cursor) {
        String url = buildUrl(cursor);
        return executeWithRetryAsync(() -> doFetchAsync(url), 0);
    }

    /**
     * Returns a function suitable for use with AsyncPaginatedIterator.
     */
    public Function<String, CompletableFuture<Page<T>>> asAsyncPageFetcher() {
        return this::fetchPageAsync;
    }

    private String buildUrl(String cursor) {
        Map<String, String> params = paginationStrategy.getQueryParams(cursor);
        if (params.isEmpty()) {
            return baseUrl;
        }

        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        urlBuilder.append(baseUrl.contains("?") ? "&" : "?");

        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                urlBuilder.append("&");
            }
            urlBuilder.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }

        return urlBuilder.toString();
    }

    private CompletableFuture<Page<T>> doFetchAsync(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int statusCode = response.statusCode();

                    if (statusCode == 429) {
                        String retryAfter = response.headers()
                                .firstValue("Retry-After")
                                .orElse("1");
                        throw new RateLimitedException(Long.parseLong(retryAfter));
                    }

                    if (statusCode >= 400) {
                        throw new HttpException(statusCode, "HTTP error: " + statusCode);
                    }

                    try {
                        return objectMapper.readValue(response.body(), pageType);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse response", e);
                    }
                });
    }

    private CompletableFuture<Page<T>> executeWithRetryAsync(
            Supplier<CompletableFuture<Page<T>>> action,
            int attempt
    ) {
        return action.get()
                .exceptionallyCompose(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

                    if (attempt >= retryConfig.maxRetries() - 1) {
                        return CompletableFuture.failedFuture(ex);
                    }

                    long delayMs;
                    if (cause instanceof RateLimitedException rle) {
                        delayMs = rle.getRetryAfterSeconds() * 1000;
                    } else if (isRetryable(cause)) {
                        delayMs = retryConfig.backoffMillis() * (1L << attempt);
                        delayMs = Math.min(delayMs, retryConfig.maxBackoffMillis());
                    } else {
                        return CompletableFuture.failedFuture(ex);
                    }

                    return delayAsync(delayMs)
                            .thenCompose(v -> executeWithRetryAsync(action, attempt + 1));
                });
    }

    private boolean isRetryable(Throwable cause) {
        return cause instanceof IOException
                || cause instanceof RateLimitedException
                || (cause instanceof HttpException he && he.getStatusCode() >= 500);
    }

    private CompletableFuture<Void> delayAsync(long millis) {
        return CompletableFuture.runAsync(
                () -> {},
                CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS)
        );
    }

    /**
     * Default cursor-based pagination strategy.
     */
    private static class CursorBasedPaginationStrategy implements PaginationStrategy {
        @Override
        public String getInitialCursor() {
            return null;
        }

        @Override
        public String getNextCursor(Page<?> currentPage) {
            return currentPage.nextCursor();
        }

        @Override
        public boolean hasMorePages(Page<?> currentPage) {
            return currentPage.hasNextPage();
        }

        @Override
        public Map<String, String> getQueryParams(String cursor) {
            return cursor != null ? Map.of("cursor", cursor) : Map.of();
        }
    }

    /**
     * Configuration for retry behavior.
     */
    public record RetryConfig(
            int maxRetries,
            long backoffMillis,
            long maxBackoffMillis
    ) {
        public static RetryConfig defaults() {
            return new RetryConfig(3, 100, 5000);
        }

        public static RetryConfig noRetry() {
            return new RetryConfig(1, 0, 0);
        }
    }

    /**
     * Exception thrown when HTTP request fails.
     */
    public static class HttpException extends RuntimeException {
        private final int statusCode;

        public HttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    /**
     * Exception thrown when rate limited (HTTP 429).
     */
    public static class RateLimitedException extends RuntimeException {
        private final long retryAfterSeconds;

        public RateLimitedException(long retryAfterSeconds) {
            super("Rate limited. Retry after " + retryAfterSeconds + " seconds");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
