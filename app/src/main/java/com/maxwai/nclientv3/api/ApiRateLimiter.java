package com.maxwai.nclientv3.api;

import android.content.Context;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ApiRateLimiter {
    @NonNull
    private final Object lock = new Object();
    @NonNull
    private final ArrayDeque<RequestTimestamp> recentRequests = new ArrayDeque<>();
    @NonNull
    private final String method;
    @NonNull
    private final String path;
    private final int unauthenticatedRequestLimit;
    private final int authenticatedRequestLimit;
    private final long windowMs;

    ApiRateLimiter(@NonNull String method, @NonNull String path, int unauthenticatedRequestLimit,
                   int authenticatedRequestLimit, long windowMs) {
        this.method = method;
        this.path = path;
        this.unauthenticatedRequestLimit = unauthenticatedRequestLimit;
        this.authenticatedRequestLimit = authenticatedRequestLimit;
        this.windowMs = windowMs;
    }

    @NonNull
    public Response execute(@NonNull Context context, @NonNull OkHttpClient client, @NonNull Request request)
        throws IOException, RateLimitException {
        return execute(context, client, request, ApiLimitConstants.API_RATE_LIMIT_FULL_QUOTA);
    }

    @NonNull
    public Response execute(@NonNull Context context, @NonNull OkHttpClient client, @NonNull Request request,
                            double quotaFactor) throws IOException, RateLimitException {
        RequestTimestamp timestamp = reserveRequest(context, quotaFactor);
        try {
            Response response = client.newCall(request).execute();
            if (response.code() == ApiLimitConstants.API_RATE_LIMIT_HTTP_STATUS) {
                removeRequest(timestamp);
                long retryAfterMs = getRetryAfterMs(response);
                response.close();
                throw new RateLimitException(retryAfterMs);
            }
            return response;
        } catch (IOException e) {
            throw e;
        }
    }

    @NonNull
    public Call executeAsync(@NonNull Context context, @NonNull OkHttpClient client, @NonNull Request request,
                             double quotaFactor, @NonNull Consumer<Response> onSuccess,
                             @NonNull Consumer<RateLimitException> onRateLimited,
                             @NonNull Consumer<IOException> onFailure, @NonNull Runnable onCancelled) {
        RequestTimestamp timestamp;
        try {
            timestamp = reserveRequest(context, quotaFactor);
        } catch (RateLimitException e) {
            Call call = client.newCall(request);
            call.cancel();
            onRateLimited.accept(e);
            return call;
        }

        Call call = client.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (call.isCanceled()) onCancelled.run();
                else onFailure.accept(e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (response.code() == ApiLimitConstants.API_RATE_LIMIT_HTTP_STATUS) {
                    removeRequest(timestamp);
                    onRateLimited.accept(new RateLimitException(getRetryAfterMs(response)));
                    response.close();
                    return;
                }
                onSuccess.accept(response);
            }
        });
        return call;
    }

    @NonNull
    public String getMethod() {
        return method;
    }

    @NonNull
    public String getPath() {
        return path;
    }

    private RequestTimestamp reserveRequest(@NonNull Context context, double quotaFactor) throws RateLimitException {
        synchronized (lock) {
            cleanWindowLocked();
            int requestLimit = AuthStore.hasValidApiKey(context) ? authenticatedRequestLimit : unauthenticatedRequestLimit;
            int effectiveLimit = getEffectiveLimit(requestLimit, quotaFactor);
            if (recentRequests.size() >= effectiveLimit) {
                throw new RateLimitException(getRetryAfterMsLocked());
            }
            RequestTimestamp timestamp = new RequestTimestamp(System.currentTimeMillis());
            recentRequests.addLast(timestamp);
            return timestamp;
        }
    }

    private int getEffectiveLimit(int requestLimit, double quotaFactor) {
        double normalizedFactor = Math.max(0, Math.min(1, quotaFactor));
        return Math.max(1, (int) Math.floor(requestLimit * normalizedFactor));
    }

    private void removeRequest(@NonNull RequestTimestamp timestamp) {
        synchronized (lock) {
            recentRequests.remove(timestamp);
        }
    }

    private void cleanWindowLocked() {
        long threshold = System.currentTimeMillis() - windowMs;
        while (!recentRequests.isEmpty() && recentRequests.peekFirst().time <= threshold) {
            recentRequests.removeFirst();
        }
    }

    private long getRetryAfterMsLocked() {
        cleanWindowLocked();
        RequestTimestamp firstRequest = recentRequests.peekFirst();
        if (firstRequest == null) return ApiLimitConstants.API_RATE_LIMIT_DEFAULT_RETRY_AFTER_MS;
        return Math.max(0, firstRequest.time + windowMs - System.currentTimeMillis());
    }

    private long getRetryAfterMs(@NonNull Response response) {
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null) {
            try {
                return Math.max(0, Long.parseLong(retryAfter) * ApiLimitConstants.API_RATE_LIMIT_RETRY_AFTER_SECONDS_MS);
            } catch (NumberFormatException e) {
                LogUtility.d("Could not parse Retry-After header: " + retryAfter);
            }
        }
        return ApiLimitConstants.API_RATE_LIMIT_DEFAULT_RETRY_AFTER_MS;
    }

    public static class RateLimitException extends Exception {
        private final long retryAfterMs;

        RateLimitException(long retryAfterMs) {
            super("API rate limited");
            this.retryAfterMs = retryAfterMs;
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }
    }

    private static class RequestTimestamp {
        private final long time;

        RequestTimestamp(long time) {
            this.time = time;
        }
    }

}
