package com.example.streaming.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Generic page wrapper for paginated API responses.
 * Contains the items for the current page and pagination metadata.
 *
 * @param <T> the type of items in the page
 */
public record Page<T>(
        List<T> data,
        String nextCursor,
        boolean hasMore
) {
    @JsonCreator
    public Page(
            @JsonProperty("data") List<T> data,
            @JsonProperty("nextCursor") String nextCursor,
            @JsonProperty("hasMore") boolean hasMore
    ) {
        this.data = data != null ? List.copyOf(data) : List.of();
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

    /**
     * Creates an empty page with no items and no next cursor.
     */
    public static <T> Page<T> empty() {
        return new Page<>(List.of(), null, false);
    }

    /**
     * Creates a page with items and a next cursor.
     */
    public static <T> Page<T> of(List<T> items, String nextCursor) {
        return new Page<>(items, nextCursor, nextCursor != null);
    }

    /**
     * Creates the last page (no more pages after this).
     */
    public static <T> Page<T> last(List<T> items) {
        return new Page<>(items, null, false);
    }

    /**
     * Returns the items in this page.
     * Alias for data() to provide more intuitive API.
     */
    public List<T> items() {
        return data;
    }

    /**
     * Checks if there are more pages available.
     */
    public boolean hasNextPage() {
        return hasMore && nextCursor != null;
    }

    /**
     * Returns the number of items in this page.
     */
    public int size() {
        return data.size();
    }

    /**
     * Checks if this page is empty.
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }
}
