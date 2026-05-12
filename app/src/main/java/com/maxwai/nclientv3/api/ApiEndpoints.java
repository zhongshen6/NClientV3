package com.maxwai.nclientv3.api;

public final class ApiEndpoints {
    public static final SearchApiEndpoint SEARCH = new SearchApiEndpoint(
        new ApiRateLimiter(
            ApiLimitConstants.SEARCH_LIST_METHOD,
            ApiLimitConstants.SEARCH_LIST_PATH,
            ApiLimitConstants.SEARCH_LIST_UNAUTHENTICATED_REQUESTS,
            ApiLimitConstants.SEARCH_LIST_AUTHENTICATED_REQUESTS,
            ApiLimitConstants.API_RATE_LIMIT_WINDOW_MS
        )
    );

    private ApiEndpoints() {
    }
}
