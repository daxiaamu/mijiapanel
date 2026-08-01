package com.daxiaamu.mijiapanel.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class UpdateInstallActivity extends Activity {
    private static final String EXTRA_DOWNLOAD_ID = "download_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        long downloadId = getIntent().getLongExtra(
                EXTRA_DOWNLOAD_ID, AppUpdater.readyDownload(this));
        if (downloadId < 0L) {
            finish();
            return;
        }
        UpdateInstaller.launchInstaller(this, downloadId);
        finish();
    }

    public static Intent intent(Context context, long downloadId) {
        return new Intent(context, UpdateInstallActivity.class)
                .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    public static void open(Context context, long downloadId) {
        context.startActivity(intent(context, downloadId));
    }
}
