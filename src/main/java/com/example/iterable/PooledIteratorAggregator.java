package com.example.iterable;

import com.example.streaming.model.OrderStats;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Aggregator that uses Jackson's streaming {@link JsonParser} API to parse
 * paginated HTTP responses directly, without creating intermediate
 * {@link com.example.streaming.model.Order} or
 * {@link com.example.streaming.model.Page} objects.
 *
 * <p>This eliminates per-item object allocation entirely. Instead of
 * deserializing JSON into Order records (which become garbage after each page),
 * this variant extracts only the {@code amount} field from each JSON object
 * and accumulates statistics inline.
 *
 * <p>Compared to {@link IterableAggregator}:
 * <ul>
 *   <li>Same O(page_size) memory model for HTTP response buffering</li>
 *   <li>Dramatically lower allocation rate (no Order/String/List objects)</li>
 *   <li>Similar peak/avg heap (dominated by JVM baseline + HTTP client)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * PooledIteratorAggregator aggregator = new PooledIteratorAggregator(
 *     "http://api.example.com/orders"
 * );
 * OrderStats stats = aggregator.aggregateOrders();
 * }</pre>
 */
public class PooledIteratorAggregator {

    private final HttpClient httpClient;
    private final JsonFactory jsonFactory;
    private final String baseUrl;

    /**
     * Creates a new PooledIteratorAggregator.
     *
     * @param baseUrl the URL of the orders API endpoint
     */
    public PooledIteratorAggregator(String baseUrl) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.jsonFactory = new JsonFactory();
        this.baseUrl = baseUrl;
    }

    /**
     * Aggregates all orders by streaming JSON responses without creating
     * Order objects. Only {@code amount} values are extracted and accumulated.
     *
     * @return aggregated statistics (count, sum, average)
     */
    public OrderStats aggregateOrders() {
        long count = 0;
        double sum = 0.0;
        String cursor = null;
        boolean hasMore = true;

        while (hasMore) {
            String url = buildUrl(cursor);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            try {
                HttpResponse<InputStream> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() >= 400) {
                    throw new RuntimeException("HTTP error: " + response.statusCode());
                }

                try (InputStream body = response.body();
                     JsonParser parser = jsonFactory.createParser(body)) {

                    PageResult pageResult = parsePage(parser);
                    count += pageResult.count;
                    sum += pageResult.sum;
                    cursor = pageResult.nextCursor;
                    hasMore = pageResult.hasMore;
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Failed to fetch page with cursor: " + cursor, e);
            }
        }

        double average = count > 0 ? sum / count : 0.0;
        return new OrderStats(count, sum, average);
    }

    /**
     * Parses a single page response using streaming JSON, extracting only
     * the amount values from the data array and pagination metadata.
     *
     * <p>Expected JSON format:
     * <pre>{@code
     * {
     *   "data": [{"id":"...","amount":10.0,"status":"..."}, ...],
     *   "nextCursor": "...",
     *   "hasMore": true
     * }
     * }</pre>
     */
    private PageResult parsePage(JsonParser parser) throws IOException {
        long count = 0;
        double sum = 0.0;
        String nextCursor = null;
        boolean hasMore = false;

        // Advance to the root object start
        parser.nextToken(); // START_OBJECT

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.currentName();
            parser.nextToken(); // Move to value

            switch (fieldName) {
                case "data" -> {
                    // Parse the data array: extract only "amount" from each object
                    // parser is now at START_ARRAY
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        // parser is at START_OBJECT for each order
                        double amount = parseOrderAmount(parser);
                        count++;
                        sum += amount;
                    }
                }
                case "nextCursor" -> {
                    if (parser.currentToken() != JsonToken.VALUE_NULL) {
                        nextCursor = parser.getText();
                    }
                }
                case "hasMore" -> hasMore = parser.getBooleanValue();
                default -> parser.skipChildren();
            }
        }

        return new PageResult(count, sum, nextCursor, hasMore);
    }

    /**
     * Parses a single order JSON object, extracting only the "amount" field.
     * All other fields are skipped without creating String objects.
     *
     * @param parser positioned at START_OBJECT
     * @return the amount value
     */
    private double parseOrderAmount(JsonParser parser) throws IOException {
        double amount = 0.0;

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.currentName();
            parser.nextToken(); // Move to value

            if ("amount".equals(fieldName)) {
                amount = parser.getDoubleValue();
            }
            // Other fields (id, status) are read by the parser but we don't
            // call getText() on them, avoiding String allocation
        }

        return amount;
    }

    private String buildUrl(String cursor) {
        if (cursor == null) {
            return baseUrl;
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "cursor=" + cursor;
    }

    /**
     * Holds the parsed result of a single page without creating Page/Order objects.
     */
    private record PageResult(long count, double sum, String nextCursor, boolean hasMore) {
    }
}
