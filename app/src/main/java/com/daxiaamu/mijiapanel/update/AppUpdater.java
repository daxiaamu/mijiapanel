package com.daxiaamu.mijiapanel.update;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.daxiaamu.mijiapanel.BuildConfig;
import com.daxiaamu.mijiapanel.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdater {
    public static final String RELEASES_URL =
            "https://github.com/daxiaamu/mijiapanel/releases";
    public static final String UPDATE_PREFERENCES = "app_update";
    public static final String KEY_DOWNLOAD_ID = "download_id";
    public static final String KEY_READY_DOWNLOAD_ID = "ready_download_id";
    private static final String KEY_DOWNLOAD_URLS = "download_urls";
    private static final String KEY_DOWNLOAD_SOURCE_INDEX = "download_source_index";
    private static final String KEY_DOWNLOAD_SHA256 = "download_sha256";
    private static final String KEY_DOWNLOAD_VERSION_CODE = "download_version_code";
    private static final String KEY_READY_VERSION_CODE = "ready_version_code";
    public static final String APK_MIME = "application/vnd.android.package-archive";

    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final Object DOWNLOAD_PLAN_LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final String MANIFEST_PATH =
            "daxiaamu/mijiapanel@main/latest-release.json";
    private static final String[] UPDATE_SOURCES = {
            "https://api.github.com/repos/daxiaamu/mijiapanel/contents/latest-release.json?ref=main",
            "https://api.github.com/repos/daxiaamu/mijiapanel/releases/latest",
            "https://cdn.jsdelivr.net/gh/" + MANIFEST_PATH,
            "https://fastly.jsdelivr.net/gh/" + MANIFEST_PATH,
            "https://gcore.jsdelivr.net/gh/" + MANIFEST_PATH,
            "https://raw.githubusercontent.com/daxiaamu/mijiapanel/main/latest-release.json"
    };

    public interface CheckCallback {
        void onSuccess(UpdateInfo info);

        void onFailure(Throwable error);
    }

    public interface ProgressCallback {
        void onResult(DownloadProgress progress);
    }

    public void checkAsync(CheckCallback callback) {
        EXECUTOR.execute(() -> {
            Throwable lastError = null;
            UpdateInfo best = null;
            for (String source : UPDATE_SOURCES) {
                try {
                    UpdateInfo candidate = fetch(source);
                    if (best == null || candidate.versionCode > best.versionCode) {
                        best = candidate;
                    }
                    break;
                } catch (Throwable error) {
                    lastError = error;
                }
            }
            UpdateInfo result = best;
            Throwable failure = lastError;
            MAIN_HANDLER.post(() -> {
                if (result != null) {
                    callback.onSuccess(result);
                } else {
                    callback.onFailure(failure != null
                            ? failure : new IllegalStateException("No update source available"));
                }
            });
        });
    }

    public boolean isNewer(UpdateInfo info) {
        return info.versionCode > BuildConfig.VERSION_CODE;
    }

    public long download(Context context, UpdateInfo info) {
        if (info.apkUrls.isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.update_download_no_apk));
        }
        return enqueueDownload(
                context, info.apkUrls, 0, info.apkSha256, info.versionCode);
    }

    public Long retryNextDownload(Context context, long failedDownloadId) {
        synchronized (DOWNLOAD_PLAN_LOCK) {
            SharedPreferences preferences = preferences(context);
            long currentId = preferences.getLong(KEY_DOWNLOAD_ID, -1L);
            if (currentId != failedDownloadId) {
                return currentId >= 0L ? currentId : null;
            }
            List<String> urls = readUrls(preferences.getString(KEY_DOWNLOAD_URLS, null));
            int nextIndex = preferences.getInt(KEY_DOWNLOAD_SOURCE_INDEX, 0) + 1;
            if (nextIndex < 0 || nextIndex >= urls.size()) {
                return null;
            }
            try {
                context.getSystemService(DownloadManager.class).remove(failedDownloadId);
            } catch (Throwable ignored) {
            }
            String sha256 = preferences.getString(KEY_DOWNLOAD_SHA256, "");
            int versionCode = preferences.getInt(KEY_DOWNLOAD_VERSION_CODE, -1);
            while (nextIndex < urls.size()) {
                try {
                    return enqueueDownload(
                            context, urls, nextIndex, sha256, versionCode);
                } catch (Throwable ignored) {
                    nextIndex++;
                }
            }
            return null;
        }
    }

    public void queryProgressAsync(Context context, long downloadId, ProgressCallback callback) {
        EXECUTOR.execute(() -> {
            DownloadProgress progress = queryProgress(context, downloadId);
            MAIN_HANDLER.post(() -> callback.onResult(progress));
        });
    }

    public String expectedSha256(Context context) {
        return preferences(context).getString(KEY_DOWNLOAD_SHA256, "");
    }

    public static long readyDownload(Context context) {
        return preferences(context).getLong(KEY_READY_DOWNLOAD_ID, -1L);
    }

    public static void markReady(Context context, long downloadId) {
        SharedPreferences preferences = preferences(context);
        preferences.edit()
                .putLong(KEY_READY_DOWNLOAD_ID, downloadId)
                .putInt(KEY_READY_VERSION_CODE,
                        preferences.getInt(KEY_DOWNLOAD_VERSION_CODE, -1))
                .apply();
    }

    public static int readyVersionCode(Context context) {
        return preferences(context).getInt(KEY_READY_VERSION_CODE, -1);
    }

    public static void discardReadyDownload(Context context) {
        SharedPreferences preferences = preferences(context);
        long downloadId = preferences.getLong(KEY_READY_DOWNLOAD_ID, -1L);
        if (downloadId >= 0L) {
            try {
                context.getSystemService(DownloadManager.class).remove(downloadId);
            } catch (Throwable ignored) {
            }
        }
        preferences.edit()
                .remove(KEY_READY_DOWNLOAD_ID)
                .remove(KEY_READY_VERSION_CODE)
                .apply();
    }

    private long enqueueDownload(
            Context context,
            List<String> urls,
            int sourceIndex,
            String expectedSha256,
            int versionCode) {
        DownloadManager.Request request = new DownloadManager.Request(
                Uri.parse(urls.get(sourceIndex)))
                .setTitle(context.getString(R.string.app_name))
                .setDescription(context.getString(R.string.update_downloading))
                .setMimeType(APK_MIME)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        long id = context.getSystemService(DownloadManager.class).enqueue(request);
        preferences(context).edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_DOWNLOAD_URLS, new JSONArray(urls).toString())
                .putInt(KEY_DOWNLOAD_SOURCE_INDEX, sourceIndex)
                .putString(KEY_DOWNLOAD_SHA256, expectedSha256)
                .putInt(KEY_DOWNLOAD_VERSION_CODE, versionCode)
                .remove(KEY_READY_DOWNLOAD_ID)
                .remove(KEY_READY_VERSION_CODE)
                .commit();
        return id;
    }

    private DownloadProgress queryProgress(Context context, long downloadId) {
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        try (android.database.Cursor cursor = manager.query(
                new DownloadManager.Query().setFilterById(downloadId))) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_STATUS));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            float fraction = total > 0L
                    ? Math.max(0.0f, Math.min(1.0f, downloaded / (float) total)) : -1.0f;
            return new DownloadProgress(status, fraction);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private UpdateInfo fetch(String source) throws Exception {
        String separator = source.contains("?") ? "&" : "?";
        HttpURLConnection connection = (HttpURLConnection) new URL(
                source + separator + "t=" + System.currentTimeMillis()).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "MijiaPanel/" + BuildConfig.VERSION_CODE);
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IllegalStateException("HTTP " + responseCode);
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            JSONObject json = new JSONObject(body.toString());
            if ("base64".equalsIgnoreCase(json.optString("encoding"))) {
                byte[] decoded = Base64.decode(json.getString("content"), Base64.DEFAULT);
                json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            }
            if (json.has("tag_name") && json.has("assets")) {
                return parseGitHubRelease(json);
            }
            return parse(json);
        } finally {
            connection.disconnect();
        }
    }

    private UpdateInfo parse(JSONObject json) {
        String tag = json.optString("tag");
        String versionName = json.optString("versionName");
        if (versionName.isEmpty()) {
            versionName = json.optString("version");
        }
        if (versionName.isEmpty()) {
            versionName = tag.startsWith("v") ? tag.substring(1) : tag;
        }
        int versionCode = json.optInt("versionCode", -1);
        if (versionCode <= 0 || versionName.isEmpty()) {
            throw new IllegalArgumentException("Invalid version metadata");
        }
        String releaseUrl = json.optString("releaseUrl", RELEASES_URL);
        if (!releaseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("Invalid release URL");
        }
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        JSONArray array = json.optJSONArray("apkUrls");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i);
                if (!value.isEmpty()) {
                    urls.add(value);
                }
            }
        }
        String singleUrl = json.optString("apkUrl");
        if (!singleUrl.isEmpty()) {
            urls.add(singleUrl);
        }
        for (String value : urls) {
            if (!value.startsWith("https://")) {
                throw new IllegalArgumentException("Invalid APK URL");
            }
        }
        String sha256 = json.optString("apkSha256").toLowerCase(Locale.ROOT);
        if (!sha256.isEmpty() && !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid APK SHA-256");
        }
        String title = json.optString("title", json.optString("name", tag));
        String notes = normalizeReleaseNotes(json.optString("notes"));
        return new UpdateInfo(
                versionCode, versionName, title, notes, releaseUrl,
                new ArrayList<>(urls), sha256);
    }

    private UpdateInfo parseGitHubRelease(JSONObject json) {
        String tag = json.optString("tag_name");
        int separator = tag.indexOf('-');
        if (separator <= 0 || separator >= tag.length() - 1) {
            throw new IllegalArgumentException("Release tag must be versionCode-versionName");
        }
        int versionCode;
        try {
            versionCode = Integer.parseInt(tag.substring(0, separator));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid release version code", error);
        }
        String versionName = tag.substring(separator + 1);
        JSONArray assets = json.optJSONArray("assets");
        String apkUrl = "";
        String sha256 = "";
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null || !asset.optString("name").endsWith(".apk")) {
                    continue;
                }
                apkUrl = asset.optString("browser_download_url");
                String digest = asset.optString("digest");
                if (digest.startsWith("sha256:")) {
                    sha256 = digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
                }
                break;
            }
        }
        List<String> apkUrls = new ArrayList<>();
        if (!apkUrl.isEmpty()) {
            apkUrls.add("https://gh-proxy.com/" + apkUrl);
            apkUrls.add("https://ghfast.top/" + apkUrl);
            apkUrls.add("https://ghproxy.net/" + apkUrl);
            apkUrls.add(apkUrl);
        }
        return new UpdateInfo(
                versionCode,
                versionName,
                json.optString("name", "MijiaPanel " + versionName),
                normalizeReleaseNotes(json.optString("body")),
                json.optString("html_url", RELEASES_URL),
                apkUrls,
                sha256);
    }

    private String normalizeReleaseNotes(String notes) {
        StringBuilder result = new StringBuilder();
        for (String line : notes.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(trimmed.startsWith("- ")
                    ? "\u2022 " + trimmed.substring(2) : trimmed);
        }
        return result.toString();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                UPDATE_PREFERENCES, Context.MODE_PRIVATE);
    }

    private List<String> readUrls(String value) {
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(value == null ? "[]" : value);
            for (int i = 0; i < array.length(); i++) {
                String url = array.optString(i);
                if (!url.isEmpty()) {
                    result.add(url);
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    public static final class UpdateInfo {
        public final int versionCode;
        public final String versionName;
        public final String title;
        public final String notes;
        public final String releaseUrl;
        public final List<String> apkUrls;
        public final String apkSha256;

        UpdateInfo(
                int versionCode,
                String versionName,
                String title,
                String notes,
                String releaseUrl,
                List<String> apkUrls,
                String apkSha256) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.title = title;
            this.notes = notes;
            this.releaseUrl = releaseUrl;
            this.apkUrls = apkUrls;
            this.apkSha256 = apkSha256;
        }
    }

    public static final class DownloadProgress {
        public final int status;
        public final float fraction;

        DownloadProgress(int status, float fraction) {
            this.status = status;
            this.fraction = fraction;
        }

        public boolean isActive() {
            return status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED;
        }

        public boolean isSuccessful() {
            return status == DownloadManager.STATUS_SUCCESSFUL;
        }
    }
}
