package com.daxiaamu.mijiapanel;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class MijiaPanelApplication extends Application {
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
    private volatile XposedService xposedService;

    @Override
    public void onCreate() {
        super.onCreate();
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
}
