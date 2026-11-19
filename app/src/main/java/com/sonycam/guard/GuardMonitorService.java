// Powered by NyphexAI, Developed by Hikaru(i@rua.moe)
package com.sonycam.guard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

/**
 * Foreground service keeping cooperative Camera2 monitoring, watchdog monitors,
 * and the LAN HTTP server alive without contending with system camera users.
 */
public final class GuardMonitorService extends Service
        implements CameraGuardController.AlertListener, MjpegHttpServer.ControlDelegate {

    public static final String ACTION_START_LOCK = "com.sonycam.guard.action.START_LOCK";
    public static final String ACTION_START_ONDEMAND = "com.sonycam.guard.action.START_ONDEMAND";
    public static final String ACTION_START_WATCHDOG = "com.sonycam.guard.action.START_WATCHDOG";
    public static final String ACTION_RESTORE_SAVED_MODE = "com.sonycam.guard.action.RESTORE_SAVED_MODE";
    public static final String ACTION_STOP_SERVICE = "com.sonycam.guard.action.STOP_SERVICE";

    private static final String CHANNEL_STATUS = "guard_status_channel";
    private static final String CHANNEL_ALERT = "guard_alert_channel";
    private static final int STATUS_NOTIF_ID = 1001;
    private static final int ALERT_NOTIF_BASE_ID = 2000;

    private GuardRuntime runtime;
    private MjpegHttpServer httpServer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NotificationManager notificationManager;
    private Handler mainHandler;
    private int alertSeq = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        runtime = GuardRuntime.get(this);
        runtime.setAlertListener(this);
        mainHandler = new Handler(Looper.getMainLooper());
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannels();

        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SonyCamGuard::MonitorWakeLock");
            wakeLock.setReferenceCounted(false);
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SonyCamGuard::MonitorWifiLock");
            wifiLock.setReferenceCounted(false);
        }

        httpServer = new MjpegHttpServer(
                GuardRuntime.HTTP_PORT,
                runtime.authManager,
                runtime.controller,
                runtime.auditLog,
                this
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null
                ? intent.getAction()
                : ACTION_START_WATCHDOG;

        if (ACTION_STOP_SERVICE.equals(action)) {
            runtime.auditLog.log("INFO", "SERVICE_STOPPED", "GuardMonitorService stopped by user");
            stopSelf();
            return START_NOT_STICKY;
        }

        CameraGuardController.GuardMode targetMode = CameraGuardController.GuardMode.WATCHDOG_ONLY;
        boolean persistMode = !(intent != null && intent.getBooleanExtra("boot_fallback", false));
        if (ACTION_RESTORE_SAVED_MODE.equals(action)) {
            persistMode = false;
            CameraGuardController.GuardMode saved = runtime.controller.getSavedGuardMode();
            if (saved != CameraGuardController.GuardMode.WATCHDOG_ONLY && hasCameraPermission()) {
                targetMode = saved;
            }
        } else if (ACTION_START_LOCK.equals(action) && hasCameraPermission()) {
            targetMode = CameraGuardController.GuardMode.COOPERATIVE;
        } else if (ACTION_START_ONDEMAND.equals(action) && hasCameraPermission()) {
            targetMode = CameraGuardController.GuardMode.ON_DEMAND;
        }

        boolean withCameraType = targetMode != CameraGuardController.GuardMode.WATCHDOG_ONLY;
        ensureForegroundAndLocks(withCameraType);
        runtime.controller.startWatchdogs();

        try {
            httpServer.start();
        } catch (Exception e) {
            runtime.auditLog.log("CRITICAL", "HTTP_SERVER_ERROR", "Failed to bind port "
                    + GuardRuntime.HTTP_PORT + ": " + e.getMessage());
        }

        runtime.controller.setGuardMode(targetMode, persistMode);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        runtime.setAlertListener(null);
        if (httpServer != null) {
            httpServer.stop();
        }
        runtime.controller.shutdown();
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSecurityAlert(String level, String message) {
        if (mainHandler != null) {
            mainHandler.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
        }
        if (notificationManager != null) {
            Intent openIntent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification alertNotif = new Notification.Builder(this, CHANNEL_ALERT)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle("BraviaCam Guard 安全警報")
                    .setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            int id = ALERT_NOTIF_BASE_ID + ((++alertSeq) % 10);
            try {
                notificationManager.notify(id, alertNotif);
            } catch (SecurityException ignored) {
            }
        }
    }

    @Override
    public void onStateChanged() {
        if (notificationManager != null) {
            try {
                notificationManager.notify(STATUS_NOTIF_ID, buildStatusNotification());
            } catch (SecurityException ignored) {
            }
        }
    }

    @Override
    public void onGuardModeRequested(CameraGuardController.GuardMode mode) {
        if (mode != CameraGuardController.GuardMode.WATCHDOG_ONLY && hasCameraPermission()) {
            ensureForegroundAndLocks(true);
            runtime.controller.setGuardMode(mode);
        } else {
            runtime.controller.setGuardMode(CameraGuardController.GuardMode.WATCHDOG_ONLY);
            ensureForegroundAndLocks(false);
        }
    }

    @Override
    public void onMediaYieldRequested(boolean enabled) {
        runtime.controller.setYieldOnMediaPlayback(enabled);
    }

    @Override
    public void onBootAutoStartRequested(boolean enabled) {
        runtime.controller.setBootAutoStartEnabled(enabled);
    }

    @Override
    public void onMicCaptureRequested(boolean enabled) {
        runtime.controller.setMicCaptureEnabled(enabled);
        boolean withCamera = runtime.controller.getGuardMode() != CameraGuardController.GuardMode.WATCHDOG_ONLY;
        ensureForegroundAndLocks(withCamera);
    }

    @Override
    public void onProfileRequested(int width, int height, int fps) {
        runtime.controller.setStreamProfile(width, height, fps);
    }

    @Override
    public String getHostIpAddress() {
        return GuardRuntime.resolveLocalIpv4();
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureForegroundAndLocks(boolean withCameraType) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
        }

        Notification notif = buildStatusNotification();
        int camType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        if (withCameraType && hasCameraPermission()) {
            camType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        int fgsType = camType;
        if (withCameraType && runtime.controller.isMicCaptureEnabled() && hasAudioPermission()) {
            fgsType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            startForeground(STATUS_NOTIF_ID, notif, fgsType);
        } catch (RuntimeException ex) {
            runtime.auditLog.log("WARN", "FGS_ELEVATION_FALLBACK",
                    "Falling back FGS type (" + fgsType + "): " + ex.getMessage());
            try {
                startForeground(STATUS_NOTIF_ID, notif, camType);
            } catch (RuntimeException ex2) {
                startForeground(STATUS_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            }
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            try {
                wifiLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    private Notification buildStatusNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String camState = runtime.controller.getCameraState();
        String mode = runtime.controller.getGuardMode().name();
        String micText = runtime.controller.isExternalMicActive()
                ? "外部麥克風警報！"
                : (runtime.controller.isSelfMicRecording() ? "授權收音中" : "麥克風靜默");
        String ip = GuardRuntime.resolveLocalIpv4();
        String text = mode + " / " + camState + " | " + micText + " | http://" + ip + ":" + GuardRuntime.HTTP_PORT;

        return new Notification.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("BraviaCam Guard 運行中")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void createNotificationChannels() {
        if (notificationManager == null) {
            return;
        }
        NotificationChannel statusChannel = new NotificationChannel(
                CHANNEL_STATUS,
                "BraviaCam Guard 狀態",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationChannel alertChannel = new NotificationChannel(
                CHANNEL_ALERT,
                "BraviaCam Guard 資安警報",
                NotificationManager.IMPORTANCE_HIGH
        );
        notificationManager.createNotificationChannel(statusChannel);
        notificationManager.createNotificationChannel(alertChannel);
    }
}
