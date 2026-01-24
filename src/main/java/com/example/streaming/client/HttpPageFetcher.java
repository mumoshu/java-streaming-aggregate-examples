package com.example.streaming.client;

import com.example.streaming.model.Page;
import com.example.streaming.pagination.PaginationStrategy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * HTTP client for fetching paginated API responses.
 * Includes retry logic with exponential backoff for resilience.
 *
 * @param <T> the type of items in each page
 */
public class HttpPageFetcher<T> {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final JavaType pageType;
    private final PaginationStrategy paginationStrategy;
    private final RetryConfig retryConfig;

    /**
     * Creates a new HttpPageFetcher with default configuration.
     *
     * @param baseUrl the base URL of the API endpoint
     * @param itemClass the class of items in each page
     */
    public HttpPageFetcher(String baseUrl, Class<T> itemClass) {
        this(baseUrl, itemClass, new CursorBasedPaginationStrategy(), RetryConfig.defaults());
    }

    /**
     * Creates a new HttpPageFetcher with custom configuration.
     */
    public HttpPageFetcher(
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
     * Creates a HttpPageFetcher with a pre-configured HttpClient and ObjectMapper.
     */
    public HttpPageFetcher(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String baseUrl,
            Class<T> itemClass,
            PaginationStrategy paginationStrategy,
            RetryConfig retryConfig
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.pageType = objectMapper.getTypeFactory()
                .constructParametricType(Page.class, itemClass);
        this.paginationStrategy = paginationStrategy;
        this.retryConfig = retryConfig;
    }

    /**
     * Fetches a page from the API with the given cursor.
     * Includes retry logic with exponential backoff.
     *
     * @param cursor the pagination cursor (null for first page)
     * @return the fetched page
     */
    public Page<T> fetchPage(String cursor) {
        String url = buildUrl(cursor);
        return executeWithRetry(() -> doFetch(url));
    }

    /**
     * Returns a function suitable for use with PaginatedSpliterator.
     */
    public Function<String, Page<T>> asPageFetcher() {
        return this::fetchPage;
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

    private Page<T> doFetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        int statusCode = response.statusCode();
        if (statusCode == 429) {
            // Rate limited - extract retry-after header if present
            String retryAfter = response.headers()
                    .firstValue("Retry-After")
                    .orElse("1");
            throw new RateLimitedException(Long.parseLong(retryAfter));
        }

        if (statusCode >= 400) {
            throw new HttpException(statusCode, "HTTP error: " + statusCode);
        }

        try (InputStream body = response.body()) {
            return objectMapper.readValue(body, pageType);
        }
    }

    private <R> R executeWithRetry(RetryableSupplier<R> action) {
        int attempts = 0;
        Exception lastException = null;

        while (attempts < retryConfig.maxRetries()) {
            try {
                return action.get();
            } catch (RateLimitedException e) {
                // Handle rate limiting specially
                lastException = e;
                attempts++;
                sleep(e.getRetryAfterSeconds() * 1000);
            } catch (IOException | InterruptedException e) {
                lastException = e;
                attempts++;
                if (attempts < retryConfig.maxRetries()) {
                    long backoffMs = retryConfig.backoffMillis() * (1L << (attempts - 1));
                    backoffMs = Math.min(backoffMs, retryConfig.maxBackoffMillis());
                    sleep(backoffMs);
                }
            }
        }

        throw new RuntimeException(
                "Failed after " + attempts + " retries",
                lastException
        );
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during backoff", e);
        }
    }

    @FunctionalInterface
    private interface RetryableSupplier<T> {
        T get() throws IOException, InterruptedException;
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
     * Default cursor-based pagination strategy for the HTTP client.
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
     * Exception thrown when HTTP request fails.
     */
    public static class HttpException extends IOException {
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
    public static class RateLimitedException extends IOException {
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
