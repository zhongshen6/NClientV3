package com.maxwai.nclientv3;

import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.snackbar.Snackbar;
import com.maxwai.nclientv3.adapters.ListAdapter;
import com.maxwai.nclientv3.api.InspectorV3;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.async.database.Queries;
import com.maxwai.nclientv3.components.activities.BaseActivity;
import com.maxwai.nclientv3.components.classes.Bookmark;
import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.utility.LogUtility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class SubscriptionActivity extends BaseActivity {
    private static final int MAX_PARALLEL_REQUESTS = 2;

    private ListAdapter adapter;
    private TextView progressText;
    private ExecutorService executor;
    private int loadId = 0;
    private Snackbar snackbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_subscription);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        actionBar.setTitle(R.string.subscriptions);

        masterLayout = findViewById(R.id.master_layout);
        progressText = findViewById(R.id.progress_text);
        refresher = findViewById(R.id.refresher);
        recycler = findViewById(R.id.recycler);
        adapter = new ListAdapter(this);
        recycler.setAdapter(adapter);
        recycler.setHasFixedSize(true);
        changeLayout(getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
        refresher.setOnRefreshListener(this::loadSubscriptions);
        loadSubscriptions();
    }

    private void loadSubscriptions() {
        int actualLoad = ++loadId;
        hideError();
        if (executor != null) executor.shutdownNow();

        List<Bookmark> bookmarks = Queries.BookmarkTable.getSubscribedBookmarks();
        if (bookmarks.isEmpty()) {
            adapter.restartDataset(new ArrayList<>());
            updateProgress(0, 0, 0);
            refresher.setRefreshing(false);
            return;
        }

        refresher.setRefreshing(true);
        updateProgress(0, 0, bookmarks.size());
        executor = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS);
        ConcurrentHashMap<Integer, GenericGallery> galleries = new ConcurrentHashMap<>();
        AtomicInteger successful = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        int total = bookmarks.size();
        for (Bookmark bookmark : bookmarks) {
            executor.execute(() -> {
                List<GenericGallery> result = loadBookmark(bookmark);
                if (actualLoad != loadId || isFinishing()) return;
                handleSubscriptionResult(result, galleries, successful, failed, total);
                if (completed.incrementAndGet() == total) finishRefresh(successful.get(), failed.get(), total, actualLoad);
            });
        }
    }

    @Nullable
    private List<GenericGallery> loadBookmark(@NonNull Bookmark bookmark) {
        try {
            InspectorV3 inspector = bookmark.createInspector(this, null);
            if (inspector == null) return null;
            inspector.setPage(1);
            if (!inspector.createDocument()) throw new IOException("Subscription request failed");
            inspector.parseDocument();
            return inspector.getGalleries();
        } catch (Exception e) {
            LogUtility.e("Subscription request failed", e);
            return null;
        }
    }

    private void handleSubscriptionResult(@Nullable List<GenericGallery> result,
                                          @NonNull ConcurrentHashMap<Integer, GenericGallery> galleries,
                                          @NonNull AtomicInteger successful, @NonNull AtomicInteger failed, int total) {
        if (result == null) {
            failed.incrementAndGet();
            updateProgress(successful.get(), failed.get(), total);
            return;
        }

        successful.incrementAndGet();
        for (GenericGallery gallery : result) {
            if (gallery != null && gallery.isValid()) galleries.put(gallery.getId(), gallery);
        }
        updateGalleries(galleries);
        updateProgress(successful.get(), failed.get(), total);
    }

    private void updateGalleries(@NonNull ConcurrentHashMap<Integer, GenericGallery> galleryMap) {
        List<GenericGallery> sorted = new ArrayList<>(galleryMap.values());
        sorted.sort((a, b) -> Integer.compare(b.getId(), a.getId()));
        runOnUiThread(() -> adapter.restartDataset(sorted));
    }

    private void updateProgress(int successful, int failed, int total) {
        runOnUiThread(() -> {
            String text = getString(R.string.subscription_progress_format, successful, failed, total);
            SpannableString spannable = new SpannableString(text);
            String successfulText = String.valueOf(successful);
            String failedText = String.valueOf(failed);
            String totalText = String.valueOf(total);
            int successfulStart = text.indexOf(successfulText);
            int failedStart = text.indexOf(failedText, successfulStart + successfulText.length());
            int totalStart = text.lastIndexOf(totalText);
            setProgressSpan(spannable, successfulStart, successfulText.length(), Color.GREEN);
            setProgressSpan(spannable, failedStart, failedText.length(), Color.RED);
            setProgressSpan(spannable, totalStart, totalText.length(), Color.WHITE);
            progressText.setText(spannable);
        });
    }

    private void setProgressSpan(@NonNull SpannableString spannable, int start, int length, int color) {
        if (start < 0) return;
        spannable.setSpan(new ForegroundColorSpan(color), start, start + length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void finishRefresh(int successful, int failed, int total, int actualLoad) {
        runOnUiThread(() -> {
            if (actualLoad != loadId || isFinishing()) return;
            if (executor != null) executor.shutdown();
            refresher.setRefreshing(false);
            updateProgress(successful, failed, total);
            if (failed == total) showError(R.string.unable_to_connect_to_the_site, v -> loadSubscriptions());
        });
    }

    private void hideError() {
        runOnUiThread(() -> {
            if (snackbar != null && snackbar.isShown()) {
                snackbar.dismiss();
                snackbar = null;
            }
        });
    }

    private void showError(int text, @Nullable View.OnClickListener listener) {
        if (listener == null) {
            snackbar = Snackbar.make(masterLayout, text, Snackbar.LENGTH_SHORT);
        } else {
            snackbar = Snackbar.make(masterLayout, text, Snackbar.LENGTH_INDEFINITE);
            snackbar.setAction(R.string.retry, listener);
        }
        snackbar.show();
    }

    @Override
    protected int getPortraitColumnCount() {
        return Global.getColPortMain();
    }

    @Override
    protected int getLandscapeColumnCount() {
        return Global.getColLandMain();
    }

    @Override
    protected void onDestroy() {
        loadId++;
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
