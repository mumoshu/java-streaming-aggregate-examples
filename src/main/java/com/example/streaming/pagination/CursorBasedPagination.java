package com.example.streaming.pagination;

import com.example.streaming.model.Page;

import java.util.HashMap;
import java.util.Map;

/**
 * Cursor-based pagination strategy.
 * Uses an opaque cursor token returned by the API to fetch subsequent pages.
 *
 * Example API response:
 * {
 *   "data": [...],
 *   "nextCursor": "eyJpZCI6MTAwfQ==",
 *   "hasMore": true
 * }
 */
public class CursorBasedPagination implements PaginationStrategy {

    private final String cursorParamName;
    private final int pageSize;
    private final String pageSizeParamName;

    /**
     * Creates a cursor-based pagination strategy with default parameter names.
     */
    public CursorBasedPagination() {
        this("cursor", 100, "limit");
    }

    /**
     * Creates a cursor-based pagination strategy with custom configuration.
     *
     * @param cursorParamName the query parameter name for the cursor
     * @param pageSize the number of items per page
     * @param pageSizeParamName the query parameter name for page size
     */
    public CursorBasedPagination(String cursorParamName, int pageSize, String pageSizeParamName) {
        this.cursorParamName = cursorParamName;
        this.pageSize = pageSize;
        this.pageSizeParamName = pageSizeParamName;
    }

    @Override
    public String getInitialCursor() {
        return null; // First page has no cursor
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
        Map<String, String> params = new HashMap<>();
        params.put(pageSizeParamName, String.valueOf(pageSize));
        if (cursor != null) {
            params.put(cursorParamName, cursor);
        }
        return params;
    }
}
