package com.example.streaming.pagination;

import com.example.streaming.model.Page;

import java.util.Map;

/**
 * Strategy interface for handling different pagination styles.
 * Supports cursor-based, offset-based, and other pagination approaches.
 */
public interface PaginationStrategy {

    /**
     * Returns the initial cursor/offset for the first page request.
     * May return null for cursor-based pagination (first page has no cursor).
     */
    String getInitialCursor();

    /**
     * Extracts the next cursor from the current page response.
     *
     * @param currentPage the current page response
     * @return the cursor for the next page, or null if no more pages
     */
    String getNextCursor(Page<?> currentPage);

    /**
     * Determines if there are more pages to fetch after the current one.
     *
     * @param currentPage the current page response
     * @return true if more pages exist
     */
    boolean hasMorePages(Page<?> currentPage);

    /**
     * Builds query parameters for the HTTP request based on the cursor.
     *
     * @param cursor the current cursor/offset value
     * @return map of query parameter names to values
     */
    Map<String, String> getQueryParams(String cursor);

    /**
     * Updates internal state after a page is fetched (for stateful strategies like offset).
     *
     * @param currentPage the page that was just fetched
     */
    default void onPageFetched(Page<?> currentPage) {
        // Default implementation does nothing (stateless strategies)
    }
}
