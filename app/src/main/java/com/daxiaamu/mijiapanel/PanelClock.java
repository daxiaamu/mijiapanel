package com.daxiaamu.mijiapanel;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Locale;

/** A window-local clock that moves together with the panel's content. */
final class PanelClock {
    private static final String TAG = "com.daxiaamu.mijiapanel.panelClock";
    private static final int MIN_HEADER_WIDTH_DP = 560;
    private static final int HOME_SELECTOR_RESERVE_DP = 192;
    private static final int ACTIONS_RESERVE_DP = 120;

    private PanelClock() {}

    static void install(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        View root = activity.findViewById(android.R.id.content);
        if (!(root instanceof FrameLayout) || root.findViewWithTag(TAG) != null) {
            return;
        }
        FrameLayout content = (FrameLayout) root;
        LinearLayout clock = new LinearLayout(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // Collapsing on narrow windows keeps the native header actions accessible.
                if (View.MeasureSpec.getSize(widthMeasureSpec)
                        < dp(this, MIN_HEADER_WIDTH_DP - HOME_SELECTOR_RESERVE_DP - ACTIONS_RESERVE_DP)) {
                    setMeasuredDimension(0, 0);
                    return;
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        clock.setTag(TAG);
        clock.setOrientation(LinearLayout.HORIZONTAL);
        clock.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        clock.setClipChildren(true);

        Configuration configuration = content.getResources().getConfiguration();
        Locale locale = configuration.getLocales().get(0);
        TextClock time = new TextClock(activity);
        time.setTextLocale(locale);
        time.setFormat12Hour(DateFormat.getBestDateTimePattern(locale, "hma"));
        time.setFormat24Hour(DateFormat.getBestDateTimePattern(locale, "Hm"));
        time.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        time.setTextSize(34);
        time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        time.setFontFeatureSettings("tnum");
        time.setSingleLine();
        clock.addView(time, new LinearLayout.LayoutParams(-2, -2));

        TextClock date = new TextClock(activity);
        String datePattern = "zh".equals(locale.getLanguage())
                ? "M月d日 EEEE"
                : DateFormat.getBestDateTimePattern(locale, "MMMEd");
        date.setTextLocale(locale);
        date.setFormat12Hour(datePattern);
        date.setFormat24Hour(datePattern);
        date.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        date.setTextSize(22);
        date.setAlpha(0.9f);
        date.setSingleLine();
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(-2, -2);
        dateParams.setMarginStart(dp(content, 12));
        clock.addView(date, dateParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                -1, dp(content, 52), Gravity.TOP | Gravity.LEFT);
        params.topMargin = dp(content, 24);
        // Reserve both sides for the home selector and the notification/exit buttons.
        params.leftMargin = dp(content, HOME_SELECTOR_RESERVE_DP);
        params.rightMargin = dp(content, ACTIONS_RESERVE_DP);
        content.addView(clock, params);
        ViewTreeObserver.OnGlobalLayoutListener align = () -> {
            if (clock.getWidth() == 0) {
                return;
            }
            int[] contentOrigin = new int[2];
            content.getLocationInWindow(contentOrigin);
            int[] position = new int[2];
            TextView title = findHomeTitle(content, clock, contentOrigin, position);
            if (title != null) {
                Rect bounds = new Rect();
                int bottom = inkBottom(title, position, bounds);
                alignBottom(time, bottom, position, bounds);
                alignBottom(date, bottom, position, bounds);
            }
        };
        clock.getViewTreeObserver().addOnGlobalLayoutListener(align);
        clock.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {}

            @Override
            public void onViewDetachedFromWindow(View view) {
                view.getViewTreeObserver().removeOnGlobalLayoutListener(align);
                view.removeOnAttachStateChangeListener(this);
            }
        });
    }

    private static TextView findHomeTitle(
            View view, View clock, int[] origin, int[] position) {
        if (view == clock || view.getVisibility() != View.VISIBLE
                || view.getWidth() == 0 || view.getHeight() == 0) {
            return null;
        }
        view.getLocationInWindow(position);
        int x = position[0] - origin[0];
        int y = position[1] - origin[1];
        // The title is the clickable label in the upper-left header, above device cards.
        // Check geometry rather than an obfuscated resource ID or a home name.
        if (x >= dp(view, 96) || y >= dp(view, 62)) {
            return null;
        }
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            if (view.isClickable() && y >= 0 && label.getText().length() != 0
                    && label.getTextSize() >= dp(view, 24)) {
                return label;
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView title = findHomeTitle(group.getChildAt(i), clock, origin, position);
                if (title != null) {
                    return title;
                }
            }
        }
        return null;
    }

    private static int inkBottom(TextView label, int[] position, Rect bounds) {
        String text = label.getText().toString();
        label.getPaint().getTextBounds(text, 0, text.length(), bounds);
        label.getLocationInWindow(position);
        return position[1] + label.getBaseline() + bounds.bottom;
    }

    private static void alignBottom(TextView label, int bottom, int[] position, Rect bounds) {
        int delta = bottom - inkBottom(label, position, bounds);
        if (delta != 0) {
            label.setTranslationY(label.getTranslationY() + delta);
        }
    }

    private static int dp(View view, int size) {
        return Math.round(size * view.getResources().getDisplayMetrics().density);
    }
}
