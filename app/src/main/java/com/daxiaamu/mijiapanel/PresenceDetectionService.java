package com.daxiaamu.mijiapanel;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.Image;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs low-rate, on-device front-camera analysis while Xiaomi Home's panel is visible.
 * Frames are never persisted or sent off the device.
 */
public final class PresenceDetectionService extends LifecycleService
        implements MijiaPanelApplication.ServiceListener {
    private static final String CHANNEL_ID = "presence_detection";
    private static final int NOTIFICATION_ID = 2101;
    private static final long FACE_INTERVAL_MS = 1_000L;
    private static final long PRESENCE_GRACE_MS = 30_000L;
    private static final long STATE_TICK_MS = 2_000L;
    private static final long SCREEN_OFF_MOTION_GUARD_MS = 3_000L;
    private static final long PANEL_SCREEN_OFF_HANDOFF_MS = 5_000L;
    private static final int MOTION_REQUIRED_FRAMES = 2;
    private static final int LUMA_DIFFERENCE_THRESHOLD = 18;
    private static final float MOTION_SAMPLE_RATIO = 0.025f;
    private static final int LIGHTING_SHIFT_THRESHOLD = 6;
    private static final float LIGHTING_CHANGED_RATIO = 0.75f;
    private static final float LIGHTING_DIRECTION_RATIO = 0.90f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean faceDetectionRunning = new AtomicBoolean();
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                long now = SystemClock.elapsedRealtime();
                lastScreenOffElapsed = now;
                // Activity.onPause and ACTION_SCREEN_OFF are not delivered in a fixed order.
                // Latch the panel session so the front camera remains available to wake the
                // display even when the Activity has already reported itself as paused.
                if (panelActive
                        || now - lastPanelActiveElapsed <= PANEL_SCREEN_OFF_HANDOFF_MS) {
                    panelScreenOffSession = true;
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                panelScreenOffSession = false;
            }
            updateCameraState();
        }
    };
    private final BroadcastReceiver panelStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BrightnessSettings.PANEL_STATE_ACTION.equals(intent.getAction())) {
                return;
            }
            String receivedToken = intent.getStringExtra(
                    BrightnessSettings.EXTRA_PANEL_STATE_TOKEN);
            if (panelStateToken == null || !panelStateToken.equals(receivedToken)) {
                return;
            }
            boolean nextPanelActive = intent.getBooleanExtra(
                    BrightnessSettings.EXTRA_PANEL_ACTIVE,
                    false);
            if (panelActive || nextPanelActive) {
                lastPanelActiveElapsed = SystemClock.elapsedRealtime();
            }
            panelActive = nextPanelActive;
            updateCameraState();
            if (!nextPanelActive) {
                // Keep the camera across the short Activity-pause/screen-off handoff. If this
                // was an ordinary navigation away from the panel, it is stopped after the grace.
                mainHandler.postDelayed(PresenceDetectionService.this::updateCameraState,
                        PANEL_SCREEN_OFF_HANDOFF_MS);
            }
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (preferences, key) -> {
                if (BrightnessSettings.PRESENCE_DETECTION.equals(key)) {
                    mainHandler.post(this::updateCameraState);
                }
            };
    private final Runnable stateTick = new Runnable() {
        @Override
        public void run() {
            publishPresence(isWithinPresenceGrace());
            mainHandler.postDelayed(this, STATE_TICK_MS);
        }
    };

    private MijiaPanelApplication application;
    private SharedPreferences remotePreferences;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private FaceDetector faceDetector;
    private boolean cameraStarting;
    private boolean cameraBound;
    private int cameraGeneration;
    private Boolean publishedPresence;
    private volatile long lastPresenceElapsed;
    private volatile long lastScreenOffElapsed;
    private volatile long lastPanelActiveElapsed;
    private volatile boolean panelActive;
    private volatile boolean panelScreenOffSession;
    private volatile String panelStateToken;

    @Override
    public void onCreate() {
        super.onCreate();
        application = (MijiaPanelApplication) getApplication();
        application.addServiceListener(this);
        faceDetector = FaceDetection.getClient(
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                        .setMinFaceSize(0.1f)
                        .enableTracking()
                        .build());
        startCameraForeground();
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        ContextCompat.registerReceiver(
                this,
                screenReceiver,
                screenFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(
                this,
                panelStateReceiver,
                new IntentFilter(BrightnessSettings.PANEL_STATE_ACTION),
                ContextCompat.RECEIVER_EXPORTED);
        mainHandler.post(stateTick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        updateCameraState();
        return START_STICKY;
    }

    @Override
    public void onServiceAvailable() {
        mainHandler.post(() -> {
            SharedPreferences preferences = application.getRemotePreferences(
                    BrightnessSettings.PREFERENCES);
            if (preferences == null || preferences == remotePreferences) {
                return;
            }
            if (remotePreferences != null) {
                remotePreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
            }
            remotePreferences = preferences;
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
            panelStateToken = UUID.randomUUID().toString();
            preferences.edit()
                    .putString(BrightnessSettings.PANEL_STATE_TOKEN, panelStateToken)
                    .putBoolean(BrightnessSettings.PRESENCE_DETECTION_READY, false)
                    .putBoolean(BrightnessSettings.PERSON_PRESENT, false)
                    .commit();
            updateCameraState();
        });
    }

    private void startCameraForeground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.presence_notification_channel),
                    NotificationManager.IMPORTANCE_LOW));
        }
        Intent settingsIntent = new Intent(this, SettingsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.presence_notification_title))
                .setContentText(getString(R.string.presence_notification_summary))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateCameraState() {
        SharedPreferences preferences = remotePreferences;
        if (preferences == null) {
            return;
        }
        boolean enabled = preferences.getBoolean(
                BrightnessSettings.PRESENCE_DETECTION,
                BrightnessSettings.DEFAULT_PRESENCE_DETECTION);
        if (!enabled) {
            stopSelf();
            return;
        }
        if (panelActive) {
            lastPanelActiveElapsed = SystemClock.elapsedRealtime();
        }
        if (shouldMonitorPanel(panelActive)) {
            startCamera();
        } else {
            stopCamera();
        }
    }

    private void startCamera() {
        if (cameraBound || cameraStarting) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        cameraStarting = true;
        int generation = ++cameraGeneration;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                if (generation != cameraGeneration || !shouldUseCamera()) {
                    cameraStarting = false;
                    return;
                }
                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(
                                new android.util.Size(480, 360),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, new PresenceAnalyzer());
                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        analysis);
                cameraProvider = provider;
                imageAnalysis = analysis;
                cameraStarting = false;
                cameraBound = true;
                // Give a newly started detector a full absence-confirmation window.
                lastPresenceElapsed = SystemClock.elapsedRealtime();
                publishedPresence = null;
                publishReady(true);
                publishPresence(true);
            } catch (Throwable error) {
                cameraStarting = false;
                cameraBound = false;
                publishReady(false);
                publishPresence(false);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private boolean shouldUseCamera() {
        SharedPreferences preferences = remotePreferences;
        if (preferences == null
                || !preferences.getBoolean(
                        BrightnessSettings.PRESENCE_DETECTION,
                        BrightnessSettings.DEFAULT_PRESENCE_DETECTION)) {
            return false;
        }
        return shouldMonitorPanel(panelActive);
    }

    private boolean shouldMonitorPanel(boolean panelActive) {
        if (panelActive || panelScreenOffSession) {
            return true;
        }
        return SystemClock.elapsedRealtime() - lastPanelActiveElapsed
                <= PANEL_SCREEN_OFF_HANDOFF_MS;
    }

    private void stopCamera() {
        ++cameraGeneration;
        cameraStarting = false;
        cameraBound = false;
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        lastPresenceElapsed = 0L;
        publishedPresence = null;
        publishReady(false);
        publishPresence(false);
    }

    private void publishReady(boolean ready) {
        SharedPreferences preferences = remotePreferences;
        if (preferences != null
                && preferences.getBoolean(BrightnessSettings.PRESENCE_DETECTION_READY, false)
                != ready) {
            preferences.edit()
                    .putBoolean(BrightnessSettings.PRESENCE_DETECTION_READY, ready)
                    .commit();
        }
    }

    private void markPresence(boolean wakeScreen) {
        lastPresenceElapsed = SystemClock.elapsedRealtime();
        publishPresence(true);
        if (wakeScreen) {
            wakeScreenIfNeeded();
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeScreenIfNeeded() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager == null || powerManager.isInteractive()) {
            return;
        }
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE,
                getPackageName() + ":presence-wake");
        wakeLock.acquire(5_000L);
    }

    private boolean isWithinPresenceGrace() {
        long last = lastPresenceElapsed;
        return last > 0L && SystemClock.elapsedRealtime() - last <= PRESENCE_GRACE_MS;
    }

    private void publishPresence(boolean present) {
        if (publishedPresence != null && publishedPresence == present) {
            return;
        }
        publishedPresence = present;
        SharedPreferences preferences = remotePreferences;
        if (preferences != null) {
            preferences.edit()
                    .putBoolean(BrightnessSettings.PERSON_PRESENT, present)
                    .commit();
        }
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(stateTick);
        stopCamera();
        unregisterReceiver(screenReceiver);
        unregisterReceiver(panelStateReceiver);
        if (remotePreferences != null) {
            remotePreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        }
        application.removeServiceListener(this);
        analysisExecutor.shutdownNow();
        faceDetector.close();
        super.onDestroy();
    }

    private final class PresenceAnalyzer implements ImageAnalysis.Analyzer {
        private byte[] previousLuma;
        private long lastFaceStartedElapsed;
        private int consecutiveMotionFrames;

        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            try {
                if (detectMotion(imageProxy)) {
                    consecutiveMotionFrames++;
                    if (consecutiveMotionFrames >= MOTION_REQUIRED_FRAMES) {
                        boolean outsideScreenOffGuard = SystemClock.elapsedRealtime()
                                - lastScreenOffElapsed >= SCREEN_OFF_MOTION_GUARD_MS;
                        if (outsideScreenOffGuard) {
                            markPresence(true);
                        }
                    }
                } else {
                    consecutiveMotionFrames = 0;
                }
                long now = SystemClock.elapsedRealtime();
                Image mediaImage = imageProxy.getImage();
                if (mediaImage != null
                        && now - lastFaceStartedElapsed >= FACE_INTERVAL_MS
                        && faceDetectionRunning.compareAndSet(false, true)) {
                    lastFaceStartedElapsed = now;
                    InputImage input = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.getImageInfo().getRotationDegrees());
                    faceDetector.process(input)
                            .addOnSuccessListener(faces -> {
                                if (!faces.isEmpty()) {
                                    markPresence(true);
                                }
                            })
                            .addOnCompleteListener(task -> {
                                faceDetectionRunning.set(false);
                                imageProxy.close();
                            });
                    return;
                }
            } catch (Throwable ignored) {
                // A later frame can recover from transient camera or detector errors.
            }
            imageProxy.close();
        }

        private boolean detectMotion(ImageProxy imageProxy) {
            ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            if (planes.length == 0) {
                return false;
            }
            ImageProxy.PlaneProxy lumaPlane = planes[0];
            ByteBuffer buffer = lumaPlane.getBuffer().duplicate();
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            int xStep = Math.max(4, width / 80);
            int yStep = Math.max(4, height / 60);
            int columns = (width + xStep - 1) / xStep;
            int rows = (height + yStep - 1) / yStep;
            byte[] current = new byte[columns * rows];
            int rowStride = lumaPlane.getRowStride();
            int pixelStride = lumaPlane.getPixelStride();
            int base = buffer.position();
            int count = 0;
            for (int y = 0; y < height; y += yStep) {
                for (int x = 0; x < width; x += xStep) {
                    int index = base + y * rowStride + x * pixelStride;
                    if (index < buffer.limit()) {
                        current[count++] = buffer.get(index);
                    }
                }
            }
            if (previousLuma == null || previousLuma.length != count) {
                previousLuma = copyOf(current, count);
                return false;
            }
            int[] deltaHistogram = new int[511];
            int significantDeltas = 0;
            int brighterDeltas = 0;
            int darkerDeltas = 0;
            for (int index = 0; index < count; index++) {
                int delta = (current[index] & 0xff) - (previousLuma[index] & 0xff);
                deltaHistogram[delta + 255]++;
                if (Math.abs(delta) >= LIGHTING_SHIFT_THRESHOLD) {
                    significantDeltas++;
                    if (delta > 0) {
                        brighterDeltas++;
                    } else {
                        darkerDeltas++;
                    }
                }
            }
            int globalLightingShift = 0;
            boolean mostPixelsChanged = significantDeltas >= count * LIGHTING_CHANGED_RATIO;
            boolean sameDirection = significantDeltas > 0
                    && Math.max(brighterDeltas, darkerDeltas)
                    >= significantDeltas * LIGHTING_DIRECTION_RATIO;
            if (mostPixelsChanged && sameDirection) {
                int midpoint = count / 2;
                int accumulated = 0;
                for (int histogramIndex = 0;
                        histogramIndex < deltaHistogram.length;
                        histogramIndex++) {
                    accumulated += deltaHistogram[histogramIndex];
                    if (accumulated > midpoint) {
                        globalLightingShift = histogramIndex - 255;
                        break;
                    }
                }
            }
            int changed = 0;
            for (int index = 0; index < count; index++) {
                int delta = (current[index] & 0xff) - (previousLuma[index] & 0xff);
                if (Math.abs(delta - globalLightingShift) >= LUMA_DIFFERENCE_THRESHOLD) {
                    changed++;
                }
            }
            previousLuma = copyOf(current, count);
            float changedRatio = count == 0 ? 0.0f : changed / (float) count;
            return changedRatio >= MOTION_SAMPLE_RATIO;
        }

        private byte[] copyOf(byte[] source, int length) {
            byte[] copy = new byte[length];
            System.arraycopy(source, 0, copy, 0, length);
            return copy;
        }
    }
}
