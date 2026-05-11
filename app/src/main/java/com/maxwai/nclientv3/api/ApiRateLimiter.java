package com.maxwai.nclientv3.api;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ApiRateLimiter {
    public static final long API_RATE_LIMIT_WINDOW_MS = 60_000;
    public static final int API_RATE_LIMIT_MAX_REQUESTS = 10;
    public static final int API_RATE_LIMIT_RESERVED_IMMEDIATE_REQUESTS = 2;
    public static final long API_RATE_LIMIT_BACKGROUND_TICK_MS = 10_000;
    public static final int API_RATE_LIMIT_MAX_CONCURRENT_REQUESTS = 2;
    public static final int API_RATE_LIMIT_HTTP_STATUS = 429;
    public static final long API_RATE_LIMIT_CONCURRENCY_RETRY_MS = 1_000;
    public static final long API_RATE_LIMIT_RETRY_AFTER_SECONDS_MS = 1_000;
    public static final long API_RATE_LIMIT_INITIAL_BACKGROUND_TICK_DELAY_MS = 0;

    private static final ApiRateLimiter INSTANCE = new ApiRateLimiter();

    @NonNull
    private final Object lock = new Object();
    @NonNull
    private final ArrayDeque<Long> recentRequests = new ArrayDeque<>();
    @NonNull
    private final ArrayDeque<QueuedRequest> backgroundQueue = new ArrayDeque<>();
    @NonNull
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    @NonNull
    private final ExecutorService executor = Executors.newFixedThreadPool(API_RATE_LIMIT_MAX_CONCURRENT_REQUESTS);
    private int runningRequests = 0;
    private ScheduledFuture<?> backgroundTick;

    private ApiRateLimiter() {
    }

    @NonNull
    public static ApiRateLimiter getInstance() {
        return INSTANCE;
    }

    @NonNull
    public Response executeNow(@NonNull OkHttpClient client, @NonNull Request request) throws IOException {
        Call call;
        synchronized (lock) {
            cleanWindowLocked();
            if (recentRequests.size() >= API_RATE_LIMIT_MAX_REQUESTS) {
                throw new RateLimitedException(getRetryAfterMsLocked());
            }
            if (runningRequests >= API_RATE_LIMIT_MAX_CONCURRENT_REQUESTS) {
                throw new RateLimitedException(API_RATE_LIMIT_CONCURRENCY_RETRY_MS);
            }
            recordRequestLocked();
            runningRequests++;
            call = client.newCall(request);
        }

        try {
            Response response = call.execute();
            if (response.code() == API_RATE_LIMIT_HTTP_STATUS) {
                long retryAfterMs = getRetryAfterMs(response);
                response.close();
                throw new RateLimitedException(retryAfterMs);
            }
            return response;
        } finally {
            finishRequest();
        }
    }

    @NonNull
    public ApiRequestHandle enqueue(@NonNull OkHttpClient client, @NonNull Request request, @NonNull ApiCallback callback) {
        QueuedRequest queuedRequest = new QueuedRequest(client, request, callback);
        synchronized (lock) {
            backgroundQueue.add(queuedRequest);
            scheduleBackgroundTickLocked(API_RATE_LIMIT_INITIAL_BACKGROUND_TICK_DELAY_MS);
        }
        return queuedRequest;
    }

    @NonNull
    public ApiRequestHandle executeNowAsync(@NonNull OkHttpClient client, @NonNull Request request, @NonNull ApiCallback callback) {
        QueuedRequest immediateRequest = new QueuedRequest(client, request, callback);
        RateLimitedException rateLimitedException = null;
        synchronized (lock) {
            cleanWindowLocked();
            if (recentRequests.size() >= API_RATE_LIMIT_MAX_REQUESTS) {
                rateLimitedException = new RateLimitedException(getRetryAfterMsLocked());
            } else if (runningRequests >= API_RATE_LIMIT_MAX_CONCURRENT_REQUESTS) {
                rateLimitedException = new RateLimitedException(API_RATE_LIMIT_CONCURRENCY_RETRY_MS);
            } else {
                recordRequestLocked();
                runningRequests++;
            }
        }
        if (rateLimitedException != null) {
            RateLimitedException exception = rateLimitedException;
            executor.execute(() -> {
                if (!immediateRequest.isCancelled()) callback.onRateLimited(exception);
            });
        } else {
            executor.execute(() -> runImmediateRequest(immediateRequest));
        }
        return immediateRequest;
    }

    private void runBackgroundTick() {
        synchronized (lock) {
            backgroundTick = null;
            cleanWindowLocked();
            int availableRequests = API_RATE_LIMIT_MAX_REQUESTS - recentRequests.size();
            int backgroundAllowedRequests = Math.max(0, availableRequests - API_RATE_LIMIT_RESERVED_IMMEDIATE_REQUESTS);
            int availableConcurrency = API_RATE_LIMIT_MAX_CONCURRENT_REQUESTS - runningRequests;
            int startCount = Math.min(Math.min(backgroundAllowedRequests, backgroundQueue.size()), availableConcurrency);
            for (int i = 0; i < startCount; i++) {
                QueuedRequest request = nextQueuedRequestLocked();
                if (request == null) break;
                recordRequestLocked();
                runningRequests++;
                executor.execute(() -> runQueuedRequest(request));
            }
            if (!backgroundQueue.isEmpty()) scheduleBackgroundTickLocked(API_RATE_LIMIT_BACKGROUND_TICK_MS);
        }
    }

    private QueuedRequest nextQueuedRequestLocked() {
        while (!backgroundQueue.isEmpty()) {
            QueuedRequest request = backgroundQueue.removeFirst();
            if (!request.isCancelled()) return request;
        }
        return null;
    }

    private void runQueuedRequest(@NonNull QueuedRequest queuedRequest) {
        try {
            if (queuedRequest.isCancelled()) return;
            Call call = queuedRequest.client.newCall(queuedRequest.request);
            queuedRequest.setCall(call);
            try (Response response = call.execute()) {
                if (queuedRequest.isCancelled()) return;
                if (response.code() == API_RATE_LIMIT_HTTP_STATUS) {
                    requeue(queuedRequest);
                    return;
                }
                queuedRequest.callback.onSuccess(response);
            }
        } catch (IOException e) {
            if (queuedRequest.isCancelled()) {
                queuedRequest.callback.onCancelled();
            } else {
                queuedRequest.callback.onFailure(e);
            }
        } finally {
            queuedRequest.clearCall();
            finishRequest();
        }
    }

    private void runImmediateRequest(@NonNull QueuedRequest immediateRequest) {
        try {
            if (immediateRequest.isCancelled()) return;
            Call call = immediateRequest.client.newCall(immediateRequest.request);
            immediateRequest.setCall(call);
            try (Response response = call.execute()) {
                if (immediateRequest.isCancelled()) return;
                if (response.code() == API_RATE_LIMIT_HTTP_STATUS) {
                    immediateRequest.callback.onRateLimited(new RateLimitedException(getRetryAfterMs(response)));
                    return;
                }
                immediateRequest.callback.onSuccess(response);
            }
        } catch (IOException e) {
            if (immediateRequest.isCancelled()) {
                immediateRequest.callback.onCancelled();
            } else {
                immediateRequest.callback.onFailure(e);
            }
        } finally {
            immediateRequest.clearCall();
            finishRequest();
        }
    }

    private void requeue(@NonNull QueuedRequest queuedRequest) {
        queuedRequest.clearCall();
        synchronized (lock) {
            if (!queuedRequest.isCancelled()) {
                backgroundQueue.addLast(queuedRequest);
                scheduleBackgroundTickLocked(API_RATE_LIMIT_BACKGROUND_TICK_MS);
            }
        }
    }

    private void finishRequest() {
        synchronized (lock) {
            runningRequests = Math.max(0, runningRequests - 1);
            if (!backgroundQueue.isEmpty()) scheduleBackgroundTickLocked(API_RATE_LIMIT_BACKGROUND_TICK_MS);
        }
    }

    private void recordRequestLocked() {
        recentRequests.addLast(System.currentTimeMillis());
    }

    private void cleanWindowLocked() {
        long threshold = System.currentTimeMillis() - API_RATE_LIMIT_WINDOW_MS;
        while (!recentRequests.isEmpty() && recentRequests.peekFirst() <= threshold) {
            recentRequests.removeFirst();
        }
    }

    private long getRetryAfterMsLocked() {
        cleanWindowLocked();
        Long firstRequest = recentRequests.peekFirst();
        if (firstRequest == null) return 0;
        return Math.max(0, firstRequest + API_RATE_LIMIT_WINDOW_MS - System.currentTimeMillis());
    }

    private long getRetryAfterMs(@NonNull Response response) {
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null) {
            try {
                return Math.max(0, Long.parseLong(retryAfter) * API_RATE_LIMIT_RETRY_AFTER_SECONDS_MS);
            } catch (NumberFormatException e) {
                LogUtility.d("Could not parse Retry-After header: " + retryAfter);
            }
        }
        synchronized (lock) {
            return getRetryAfterMsLocked();
        }
    }

    private void scheduleBackgroundTickLocked(long delayMs) {
        if (backgroundTick != null && !backgroundTick.isDone()) return;
        backgroundTick = scheduler.schedule(this::runBackgroundTick, delayMs, TimeUnit.MILLISECONDS);
    }

    public interface ApiRequestHandle {
        void cancel();

        boolean isCancelled();
    }

    public interface ApiCallback {
        void onSuccess(@NonNull Response response) throws IOException;

        void onRateLimited(@NonNull RateLimitedException e);

        void onFailure(@NonNull IOException e);

        void onCancelled();
    }

    public static class RateLimitedException extends IOException {
        private final long retryAfterMs;

        RateLimitedException(long retryAfterMs) {
            super("API rate limited");
            this.retryAfterMs = retryAfterMs;
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }
    }

    private static class QueuedRequest implements ApiRequestHandle {
        @NonNull
        private final OkHttpClient client;
        @NonNull
        private final Request request;
        @NonNull
        private final ApiCallback callback;
        private boolean cancelled = false;
        private Call call;

        QueuedRequest(@NonNull OkHttpClient client, @NonNull Request request, @NonNull ApiCallback callback) {
            this.client = client;
            this.request = request;
            this.callback = callback;
        }

        @Override
        public void cancel() {
            Call callToCancel;
            synchronized (this) {
                cancelled = true;
                callToCancel = call;
            }
            if (callToCancel != null) callToCancel.cancel();
        }

        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }

        synchronized void setCall(@NonNull Call call) {
            this.call = call;
            if (cancelled) call.cancel();
        }

        synchronized void clearCall() {
            call = null;
        }
    }
}
