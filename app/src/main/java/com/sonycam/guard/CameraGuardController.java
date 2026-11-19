// Powered by NyphexAI, Developed by Hikaru(i@rua.moe)
package com.sonycam.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Zero-contention cooperative Camera2 controller and Camera/Microphone/Playback watchdog.
 * Never preempts active system or foreground camera users and yields during TV media playback.
 */
public final class CameraGuardController {

    public enum GuardMode {
        COOPERATIVE,
        ON_DEMAND,
        WATCHDOG_ONLY
    }

    public interface AlertListener {
        void onSecurityAlert(String level, String message);
        void onStateChanged();
    }

    public static final class FrameSnapshot {
        public final long sequence;
        public final byte[] jpeg;

        public FrameSnapshot(long sequence, byte[] jpeg) {
            this.sequence = sequence;
            this.jpeg = jpeg;
        }
    }

    private static final int MOTION_GRID_COLS = 32;
    private static final int MOTION_GRID_ROWS = 18;
    private static final int MOTION_CELL_LUMA_THRESHOLD = 18;
    private static final int MOTION_MIN_CHANGED_CELLS = 22;
    private static final long MOTION_AUDIT_COOLDOWN_MS = 5000L;

    private static final long YIELD_COOLDOWN_MS = 5000L;
    private static final long MEDIA_IDLE_RESUME_MS = 12_000L;
    private static final long ON_DEMAND_IDLE_MS = 10_000L;

    private static final String PREFS_NAME = "guard_prefs";
    private static final String PREF_GUARD_MODE = "pref_guard_mode";
    private static final String PREF_YIELD_MEDIA = "pref_yield_media";
    private static final String PREF_REQ_WIDTH = "pref_req_width";
    private static final String PREF_REQ_HEIGHT = "pref_req_height";
    private static final String PREF_TARGET_FPS = "pref_target_fps";
    private static final String PREF_BOOT_AUTOSTART = "pref_boot_autostart";
    private static final String PREF_MIC_ENABLED = "pref_mic_enabled";
    public static final int MIC_SAMPLE_RATE = 16000;

    private final Context context;
    private final SharedPreferences prefs;
    private final SecurityAuditLog auditLog;
    private final AlertListener alertListener;
    private final CameraManager cameraManager;
    private final AudioManager audioManager;
    private final PowerManager powerManager;

    private HandlerThread workerThread;
    private Handler workerHandler;
    private Executor workerExecutor;

    private volatile boolean started = false;
    private volatile GuardMode guardMode = GuardMode.COOPERATIVE;
    private volatile boolean yieldOnMediaPlayback = true;
    private volatile boolean bootAutoStartEnabled = true;
    private volatile boolean micCaptureEnabled = false;
    private volatile boolean systemCameraAvailable = true;
    private volatile boolean mediaPlaying = false;
    private volatile String activeMediaSummary = "無媒體播放";

    private volatile String targetCameraId = null;
    private volatile String cameraState = "IDLE";
    private volatile String lastError = "";
    private volatile boolean selfHoldingCamera = false;
    private volatile boolean selfOpening = false;

    private volatile int requestedWidth = 1280;
    private volatile int requestedHeight = 720;
    private volatile int activeWidth = 1280;
    private volatile int activeHeight = 720;
    private volatile int targetFps = 10;

    private volatile long framesCaptured = 0L;
    private volatile long lastFrameTimestampMs = 0L;
    private volatile long lastEncodeAttemptMs = 0L;
    private volatile long lastClientDemandMs = 0L;
    private volatile long lastIdleScheduleMs = 0L;

    private volatile boolean micActive = false;
    private volatile boolean selfMicRecording = false;
    private volatile boolean externalMicActive = false;
    private volatile int selfAudioSessionId = -1;
    private volatile int micLevelPercent = 0;
    private volatile int micChannels = 2;
    private volatile String activeMicSummary = "待機中（未偵測到系統收音）";
    private volatile String lastExternalMicSummary = "";
    private volatile long lastMicEventMs = 0L;
    private final CopyOnWriteArrayList<LinkedBlockingQueue<byte[]>> audioSubscribers = new CopyOnWriteArrayList<>();
    private final Object micLock = new Object();
    private volatile AudioRecord activeAudioRecord = null;
    private Thread micCaptureThread = null;

    private volatile boolean motionDetected = false;
    private volatile long lastMotionTimestampMs = 0L;
    private volatile long lastMotionAuditMs = 0L;
    private volatile byte[] latestMotionJpeg = null;

    private final Object frameLock = new Object();
    private long frameSequence = 0L;
    private byte[] latestJpeg = null;

    private byte[] nv21Buffer = null;
    private int[] prevLumaGrid = null;
    private long warmupFrames = 0L;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;

    private final Runnable politeResumeTask = new Runnable() {
        @Override
        public void run() {
            evaluateCameraLifecycle();
        }
    };

    private final Runnable mediaIdleResumeTask = new Runnable() {
        @Override
        public void run() {
            evaluateCameraLifecycle();
        }
    };

    private final Runnable onDemandIdleCloseTask = new Runnable() {
        @Override
        public void run() {
            evaluateCameraLifecycle();
        }
    };

    private final CameraManager.AvailabilityCallback availabilityCallback =
            new CameraManager.AvailabilityCallback() {
                @Override
                public void onCameraAvailable(String cameraId) {
                    handleCameraAvailable(cameraId);
                }

                @Override
                public void onCameraUnavailable(String cameraId) {
                    handleCameraUnavailable(cameraId);
                }
            };

