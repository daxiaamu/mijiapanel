package com.daxiaamu.mijiapanel;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;

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
    private static final String SYSTEM_SCOPE_PACKAGE = "system";
    private static final String SYSTEM_CONTEXT_PACKAGE = "android";
    private static final String MODULE_PACKAGE = "com.daxiaamu.mijiapanel";
    private static final String PRESENCE_SERVICE_CLASS =
            "com.daxiaamu.mijiapanel.PresenceDetectionService";
    private static final String SYSTEM_BRIDGE_ACTION = BrightnessSettings.SYSTEM_BRIDGE_ACTION;
    private static final String EXTRA_SYSTEM_BRIDGE_COMMAND =
            BrightnessSettings.EXTRA_SYSTEM_BRIDGE_COMMAND;
    private static final String EXTRA_SYSTEM_BRIDGE_TOKEN =
            BrightnessSettings.EXTRA_SYSTEM_BRIDGE_TOKEN;
    private static final int SYSTEM_BRIDGE_START =
            BrightnessSettings.SYSTEM_BRIDGE_COMMAND_START;
    private static final int SYSTEM_BRIDGE_STOP = 2;
    private static final int SYSTEM_BRIDGE_GO_TO_SLEEP =
            BrightnessSettings.SYSTEM_BRIDGE_COMMAND_GO_TO_SLEEP;
    private static final int SYSTEM_BRIDGE_WAKE_UP =
            BrightnessSettings.SYSTEM_BRIDGE_COMMAND_WAKE_UP;
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
    private static final String PAD_PACKAGE_PREFIX = "com.xiaomi.smarthome.pad.";
    private static final int TARGET_SHORTEST_DP = 600;
    private static final int MIN_DENSITY_DPI = 240;
    private static final long PANEL_PAUSE_STATE_DELAY_MS = 500L;
    private static final long BURN_IN_SHIFT_INTERVAL_MS = BuildConfig.BURN_IN_SHIFT_INTERVAL_MS;
    private static final int BURN_IN_SHIFT_STEP_PX = 4;
    private static final int BURN_IN_SHIFT_RADIUS_STEPS = 4;
    private static final String DEBUG_BURN_IN_STATUS_ACTION =
            "com.daxiaamu.mijiapanel.action.DEBUG_BURN_IN_STATUS";
    private static final String PAD_WAKE_LOCK_TAG = "smarthome:pow_sh_pad";

    private volatile Context targetContext;
    private volatile CompatibilityProfile compatibilityProfile = CompatibilityProfile.UNKNOWN;
    private volatile SharedPreferences brightnessPreferences;
    private volatile WeakReference<Activity> activePadActivity = new WeakReference<>(null);
    private volatile WeakReference<PowerManager.WakeLock> padWakeLock =
            new WeakReference<>(null);
    private final Object burnInControllerLock = new Object();
    private BurnInShiftController burnInController;
    private final AtomicBoolean debugReceiverRegistered = new AtomicBoolean();
    private final AtomicBoolean presenceStateReceiverRegistered = new AtomicBoolean();
    private final AtomicBoolean systemBridgeHookInstalled = new AtomicBoolean();
    private final AtomicBoolean oplusStartupAllowanceHookInstalled = new AtomicBoolean();
    private final AtomicBoolean systemBridgeRetryScheduled = new AtomicBoolean();
    private final AtomicBoolean systemBridgeReceiverRegistered = new AtomicBoolean();
    private final Object presenceServiceBindingLock = new Object();
    private final Handler systemBridgeHandler = new Handler(Looper.getMainLooper());
    private volatile Context systemBridgeContext;
    private boolean presenceServiceDesired;
    private boolean presenceServiceBound;
    private boolean presenceServiceBinding;
    private int presenceServiceRestartAttempts;
    private final AtomicBoolean compatibilityHooksInstalled = new AtomicBoolean();
    private volatile String validatedPresenceBridgeToken;
    private final ServiceConnection presenceServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (presenceServiceBindingLock) {
                presenceServiceBinding = false;
                presenceServiceBound = true;
                presenceServiceRestartAttempts = 0;
            }
            log(Log.INFO, TAG, "System bridge bound the presence service");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            handlePresenceServiceConnectionLost("disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            handlePresenceServiceConnectionLost("binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            handlePresenceServiceConnectionLost("returned a null binding");
        }
    };
    private final Runnable presenceServiceRestart = new Runnable() {
        @Override
        public void run() {
            Context context;
            synchronized (presenceServiceBindingLock) {
                if (!presenceServiceDesired) {
                    return;
                }
                context = systemBridgeContext;
            }
            if (context != null) {
                ensurePresenceServiceAlive(context);
            }
        }
    };
    private final BroadcastReceiver presenceStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BrightnessSettings.PRESENCE_STATE_ACTION.equals(intent.getAction())) {
                return;
            }
            try {
                SharedPreferences preferences = getBrightnessPreferences();
                String expectedToken = preferences.getString(
                        BrightnessSettings.PANEL_STATE_TOKEN,
                        null);
                String receivedToken = intent.getStringExtra(
                        BrightnessSettings.EXTRA_PANEL_STATE_TOKEN);
                if (expectedToken == null || !expectedToken.equals(receivedToken)) {
                    log(Log.WARN, TAG, "Ignored presence state with invalid token");
                    return;
                }
                boolean present = intent.getBooleanExtra(
                        BrightnessSettings.EXTRA_PERSON_PRESENT,
                        false);
                boolean ready = intent.getBooleanExtra(
                        BrightnessSettings.EXTRA_PRESENCE_READY,
                        false);
                Activity activity = activePadActivity.get();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                activity.runOnUiThread(
                        () -> applyBrightnessSetting(activity, present, ready));
            } catch (Throwable error) {
                logFailure("Unable to apply explicit presence state", error);
            }
        }
    };
    private final BroadcastReceiver debugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DEBUG_BURN_IN_STATUS_ACTION.equals(intent.getAction())) {
                return;
            }
            String status;
            synchronized (burnInControllerLock) {
                status = burnInController == null
                        ? "burn-in active=false"
                        : burnInController.debugStatus();
            }
            Log.i(TAG, status);
            setResultData(status);
        }
    };
    private final BroadcastReceiver systemBridgeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!SYSTEM_BRIDGE_ACTION.equals(intent.getAction())) {
                return;
            }
            try {
                int command = intent.getIntExtra(EXTRA_SYSTEM_BRIDGE_COMMAND, 0);
                String receivedToken = intent.getStringExtra(EXTRA_SYSTEM_BRIDGE_TOKEN);
                Intent serviceIntent = new Intent()
                        .setClassName(MODULE_PACKAGE, PRESENCE_SERVICE_CLASS)
                        .putExtra(BrightnessSettings.EXTRA_SYSTEM_BRIDGE_START, true);
                if (command == BrightnessSettings.SYSTEM_BRIDGE_COMMAND_PROBE) {
                    // An APK update can recreate the module process and its session token
                    // while system_server still holds an older RemotePreferences snapshot.
                    // On Android 14+, authenticate the probe by sender UID and adopt the
                    // current token without requiring another reboot.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                            && receivedToken != null
                            && !receivedToken.isEmpty()
                            && isTrustedPresenceBridgeSender(context, getSentFromUid())) {
                        validatedPresenceBridgeToken = receivedToken;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    log(Log.INFO, TAG, "System bridge probe succeeded");
                    return;
                }
                SharedPreferences preferences = getRemotePreferences(
                        BrightnessSettings.PREFERENCES);
                String expectedToken = preferences.getString(
                        BrightnessSettings.PANEL_STATE_TOKEN,
                        null);
                boolean matchesPreferences = expectedToken != null
                        && expectedToken.equals(receivedToken);
                boolean matchesValidatedSession = validatedPresenceBridgeToken != null
                        && validatedPresenceBridgeToken.equals(receivedToken);
                if (!matchesPreferences && !matchesValidatedSession) {
                    log(Log.WARN, TAG, "Rejected presence bridge request with invalid token");
                    return;
                }
                validatedPresenceBridgeToken = receivedToken;
                if (command == SYSTEM_BRIDGE_GO_TO_SLEEP) {
                    putSystemToSleep(context);
                    return;
                }
                if (command == SYSTEM_BRIDGE_WAKE_UP) {
                    wakeSystem(context);
                    return;
                }
                if (command == SYSTEM_BRIDGE_START) {
                    boolean enabled = preferences.getBoolean(
                            BrightnessSettings.PRESENCE_DETECTION,
                            BrightnessSettings.DEFAULT_PRESENCE_DETECTION);
                    if (!enabled) {
                        return;
                    }
                    setPresenceServiceDesired(context, true);
                } else if (command == SYSTEM_BRIDGE_STOP) {
                    setPresenceServiceDesired(context, false);
                }
            } catch (Throwable error) {
                logFailure("Presence system bridge request failed", error);
            }
        }
    };

    private boolean isTrustedPresenceBridgeSender(Context context, int senderUid) {
        try {
            return senderUid == context.getPackageManager().getPackageUid(MODULE_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private Intent createPresenceServiceIntent() {
        return new Intent()
                .setClassName(MODULE_PACKAGE, PRESENCE_SERVICE_CLASS)
                .putExtra(BrightnessSettings.EXTRA_SYSTEM_BRIDGE_START, true);
    }

    private void setPresenceServiceDesired(Context context, boolean desired) {
        synchronized (presenceServiceBindingLock) {
            systemBridgeContext = context;
            presenceServiceDesired = desired;
            presenceServiceRestartAttempts = 0;
        }
        systemBridgeHandler.removeCallbacks(presenceServiceRestart);
        if (desired) {
            ensurePresenceServiceAlive(context);
            return;
        }
        boolean shouldUnbind;
        synchronized (presenceServiceBindingLock) {
            shouldUnbind = presenceServiceBound || presenceServiceBinding;
            presenceServiceBound = false;
            presenceServiceBinding = false;
        }
        if (shouldUnbind) {
            try {
                context.unbindService(presenceServiceConnection);
            } catch (Throwable error) {
                logFailure("Unable to unbind presence service", error);
            }
        }
        try {
            context.stopService(createPresenceServiceIntent());
            log(Log.INFO, TAG, "System bridge released the presence service");
        } catch (Throwable error) {
            logFailure("Unable to stop presence service", error);
        }
    }

    private void ensurePresenceServiceAlive(Context context) {
        synchronized (presenceServiceBindingLock) {
            if (!presenceServiceDesired || presenceServiceBound || presenceServiceBinding) {
                return;
            }
            presenceServiceBinding = true;
        }
        Intent serviceIntent = createPresenceServiceIntent();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            boolean accepted = context.bindService(
                    serviceIntent,
                    presenceServiceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            if (!accepted) {
                synchronized (presenceServiceBindingLock) {
                    presenceServiceBinding = false;
                }
                schedulePresenceServiceRestart();
                return;
            }
            log(Log.INFO, TAG, "System bridge started and is binding the presence service");
        } catch (Throwable error) {
            synchronized (presenceServiceBindingLock) {
                presenceServiceBinding = false;
            }
            logFailure("Unable to keep presence service alive", error);
            schedulePresenceServiceRestart();
        }
    }

    private void handlePresenceServiceConnectionLost(String reason) {
        Context context;
        synchronized (presenceServiceBindingLock) {
            presenceServiceBound = false;
            presenceServiceBinding = false;
            context = systemBridgeContext;
        }
        // A dead/null binding must be released before the same ServiceConnection can
        // establish a clean replacement connection.
        if (context != null) {
            try {
                context.unbindService(presenceServiceConnection);
            } catch (Throwable ignored) {
                // The framework may already have removed a connection whose process died.
            }
        }
        log(Log.WARN, TAG, "Presence service " + reason + "; scheduling recovery");
        schedulePresenceServiceRestart();
    }

    private void schedulePresenceServiceRestart() {
        int attempt;
        synchronized (presenceServiceBindingLock) {
            if (!presenceServiceDesired) {
                return;
            }
            attempt = ++presenceServiceRestartAttempts;
        }
        long delay = Math.min(30_000L, 1_000L << Math.min(attempt - 1, 5));
        systemBridgeHandler.removeCallbacks(presenceServiceRestart);
        systemBridgeHandler.postDelayed(presenceServiceRestart, delay);
    }
    private final SharedPreferences.OnSharedPreferenceChangeListener brightnessChangeListener =
            (preferences, key) -> {
                boolean brightnessChanged = BrightnessSettings.LOCK_BRIGHTNESS.equals(key)
                        || BrightnessSettings.BRIGHTNESS_PERCENT.equals(key);
                boolean burnInChanged = BrightnessSettings.BURN_IN_PROTECTION.equals(key);
                boolean displayCutoutChanged =
                        BrightnessSettings.DRAW_IN_DISPLAY_CUTOUT.equals(key);
                boolean presenceChanged = BrightnessSettings.PRESENCE_DETECTION.equals(key)
                        || BrightnessSettings.ABSENCE_BEHAVIOR.equals(key)
                        || BrightnessSettings.PRESENCE_DETECTION_READY.equals(key)
                        || BrightnessSettings.PERSON_PRESENT.equals(key);
                boolean absenceStateChanged =
                        BrightnessSettings.ABSENCE_BEHAVIOR.equals(key)
                                || BrightnessSettings.PRESENCE_DETECTION_READY.equals(key)
                                || BrightnessSettings.PERSON_PRESENT.equals(key);
                boolean panelStateTokenChanged =
                        BrightnessSettings.PANEL_STATE_TOKEN.equals(key);
                boolean presenceToggleChanged =
                        BrightnessSettings.PRESENCE_DETECTION.equals(key);
                if (!brightnessChanged && !burnInChanged && !displayCutoutChanged
                        && !presenceChanged
                        && !panelStateTokenChanged) {
                    return;
                }
                Activity activity = activePadActivity.get();
                if (presenceToggleChanged || panelStateTokenChanged) {
                    Context context = activity != null ? activity : targetContext;
                    if (context != null) {
                        requestPresenceServiceBridge(context);
                    }
                }
                if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                    activity.runOnUiThread(() -> {
                        if (brightnessChanged) {
                            applyBrightnessSetting(activity);
                        }
                        if (burnInChanged) {
                            configureBurnInProtection(activity);
                        }
                        if (displayCutoutChanged) {
                            applyDisplayCutoutPolicy(activity);
                        }
                        if (presenceChanged) {
                            applyKeepScreenPolicy(activity);
                            applyBrightnessSetting(activity);
                            if (absenceStateChanged) {
                                requestImmediateScreenOffIfNeeded(activity);
                            }
                        }
                        if (panelStateTokenChanged) {
                            publishPanelActive(activity, true);
                        }
                    });
                }
            };

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded with modern Xposed API " + getApiVersion());
    }

    @Override
    public void onSystemServerStarting(
            XposedModuleInterface.SystemServerStartingParam param) {
        log(Log.INFO, TAG, "System server starting; installing presence bridge");
        ClassLoader loader = param.getClassLoader();
        hookOplusStartupAllowance(loader);
        hookSystemBridge(loader);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (SYSTEM_SCOPE_PACKAGE.equals(param.getPackageName())) {
            installSystemBridge(param.getClassLoader());
            return;
        }
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        ClassLoader loader = param.getClassLoader();
        captureApplicationContext(loader);
        hookPadActivityContexts(loader);
        hookCentralControlEntry(loader);
        hookPadSystemBars(loader);
        hookPadWakeLock();
    }

    private void hookSystemBridge(ClassLoader loader) {
        if (!systemBridgeHookInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> systemServer = Class.forName(
                    "com.android.server.SystemServer",
                    false,
                    loader);
            Method nextStartupPhase = null;
            for (Method method : systemServer.getDeclaredMethods()) {
                if ("startCoreServices".equals(method.getName())) {
                    nextStartupPhase = method;
                    break;
                }
            }
            if (nextStartupPhase == null) {
                for (Method method : systemServer.getDeclaredMethods()) {
                    if ("startOtherServices".equals(method.getName())) {
                        nextStartupPhase = method;
                        break;
                    }
                }
            }
            if (nextStartupPhase == null) {
                throw new NoSuchMethodException(
                        "SystemServer.startCoreServices/startOtherServices");
            }
            nextStartupPhase.setAccessible(true);
            hook(nextStartupPhase)
                    .setId("mijia-panel.system-presence-bridge")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(chain -> {
                        installSystemBridge(loader);
                        return chain.proceed();
                    });
        } catch (Throwable error) {
            systemBridgeHookInstalled.set(false);
            logFailure("Unable to install presence system bridge", error);
            scheduleSystemBridgeRetry(loader);
        }
    }

    private void hookOplusStartupAllowance(ClassLoader loader) {
        if (!oplusStartupAllowanceHookInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> managerClass = Class.forName(
                    "com.android.server.am.OplusAppStartupManager",
                    false,
                    loader);
            Method target = null;
            for (Method method : managerClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("isAllowStartFromStartService".equals(method.getName())
                        && parameters.length == 6
                        && Intent.class.isAssignableFrom(parameters[5])) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                throw new NoSuchMethodException(
                        "OplusAppStartupManager.isAllowStartFromStartService");
            }
            target.setAccessible(true);
            hook(target)
                    .setId("mijia-panel.oplus-presence-service-allowance")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(chain -> {
                        int callingUid = (Integer) chain.getArg(2);
                        String callingPackage = (String) chain.getArg(3);
                        Intent intent = (Intent) chain.getArg(5);
                        ComponentName component = intent == null ? null : intent.getComponent();
                        if (callingUid == android.os.Process.SYSTEM_UID
                                && SYSTEM_CONTEXT_PACKAGE.equals(callingPackage)
                                && component != null
                                && MODULE_PACKAGE.equals(component.getPackageName())
                                && PRESENCE_SERVICE_CLASS.equals(component.getClassName())) {
                            log(Log.INFO, TAG,
                                    "Allowed system bridge to start presence service on ColorOS");
                            return true;
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "Installed ColorOS presence service allowance");
        } catch (ClassNotFoundException ignored) {
            oplusStartupAllowanceHookInstalled.set(false);
        } catch (Throwable error) {
            oplusStartupAllowanceHookInstalled.set(false);
            logFailure("Unable to install ColorOS presence service allowance", error);
        }
    }

    private void installSystemBridge(ClassLoader loader) {
        Context context;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread", false, loader);
            Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object thread = currentActivityThread.invoke(null);
            if (thread == null) {
                scheduleSystemBridgeRetry(loader);
                return;
            }
            Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            context = (Context) getSystemContext.invoke(thread);
            if (context == null) {
                scheduleSystemBridgeRetry(loader);
                return;
            }
        } catch (Throwable error) {
            logFailure("Unable to obtain system context for presence bridge", error);
            scheduleSystemBridgeRetry(loader);
            return;
        }
        if (!systemBridgeReceiverRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(SYSTEM_BRIDGE_ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        systemBridgeReceiver,
                        filter,
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(systemBridgeReceiver, filter);
            }
            log(Log.INFO, TAG, "Presence system bridge is ready");
        } catch (Throwable error) {
            systemBridgeReceiverRegistered.set(false);
            logFailure("Unable to register presence system bridge", error);
        }
    }

    private void scheduleSystemBridgeRetry(ClassLoader loader) {
        if (!systemBridgeRetryScheduled.compareAndSet(false, true)) {
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (systemBridgeReceiverRegistered.get()) {
                    systemBridgeRetryScheduled.set(false);
                    return;
                }
                if (++attempts > 60) {
                    systemBridgeRetryScheduled.set(false);
                    log(Log.ERROR, TAG, "Presence system bridge context was not ready");
                    return;
                }
                installSystemBridge(loader);
                if (!systemBridgeReceiverRegistered.get()) {
                    handler.postDelayed(this, 1_000L);
                } else {
                    systemBridgeRetryScheduled.set(false);
                }
            }
        }, 1_000L);
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
                        registerDebugReceiver(base);
                        registerPresenceStateReceiver(base);
                        ensureCompatibilityHooks(loader, base);
                        // Never replace the Application context. A process-wide
                        // tablet override breaks the normal page after pad-mode exit.
                        return chain.proceed();
                    });
        } catch (Throwable error) {
            logFailure("Unable to hook SHApplication.attachBaseContext", error);
        }
    }

    private void registerDebugReceiver(Context context) {
        if (!isMainProcess()) {
            return;
        }
        if (!debugReceiverRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(DEBUG_BURN_IN_STATUS_ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(debugReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(debugReceiver, filter);
            }
        } catch (Throwable error) {
            debugReceiverRegistered.set(false);
            logFailure("Unable to register burn-in debug receiver", error);
        }
    }

    private boolean isMainProcess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return TARGET_PACKAGE.equals(Application.getProcessName());
            }
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentProcessName = activityThread.getDeclaredMethod("currentProcessName");
            currentProcessName.setAccessible(true);
            return TARGET_PACKAGE.equals(currentProcessName.invoke(null));
        } catch (Throwable error) {
            logFailure("Unable to identify Xiaomi Home main process", error);
            return false;
        }
    }

    private void hookPadActivityContexts(ClassLoader loader) {
        try {
            Class<?> commonActivity = Class.forName(
                    "com.xiaomi.smarthome.framework.page.CommonActivity", false, loader);
            Method attach = commonActivity.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setId("mijia-panel.pad-activity-context")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(chain -> {
                        Context base = (Context) chain.getArg(0);
                        Object activity = chain.getThisObject();
                        boolean isPadActivity = activity != null
                                && activity.getClass().getName().startsWith(PAD_PACKAGE_PREFIX);
                        Context effectiveContext = isPadActivity && isPadModeEnabled(base)
                                ? makeTabletContext(base)
                                : base;
                        return chain.proceed(new Object[]{effectiveContext});
                    });
        } catch (Throwable error) {
            logFailure("Unable to hook pad activity contexts", error);
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

    private void hookPadSystemBars(ClassLoader loader) {
        try {
            Class<?> padActivity = Class.forName(PAD_MAIN, false, loader);
            Method onCreate = padActivity.getDeclaredMethod("onCreate", Bundle.class);
            onCreate.setAccessible(true);
            hook(onCreate)
                    .setId("mijia-panel.pad-system-bars-on-create")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        applyPadWindowPolicy((Activity) chain.getThisObject());
                        return result;
                    });

            Method onResume = padActivity.getDeclaredMethod("onResume");
            onResume.setAccessible(true);
            hook(onResume)
                    .setId("mijia-panel.pad-system-bars-on-resume")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        applyPadWindowPolicy((Activity) chain.getThisObject());
                        return result;
                    });

            Method onWindowFocusChanged =
                    Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
            onWindowFocusChanged.setAccessible(true);
            hook(onWindowFocusChanged)
                    .setId("mijia-panel.pad-system-bars-on-focus")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Activity activity = (Activity) chain.getThisObject();
                        if ((boolean) chain.getArg(0) && padActivity.isInstance(activity)) {
                            applyPadWindowPolicy(activity);
                        }
                        return result;
                    });

            Method onPause = Activity.class.getDeclaredMethod("onPause");
            onPause.setAccessible(true);
            hook(onPause)
                    .setId("mijia-panel.pad-burn-in-on-pause")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Activity activity = (Activity) chain.getThisObject();
                        if (padActivity.isInstance(activity)) {
                            stopBurnInProtection(activity);
                            // PowerManager may still report interactive while onPause is running.
                            // Publish after the screen transition has had a chance to settle so a
                            // screen-off panel remains monitored for presence-triggered wake-up.
                            activity.getWindow().getDecorView().postDelayed(
                                    () -> {
                                        if (!activity.isDestroyed()) {
                                            boolean resumedAgain =
                                                    activePadActivity.get() == activity
                                                            && activity.hasWindowFocus();
                                            publishPanelActive(
                                                    activity,
                                                    resumedAgain
                                                            || !isDeviceInteractive(activity));
                                        }
                                    },
                                    PANEL_PAUSE_STATE_DELAY_MS);
                        }
                        return result;
                    });

            Method activityOnResume = Activity.class.getDeclaredMethod("onResume");
            activityOnResume.setAccessible(true);
            hook(activityOnResume)
                    .setId("mijia-panel.clear-presence-outside-pad")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Activity activity = (Activity) chain.getThisObject();
                        if (!padActivity.isInstance(activity)) {
                            publishPanelActive(activity, false);
                        }
                        return result;
                    });

            Method onDestroy = Activity.class.getDeclaredMethod("onDestroy");
            onDestroy.setAccessible(true);
            hook(onDestroy)
                    .setId("mijia-panel.stop-presence-on-pad-destroy")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Activity activity = (Activity) chain.getThisObject();
                        if (padActivity.isInstance(activity)) {
                            publishPanelActive(activity, false);
                            stopBurnInProtection(activity);
                        }
                        return result;
                    });
        } catch (Throwable error) {
            logFailure("Unable to install pad system-bar hooks", error);
        }
    }

    private void applyPadWindowPolicy(Activity activity) {
        activePadActivity = new WeakReference<>(activity);
        publishPanelActive(activity, true);
        requestPresenceServiceBridge(activity);
        applyDisplayCutoutPolicy(activity);
        hideSystemBars(activity);
        applyKeepScreenPolicy(activity);
        applyBrightnessSetting(activity);
        configureBurnInProtection(activity);
    }

    private void applyDisplayCutoutPolicy(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        boolean enabled = BrightnessSettings.DEFAULT_DRAW_IN_DISPLAY_CUTOUT;
        try {
            enabled = getBrightnessPreferences().getBoolean(
                    BrightnessSettings.DRAW_IN_DISPLAY_CUTOUT,
                    BrightnessSettings.DEFAULT_DRAW_IN_DISPLAY_CUTOUT);
        } catch (Throwable error) {
            logFailure("Unable to read display-cutout setting", error);
        }
        Window window = activity.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        int desiredMode;
        if (!enabled) {
            desiredMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            desiredMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            desiredMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (attributes.layoutInDisplayCutoutMode != desiredMode) {
            attributes.layoutInDisplayCutoutMode = desiredMode;
            window.setAttributes(attributes);
        }
    }

    private void configureBurnInProtection(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        boolean enabled = BrightnessSettings.DEFAULT_BURN_IN_PROTECTION;
        try {
            enabled = getBrightnessPreferences().getBoolean(
                    BrightnessSettings.BURN_IN_PROTECTION,
                    BrightnessSettings.DEFAULT_BURN_IN_PROTECTION);
        } catch (Throwable error) {
            logFailure("Unable to read burn-in protection setting", error);
        }
        synchronized (burnInControllerLock) {
            if (!enabled) {
                if (burnInController != null) {
                    burnInController.stop();
                    burnInController = null;
                }
                return;
            }
            if (burnInController != null && burnInController.isFor(activity)) {
                return;
            }
            if (burnInController != null) {
                burnInController.stop();
            }
            View content = activity.findViewById(android.R.id.content);
            if (content != null) {
                burnInController = new BurnInShiftController(activity, content);
                burnInController.start();
            }
        }
    }

    private void stopBurnInProtection(Activity activity) {
        synchronized (burnInControllerLock) {
            if (burnInController != null && burnInController.isFor(activity)) {
                burnInController.stop();
                burnInController = null;
            }
        }
        if (activePadActivity.get() == activity) {
            activePadActivity = new WeakReference<>(null);
        }
    }

    private void applyBrightnessSetting(Activity activity) {
        applyBrightnessSetting(activity, null, null);
    }

    private void applyBrightnessSetting(
            Activity activity,
            Boolean personPresentOverride,
            Boolean detectionReadyOverride) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        float requestedBrightness = -1.0f;
        try {
            SharedPreferences preferences = getBrightnessPreferences();
            boolean detectionEnabled = preferences.getBoolean(
                    BrightnessSettings.PRESENCE_DETECTION,
                    BrightnessSettings.DEFAULT_PRESENCE_DETECTION);
            boolean detectionReady = detectionReadyOverride != null
                    ? detectionReadyOverride
                    : preferences.getBoolean(
                    BrightnessSettings.PRESENCE_DETECTION_READY,
                    false);
            boolean personPresent = personPresentOverride != null
                    ? personPresentOverride
                    : preferences.getBoolean(
                    BrightnessSettings.PERSON_PRESENT,
                    false);
            int absenceBehavior = preferences.getInt(
                    BrightnessSettings.ABSENCE_BEHAVIOR,
                    BrightnessSettings.DEFAULT_ABSENCE_BEHAVIOR);
            boolean useMinimumBrightness = detectionEnabled
                    && detectionReady
                    && !personPresent
                    && absenceBehavior == BrightnessSettings.ABSENCE_MINIMUM_BRIGHTNESS;
            if (useMinimumBrightness) {
                requestedBrightness = 0.01f;
            } else if (preferences.getBoolean(BrightnessSettings.LOCK_BRIGHTNESS, false)) {
                int percent = BrightnessSettings.clampPercent(preferences.getInt(
                        BrightnessSettings.BRIGHTNESS_PERCENT,
                        BrightnessSettings.DEFAULT_BRIGHTNESS_PERCENT));
                requestedBrightness = percent / 100.0f;
            }
        } catch (Throwable error) {
            logFailure("Unable to read panel brightness settings", error);
        }

        Window window = activity.getWindow();
        android.view.WindowManager.LayoutParams attributes = window.getAttributes();
        if (Float.compare(attributes.screenBrightness, requestedBrightness) != 0) {
            attributes.screenBrightness = requestedBrightness;
            window.setAttributes(attributes);
        }
    }

    private SharedPreferences getBrightnessPreferences() {
        SharedPreferences preferences = brightnessPreferences;
        if (preferences != null) {
            return preferences;
        }
        synchronized (this) {
            preferences = brightnessPreferences;
            if (preferences == null) {
                preferences = getRemotePreferences(BrightnessSettings.PREFERENCES);
                preferences.registerOnSharedPreferenceChangeListener(brightnessChangeListener);
                brightnessPreferences = preferences;
            }
            return preferences;
        }
    }

    private void publishPanelActive(Activity activity, boolean active) {
        try {
            SharedPreferences preferences = getBrightnessPreferences();
            String token = preferences.getString(BrightnessSettings.PANEL_STATE_TOKEN, null);
            if (token != null && !token.isEmpty()) {
                Intent intent = new Intent(BrightnessSettings.PANEL_STATE_ACTION)
                        .setPackage("com.daxiaamu.mijiapanel")
                        .putExtra(BrightnessSettings.EXTRA_PANEL_ACTIVE, active)
                        .putExtra(BrightnessSettings.EXTRA_PANEL_STATE_TOKEN, token);
                activity.sendBroadcast(intent);
            }
        } catch (Throwable error) {
            logFailure("Unable to publish panel activity state", error);
        }
    }

    private void requestPresenceServiceBridge(Context context) {
        try {
            SharedPreferences preferences = getBrightnessPreferences();
            String token = preferences.getString(BrightnessSettings.PANEL_STATE_TOKEN, null);
            if (token == null || token.isEmpty()) {
                return;
            }
            boolean enabled = preferences.getBoolean(
                    BrightnessSettings.PRESENCE_DETECTION,
                    BrightnessSettings.DEFAULT_PRESENCE_DETECTION);
            Intent intent = new Intent(SYSTEM_BRIDGE_ACTION)
                    .setPackage(SYSTEM_CONTEXT_PACKAGE)
                    .putExtra(
                            EXTRA_SYSTEM_BRIDGE_COMMAND,
                            enabled ? SYSTEM_BRIDGE_START : SYSTEM_BRIDGE_STOP)
                    .putExtra(EXTRA_SYSTEM_BRIDGE_TOKEN, token);
            context.sendBroadcast(intent);
        } catch (Throwable error) {
            logFailure("Unable to request presence system bridge", error);
        }
    }

    private void registerPresenceStateReceiver(Context context) {
        if (!isMainProcess()
                || !presenceStateReceiverRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(BrightnessSettings.PRESENCE_STATE_ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                        presenceStateReceiver,
                        filter,
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(presenceStateReceiver, filter);
            }
        } catch (Throwable error) {
            presenceStateReceiverRegistered.set(false);
            logFailure("Unable to register explicit presence-state receiver", error);
        }
    }

    private void requestImmediateScreenOffIfNeeded(Context context) {
        try {
            SharedPreferences preferences = getBrightnessPreferences();
            boolean shouldScreenOff = preferences.getBoolean(
                    BrightnessSettings.PRESENCE_DETECTION,
                    BrightnessSettings.DEFAULT_PRESENCE_DETECTION)
                    && preferences.getBoolean(
                    BrightnessSettings.PRESENCE_DETECTION_READY,
                    false)
                    && !preferences.getBoolean(
                    BrightnessSettings.PERSON_PRESENT,
                    false)
                    && preferences.getInt(
                    BrightnessSettings.ABSENCE_BEHAVIOR,
                    BrightnessSettings.DEFAULT_ABSENCE_BEHAVIOR)
                    == BrightnessSettings.ABSENCE_SCREEN_OFF;
            if (!shouldScreenOff || !isDeviceInteractive(context)) {
                return;
            }
            String token = preferences.getString(BrightnessSettings.PANEL_STATE_TOKEN, null);
            if (token == null || token.isEmpty()) {
                return;
            }
            Intent intent = new Intent(SYSTEM_BRIDGE_ACTION)
                    .setPackage(SYSTEM_CONTEXT_PACKAGE)
                    .putExtra(EXTRA_SYSTEM_BRIDGE_COMMAND, SYSTEM_BRIDGE_GO_TO_SLEEP)
                    .putExtra(EXTRA_SYSTEM_BRIDGE_TOKEN, token);
            context.sendBroadcast(intent);
        } catch (Throwable error) {
            logFailure("Unable to request immediate panel screen-off", error);
        }
    }

    private void putSystemToSleep(Context context) {
        try {
            PowerManager powerManager = context.getSystemService(PowerManager.class);
            if (powerManager == null || !powerManager.isInteractive()) {
                return;
            }
            Method singleArgument = null;
            Method threeArguments = null;
            for (Method method : PowerManager.class.getDeclaredMethods()) {
                if (!"goToSleep".equals(method.getName())) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && parameters[0] == long.class) {
                    singleArgument = method;
                } else if (parameters.length == 3
                        && parameters[0] == long.class
                        && parameters[1] == int.class
                        && parameters[2] == int.class) {
                    threeArguments = method;
                }
            }
            long now = SystemClock.uptimeMillis();
            if (threeArguments != null) {
                threeArguments.setAccessible(true);
                threeArguments.invoke(powerManager, now, 0, 0);
            } else if (singleArgument != null) {
                singleArgument.setAccessible(true);
                singleArgument.invoke(powerManager, now);
            } else {
                throw new NoSuchMethodException("PowerManager.goToSleep");
            }
            log(Log.INFO, TAG, "System bridge put the absent panel to sleep");
        } catch (Throwable error) {
            logFailure("System bridge could not put the panel to sleep", error);
        }
    }

    private void wakeSystem(Context context) {
        try {
            PowerManager powerManager = context.getSystemService(PowerManager.class);
            if (powerManager == null || powerManager.isInteractive()) {
                return;
            }
            Method oneArgument = null;
            Method threeArguments = null;
            Method fourArguments = null;
            for (Method method : PowerManager.class.getDeclaredMethods()) {
                if (!"wakeUp".equals(method.getName())) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && parameters[0] == long.class) {
                    oneArgument = method;
                } else if (parameters.length == 3
                        && parameters[0] == long.class
                        && parameters[1] == int.class
                        && parameters[2] == String.class) {
                    threeArguments = method;
                } else if (parameters.length == 4
                        && parameters[0] == long.class
                        && parameters[1] == int.class
                        && parameters[2] == String.class
                        && parameters[3] == String.class) {
                    fourArguments = method;
                }
            }
            long now = SystemClock.uptimeMillis();
            String details = "MijiaPanel presence";
            if (fourArguments != null) {
                fourArguments.setAccessible(true);
                fourArguments.invoke(
                        powerManager,
                        now,
                        2,
                        details,
                        context.getOpPackageName());
            } else if (threeArguments != null) {
                threeArguments.setAccessible(true);
                threeArguments.invoke(powerManager, now, 2, details);
            } else if (oneArgument != null) {
                oneArgument.setAccessible(true);
                oneArgument.invoke(powerManager, now);
            } else {
                throw new NoSuchMethodException("PowerManager.wakeUp");
            }
            log(Log.INFO, TAG, "System bridge woke the panel for detected presence");
        } catch (Throwable error) {
            logFailure("System bridge could not wake the panel", error);
        }
    }

    private static boolean isDeviceInteractive(Context activity) {
        android.os.PowerManager powerManager =
                (android.os.PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        return powerManager == null || powerManager.isInteractive();
    }

    private void applyKeepScreenPolicy(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        boolean detectionEnabled = false;
        try {
            SharedPreferences preferences = getBrightnessPreferences();
            detectionEnabled = preferences.getBoolean(
                    BrightnessSettings.PRESENCE_DETECTION,
                    BrightnessSettings.DEFAULT_PRESENCE_DETECTION);
        } catch (Throwable error) {
            logFailure("Unable to read presence detection state", error);
        }
        Window window = activity.getWindow();
        // The panel owns its screen policy. In screen-off mode a confirmed absence is
        // handled explicitly through PowerManager.goToSleep(). Clearing this flag would
        // let Android's normal timeout enter its DIM phase first, leaving the panel at
        // near-minimum brightness instead of performing the selected absence action.
        window.addFlags(WindowManagerFlags.FLAG_KEEP_SCREEN_ON);
        applyPadWakeLockPolicy(detectionEnabled);
    }

    private void hookPadWakeLock() {
        try {
            Method newWakeLock = PowerManager.class.getDeclaredMethod(
                    "newWakeLock",
                    int.class,
                    String.class);
            hook(newWakeLock)
                    .setId("mijia-panel.capture-pad-wake-lock")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof PowerManager.WakeLock
                                && PAD_WAKE_LOCK_TAG.equals(chain.getArg(1))) {
                            PowerManager.WakeLock wakeLock = (PowerManager.WakeLock) result;
                            padWakeLock = new WeakReference<>(wakeLock);
                        }
                        return result;
                    });

            Method acquire = PowerManager.WakeLock.class.getDeclaredMethod("acquire");
            hook(acquire)
                    .setId("mijia-panel.control-pad-wake-lock-acquire")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(chain -> {
                        PowerManager.WakeLock wakeLock =
                                (PowerManager.WakeLock) chain.getThisObject();
                        if (isCapturedPadWakeLock(wakeLock) && shouldSuppressPadWakeLock()) {
                            return null;
                        }
                        return chain.proceed();
                    });

            Method acquireWithTimeout =
                    PowerManager.WakeLock.class.getDeclaredMethod("acquire", long.class);
            hook(acquireWithTimeout)
                    .setId("mijia-panel.control-pad-wake-lock-acquire-timeout")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(chain -> {
                        PowerManager.WakeLock wakeLock =
                                (PowerManager.WakeLock) chain.getThisObject();
                        if (isCapturedPadWakeLock(wakeLock) && shouldSuppressPadWakeLock()) {
                            return null;
                        }
                        return chain.proceed();
                    });

            Method release = PowerManager.WakeLock.class.getDeclaredMethod("release");
            hook(release)
                    .setId("mijia-panel.control-pad-wake-lock-release")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(chain -> {
                        PowerManager.WakeLock wakeLock =
                                (PowerManager.WakeLock) chain.getThisObject();
                        if (isCapturedPadWakeLock(wakeLock) && !wakeLock.isHeld()) {
                            return null;
                        }
                        return chain.proceed();
                    });
        } catch (Throwable error) {
            logFailure("Unable to install pad wake-lock hooks", error);
        }
    }

    private boolean isCapturedPadWakeLock(PowerManager.WakeLock wakeLock) {
        return wakeLock != null && padWakeLock.get() == wakeLock;
    }

    private boolean shouldSuppressPadWakeLock() {
        try {
            SharedPreferences preferences = getBrightnessPreferences();
            boolean detectionEnabled = preferences.getBoolean(
                    BrightnessSettings.PRESENCE_DETECTION,
                    BrightnessSettings.DEFAULT_PRESENCE_DETECTION);
            return detectionEnabled;
        } catch (Throwable error) {
            logFailure("Unable to read pad wake-lock policy", error);
            return false;
        }
    }

    private void applyPadWakeLockPolicy(boolean detectionEnabled) {
        PowerManager.WakeLock wakeLock = padWakeLock.get();
        if (wakeLock == null) {
            return;
        }
        try {
            boolean suppress = detectionEnabled;
            if (suppress) {
                int releases = 0;
                while (wakeLock.isHeld() && releases++ < 8) {
                    wakeLock.release();
                }
            } else if (!wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Throwable error) {
            logFailure("Unable to apply pad wake-lock policy", error);
        }
    }

    private static void hideSystemBars(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Window window = activity.getWindow();
        window.addFlags(
                WindowManagerFlags.FLAG_FULLSCREEN
                        | WindowManagerFlags.FLAG_SHOW_WHEN_LOCKED
                        | WindowManagerFlags.FLAG_DISMISS_KEYGUARD
                        | WindowManagerFlags.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /**
     * Keeps the Android constant isolated so this class does not need to import
     * android.view.WindowManager solely for one flag.
     */
    private static final class WindowManagerFlags {
        private static final int FLAG_KEEP_SCREEN_ON = 0x00000080;
        private static final int FLAG_FULLSCREEN = 0x00000400;
        private static final int FLAG_SHOW_WHEN_LOCKED = 0x00080000;
        private static final int FLAG_TURN_SCREEN_ON = 0x00200000;
        private static final int FLAG_DISMISS_KEYGUARD = 0x00400000;

        private WindowManagerFlags() {
        }
    }

    private static final class BurnInShiftController implements Runnable {
        private static final long SHIFT_ANIMATION_MS = 800L;

        private final WeakReference<Activity> activityReference;
        private final View content;
        private final List<int[]> shiftPositions = new ArrayList<>();
        private int shiftPositionIndex;
        private int currentX;
        private int currentY;
        private long nextShiftElapsedRealtime;
        private boolean stopped;

        BurnInShiftController(Activity activity, View content) {
            activityReference = new WeakReference<>(activity);
            this.content = content;
        }

        void start() {
            content.removeCallbacks(this);
            scheduleNext("Burn-in timer started");
        }

        boolean isFor(Activity activity) {
            return activityReference.get() == activity;
        }

        void stop() {
            stopped = true;
            content.removeCallbacks(this);
            content.animate().cancel();
            content.setTranslationX(0.0f);
            content.setTranslationY(0.0f);
            nextShiftElapsedRealtime = 0L;
            Log.i(TAG, "Burn-in timer stopped");
        }

        String debugStatus() {
            long remainingMs = Math.max(
                    0L,
                    nextShiftElapsedRealtime - SystemClock.elapsedRealtime());
            long remainingSeconds = (remainingMs + 999L) / 1_000L;
            return "burn-in active=" + !stopped
                    + ", remainingSeconds=" + remainingSeconds
                    + ", offsetX=" + currentX
                    + ", offsetY=" + currentY
                    + ", windowFocused=" + content.hasWindowFocus();
        }

        @Override
        public void run() {
            Activity activity = activityReference.get();
            if (stopped || activity == null || activity.isFinishing() || activity.isDestroyed()) {
                stop();
                return;
            }
            if (activity.hasWindowFocus()) {
                int[] nextPosition = nextShiftPosition();
                currentX = nextPosition[0];
                currentY = nextPosition[1];
                content.animate()
                        .translationX(currentX)
                        .translationY(currentY)
                        .setDuration(SHIFT_ANIMATION_MS)
                        .start();
                scheduleNext("Burn-in shifted to (" + currentX + ", " + currentY + ")");
            } else {
                scheduleNext("Burn-in shift skipped because the window has no focus");
            }
        }

        private int[] nextShiftPosition() {
            if (shiftPositionIndex >= shiftPositions.size()) {
                refillShiftPositions();
            }
            return shiftPositions.get(shiftPositionIndex++);
        }

        private void refillShiftPositions() {
            shiftPositions.clear();
            for (int xStep = -BURN_IN_SHIFT_RADIUS_STEPS;
                    xStep <= BURN_IN_SHIFT_RADIUS_STEPS;
                    xStep++) {
                for (int yStep = -BURN_IN_SHIFT_RADIUS_STEPS;
                        yStep <= BURN_IN_SHIFT_RADIUS_STEPS;
                        yStep++) {
                    shiftPositions.add(new int[]{
                            xStep * BURN_IN_SHIFT_STEP_PX,
                            yStep * BURN_IN_SHIFT_STEP_PX
                    });
                }
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int index = shiftPositions.size() - 1; index > 0; index--) {
                int swapIndex = random.nextInt(index + 1);
                int[] temporary = shiftPositions.get(index);
                shiftPositions.set(index, shiftPositions.get(swapIndex));
                shiftPositions.set(swapIndex, temporary);
            }
            if (shiftPositions.size() > 1
                    && shiftPositions.get(0)[0] == currentX
                    && shiftPositions.get(0)[1] == currentY) {
                int[] first = shiftPositions.get(0);
                shiftPositions.set(0, shiftPositions.get(1));
                shiftPositions.set(1, first);
            }
            shiftPositionIndex = 0;
        }

        private void scheduleNext(String event) {
            nextShiftElapsedRealtime = SystemClock.elapsedRealtime()
                    + BURN_IN_SHIFT_INTERVAL_MS;
            content.postDelayed(this, BURN_IN_SHIFT_INTERVAL_MS);
            Log.i(TAG, event + "; next attempt in "
                    + (BURN_IN_SHIFT_INTERVAL_MS / 1_000L) + "s");
        }
    }

    /**
     * Exposes roughly 600 dp on the short edge so Xiaomi Home selects its pad
     * resources. This context is applied only to com.xiaomi.smarthome.pad.*.
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
