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
