package com.daxiaamu.mijiapanel.update;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.daxiaamu.mijiapanel.R;
import java.io.FileInputStream;
import java.security.MessageDigest;

public final class UpdateInstaller {
    private static final String CHANNEL_ID = "app_update_install";
    private static final int NOTIFICATION_ID = 0x4D50;

    private UpdateInstaller() {
    }

    public static boolean isSuccessful(Context context, long downloadId) {
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        try (android.database.Cursor cursor = manager.query(
                new DownloadManager.Query().setFilterById(downloadId))) {
            return cursor.moveToFirst()
                    && cursor.getInt(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                    && manager.getUriForDownloadedFile(downloadId) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isVerified(
            Context context, long downloadId, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isEmpty()) {
            return true;
        }
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        try (ParcelFileDescriptor descriptor = manager.openDownloadedFile(downloadId);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) {
                actual.append(String.format("%02x", value & 0xff));
            }
            return actual.toString().equalsIgnoreCase(expectedSha256);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void showInstallNotification(Context context, long downloadId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH));
        }
        PendingIntent action = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                UpdateInstallActivity.intent(context, downloadId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.update_download_ready))
                .setContentIntent(action)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    public static boolean launchInstaller(Activity activity, long downloadId) {
        DownloadManager manager = activity.getSystemService(DownloadManager.class);
        android.net.Uri uri = manager.getUriForDownloadedFile(downloadId);
        if (uri == null) {
            return false;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, AppUpdater.APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            activity.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

}
