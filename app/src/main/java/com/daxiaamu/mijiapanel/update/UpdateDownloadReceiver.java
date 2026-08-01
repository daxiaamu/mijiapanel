package com.daxiaamu.mijiapanel.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }
        long expected = context.getSharedPreferences(
                AppUpdater.UPDATE_PREFERENCES, Context.MODE_PRIVATE)
                .getLong(AppUpdater.KEY_DOWNLOAD_ID, -1L);
        long completed = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (expected < 0L || completed != expected) {
            return;
        }
        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                AppUpdater updater = new AppUpdater();
                boolean verified = UpdateInstaller.isSuccessful(context, completed)
                        && UpdateInstaller.isVerified(
                        context, completed, updater.expectedSha256(context));
                if (verified) {
                    AppUpdater.markReady(context, completed);
                    UpdateInstaller.showInstallNotification(context, completed);
                } else {
                    updater.retryNextDownload(context, completed);
                }
            } finally {
                pendingResult.finish();
            }
        }, "MijiaPanel-update-verify").start();
    }
}
