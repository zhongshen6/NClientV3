package com.maxwai.nclientv3.api;

public final class ApiLimitConstants {
    public static final long API_RATE_LIMIT_WINDOW_MS = 60_000;
    public static final long API_RATE_LIMIT_DEFAULT_RETRY_AFTER_MS = 10_000;
    public static final long API_RATE_LIMIT_RETRY_AFTER_SECONDS_MS = 1_000;
    public static final int API_RATE_LIMIT_HTTP_STATUS = 429;
    public static final double API_RATE_LIMIT_FULL_QUOTA = 1.0;
    public static final double API_RATE_LIMIT_CONSERVATIVE_QUOTA = 0.8;

    public static final String SEARCH_LIST_METHOD = "GET";
    public static final String SEARCH_LIST_PATH = "/api/v2/search";
    public static final int SEARCH_LIST_UNAUTHENTICATED_REQUESTS = 10;
    public static final int SEARCH_LIST_AUTHENTICATED_REQUESTS = 10;

    private ApiLimitConstants() {
    }
}
