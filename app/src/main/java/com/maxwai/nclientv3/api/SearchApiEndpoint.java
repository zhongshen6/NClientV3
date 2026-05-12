package com.maxwai.nclientv3.api;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maxwai.nclientv3.api.components.Ranges;
import com.maxwai.nclientv3.api.components.Tag;
import com.maxwai.nclientv3.api.enums.SortType;
import com.maxwai.nclientv3.api.enums.TagStatus;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Collection;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SearchApiEndpoint {
    @NonNull
    private final ApiRateLimiter rateLimiter;

    SearchApiEndpoint(@NonNull ApiRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @NonNull
    public Response execute(@NonNull Context context, @NonNull OkHttpClient client, @Nullable String query,
                            @Nullable Collection<Tag> tags, @Nullable Ranges ranges, int page,
                            @Nullable SortType sortType) throws IOException, ApiRateLimiter.RateLimitException {
        return rateLimiter.execute(context, client, buildRequest(query, tags, ranges, page, sortType));
    }

    @NonNull
    public Request buildRequest(@Nullable String query, @Nullable Collection<Tag> tags, @Nullable Ranges ranges,
                                int page, @Nullable SortType sortType) {
        return new Request.Builder().url(buildUrl(query, tags, ranges, page, sortType)).build();
    }

    @NonNull
    public String buildUrl(@Nullable String query, @Nullable Collection<Tag> tags, @Nullable Ranges ranges,
                           int page, @Nullable SortType sortType) {
        StringBuilder builder = new StringBuilder(Utility.getBaseUrl()).append("api/v2/search?query=").append(encode(query));
        if (tags != null) {
            for (Tag tag : tags) {
                if (builder.toString().contains(tag.toQueryTag(TagStatus.ACCEPTED))) continue;
                builder.append('+').append(encode(tag.toQueryTag()));
            }
        }
        if (ranges != null) builder.append('+').append(ranges.toQuery());
        builder.append("&page=").append(page);
        if (sortType != null && sortType.getUrlAddition() != null) {
            builder.append("&sort=").append(sortType.getUrlAddition());
        }
        return builder.toString().replace(' ', '+');
    }

    @NonNull
    private String encode(@Nullable String value) {
        if (value == null) return "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return URLEncoder.encode(value, Charset.defaultCharset());
        }
        try {
            //noinspection CharsetObjectCanBeUsed
            return URLEncoder.encode(value, Charset.defaultCharset().name());
        } catch (UnsupportedEncodingException e) {
            LogUtility.wtf("This should not happen since we used the default charset", e);
            return value;
        }
    }
}
