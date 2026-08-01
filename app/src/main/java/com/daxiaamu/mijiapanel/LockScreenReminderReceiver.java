package com.daxiaamu.mijiapanel;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

/** Reminds dedicated-panel users that a swipe/PIN screen blocks direct auto-wake. */
public final class LockScreenReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "lock_screen_reminder";
    private static final int NOTIFICATION_ID = 2102;

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean enabled = context.getSharedPreferences(
                        BrightnessSettings.PREFERENCES,
                        Context.MODE_PRIVATE)
                .getBoolean(
                        BrightnessSettings.PRESENCE_DETECTION,
                        BrightnessSettings.DEFAULT_PRESENCE_DETECTION);
        if (!enabled) {
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.lock_screen_reminder_channel),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent appSettings = new Intent(context, SettingsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                appSettings,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent securitySettings = new Intent(Settings.ACTION_SECURITY_SETTINGS);
        PendingIntent securitySettingsIntent = PendingIntent.getActivity(
                context,
                1,
                securitySettings,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(
                NOTIFICATION_ID,
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                        .setContentTitle(context.getString(R.string.lock_screen_reminder_title))
                        .setContentText(context.getString(R.string.lock_screen_reminder_summary))
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(
                                context.getString(R.string.lock_screen_reminder_summary)))
                        .setContentIntent(pendingIntent)
                        .addAction(
                                android.R.drawable.ic_menu_manage,
                                context.getString(R.string.open_lock_screen_settings),
                                securitySettingsIntent)
                        .setAutoCancel(true)
                        .build());
    }
}
