package com.daxiaamu.mijiapanel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Modern Xposed API 102 entry point.
 *
 * The target APK (Xiaomi Home 11.5.705) already contains a complete pad UI. This
 * module enables that UI instead of replacing or patching any Xiaomi resources.
 */
public final class MijiaPanelModule extends XposedModule {
    private static final String TAG = "MijiaPanel";
    private static final String TARGET_PACKAGE = "com.xiaomi.smarthome";
    private static final String PAD_MAIN = "com.xiaomi.smarthome.pad.MainActivity";
    private static final String MODE_ACTIVITY =
            "com.xiaomi.smarthome.pad.settings.ModeActivity";
    private static final String SETTINGS_FRAGMENT =
            "com.xiaomi.smarthome.miio.page.SettingMainPageV2";
    private static final String PAD_PREFS = "pad_mode";
    private static final String PAD_ENABLED = "pad_mode_enable";
    private static final int TARGET_SHORTEST_DP = 600;
    private static final int MIN_DENSITY_DPI = 240;

    private volatile Context targetContext;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded with modern Xposed API " + getApiVersion());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        ClassLoader loader = param.getClassLoader();
        captureApplicationContext(loader);
        hookActivityContexts(loader);
        hookTabletChecks(loader);
        hookCentralControlEntry(loader);
        hookPadStatusBar(loader);
    }

    private void captureApplicationContext(ClassLoader loader) {
        try {
            Class<?> appClass = Class.forName(
                    "com.xiaomi.smarthome.application.SHApplication", false, loader);
            Method attach = appClass.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setId("mijia-panel.application-context")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(chain -> {
                        Context base = (Context) chain.getArg(0);
                        targetContext = base;
                        // Never replace the Application context. A process-wide
                        // tablet override breaks Xiaomi Home's normal page after
                        // the user exits central-control mode.
                        return chain.proceed();
                    });
        } catch (Throwable error) {
            logFailure("Unable to hook SHApplication.attachBaseContext", error);
        }
    }

    private void hookActivityContexts(ClassLoader loader) {
        try {
            Class<?> commonActivity = Class.forName(
                    "com.xiaomi.smarthome.framework.page.CommonActivity", false, loader);
            Method attach = commonActivity.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setId("mijia-panel.activity-context")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(chain -> {
                        Context base = (Context) chain.getArg(0);
                        Context applicationContext = base.getApplicationContext();
                        targetContext = applicationContext != null ? applicationContext : base;
                        Context effectiveContext = isPadModeEnabled(base)
                                ? makeTabletContext(base)
                                : base;
                        return chain.proceed(new Object[]{effectiveContext});
                    });
        } catch (Throwable error) {
            logFailure("Unable to hook CommonActivity.attachBaseContext", error);
        }
    }

    private void hookTabletChecks(ClassLoader loader) {
        hookBooleanTrue(loader, "_m_j.jy7", "Oooo0O0", "mijia-panel.is-pad-core");
        hookBooleanTrue(
                loader,
                "com.xiaomi.smarthome.utils.OooO00o",
                "OooOOO0",
                "mijia-panel.is-pad-activity");
    }

    private void hookBooleanTrue(
            ClassLoader loader, String className, String methodName, String hookId) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            Method method = type.getDeclaredMethod(methodName);
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != boolean.class) {
                throw new NoSuchMethodException(className + "#" + methodName
                        + " is not static boolean()");
            }
            method.setAccessible(true);
            hook(method)
                    .setId(hookId)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(chain -> isPadModeEnabled(targetContext)
                            ? true
                            : chain.proceed());
        } catch (Throwable error) {
            logFailure("Unable to hook " + className + "#" + methodName, error);
        }
    }

    private void hookCentralControlEntry(ClassLoader loader) {
        try {
            Class<?> settingsClass = Class.forName(SETTINGS_FRAGMENT, false, loader);
            Method onViewCreated =
                    settingsClass.getDeclaredMethod("onViewCreated", View.class, Bundle.class);
            onViewCreated.setAccessible(true);
            hook(onViewCreated)
                    .setId("mijia-panel.central-control-entry")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        ensureCentralControlItem(
                                (View) chain.getArg(0),
                                loader);
                        return result;
                    });
        } catch (Throwable error) {
            logFailure("Unable to install central-control entry hook", error);
        }
    }

    private void ensureCentralControlItem(View root, ClassLoader loader) {
        if (root == null) {
            return;
        }
        try {
            Context context = root.getContext();
            int entryId = context.getResources().getIdentifier(
                    "dcf", "id", TARGET_PACKAGE);
            View entry = entryId != 0 ? root.findViewById(entryId) : null;

            if (entry == null) {
                int containerId = context.getResources().getIdentifier(
                        "doo", "id", TARGET_PACKAGE);
                View containerView = containerId != 0
                        ? root.findViewById(containerId)
                        : null;
                if (!(containerView instanceof ViewGroup)) {
                    return;
                }

                Class<?> itemClass = Class.forName(
                        "com.xiaomi.smarthome.miio.page.ItemOptionView",
                        false,
                        loader);
                entry = (View) itemClass.getConstructor(Context.class).newInstance(context);
                itemClass.getMethod("setTitle", CharSequence.class)
                        .invoke(entry, "全屋中控");
                itemClass.getMethod("setSubTitle", CharSequence.class)
                        .invoke(entry, "手动进入中控模式");
                if (entryId != 0) {
                    entry.setId(entryId);
                }
                ((ViewGroup) containerView).addView(entry);
            }

            entry.setVisibility(View.VISIBLE);
            Class<?> modeActivity = Class.forName(MODE_ACTIVITY, false, loader);
            entry.setOnClickListener(view -> {
                Context clickContext = view.getContext();
                clickContext.startActivity(new Intent(clickContext, modeActivity));
            });
        } catch (Throwable error) {
            logFailure("Unable to add central-control entry", error);
        }
    }

    private void hookPadStatusBar(ClassLoader loader) {
        try {
            Class<?> padActivity = Class.forName(PAD_MAIN, false, loader);
            Method onCreate = padActivity.getDeclaredMethod("onCreate", Bundle.class);
            onCreate.setAccessible(true);
            hook(onCreate)
                    .setId("mijia-panel.pad-status-bar-on-create")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        hideStatusBar((Activity) chain.getThisObject());
                        return result;
                    });

            Method onResume = padActivity.getDeclaredMethod("onResume");
            onResume.setAccessible(true);
            hook(onResume)
                    .setId("mijia-panel.pad-status-bar-on-resume")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        hideStatusBar((Activity) chain.getThisObject());
                        return result;
                    });
        } catch (Throwable error) {
            logFailure("Unable to install pad status-bar hooks", error);
        }
    }

    private static void hideStatusBar(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Window window = activity.getWindow();
        window.addFlags(WindowManagerFlags.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /**
     * Keeps the Android constant isolated so this class does not need to import
     * android.view.WindowManager solely for one flag.
     */
    private static final class WindowManagerFlags {
        private static final int FLAG_FULLSCREEN = 0x00000400;

        private WindowManagerFlags() {
        }
    }

    /**
     * A phone must expose more dp, not a larger numeric density value, to select
     * tablet resources. For a 1080 px short edge this computes 288 dpi, yielding
     * 600 dp. The change is isolated to Xiaomi Home's contexts.
     */
    private static Context makeTabletContext(Context base) {
        if (base == null) {
            return null;
        }
        DisplayMetrics metrics = base.getResources().getDisplayMetrics();
        Configuration current = base.getResources().getConfiguration();
        int shortPixels = Math.min(metrics.widthPixels, metrics.heightPixels);
        int longPixels = Math.max(metrics.widthPixels, metrics.heightPixels);
        if (shortPixels <= 0 || longPixels <= 0) {
            return base;
        }

        int originalDpi = current.densityDpi > 0 ? current.densityDpi : metrics.densityDpi;
        int targetDpi = Math.round(shortPixels * DisplayMetrics.DENSITY_DEFAULT
                / (float) TARGET_SHORTEST_DP);
        targetDpi = Math.max(MIN_DENSITY_DPI, Math.min(originalDpi, targetDpi));

        Configuration override = new Configuration(current);
        override.densityDpi = targetDpi;
        int shortDp = Math.round(shortPixels * DisplayMetrics.DENSITY_DEFAULT
                / (float) targetDpi);
        int longDp = Math.round(longPixels * DisplayMetrics.DENSITY_DEFAULT
                / (float) targetDpi);
        override.smallestScreenWidthDp = shortDp;
        if (current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            override.screenWidthDp = longDp;
            override.screenHeightDp = shortDp;
        } else {
            override.screenWidthDp = shortDp;
            override.screenHeightDp = longDp;
        }
        return base.createConfigurationContext(override);
    }

    private static boolean isPadModeEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return context.getSharedPreferences(PAD_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PAD_ENABLED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void logFailure(String message, Throwable error) {
        log(Log.ERROR, TAG, message, error);
    }
}
