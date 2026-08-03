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
import android.provider.Settings;
import android.util.Log;

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
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

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
    private static final long POSE_INTERVAL_MS = 1_500L;
    private static final long STATE_TICK_MS = 2_000L;
    private static final long SCREEN_OFF_MOTION_GUARD_MS = 3_000L;
    private static final long PANEL_SCREEN_OFF_HANDOFF_MS = 5_000L;
    private static final int MOTION_REQUIRED_FRAMES = 2;
    // Retained for possible fallback use, but excluded from presence decisions now that
    // face and pose detection cover the dedicated-panel scenario without lighting noise.
    private static final boolean LUMA_MOTION_DETECTION_ENABLED = false;
    private static final int LUMA_DIFFERENCE_THRESHOLD = 18;
    private static final float MOTION_SAMPLE_RATIO = 0.025f;
    private static final int LIGHTING_SHIFT_THRESHOLD = 6;
    private static final float LIGHTING_CHANGED_RATIO = 0.75f;
    private static final float LIGHTING_DIRECTION_RATIO = 0.90f;
    private static final long FACE_DETECTOR_RETRY_MS = 5_000L;
    private static final long POSE_DETECTOR_RETRY_MS = 5_000L;
    private static final int MIN_CONFIDENT_POSE_LANDMARKS = 5;
    private static final float MIN_POSE_LANDMARK_CONFIDENCE = 0.5f;
    private static final String TAG = "MijiaPanelPresence";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean visionDetectionRunning = new AtomicBoolean();
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
                boolean returningToPanel = panelScreenOffSession || panelActive;
                panelScreenOffSession = false;
                if (returningToPanel && cameraBound) {
                    // Re-arm the complete absence window after a manual/presence wake.
                    // Otherwise a stale absent state can immediately hand control back
                    // to Android's timeout or fail to trigger the next explicit sleep.
                    lastPresenceElapsed = SystemClock.elapsedRealtime();
                    publishPresence(true);
                }
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
                Log.w(TAG, "Ignored panel state because its token does not match");
                return;
            }
            boolean nextPanelActive = intent.getBooleanExtra(
                    BrightnessSettings.EXTRA_PANEL_ACTIVE,
                    false);
            if (panelActive || nextPanelActive) {
                lastPanelActiveElapsed = SystemClock.elapsedRealtime();
            }
            panelActive = nextPanelActive;
            Log.w(TAG, "Panel state changed: active=" + panelActive
                    + ", screenOffSession=" + panelScreenOffSession);
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
    private PoseDetector poseDetector;
    private long lastFaceDetectorInitAttemptElapsed;
    private long lastPoseDetectorInitAttemptElapsed;
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
    private volatile boolean startedBySystemBridge;

    @Override
    public void onCreate() {
        super.onCreate();
        application = (MijiaPanelApplication) getApplication();
        application.addServiceListener(this);
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
        if (intent != null && intent.getBooleanExtra(
                BrightnessSettings.EXTRA_SYSTEM_BRIDGE_START,
                false)) {
            startedBySystemBridge = true;
            markSystemBridgeReady(remotePreferences);
        }
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
            panelStateToken = preferences.getString(
                    BrightnessSettings.PANEL_STATE_TOKEN,
                    null);
            if (panelStateToken == null || panelStateToken.isEmpty()) {
                panelStateToken = UUID.randomUUID().toString();
            }
            preferences.edit()
                    .putString(BrightnessSettings.PANEL_STATE_TOKEN, panelStateToken)
                    .putBoolean(BrightnessSettings.PRESENCE_DETECTION_READY, false)
                    .putBoolean(BrightnessSettings.PERSON_PRESENT, false)
                    .commit();
            markSystemBridgeReady(preferences);
            requestSystemBridgeValidation();
            mainHandler.postDelayed(this::requestSystemBridgeValidation, 1_000L);
            mainHandler.postDelayed(this::requestSystemBridgeValidation, 3_000L);
            updateCameraState();
        });
    }

    private void requestSystemBridgeValidation() {
        if (startedBySystemBridge || panelStateToken == null || panelStateToken.isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent(BrightnessSettings.SYSTEM_BRIDGE_ACTION)
                    .setPackage("android")
                    .putExtra(
                            BrightnessSettings.EXTRA_SYSTEM_BRIDGE_COMMAND,
                            BrightnessSettings.SYSTEM_BRIDGE_COMMAND_PROBE)
                    .putExtra(
                            BrightnessSettings.EXTRA_SYSTEM_BRIDGE_TOKEN,
                            panelStateToken);
            sendBroadcast(intent);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to validate the system bridge", error);
        }
    }

    private void markSystemBridgeReady(SharedPreferences preferences) {
        if (!startedBySystemBridge) {
            return;
        }
        int bootCount = Settings.Global.getInt(
                getContentResolver(),
                Settings.Global.BOOT_COUNT,
                -1);
        if (bootCount >= 0) {
            // Bridge readiness belongs to this app, so keep a local copy as the
            // authoritative UI signal. Remote preferences can be delayed across the
            // Xposed service boundary during early boot.
            getSharedPreferences(BrightnessSettings.PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putInt(BrightnessSettings.SYSTEM_BRIDGE_BOOT_COUNT, bootCount)
                    .commit();
            if (preferences != null) {
                preferences.edit()
                        .putInt(BrightnessSettings.SYSTEM_BRIDGE_BOOT_COUNT, bootCount)
                        .commit();
            }
        }
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
            Log.w(TAG, "Stopping service because presence detection is disabled");
            stopSelf();
            return;
        }
        if (panelActive) {
            lastPanelActiveElapsed = SystemClock.elapsedRealtime();
        }
        if (shouldMonitorPanel(panelActive)) {
            startCamera();
        } else {
            Log.w(TAG, "Camera no longer needed: panelActive=" + panelActive
                    + ", screenOffSession=" + panelScreenOffSession
                    + ", lastPanelAgeMs="
                    + (SystemClock.elapsedRealtime() - lastPanelActiveElapsed));
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
                Log.w(TAG, "Front camera bound for presence detection");
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
                Log.w(TAG, "Unable to bind front camera", error);
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
        if (cameraBound || cameraStarting || cameraProvider != null) {
            Log.w(TAG, "Stopping front-camera presence analysis");
        }
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

    private void markPresence(String source, boolean wakeScreen) {
        lastPresenceElapsed = SystemClock.elapsedRealtime();
        if (publishedPresence == null || !publishedPresence) {
            Log.w(TAG, "Presence detected by " + source);
        }
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
        SharedPreferences preferences = remotePreferences;
        int delaySeconds = BrightnessSettings.DEFAULT_ABSENCE_DELAY_SECONDS;
        if (preferences != null) {
            delaySeconds = BrightnessSettings.clampAbsenceDelaySeconds(
                    preferences.getInt(
                            BrightnessSettings.ABSENCE_DELAY_SECONDS,
                            BrightnessSettings.DEFAULT_ABSENCE_DELAY_SECONDS));
        }
        long effectiveGraceMs = delaySeconds * 1_000L;
        return last > 0L && SystemClock.elapsedRealtime() - last <= effectiveGraceMs;
    }

    private void publishPresence(boolean present) {
        if (publishedPresence != null && publishedPresence == present) {
            return;
        }
        Log.w(TAG, "Presence state changed: present=" + present);
        publishedPresence = present;
        SharedPreferences preferences = remotePreferences;
        if (preferences != null) {
            preferences.edit()
                    .putBoolean(BrightnessSettings.PERSON_PRESENT, present)
                    .commit();
        }
        broadcastPresenceState(preferences, present);
        if (!present) {
            requestScreenOffForConfirmedAbsence(preferences);
        }
    }

    private void broadcastPresenceState(SharedPreferences preferences, boolean present) {
        if (preferences == null || panelStateToken == null || panelStateToken.isEmpty()) {
            return;
        }
        try {
            boolean ready = preferences.getBoolean(
                    BrightnessSettings.PRESENCE_DETECTION_READY,
                    false);
            Intent intent = new Intent(BrightnessSettings.PRESENCE_STATE_ACTION)
                    .setPackage("com.xiaomi.smarthome")
                    .putExtra(BrightnessSettings.EXTRA_PANEL_STATE_TOKEN, panelStateToken)
                    .putExtra(BrightnessSettings.EXTRA_PERSON_PRESENT, present)
                    .putExtra(BrightnessSettings.EXTRA_PRESENCE_READY, ready);
            sendBroadcast(intent);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to publish presence state to Xiaomi Home", error);
        }
    }

    private void requestScreenOffForConfirmedAbsence(SharedPreferences preferences) {
        if (preferences == null
                || !cameraBound
                || !preferences.getBoolean(
                BrightnessSettings.PRESENCE_DETECTION_READY,
                false)
                || preferences.getInt(
                BrightnessSettings.ABSENCE_BEHAVIOR,
                BrightnessSettings.DEFAULT_ABSENCE_BEHAVIOR)
                != BrightnessSettings.ABSENCE_SCREEN_OFF
                || panelStateToken == null
                || panelStateToken.isEmpty()) {
            return;
        }
        try {
            Log.w(TAG, "Requesting screen off after confirmed absence");
            Intent intent = new Intent(BrightnessSettings.SYSTEM_BRIDGE_ACTION)
                    .setPackage("android")
                    .putExtra(
                            BrightnessSettings.EXTRA_SYSTEM_BRIDGE_COMMAND,
                            BrightnessSettings.SYSTEM_BRIDGE_COMMAND_GO_TO_SLEEP)
                    .putExtra(
                            BrightnessSettings.EXTRA_SYSTEM_BRIDGE_TOKEN,
                            panelStateToken);
            sendBroadcast(intent);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to request screen off after confirmed absence", error);
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
        if (faceDetector != null) {
            faceDetector.close();
        }
        if (poseDetector != null) {
            poseDetector.close();
        }
        super.onDestroy();
    }

    private FaceDetector getOrCreateFaceDetector() {
        if (faceDetector != null) {
            return faceDetector;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastFaceDetectorInitAttemptElapsed < FACE_DETECTOR_RETRY_MS) {
            return null;
        }
        lastFaceDetectorInitAttemptElapsed = now;
        try {
            faceDetector = FaceDetection.getClient(
                    new FaceDetectorOptions.Builder()
                            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                            .setMinFaceSize(0.1f)
                            .enableTracking()
                            .build());
            return faceDetector;
        } catch (Throwable error) {
            Log.w(TAG, "Face detector initialization failed; motion detection remains active", error);
            return null;
        }
    }

    private PoseDetector getOrCreatePoseDetector() {
        if (poseDetector != null) {
            return poseDetector;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastPoseDetectorInitAttemptElapsed < POSE_DETECTOR_RETRY_MS) {
            return null;
        }
        lastPoseDetectorInitAttemptElapsed = now;
        try {
            poseDetector = PoseDetection.getClient(
                    new PoseDetectorOptions.Builder()
                            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                            .build());
            return poseDetector;
        } catch (Throwable error) {
            Log.w(TAG, "Pose detector initialization failed; face and motion remain active",
                    error);
            return null;
        }
    }

    private static boolean containsConfidentPose(
            java.util.List<PoseLandmark> landmarks) {
        int confidentLandmarks = 0;
        for (PoseLandmark landmark : landmarks) {
            if (landmark.getInFrameLikelihood() >= MIN_POSE_LANDMARK_CONFIDENCE
                    && ++confidentLandmarks >= MIN_CONFIDENT_POSE_LANDMARKS) {
                return true;
            }
        }
        return false;
    }

    private final class PresenceAnalyzer implements ImageAnalysis.Analyzer {
        private byte[] previousLuma;
        private long lastFaceStartedElapsed;
        private long lastPoseStartedElapsed;
        private long lastFaceHitLogElapsed;
        private long lastPoseHitLogElapsed;
        private int consecutiveMotionFrames;

        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            try {
                if (LUMA_MOTION_DETECTION_ENABLED && detectMotion(imageProxy)) {
                    consecutiveMotionFrames++;
                    if (consecutiveMotionFrames >= MOTION_REQUIRED_FRAMES) {
                        boolean outsideScreenOffGuard = SystemClock.elapsedRealtime()
                                - lastScreenOffElapsed >= SCREEN_OFF_MOTION_GUARD_MS;
                        if (outsideScreenOffGuard) {
                            markPresence("luma motion", true);
                        }
                    }
                } else if (LUMA_MOTION_DETECTION_ENABLED) {
                    consecutiveMotionFrames = 0;
                }
                long now = SystemClock.elapsedRealtime();
                Image mediaImage = imageProxy.getImage();
                boolean faceDue = now - lastFaceStartedElapsed >= FACE_INTERVAL_MS;
                boolean poseDue = now - lastPoseStartedElapsed >= POSE_INTERVAL_MS;
                if (mediaImage != null && (faceDue || poseDue)
                        && visionDetectionRunning.compareAndSet(false, true)) {
                    InputImage input = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.getImageInfo().getRotationDegrees());
                    boolean runPose = poseDue
                            && (!faceDue || lastPoseStartedElapsed <= lastFaceStartedElapsed);
                    if (runPose) {
                        PoseDetector detector = getOrCreatePoseDetector();
                        if (detector == null) {
                            visionDetectionRunning.set(false);
                            imageProxy.close();
                            return;
                        }
                        lastPoseStartedElapsed = now;
                        detector.process(input)
                                .addOnSuccessListener(pose -> {
                                    if (containsConfidentPose(pose.getAllPoseLandmarks())) {
                                        long hitNow = SystemClock.elapsedRealtime();
                                        if (hitNow - lastPoseHitLogElapsed >= 5_000L) {
                                            lastPoseHitLogElapsed = hitNow;
                                            Log.w(TAG, "Pose detector currently reports a person");
                                        }
                                        markPresence("pose", true);
                                    }
                                })
                                .addOnCompleteListener(task -> {
                                    visionDetectionRunning.set(false);
                                    imageProxy.close();
                                });
                    } else {
                        FaceDetector detector = getOrCreateFaceDetector();
                        if (detector == null) {
                            visionDetectionRunning.set(false);
                            imageProxy.close();
                            return;
                        }
                        lastFaceStartedElapsed = now;
                        detector.process(input)
                                .addOnSuccessListener(faces -> {
                                    if (!faces.isEmpty()) {
                                        long hitNow = SystemClock.elapsedRealtime();
                                        if (hitNow - lastFaceHitLogElapsed >= 5_000L) {
                                            lastFaceHitLogElapsed = hitNow;
                                            Log.w(TAG, "Face detector currently reports "
                                                    + faces.size() + " face(s)");
                                        }
                                        markPresence("face", true);
                                    }
                                })
                                .addOnCompleteListener(task -> {
                                    visionDetectionRunning.set(false);
                                    imageProxy.close();
                                });
                    }
                    return;
                }
            } catch (Throwable ignored) {
                // A later frame can recover from transient camera or detector errors.
                visionDetectionRunning.set(false);
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