    private final AudioManager.AudioRecordingCallback recordingCallback =
            new AudioManager.AudioRecordingCallback() {
                @Override
                public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    handleRecordingConfigs(configs);
                }
            };

    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    handlePlaybackConfigs(configs);
                }
            };

    public CameraGuardController(Context context, SecurityAuditLog auditLog, AlertListener alertListener) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.auditLog = auditLog;
        this.alertListener = alertListener;
        this.cameraManager = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);

        this.guardMode = parseGuardMode(prefs.getString(PREF_GUARD_MODE, GuardMode.COOPERATIVE.name()));
        this.yieldOnMediaPlayback = prefs.getBoolean(PREF_YIELD_MEDIA, true);
        int savedW = prefs.getInt(PREF_REQ_WIDTH, 1280);
        int savedFps = prefs.getInt(PREF_TARGET_FPS, 10);
        this.requestedWidth = (savedW == 640 || savedW == 1280 || savedW == 1920) ? savedW : 1280;
        this.requestedHeight = (this.requestedWidth == 640) ? 480 : (this.requestedWidth == 1920 ? 1080 : 720);
        this.activeWidth = this.requestedWidth;
        this.activeHeight = this.requestedHeight;
        this.targetFps = Math.max(2, Math.min(savedFps, 30));
        this.bootAutoStartEnabled = prefs.getBoolean(PREF_BOOT_AUTOSTART, true);
        this.micCaptureEnabled = prefs.getBoolean(PREF_MIC_ENABLED, false);
    }

    private static GuardMode parseGuardMode(String raw) {
        if (raw == null) {
            return GuardMode.COOPERATIVE;
        }
        try {
            return GuardMode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return GuardMode.COOPERATIVE;
        }
    }

    public synchronized void startWatchdogs() {
        if (started) {
            return;
        }
        workerThread = new HandlerThread("CameraGuardThread");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        workerExecutor = command -> {
            Handler h = workerHandler;
            if (h != null) {
                h.post(command);
            }
        };

        started = true;
        targetCameraId = selectTargetCameraId();
        systemCameraAvailable = (targetCameraId != null);
        cameraState = "WATCHDOG_ONLY";

        cameraManager.registerAvailabilityCallback(availabilityCallback, workerHandler);
        audioManager.registerAudioRecordingCallback(recordingCallback, workerHandler);
        audioManager.registerAudioPlaybackCallback(playbackCallback, workerHandler);

        workerHandler.post(() -> {
            handleRecordingConfigs(audioManager.getActiveRecordingConfigurations());
            handlePlaybackConfigs(audioManager.getActivePlaybackConfigurations());
        });

        auditLog.log("INFO", "WATCHDOG_STARTED",
                "Cooperative Camera/Mic/Playback watchdog active; targetCameraId="
                        + (targetCameraId != null ? targetCameraId : "none"));
        notifyStateChanged();
    }

    public GuardMode getSavedGuardMode() {
        return parseGuardMode(prefs.getString(PREF_GUARD_MODE, GuardMode.COOPERATIVE.name()));
    }

    public void setGuardMode(GuardMode mode) {
        setGuardMode(mode, true);
    }

    public void setGuardMode(GuardMode mode, boolean persist) {
        if (mode == null) {
            return;
        }
        if (persist) {
            prefs.edit().putString(PREF_GUARD_MODE, mode.name()).apply();
        }
        Handler h = workerHandler;
        if (h == null) {
            this.guardMode = mode;
            return;
        }
        h.post(() -> {
            GuardMode prev = this.guardMode;
            this.guardMode = mode;
            if (mode == GuardMode.ON_DEMAND) {
                lastClientDemandMs = 0L;
            }
            if (prev != mode) {
                auditLog.log("INFO", "GUARD_MODE_CHANGED", "Mode switched: " + prev.name() + " -> " + mode.name());
            }
            evaluateCameraLifecycle();
        });
    }

    public void setYieldOnMediaPlayback(boolean enabled) {
        Handler h = workerHandler;
        this.yieldOnMediaPlayback = enabled;
        prefs.edit().putBoolean(PREF_YIELD_MEDIA, enabled).apply();
        auditLog.log("INFO", "MEDIA_YIELD_UPDATED", "yieldOnMediaPlayback=" + enabled);
        if (h != null) {
            h.post(this::evaluateCameraLifecycle);
        } else {
            notifyStateChanged();
        }
    }

    public boolean isBootAutoStartEnabled() {
        return bootAutoStartEnabled;
    }

    public void setBootAutoStartEnabled(boolean enabled) {
        this.bootAutoStartEnabled = enabled;
        prefs.edit().putBoolean(PREF_BOOT_AUTOSTART, enabled).apply();
        auditLog.log("INFO", "BOOT_AUTOSTART_UPDATED", "bootAutoStart=" + enabled);
        notifyStateChanged();
    }

    public boolean isMicCaptureEnabled() {
        return micCaptureEnabled;
    }

    public void setMicCaptureEnabled(boolean enabled) {
        this.micCaptureEnabled = enabled;
        prefs.edit().putBoolean(PREF_MIC_ENABLED, enabled).apply();
        auditLog.log("INFO", enabled ? "MIC_CAPTURE_ENABLED" : "MIC_CAPTURE_DISABLED",
                enabled ? "Operator enabled dual-mic live audio capture"
                        : "Operator disabled dual-mic live audio capture");
        Handler h = workerHandler;
        if (h != null) {
            h.post(this::evaluateCameraLifecycle);
        } else {
            notifyStateChanged();
        }
    }

    public LinkedBlockingQueue<byte[]> registerAudioSubscriber() {
        LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(32);
        audioSubscribers.add(queue);
        if (guardMode == GuardMode.ON_DEMAND && micCaptureEnabled) {
            notifyClientDemand();
        }
        return queue;
    }

    public void unregisterAudioSubscriber(LinkedBlockingQueue<byte[]> queue) {
        if (queue != null) {
            audioSubscribers.remove(queue);
        }
    }

    public void notifyClientDemand() {
        long now = System.currentTimeMillis();
        lastClientDemandMs = now;
        Handler h = workerHandler;
        if (h == null || !started || guardMode != GuardMode.ON_DEMAND) {
            return;
        }
        if (now - lastIdleScheduleMs >= 1000L) {
            lastIdleScheduleMs = now;
            h.removeCallbacks(onDemandIdleCloseTask);
            h.postDelayed(onDemandIdleCloseTask, ON_DEMAND_IDLE_MS + 200L);
        }
        if ((cameraDevice == null && !selfOpening) || (micCaptureEnabled && !selfMicRecording)) {
            h.post(this::evaluateCameraLifecycle);
        }
    }

    public void setStreamProfile(int width, int height, int fps) {
        int safeFps = Math.max(2, Math.min(fps, 30));
        int safeW = (width == 640 || width == 1280 || width == 1920) ? width : 1280;
        int safeH = (safeW == 640) ? 480 : (safeW == 1920 ? 1080 : 720);
        prefs.edit()
                .putInt(PREF_REQ_WIDTH, safeW)
                .putInt(PREF_REQ_HEIGHT, safeH)
                .putInt(PREF_TARGET_FPS, safeFps)
                .apply();
        Handler h = workerHandler;
        if (h == null) {
            this.requestedWidth = safeW;
            this.requestedHeight = safeH;
            this.activeWidth = safeW;
            this.activeHeight = safeH;
            this.targetFps = safeFps;
            return;
        }
        h.post(() -> {
            boolean sizeChanged = (safeW != requestedWidth || safeH != requestedHeight);
            requestedWidth = safeW;
            requestedHeight = safeH;
            targetFps = safeFps;
            auditLog.log("INFO", "PROFILE_UPDATED",
                    "Requested profile=" + safeW + "x" + safeH + " @" + safeFps + "fps");
            if (sizeChanged && (cameraDevice != null || selfOpening)) {
                closeCameraInternal("IDLE");
                evaluateCameraLifecycle();
            } else {
                notifyStateChanged();
            }
        });
    }

    public synchronized void shutdown() {
        started = false;
        guardMode = GuardMode.WATCHDOG_ONLY;
        Handler h = workerHandler;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            h.post(() -> {
                stopMicCaptureInternal();
                closeCameraInternal("IDLE");
            });
        } else {
            stopMicCaptureInternal();
        }
        try {
            cameraManager.unregisterAvailabilityCallback(availabilityCallback);
        } catch (Exception ignored) {
        }
        try {
            audioManager.unregisterAudioRecordingCallback(recordingCallback);
        } catch (Exception ignored) {
        }
        try {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        } catch (Exception ignored) {
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        workerHandler = null;
        wakeAllFrameWaiters();
    }

    public FrameSnapshot getLatestFrame() {
        synchronized (frameLock) {
            return new FrameSnapshot(frameSequence, latestJpeg);
        }
    }

    public FrameSnapshot waitForNextFrame(long lastSeq, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (frameLock) {
            while (started && isCaptureExpected() && frameSequence <= lastSeq) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    frameLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return new FrameSnapshot(frameSequence, latestJpeg);
        }
    }

    public boolean isCaptureExpected() {
        if (!started || guardMode == GuardMode.WATCHDOG_ONLY) {
            return false;
        }
        if (yieldOnMediaPlayback && mediaPlaying) {
            return false;
        }
        if (!systemCameraAvailable && !selfHoldingCamera && !selfOpening) {
            return false;
        }
        if (guardMode == GuardMode.ON_DEMAND) {
            return (System.currentTimeMillis() - lastClientDemandMs) < ON_DEMAND_IDLE_MS;
        }
        return true;
    }

    public byte[] getLatestMotionJpeg() {
        return latestMotionJpeg;
    }

    public GuardMode getGuardMode() {
        return guardMode;
    }

    public boolean isYieldOnMediaPlayback() {
        return yieldOnMediaPlayback;
    }

    public boolean isMediaPlaying() {
        return mediaPlaying;
    }

    public String getActiveMediaSummary() {
        return activeMediaSummary;
    }

    public boolean isSystemCameraAvailable() {
        return systemCameraAvailable;
    }

    public String getCameraState() {
        return cameraState;
    }

    public String getTargetCameraId() {
        return targetCameraId;
    }

    public boolean isMicActive() {
        return micActive;
    }

    public boolean isSelfMicRecording() {
        return selfMicRecording;
    }

    public boolean isExternalMicActive() {
        return externalMicActive;
    }

    public int getMicLevelPercent() {
        return micLevelPercent;
    }

    public int getMicChannelCount() {
        return micChannels;
    }

    public String getActiveMicSummary() {
        return activeMicSummary;
    }

    public long getFramesCaptured() {
        return framesCaptured;
    }

    public int getActiveWidth() {
        return activeWidth;
    }

    public int getActiveHeight() {
        return activeHeight;
    }

    public int getTargetFps() {
        return targetFps;
    }

    public boolean isMotionDetected() {
        return motionDetected;
    }

    public long getLastMotionTimestampMs() {
        return lastMotionTimestampMs;
    }

    public JSONObject buildStatusJson(String hostIp, int port) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("cameraId", targetCameraId != null ? targetCameraId : "none");
            obj.put("cameraState", cameraState);
            obj.put("guardMode", guardMode.name());
            obj.put("monitoringDesired", guardMode != GuardMode.WATCHDOG_ONLY);
            obj.put("yieldOnMediaPlayback", yieldOnMediaPlayback);
            obj.put("mediaPlaying", mediaPlaying);
            obj.put("activeMediaSummary", activeMediaSummary);
            obj.put("systemCameraAvailable", systemCameraAvailable);
            obj.put("selfHoldingCamera", selfHoldingCamera);
            obj.put("width", activeWidth);
            obj.put("height", activeHeight);
            obj.put("targetFps", targetFps);
            obj.put("framesCaptured", framesCaptured);
            obj.put("lastFrameTimestampMs", lastFrameTimestampMs);
            obj.put("micActive", micActive);
            obj.put("micCaptureEnabled", micCaptureEnabled);
            obj.put("selfMicRecording", selfMicRecording);
            obj.put("externalMicActive", externalMicActive);
            obj.put("micLevelPercent", micLevelPercent);
            obj.put("micChannels", micChannels);
            obj.put("activeMicSummary", activeMicSummary);
            obj.put("lastMicEventMs", lastMicEventMs);
            obj.put("motionDetected", motionDetected);
            obj.put("lastMotionTimestampMs", lastMotionTimestampMs);
            obj.put("hasMotionSnapshot", latestMotionJpeg != null);
            obj.put("preemptionCount", auditLog.getPreemptionCount());
            obj.put("yieldCount", auditLog.getYieldCount());
            obj.put("externalCameraCount", auditLog.getExternalCameraCount());
            obj.put("micActivationCount", auditLog.getMicActivationCount());
            obj.put("authFailCount", auditLog.getAuthFailCount());
            obj.put("motionCount", auditLog.getMotionCount());
            obj.put("lastError", lastError);
            obj.put("hostIp", hostIp);
            obj.put("port", port);
            obj.put("displayInteractive", powerManager != null && powerManager.isInteractive());
            obj.put("bootAutoStart", bootAutoStartEnabled);
            obj.put("micPermissionDeclared", true);
        } catch (Exception ignored) {
        }
        return obj;
    }

    private void evaluateCameraLifecycle() {
        if (!started) {
            return;
        }
        Handler h = workerHandler;
        long now = System.currentTimeMillis();

        if (guardMode == GuardMode.WATCHDOG_ONLY) {
            if (h != null) {
                h.removeCallbacks(politeResumeTask);
                h.removeCallbacks(mediaIdleResumeTask);
                h.removeCallbacks(onDemandIdleCloseTask);
            }
            stopMicCaptureInternal();
            if (cameraDevice != null || selfOpening) {
                closeCameraInternal("WATCHDOG_ONLY");
            } else {
                cameraState = systemCameraAvailable ? "WATCHDOG_ONLY" : "YIELDED_EXTERNAL";
            }
            wakeAllFrameWaiters();
            notifyStateChanged();
            return;
        }

        if (yieldOnMediaPlayback && mediaPlaying) {
            if (h != null) {
                h.removeCallbacks(politeResumeTask);
                h.removeCallbacks(mediaIdleResumeTask);
            }
            stopMicCaptureInternal();
            if (cameraDevice != null || selfOpening) {
                closeCameraInternal("YIELDED_MEDIA");
                auditLog.log("INFO", "CAMERA_YIELDED_MEDIA",
                        "Released camera for Sony BRAVIA CAM optimization during playback: " + activeMediaSummary);
            } else {
                cameraState = "YIELDED_MEDIA";
            }
            wakeAllFrameWaiters();
            notifyStateChanged();
            return;
        }

        boolean demandActive = (guardMode == GuardMode.COOPERATIVE)
                || (guardMode == GuardMode.ON_DEMAND
                && ((now - lastClientDemandMs) < ON_DEMAND_IDLE_MS || !audioSubscribers.isEmpty()));

        if (!demandActive) {
            stopMicCaptureInternal();
            if (cameraDevice != null || selfOpening) {
                closeCameraInternal("ON_DEMAND_IDLE");
                auditLog.log("INFO", "ON_DEMAND_RELEASED",
                        "No active viewer for 10s; released camera 112 for system features");
            } else {
                cameraState = systemCameraAvailable ? "ON_DEMAND_IDLE" : "YIELDED_EXTERNAL";
            }
            wakeAllFrameWaiters();
            notifyStateChanged();
            return;
        }

        if (micCaptureEnabled) {
            startMicCaptureInternal();
        } else {
            stopMicCaptureInternal();
        }

        if (!systemCameraAvailable && !selfHoldingCamera && !selfOpening) {
            if (cameraDevice != null) {
                closeCameraInternal("YIELDED_EXTERNAL");
            } else if (!"DISCONNECTED_USB".equals(cameraState)) {
                cameraState = "YIELDED_EXTERNAL";
            }
            wakeAllFrameWaiters();
            notifyStateChanged();
            return;
        }

        if (cameraDevice == null && !selfOpening) {
            openCameraInternal();
        }
    }

    private String selectTargetCameraId() {
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids == null || ids.length == 0) {
                return null;
            }
            for (String id : ids) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    return id;
                }
            }
            return ids[0];
        } catch (Exception e) {
            lastError = "Camera enumeration error: " + e.getMessage();
            return null;
        }
    }

    private boolean isCameraPresentInSystem(String cameraId) {
        if (cameraId == null) {
            return false;
        }
        try {
            String[] ids = cameraManager.getCameraIdList();
            for (String id : ids) {
                if (cameraId.equals(id)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void handleCameraAvailable(String cameraId) {
        if (!started) {
            return;
        }
        if (targetCameraId == null) {
            targetCameraId = selectTargetCameraId();
        }
        if (targetCameraId == null || !targetCameraId.equals(cameraId)) {
            return;
        }

        boolean wasYieldedOrDetached = "YIELDED_EXTERNAL".equals(cameraState)
                || "EXTERNAL_IN_USE".equals(cameraState)
                || "DISCONNECTED_USB".equals(cameraState);
        systemCameraAvailable = true;

        if ("YIELDED_EXTERNAL".equals(cameraState) || "EXTERNAL_IN_USE".equals(cameraState)) {
            auditLog.log("INFO", "EXTERNAL_CAMERA_RELEASED", "System/external app released camera " + cameraId);
        } else if ("DISCONNECTED_USB".equals(cameraState)) {
            auditLog.log("INFO", "CAMERA_USB_ATTACHED", "Camera " + cameraId + " re-attached");
        }

        Handler h = workerHandler;
        if (guardMode == GuardMode.COOPERATIVE && !(yieldOnMediaPlayback && mediaPlaying)) {
            if (wasYieldedOrDetached && h != null) {
                h.removeCallbacks(politeResumeTask);
                h.postDelayed(politeResumeTask, YIELD_COOLDOWN_MS);
            } else {
                evaluateCameraLifecycle();
            }
        } else {
            evaluateCameraLifecycle();
        }
    }

    private void handleCameraUnavailable(String cameraId) {
        if (!started) {
            return;
        }
        if (targetCameraId != null && !targetCameraId.equals(cameraId)) {
            return;
        }
        if (selfHoldingCamera || selfOpening) {
            return;
        }

        Handler h = workerHandler;
        if (h != null) {
            h.removeCallbacks(politeResumeTask);
        }
        systemCameraAvailable = false;

        if (!isCameraPresentInSystem(cameraId)) {
            cameraState = "DISCONNECTED_USB";
            auditLog.log("INFO", "CAMERA_USB_DETACHED", "Camera " + cameraId + " disconnected from USB");
        } else {
            cameraState = "YIELDED_EXTERNAL";
            auditLog.log("WARN", "EXTERNAL_CAMERA_ACTIVE",
                    "System or external app opened camera " + cameraId + "; yielding cleanly");
        }
        wakeAllFrameWaiters();
        notifyStateChanged();
    }

    private void handlePlaybackConfigs(List<AudioPlaybackConfiguration> configs) {
        if (!started) {
            return;
        }
        boolean foundMedia = false;
        String summary = "無媒體播放";

        if (configs != null) {
            for (AudioPlaybackConfiguration cfg : configs) {
                AudioAttributes attrs = cfg.getAudioAttributes();
                if (attrs == null) {
                    continue;
                }
                int usage = attrs.getUsage();
                if (isActiveMediaUsage(usage)) {
                    foundMedia = true;
                    summary = usageLabel(usage);
                    break;
                }
            }
        }

        Handler h = workerHandler;
        if (foundMedia) {
            if (h != null) {
                h.removeCallbacks(mediaIdleResumeTask);
            }
            boolean changed = !mediaPlaying || !summary.equals(activeMediaSummary);
            mediaPlaying = true;
            activeMediaSummary = summary;
            if (changed) {
                evaluateCameraLifecycle();
            }
        } else {
            boolean wasPlaying = mediaPlaying;
            mediaPlaying = false;
            activeMediaSummary = "無媒體播放";
            if (wasPlaying) {
                if (h != null && guardMode == GuardMode.COOPERATIVE && yieldOnMediaPlayback) {
                    h.removeCallbacks(mediaIdleResumeTask);
                    h.postDelayed(mediaIdleResumeTask, MEDIA_IDLE_RESUME_MS);
                    notifyStateChanged();
                } else {
                    evaluateCameraLifecycle();
                }
            }
        }
    }

    private static boolean isActiveMediaUsage(int usage) {
        return usage == AudioAttributes.USAGE_MEDIA
                || usage == AudioAttributes.USAGE_GAME
                || usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
                || usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
                || usage == AudioAttributes.USAGE_ASSISTANT;
    }

    private static String usageLabel(int usage) {
        if (usage == AudioAttributes.USAGE_MEDIA) {
            return "影音串流 (USAGE_MEDIA)";
        }
        if (usage == AudioAttributes.USAGE_GAME) {
            return "遊戲畫面 (USAGE_GAME)";
        }
        if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
                || usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING) {
            return "視訊 / 語音通話";
        }
        if (usage == AudioAttributes.USAGE_ASSISTANT) {
            return "語音助理互動";
        }
        return "播放使用中 (" + usage + ")";
    }

    private void handleRecordingConfigs(List<AudioRecordingConfiguration> configs) {
        if (!started) {
            return;
        }
        boolean prevExternal = externalMicActive;
        boolean prevAny = micActive;
        boolean sawSelf = false;
        boolean sawExternal = false;
        StringBuilder extSb = new StringBuilder();

        if (configs != null) {
            for (AudioRecordingConfiguration cfg : configs) {
                int sessionId = cfg.getClientAudioSessionId();
                if (selfAudioSessionId > 0 && sessionId == selfAudioSessionId) {
                    sawSelf = true;
                    continue;
                }
                if (sawExternal) {
                    extSb.append(" | ");
                }
                sawExternal = true;
                AudioDeviceInfo dev = cfg.getAudioDevice();
                String devName = dev != null && dev.getProductName() != null
                        ? dev.getProductName().toString()
                        : "AudioDevice";
                int devType = dev != null ? dev.getType() : -1;
                extSb.append(devName)
                        .append(" (devType=").append(devType)
                        .append(", src=").append(cfg.getClientAudioSource())
                        .append(", silenced=").append(cfg.isClientSilenced())
                        .append(")");
            }
        }

        externalMicActive = sawExternal;
        micActive = sawExternal || sawSelf || selfMicRecording;
        lastMicEventMs = System.currentTimeMillis();

        if (sawExternal) {
            String extSummary = extSb.toString();
            activeMicSummary = "外部收音警報: " + extSummary;
            if (!prevExternal || !extSummary.equals(lastExternalMicSummary)) {
                lastExternalMicSummary = extSummary;
                auditLog.log("WARN", "MIC_RECORDING_ACTIVE",
                        "External system microphone recording detected: " + extSummary);
                if (alertListener != null) {
                    alertListener.onSecurityAlert("CRITICAL",
                            "麥克風警報：偵測到外部程式正在錄音 (" + extSummary + ")！");
                }
            }
        } else if (sawSelf || selfMicRecording) {
            lastExternalMicSummary = "";
            activeMicSummary = "本機授權收音中 (16kHz " + (micChannels == 2 ? "雙聲道立體聲" : "單聲道") + ")";
        } else {
            lastExternalMicSummary = "";
            activeMicSummary = "待機中（未偵測到系統收音）";
            if (prevAny) {
                auditLog.log("INFO", "MIC_RECORDING_IDLE", "All system microphone recording stopped");
            }
        }
        notifyStateChanged();
    }

    private AudioDeviceInfo findPreferredUsbMic() {
        try {
            AudioDeviceInfo[] inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            AudioDeviceInfo usbFallback = null;
            for (AudioDeviceInfo dev : inputs) {
                CharSequence pname = dev.getProductName();
                if (pname != null && pname.toString().contains("CMU-BC1")) {
                    return dev;
                }
                if (dev.getType() == AudioDeviceInfo.TYPE_USB_DEVICE && usbFallback == null) {
                    usbFallback = dev;
                }
            }
            return usbFallback;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void startMicCaptureInternal() {
        synchronized (micLock) {
            if (!started || !micCaptureEnabled || selfMicRecording) {
                return;
            }
            try {
                int chConfig = AudioFormat.CHANNEL_IN_STEREO;
                int chCount = 2;
                int minBuf = AudioRecord.getMinBufferSize(MIC_SAMPLE_RATE, chConfig, AudioFormat.ENCODING_PCM_16BIT);
                if (minBuf <= 0) {
                    chConfig = AudioFormat.CHANNEL_IN_MONO;
                    chCount = 1;
                    minBuf = AudioRecord.getMinBufferSize(MIC_SAMPLE_RATE, chConfig, AudioFormat.ENCODING_PCM_16BIT);
                }
                if (minBuf <= 0) {
                    minBuf = MIC_SAMPLE_RATE * chCount * 2;
                }
                int bufSize = Math.max(minBuf * 2, MIC_SAMPLE_RATE * chCount * 2 / 2);

                AudioRecord record = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        MIC_SAMPLE_RATE,
                        chConfig,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                );
                if (record.getState() != AudioRecord.STATE_INITIALIZED && chCount == 2) {
                    record.release();
                    chConfig = AudioFormat.CHANNEL_IN_MONO;
                    chCount = 1;
                    minBuf = AudioRecord.getMinBufferSize(MIC_SAMPLE_RATE, chConfig, AudioFormat.ENCODING_PCM_16BIT);
                    bufSize = Math.max(Math.max(minBuf, 4096) * 2, MIC_SAMPLE_RATE);
                    record = new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            MIC_SAMPLE_RATE,
                            chConfig,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufSize
                    );
                }
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    record.release();
                    auditLog.log("WARN", "MIC_OPEN_FAILED", "AudioRecord initialization failed");
                    return;
                }

                AudioDeviceInfo usbMic = findPreferredUsbMic();
                if (usbMic != null) {
                    record.setPreferredDevice(usbMic);
                }

                // Set session ID before startRecording() so AudioRecordingCallback ignores our own session
                selfAudioSessionId = record.getAudioSessionId();
                micChannels = chCount;
                activeAudioRecord = record;
                selfMicRecording = true;
                micActive = true;
                if (!externalMicActive) {
                    activeMicSummary = "本機授權收音中 (16kHz " + (chCount == 2 ? "雙聲道立體聲" : "單聲道") + ")";
                }

                record.startRecording();
                auditLog.log("INFO", "MIC_CAPTURE_STARTED",
                        "Self mic started (session=" + selfAudioSessionId + ", 16000Hz, ch=" + chCount + ")");

                final AudioRecord recRef = record;
                final int chunkBytes = (MIC_SAMPLE_RATE * chCount * 2) / 10; // ~100ms chunks
                micCaptureThread = new Thread(() -> runMicReadLoop(recRef, chunkBytes), "GuardMicThread");
                micCaptureThread.start();
                notifyStateChanged();
            } catch (SecurityException se) {
                selfMicRecording = false;
                selfAudioSessionId = -1;
                auditLog.log("CRITICAL", "MIC_PERMISSION_DENIED",
                        "RECORD_AUDIO permission not granted: " + se.getMessage());
                notifyStateChanged();
            } catch (Exception e) {
                selfMicRecording = false;
                selfAudioSessionId = -1;
                auditLog.log("WARN", "MIC_OPEN_FAILED", "Mic start error: " + e.getMessage());
                notifyStateChanged();
            }
        }
    }

    private void runMicReadLoop(AudioRecord record, int chunkBytes) {
        byte[] buf = new byte[chunkBytes];
        while (selfMicRecording && activeAudioRecord == record) {
            int read = record.read(buf, 0, buf.length);
            if (read <= 0) {
                if (read < 0) {
                    break;
                }
                continue;
            }
            long sumSq = 0L;
            int samples = read / 2;
            for (int i = 0; i + 1 < read; i += 2) {
                int s = (buf[i] & 0xFF) | (buf[i + 1] << 8);
                sumSq += (long) s * s;
            }
            if (samples > 0) {
                double rms = Math.sqrt((double) sumSq / samples);
                micLevelPercent = (int) Math.min(100L, Math.round((rms / 12000.0) * 100.0));
            }
            if (!audioSubscribers.isEmpty()) {
                byte[] copy = Arrays.copyOf(buf, read);
                for (LinkedBlockingQueue<byte[]> q : audioSubscribers) {
                    if (q.remainingCapacity() == 0) {
                        q.poll();
                    }
                    q.offer(copy);
                }
            }
        }
        micLevelPercent = 0;
    }

    private void stopMicCaptureInternal() {
        AudioRecord recordToRelease;
        Thread threadToJoin;
        boolean wasRecording;
        synchronized (micLock) {
            wasRecording = selfMicRecording;
            selfMicRecording = false;
            selfAudioSessionId = -1;
            micLevelPercent = 0;
            recordToRelease = activeAudioRecord;
            activeAudioRecord = null;
            threadToJoin = micCaptureThread;
            micCaptureThread = null;
        }
        if (recordToRelease != null) {
            try {
                recordToRelease.stop();
            } catch (Exception ignored) {
            }
            try {
                recordToRelease.release();
            } catch (Exception ignored) {
            }
        }
        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            try {
                threadToJoin.join(300L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        for (LinkedBlockingQueue<byte[]> q : audioSubscribers) {
            q.offer(new byte[0]);
        }
        if (wasRecording) {
            micActive = externalMicActive;
            if (!externalMicActive) {
                activeMicSummary = "待機中（未偵測到系統收音）";
            }
            auditLog.log("INFO", "MIC_CAPTURE_STOPPED", "Self mic capture stopped");
            notifyStateChanged();
        }
    }

    private void openCameraInternal() {
        if (!started || selfOpening || cameraDevice != null) {
            return;
        }
        // Invariant 1: NEVER evict or fight an active system/foreground holder
        if (!systemCameraAvailable) {
            cameraState = "YIELDED_EXTERNAL";
            wakeAllFrameWaiters();
            notifyStateChanged();
            return;
        }
        if (yieldOnMediaPlayback && mediaPlaying) {
            cameraState = "YIELDED_MEDIA";
            wakeAllFrameWaiters();
            notifyStateChanged();
            return;
        }

        String camId = targetCameraId != null ? targetCameraId : selectTargetCameraId();
        targetCameraId = camId;
        if (camId == null) {
            systemCameraAvailable = false;
            cameraState = "DISCONNECTED_USB";
            lastError = "CMU-BC1 camera not found (check USB & lens cover)";
            wakeAllFrameWaiters();
            notifyStateChanged();
            return;
        }

        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(camId);
            Size chosenSize = chooseYuvSize(chars, requestedWidth, requestedHeight);
            Range<Integer> chosenFpsRange = chooseFpsRange(chars, targetFps);

            activeWidth = chosenSize.getWidth();
            activeHeight = chosenSize.getHeight();
            nv21Buffer = new byte[activeWidth * activeHeight * 3 / 2];
            prevLumaGrid = null;
            warmupFrames = 0L;

            if (imageReader != null) {
                imageReader.close();
            }
            imageReader = ImageReader.newInstance(activeWidth, activeHeight, ImageFormat.YUV_420_888, 3);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, workerHandler);

            selfOpening = true;
            cameraState = "OPENING";
            lastError = "";
            notifyStateChanged();

            cameraManager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    selfOpening = false;
                    if (!started || !isCaptureExpected()) {
                        camera.close();
                        evaluateCameraLifecycle();
                        return;
                    }
                    cameraDevice = camera;
                    selfHoldingCamera = true;
                    createCaptureSession(camera, chosenFpsRange);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    selfOpening = false;
                    handleCameraLost(camera, "DISCONNECTED", 0);
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    selfOpening = false;
                    handleCameraLost(camera, "ERROR", error);
                }
            }, workerHandler);
        } catch (SecurityException se) {
            selfOpening = false;
            cameraState = "ERROR";
            lastError = "Missing CAMERA permission";
            auditLog.log("CRITICAL", "CAMERA_PERMISSION_DENIED", se.getMessage());
            wakeAllFrameWaiters();
            notifyStateChanged();
        } catch (CameraAccessException cae) {
            selfOpening = false;
            lastError = "CameraAccessException: " + cae.getReason();
            if (cae.getReason() == CameraAccessException.CAMERA_IN_USE
                    || cae.getReason() == CameraAccessException.MAX_CAMERAS_IN_USE) {
                systemCameraAvailable = false;
                cameraState = "YIELDED_EXTERNAL";
                auditLog.log("WARN", "CAMERA_YIELDED_EXTERNAL",
                        "Camera " + camId + " held by system or another app; yielding");
            } else {
                cameraState = "ERROR";
                auditLog.log("WARN", "CAMERA_OPEN_FAILED", lastError);
            }
            wakeAllFrameWaiters();
            notifyStateChanged();
        } catch (Exception e) {
            selfOpening = false;
            cameraState = "ERROR";
            lastError = "Open error: " + e.getMessage();
            auditLog.log("WARN", "CAMERA_OPEN_FAILED", lastError);
            wakeAllFrameWaiters();
            notifyStateChanged();
        }
    }

    private void createCaptureSession(CameraDevice camera, Range<Integer> fpsRange) {
        try {
            Surface surface = imageReader.getSurface();
            OutputConfiguration outputConfig = new OutputConfiguration(surface);
            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(outputConfig),
                    workerExecutor,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (!started || cameraDevice != camera || !isCaptureExpected()) {
                                session.close();
                                evaluateCameraLifecycle();
                                return;
                            }
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(surface);
                                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                if (fpsRange != null) {
                                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                                }
                                session.setRepeatingRequest(builder.build(), null, workerHandler);
                                cameraState = "GUARD_LOCKED";
                                lastError = "";
                                auditLog.log("INFO", "CAMERA_LOCKED",
                                        "Camera " + targetCameraId + " active (" + activeWidth + "x" + activeHeight
                                                + ", mode=" + guardMode.name() + ", aeRange=" + fpsRange + ")");
                                notifyStateChanged();
                            } catch (Exception e) {
                                lastError = "Repeating request error: " + e.getMessage();
                                closeCameraInternal("ERROR");
                                wakeAllFrameWaiters();
                                notifyStateChanged();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            lastError = "CameraCaptureSession configure failed";
                            auditLog.log("WARN", "CAMERA_SESSION_FAILED", lastError);
                            closeCameraInternal("ERROR");
                            wakeAllFrameWaiters();
                            notifyStateChanged();
                        }
                    });
            camera.createCaptureSession(sessionConfig);
        } catch (Exception e) {
            lastError = "Create session failed: " + e.getMessage();
            closeCameraInternal("ERROR");
            wakeAllFrameWaiters();
            notifyStateChanged();
        }
    }

    private void handleCameraLost(CameraDevice camera, String reason, int errorCode) {
        if (camera != null && camera == cameraDevice) {
            cameraDevice = null;
        }
        if (camera != null) {
            try {
                camera.close();
            } catch (Exception ignored) {
            }
        }
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Exception ignored) {
            }
            captureSession = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }
        selfHoldingCamera = false;
        clearLatestJpeg();

        if (guardMode == GuardMode.WATCHDOG_ONLY) {
            cameraState = "WATCHDOG_ONLY";
            wakeAllFrameWaiters();
            notifyStateChanged();
            return;
        }

        boolean present = isCameraPresentInSystem(targetCameraId);
        if (!present) {
            systemCameraAvailable = false;
            cameraState = "DISCONNECTED_USB";
            lastError = "USB camera detached (standby or unplugged)";
            auditLog.log("INFO", "CAMERA_USB_DETACHED", "Camera " + targetCameraId + " lost: " + reason);
        } else if ("DISCONNECTED".equals(reason) || errorCode == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) {
            // Invariant 2: Yield politely without reclaiming until AvailabilityCallback says released
            systemCameraAvailable = false;
            cameraState = "YIELDED_EXTERNAL";
            lastError = "Yielded to system or foreground app";
            auditLog.log("WARN", "CAMERA_YIELDED_EXTERNAL",
                    "Yielded camera " + targetCameraId + " to system/foreground app (" + reason + ", code=" + errorCode + ")");
        } else {
            cameraState = "ERROR";
            lastError = "Camera error " + errorCode;
            auditLog.log("WARN", "CAMERA_ERROR", "Camera " + targetCameraId + " error=" + errorCode);
        }
        wakeAllFrameWaiters();
        notifyStateChanged();
    }

    private void closeCameraInternal(String nextState) {
        selfOpening = false;
        selfHoldingCamera = false;
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Exception ignored) {
            }
            captureSession = null;
        }
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Exception ignored) {
            }
            cameraDevice = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }
        clearLatestJpeg();
        cameraState = nextState;
    }

    private void clearLatestJpeg() {
        synchronized (frameLock) {
            latestJpeg = null;
        }
    }

    private void wakeAllFrameWaiters() {
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            long now = System.currentTimeMillis();
            long minIntervalMs = Math.max(30L, 1000L / Math.max(1, targetFps));
            if (now - lastEncodeAttemptMs < minIntervalMs) {
                return;
            }
            lastEncodeAttemptMs = now;

            int width = image.getWidth();
            int height = image.getHeight();
            int expectedSize = width * height * 3 / 2;
            if (nv21Buffer == null || nv21Buffer.length != expectedSize) {
                nv21Buffer = new byte[expectedSize];
            }

            yuv420ToNv21(image, nv21Buffer);
            boolean motionNow = detectMotion(nv21Buffer, width, height, now);

            YuvImage yuvImage = new YuvImage(nv21Buffer, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 80, baos);
            byte[] jpegBytes = baos.toByteArray();

            framesCaptured++;
            lastFrameTimestampMs = now;

            if (motionNow) {
                latestMotionJpeg = jpegBytes;
            }

            synchronized (frameLock) {
                latestJpeg = jpegBytes;
                frameSequence++;
                frameLock.notifyAll();
            }
        } catch (Exception e) {
            lastError = "Frame encode error: " + e.getMessage();
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private boolean detectMotion(byte[] nv21, int width, int height, long now) {
        warmupFrames++;
        int[] grid = new int[MOTION_GRID_COLS * MOTION_GRID_ROWS];
        int cellW = Math.max(1, width / MOTION_GRID_COLS);
        int cellH = Math.max(1, height / MOTION_GRID_ROWS);
        int stepX = Math.max(1, cellW / 4);
        int stepY = Math.max(1, cellH / 4);

        for (int gy = 0; gy < MOTION_GRID_ROWS; gy++) {
            int yStart = gy * cellH;
            for (int gx = 0; gx < MOTION_GRID_COLS; gx++) {
                int xStart = gx * cellW;
                int sum = 0;
                int samples = 0;
                for (int dy = 0; dy < cellH; dy += stepY) {
                    int rowOffset = (yStart + dy) * width;
                    for (int dx = 0; dx < cellW; dx += stepX) {
                        sum += (nv21[rowOffset + xStart + dx] & 0xFF);
                        samples++;
                    }
                }
                grid[gy * MOTION_GRID_COLS + gx] = samples > 0 ? (sum / samples) : 0;
            }
        }

        boolean triggered = false;
        if (prevLumaGrid != null && warmupFrames > 10) {
            int changed = 0;
            for (int i = 0; i < grid.length; i++) {
                if (Math.abs(grid[i] - prevLumaGrid[i]) >= MOTION_CELL_LUMA_THRESHOLD) {
                    changed++;
                }
            }
            if (changed >= MOTION_MIN_CHANGED_CELLS) {
                triggered = true;
                motionDetected = true;
                lastMotionTimestampMs = now;
                if (now - lastMotionAuditMs >= MOTION_AUDIT_COOLDOWN_MS) {
                    lastMotionAuditMs = now;
                    auditLog.recordMotionEvent();
                }
            } else if (motionDetected && (now - lastMotionTimestampMs) > 5000L) {
                motionDetected = false;
            }
        }
        prevLumaGrid = grid;
        return triggered;
    }

    private static void yuv420ToNv21(Image image, byte[] outNv21) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();

        int pos = 0;
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.rewind();
            yBuffer.get(outNv21, 0, width * height);
            pos = width * height;
        } else {
            byte[] rowData = new byte[yRowStride];
            for (int row = 0; row < height; row++) {
                int rowPos = row * yRowStride;
                if (yPixelStride == 1) {
                    yBuffer.position(rowPos);
                    yBuffer.get(outNv21, pos, width);
                    pos += width;
                } else {
                    yBuffer.position(rowPos);
                    int readLen = Math.min(yRowStride, yBuffer.remaining());
                    yBuffer.get(rowData, 0, readLen);
                    for (int col = 0; col < width; col++) {
                        outNv21[pos++] = rowData[col * yPixelStride];
                    }
                }
            }
        }

        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();

        int chromaHeight = height / 2;
        int chromaWidth = width / 2;

        byte[] uRow = new byte[uRowStride];
        byte[] vRow = new byte[vRowStride];

        for (int row = 0; row < chromaHeight; row++) {
            int uStart = row * uRowStride;
            int vStart = row * vRowStride;
            uBuffer.position(uStart);
            vBuffer.position(vStart);
            int uLen = Math.min(uRowStride, uBuffer.remaining());
            int vLen = Math.min(vRowStride, vBuffer.remaining());
            uBuffer.get(uRow, 0, uLen);
            vBuffer.get(vRow, 0, vLen);

            for (int col = 0; col < chromaWidth; col++) {
                outNv21[pos++] = vRow[col * vPixelStride];
                outNv21[pos++] = uRow[col * uPixelStride];
            }
        }
    }

    private static Size chooseYuvSize(CameraCharacteristics chars, int reqW, int reqH) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return new Size(1280, 720);
        }
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) {
            return new Size(1280, 720);
        }
        Size best = sizes[0];
        long bestDiff = Long.MAX_VALUE;
        long targetPixels = (long) reqW * reqH;
        for (Size s : sizes) {
            if (s.getWidth() == reqW && s.getHeight() == reqH) {
                return s;
            }
            long diff = Math.abs((long) s.getWidth() * s.getHeight() - targetPixels);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    private static Range<Integer> chooseFpsRange(CameraCharacteristics chars, int targetFps) {
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        Range<Integer> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Range<Integer> r : ranges) {
            int upper = r.getUpper();
            int lower = r.getLower();
            int score;
            if (upper >= targetFps) {
                score = (upper - targetFps) * 10 + Math.abs(targetFps - lower);
            } else {
                score = 1000 + (targetFps - upper) * 10;
            }
            if (score < bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    private void notifyStateChanged() {
        if (alertListener != null) {
            alertListener.onStateChanged();
        }
    }
}
