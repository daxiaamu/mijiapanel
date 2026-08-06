package com.daxiaamu.mijiapanel;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class MijiaPanelApplication extends Application {
    private static final String TAG = "MijiaPanelIntegrity";

    interface ServiceListener {
        void onServiceAvailable();
    }

    interface ScopeRequestListener {
        void onApproved();

        void onFailed();
    }

    private final Set<ServiceListener> listeners =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable integrityWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!integrityTrusted) {
                return;
            }
            if (!AppIntegrity.verify(MijiaPanelApplication.this)) {
                integrityTrusted = false;
                xposedService = null;
                stopService(new Intent(
                        MijiaPanelApplication.this,
                        PresenceDetectionService.class));
                Log.e(TAG, "Runtime integrity capability was revoked");
                return;
            }
            scheduleIntegrityWatchdog();
        }
    };
    private volatile XposedService xposedService;
    private volatile boolean integrityTrusted;

    @Override
    public void onCreate() {
        super.onCreate();
        integrityTrusted = AppIntegrity.verify(this);
        if (!integrityTrusted) {
            Log.e(TAG, "Application startup rejected by integrity policy");
            return;
        }
        Log.i(TAG, "Integrity capability issued for " + BuildConfig.HARDENING_BUILD_ID);
        scheduleIntegrityWatchdog();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                xposedService = service;
                mainHandler.post(MijiaPanelApplication.this::notifyServiceAvailable);
            }

            @Override
            public void onServiceDied(XposedService service) {
                if (xposedService == service) {
                    xposedService = null;
                }
            }
        });
    }

    SharedPreferences getRemotePreferences(String group) {
        XposedService service = xposedService;
        return service == null ? null : service.getRemotePreferences(group);
    }

    boolean isIntegrityTrusted() {
        return integrityTrusted;
    }

    boolean isScopeEnabled(String packageName) {
        XposedService service = xposedService;
        return service != null && service.getScope().contains(packageName);
    }

    boolean requestScope(String packageName, ScopeRequestListener listener) {
        XposedService service = xposedService;
        if (service == null) {
            return false;
        }
        try {
            service.requestScope(
                    Collections.singletonList(packageName),
                    new XposedService.OnScopeEventListener() {
                        @Override
                        public void onScopeRequestApproved(java.util.List<String> scope) {
                            listener.onApproved();
                        }

                        @Override
                        public void onScopeRequestFailed(String message) {
                            listener.onFailed();
                        }
                    });
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    void addServiceListener(ServiceListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
        if (xposedService != null) {
            listener.onServiceAvailable();
        }
    }

    void removeServiceListener(ServiceListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyServiceAvailable() {
        ServiceListener[] snapshot;
        synchronized (listeners) {
            snapshot = listeners.toArray(new ServiceListener[0]);
        }
        for (ServiceListener listener : snapshot) {
            listener.onServiceAvailable();
        }
    }

    private void scheduleIntegrityWatchdog() {
        long base = Math.max(700L, BuildConfig.INTEGRITY_WATCHDOG_MS);
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 3L));
        mainHandler.postDelayed(integrityWatchdog, base + jitter);
    }
}
