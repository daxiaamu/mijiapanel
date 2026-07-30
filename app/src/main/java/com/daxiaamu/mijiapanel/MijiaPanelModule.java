package com.daxiaamu.mijiapanel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.wrap.DexMethod;

/**
 * Modern Xposed API 102 entry point.
 *
 * Xiaomi Home already contains a complete pad UI. This module exposes and
 * enables that native UI instead of replacing or patching Xiaomi resources.
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
    private static final String COMPAT_PREFS = "compatibility";
    private static final String CACHE_VERSION_CODE = "dexkit_version_code";
    private static final String CACHE_UPDATE_TIME = "dexkit_update_time";
    private static final String CACHE_PAD_METHOD = "dexkit_pad_method";
    private static final int TARGET_SHORTEST_DP = 600;
    private static final int MIN_DENSITY_DPI = 240;

    private volatile Context targetContext;
    private volatile CompatibilityProfile compatibilityProfile = CompatibilityProfile.UNKNOWN;
    private final AtomicBoolean compatibilityHooksInstalled = new AtomicBoolean();

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
                        ensureCompatibilityHooks(loader, base);
                        // Never replace the Application context. A process-wide
                        // tablet override breaks the normal page after pad-mode exit.
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

    private void ensureCompatibilityHooks(ClassLoader loader, Context context) {
        if (compatibilityHooksInstalled.get()) {
            return;
        }

        long versionCode = getTargetVersionCode(context);
        CompatibilityProfile profile = CompatibilityProfile.forVersion(versionCode);
        if (!compatibilityHooksInstalled.compareAndSet(false, true)) {
            return;
        }

        compatibilityProfile = profile;
        if (!profile.known) {
            log(Log.WARN, TAG, "Unknown Xiaomi Home versionCode " + versionCode
                    + "; searching for the tablet check by DEX structure");
            hookDiscoveredTabletCheck(loader, context, versionCode);
            return;
        }

        log(Log.INFO, TAG, "Using Xiaomi Home " + profile.versionName
                + " compatibility profile");
        boolean coreHooked = hookBooleanTrue(
                loader,
                profile.coreClass,
                profile.coreMethod,
                "mijia-panel.is-pad-core");
        hookBooleanTrue(
                loader,
                profile.utilityClass,
                profile.utilityMethod,
                "mijia-panel.is-pad-activity");
        if (!coreHooked) {
            hookDiscoveredTabletCheck(loader, context, versionCode);
        }
    }

    private boolean hookBooleanTrue(
            ClassLoader loader, String className, String methodName, String hookId) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            Method method = type.getDeclaredMethod(methodName);
            hookBooleanTrue(method, hookId);
            return true;
        } catch (Throwable error) {
            logFailure("Unable to hook " + className + "#" + methodName, error);
            return false;
        }
    }

    private void hookBooleanTrue(Method method, String hookId) throws NoSuchMethodException {
        if (!Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != boolean.class
                || method.getParameterTypes().length != 0) {
            throw new NoSuchMethodException(method + " is not static boolean()");
        }
        method.setAccessible(true);
        hook(method)
                .setId(hookId)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept(chain -> isPadModeEnabled(targetContext)
                        ? true
                        : chain.proceed());
    }

    private void hookDiscoveredTabletCheck(
            ClassLoader loader, Context context, long versionCode) {
        try {
            long updateTime = getTargetUpdateTime(context);
            Method cached = readCachedPadMethod(loader, versionCode, updateTime);
            if (cached != null) {
                hookBooleanTrue(cached, "mijia-panel.is-pad-dexkit");
                log(Log.INFO, TAG, "Using cached tablet check " + cached);
                return;
            }

            System.loadLibrary("dexkit");
            String sourceDir = context.getApplicationInfo().sourceDir;
            try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
                MethodMatcher matcher = MethodMatcher.create()
                        .modifiers(Modifier.STATIC)
                        .returnType("boolean")
                        .paramCount(0)
                        .usingEqStrings("developer_setting", "force_not_pad")
                        .usingNumbers(530.0f, 1.8f);
                MethodDataList matches = bridge.findMethod(
                        FindMethod.create()
                                .searchPackages("_m_j")
                                .matcher(matcher));
                if (matches.isEmpty()) {
                    // Keep the semantic strings and signature as the required
                    // identity, but tolerate future package or threshold changes.
                    matches = bridge.findMethod(
                            FindMethod.create()
                                    .matcher(MethodMatcher.create()
                                            .modifiers(Modifier.STATIC)
                                            .returnType("boolean")
                                            .paramCount(0)
                                            .usingEqStrings(
                                                    "developer_setting",
                                                    "force_not_pad")));
                }
                if (matches.size() != 1) {
                    log(Log.WARN, TAG, "DEX tablet-check search returned "
                            + matches.size() + " candidates; refusing an ambiguous hook");
                    return;
                }

                MethodData match = matches.get(0);
                Method method = match.getMethodInstance(loader);
                hookBooleanTrue(method, "mijia-panel.is-pad-dexkit");
                cachePadMethod(match.getDescriptor(), versionCode, updateTime);
                log(Log.INFO, TAG, "Discovered tablet check " + match.getDescriptor());
            }
        } catch (Throwable error) {
            logFailure("Unable to discover the tablet check", error);
        }
    }

    private Method readCachedPadMethod(
            ClassLoader loader, long versionCode, long updateTime) {
        try {
            SharedPreferences preferences = getRemotePreferences(COMPAT_PREFS);
            if (preferences.getLong(CACHE_VERSION_CODE, -1) != versionCode
                    || preferences.getLong(CACHE_UPDATE_TIME, -1) != updateTime) {
                return null;
            }
            String descriptor = preferences.getString(CACHE_PAD_METHOD, null);
            if (descriptor == null || descriptor.isEmpty()) {
                return null;
            }
            Method method = new DexMethod(descriptor).getMethodInstance(loader);
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != boolean.class
                    || method.getParameterTypes().length != 0) {
                return null;
            }
            return method;
        } catch (Throwable error) {
            logFailure("Unable to read the DEX compatibility cache", error);
            return null;
        }
    }

    private void cachePadMethod(String descriptor, long versionCode, long updateTime) {
        try {
            getRemotePreferences(COMPAT_PREFS)
                    .edit()
                    .putLong(CACHE_VERSION_CODE, versionCode)
                    .putLong(CACHE_UPDATE_TIME, updateTime)
                    .putString(CACHE_PAD_METHOD, descriptor)
                    .apply();
        } catch (Throwable error) {
            logFailure("Unable to save the DEX compatibility cache", error);
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
                        ensureCentralControlItem((View) chain.getArg(0), loader);
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
            Class<?> itemClass = Class.forName(
                    "com.xiaomi.smarthome.miio.page.ItemOptionView",
                    false,
                    loader);
            CompatibilityProfile profile = compatibilityProfile;
            View entry = null;

            if (profile.known) {
                int entryId = context.getResources().getIdentifier(
                        profile.entryId, "id", TARGET_PACKAGE);
                View exactEntry = entryId != 0 ? root.findViewById(entryId) : null;
                if (itemClass.isInstance(exactEntry)) {
                    entry = exactEntry;
                }
            }

            if (entry == null) {
                entry = findSemanticCentralControlEntry(root, itemClass);
            }
            if (entry == null) {
                log(Log.WARN, TAG, "Central-control entry was not found safely");
                return;
            }

            entry.setVisibility(View.VISIBLE);
            Class<?> modeActivity = Class.forName(MODE_ACTIVITY, false, loader);
            entry.setOnClickListener(view -> {
                Context clickContext = view.getContext();
                clickContext.startActivity(new Intent(clickContext, modeActivity));
            });
        } catch (Throwable error) {
            logFailure("Unable to expose central-control entry", error);
        }
    }

    private static View findSemanticCentralControlEntry(View root, Class<?> itemClass) {
        List<View> matches = new ArrayList<>(2);
        collectSemanticCentralControlEntries(root, itemClass, matches);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static void collectSemanticCentralControlEntries(
            View view, Class<?> itemClass, List<View> matches) {
        if (view == null || matches.size() > 1) {
            return;
        }
        if (itemClass.isInstance(view) && containsCentralControlText(view)) {
            matches.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectSemanticCentralControlEntries(
                        group.getChildAt(index), itemClass, matches);
            }
        }
    }

    private static boolean containsCentralControlText(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                String normalized = text.toString().toLowerCase(Locale.ROOT);
                if (normalized.contains("中控")
                        || normalized.contains("control panel")
                        || normalized.contains("central control")) {
                    return true;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsCentralControlText(group.getChildAt(index))) {
                    return true;
                }
            }
        }
        return false;
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
     * 600 dp. The change is isolated to Xiaomi Home's activity contexts.
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

    private static long getTargetVersionCode(Context context) {
        if (context == null) {
            return -1;
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static long getTargetUpdateTime(Context context) {
        if (context == null) {
            return -1;
        }
        try {
            return context.getPackageManager()
                    .getPackageInfo(TARGET_PACKAGE, 0)
                    .lastUpdateTime;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void logFailure(String message, Throwable error) {
        log(Log.ERROR, TAG, message, error);
    }

    private static final class CompatibilityProfile {
        private static final String UTILITY_CLASS =
                "com.xiaomi.smarthome.utils.OooO00o";
        private static final CompatibilityProfile UNKNOWN =
                new CompatibilityProfile(-1, "unknown", null, null, null, null, false);
        private static final CompatibilityProfile[] KNOWN = {
                new CompatibilityProfile(
                        11051705L, "11.5.705", "dcf",
                        "_m_j.jy7", "Oooo0O0", "OooOOO0", true),
                new CompatibilityProfile(
                        110615011L, "11.6.501", "de_",
                        "_m_j.g14", "OooOOO", "OooOOo", true),
                new CompatibilityProfile(
                        110616251L, "11.6.625", "dec",
                        "_m_j.g09", "OooOooo", "OooOOO0", true),
                new CompatibilityProfile(
                        110617011L, "11.6.701", "dec",
                        "_m_j.xz8", "OooOooo", "OooOOO0", true),
                new CompatibilityProfile(
                        110617031L, "11.6.703", "rl_pad_mode",
                        "_m_j.n84", "OooOOoo", "OooOoOO", true),
                new CompatibilityProfile(
                        110617051L, "11.6.705", "dec",
                        "_m_j.yz8", "OooOooo", "OooOOO0", true)
        };

        private final long versionCode;
        private final String versionName;
        private final String entryId;
        private final String coreClass;
        private final String coreMethod;
        private final String utilityClass;
        private final String utilityMethod;
        private final boolean known;

        private CompatibilityProfile(
                long versionCode,
                String versionName,
                String entryId,
                String coreClass,
                String coreMethod,
                String utilityMethod,
                boolean known) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.entryId = entryId;
            this.coreClass = coreClass;
            this.coreMethod = coreMethod;
            this.utilityClass = UTILITY_CLASS;
            this.utilityMethod = utilityMethod;
            this.known = known;
        }

        private static CompatibilityProfile forVersion(long versionCode) {
            for (CompatibilityProfile profile : KNOWN) {
                if (profile.versionCode == versionCode) {
                    return profile;
                }
            }
            return UNKNOWN;
        }
    }
}
