package com.daxiaamu.mijiapanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Closed failure surface used when the installed module no longer matches its signer. */
public final class IntegrityFailureActivity extends Activity {
    private AlertDialog failureDialog;

    static void open(Context context) {
        Intent intent = new Intent(context, IntegrityFailureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setFinishOnTouchOutside(false);
        setContentView(createFallbackSurface());
        showFailureDialog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (failureDialog == null || !failureDialog.isShowing()) {
            showFailureDialog();
        }
    }

    @Override
    public void onBackPressed() {
        // The integrity failure surface can only be left through the explicit Exit action.
    }

    private LinearLayout createFallbackSurface() {
        int padding = Math.round(32.0f * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.integrity_failure_title);
        title.setTextSize(22.0f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(this);
        message.setText(R.string.integrity_failure_message);
        message.setTextSize(16.0f);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.setMargins(0, padding / 2, 0, padding);
        root.addView(message, messageParams);

        Button exit = new Button(this);
        exit.setText(R.string.integrity_failure_exit);
        exit.setOnClickListener(view -> exitApplication());
        root.addView(exit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void showFailureDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        failureDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.integrity_failure_title)
                .setMessage(R.string.integrity_failure_message)
                .setCancelable(false)
                .setPositiveButton(R.string.integrity_failure_exit,
                        (dialog, which) -> exitApplication())
                .create();
        failureDialog.setCanceledOnTouchOutside(false);
        failureDialog.setOnDismissListener(dialog -> {
            if (!isFinishing() && !isDestroyed()) {
                getWindow().getDecorView().post(this::showFailureDialog);
            }
        });
        failureDialog.show();
    }

    private void exitApplication() {
        if (failureDialog != null) {
            failureDialog.setOnDismissListener(null);
            failureDialog.dismiss();
        }
        finishAndRemoveTask();
        Process.killProcess(Process.myPid());
    }
}
