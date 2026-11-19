// Powered by NyphexAI, Developed by Hikaru(i@rua.moe)
package com.sonycam.guard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * D-pad friendly Android TV console displaying connection URL, access token,
 * cooperative Camera/Mic watchdog states, and audit log.
 */
public final class MainActivity extends Activity {
    private static final int REQ_CAMERA = 101;
    private static final int REQ_NOTIF = 102;
    private static final int REQ_AUDIO = 103;

    private GuardRuntime runtime;
    private Handler uiHandler;

    private TextView statusTitleView;
    private TextView cameraInfoView;
    private TextView micInfoView;
    private TextView endpointInfoView;
    private TextView auditTextView;
    private Button micToggleBtn;
    private Button mediaYieldBtn;
    private Button bootAutoBtn;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            updateUi();
            uiHandler.postDelayed(this, 1500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runtime = GuardRuntime.get(this);
        uiHandler = new Handler(Looper.getMainLooper());

        buildTvLayout();
        handleIntentExtras(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentExtras(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        uiHandler.post(refreshTask);
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiHandler.removeCallbacks(refreshTask);
    }

    private void handleIntentExtras(Intent intent) {
        if (intent != null) {
            String bootstrapToken = intent.getStringExtra("bootstrap_token");
            if (bootstrapToken != null && !bootstrapToken.isEmpty()) {
                runtime.authManager.bootstrapTokenIfUnsetOrMatch(bootstrapToken);
            }
            boolean autoStart = intent.getBooleanExtra("auto_start", false);
            if (autoStart) {
                startModeWithPermissionCheck(GuardMonitorService.ACTION_RESTORE_SAVED_MODE);
                return;
            }
        }
        if (hasCameraPermission()) {
            startMonitorService(GuardMonitorService.ACTION_RESTORE_SAVED_MODE);
        } else {
            startMonitorService(GuardMonitorService.ACTION_START_WATCHDOG);
            requestCameraAndNotifPermissions();
        }
    }

    private void startModeWithPermissionCheck(String action) {
        if (hasCameraPermission()) {
            startMonitorService(action);
        } else {
            startMonitorService(GuardMonitorService.ACTION_START_WATCHDOG);
            requestCameraAndNotifPermissions();
        }
    }

    private void startMonitorService(String action) {
        Intent svc = new Intent(this, GuardMonitorService.class);
        svc.setAction(action);
        startForegroundService(svc);
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraAndNotifPermissions() {
        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMonitorService(GuardMonitorService.ACTION_RESTORE_SAVED_MODE);
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        } else if (requestCode == REQ_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            runtime.controller.setMicCaptureEnabled(true);
            startMonitorService(GuardMonitorService.ACTION_RESTORE_SAVED_MODE);
        }
        updateUi();
    }

    private void buildTvLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#090F1C"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 36, 48, 36);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("SONY BraviaCam Guard — 零搶佔智慧監控與資安監管中樞");
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#F8FAFC"));
        root.addView(title);

        statusTitleView = new TextView(this);
        statusTitleView.setTextSize(15f);
        statusTitleView.setTextColor(Color.parseColor("#34D399"));
        statusTitleView.setPadding(0, 8, 0, 16);
        root.addView(statusTitleView);

        LinearLayout cardsRow = new LinearLayout(this);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(cardsRow);

        cameraInfoView = createInfoCard(cardsRow);
        View spacer = new View(this);
        cardsRow.addView(spacer, new LinearLayout.LayoutParams(24, 1));
        micInfoView = createInfoCard(cardsRow);

        endpointInfoView = new TextView(this);
        endpointInfoView.setBackgroundColor(Color.parseColor("#111A2E"));
        endpointInfoView.setTextColor(Color.parseColor("#E2E8F0"));
        endpointInfoView.setTextSize(14f);
        endpointInfoView.setTypeface(Typeface.MONOSPACE);
        endpointInfoView.setPadding(24, 20, 24, 20);
        LinearLayout.LayoutParams epParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        epParams.topMargin = 18;
        root.addView(endpointInfoView, epParams);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.START);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = 18;
        root.addView(btnRow, rowParams);

        Button coopBtn = createActionButton("智慧禮讓常駐", true);
        coopBtn.setOnClickListener(v -> startModeWithPermissionCheck(GuardMonitorService.ACTION_START_LOCK));
        btnRow.addView(coopBtn);

        Button demandBtn = createActionButton("按需喚醒零佔用", false);
        demandBtn.setOnClickListener(v -> startModeWithPermissionCheck(GuardMonitorService.ACTION_START_ONDEMAND));
        btnRow.addView(demandBtn);

        Button watchdogBtn = createActionButton("純哨兵監管", false);
        watchdogBtn.setOnClickListener(v -> startMonitorService(GuardMonitorService.ACTION_START_WATCHDOG));
        btnRow.addView(watchdogBtn);

        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        btnRow2.setGravity(Gravity.START);
        LinearLayout.LayoutParams row2Params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Params.topMargin = 10;
        root.addView(btnRow2, row2Params);

        micToggleBtn = createActionButton("麥克風收音：關", false);
        micToggleBtn.setOnClickListener(v -> {
            boolean next = !runtime.controller.isMicCaptureEnabled();
            if (next && !hasAudioPermission()) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
                return;
            }
            runtime.controller.setMicCaptureEnabled(next);
            startMonitorService(GuardMonitorService.ACTION_RESTORE_SAVED_MODE);
            updateUi();
        });
        btnRow2.addView(micToggleBtn);

        mediaYieldBtn = createActionButton("觀影自動讓位：開", false);
        mediaYieldBtn.setOnClickListener(v -> {
            runtime.controller.setYieldOnMediaPlayback(!runtime.controller.isYieldOnMediaPlayback());
            updateUi();
        });
        btnRow2.addView(mediaYieldBtn);

        bootAutoBtn = createActionButton("開機自啟：開", false);
        bootAutoBtn.setOnClickListener(v -> {
            runtime.controller.setBootAutoStartEnabled(!runtime.controller.isBootAutoStartEnabled());
            updateUi();
        });
        btnRow2.addView(bootAutoBtn);

        Button rotateBtn = createActionButton("重設存取金鑰", false);
        rotateBtn.setOnClickListener(v -> {
            runtime.authManager.rotateToken();
            updateUi();
        });
        btnRow2.addView(rotateBtn);

        TextView tipView = new TextView(this);
        tipView.setText("禮讓原則：系統或視訊程式使用鏡頭時絕不搶奪；看電視/玩遊戲時自動讓位給 Sony 畫音質最佳化；若要平時完全不佔用鏡頭，可切換「按需喚醒零佔用」。");
        tipView.setTextSize(13f);
        tipView.setTextColor(Color.parseColor("#94A3B8"));
        tipView.setPadding(0, 14, 0, 10);
        root.addView(tipView);

        TextView auditHeader = new TextView(this);
        auditHeader.setText("最近資安與裝置審計事件");
        auditHeader.setTextSize(16f);
        auditHeader.setTypeface(Typeface.DEFAULT_BOLD);
        auditHeader.setTextColor(Color.parseColor("#E2E8F0"));
        auditHeader.setPadding(0, 8, 0, 8);
        root.addView(auditHeader);

        auditTextView = new TextView(this);
        auditTextView.setBackgroundColor(Color.parseColor("#0D1526"));
        auditTextView.setTextColor(Color.parseColor("#CBD5E1"));
        auditTextView.setTextSize(12.5f);
        auditTextView.setTypeface(Typeface.MONOSPACE);
        auditTextView.setPadding(20, 16, 20, 16);
        root.addView(auditTextView);

        TextView footerView = new TextView(this);
        footerView.setText("Powered by NyphexAI, Developed by Hikaru(i@rua.moe)");
        footerView.setTextColor(Color.parseColor("#64748B"));
        footerView.setTextSize(12.5f);
        footerView.setPadding(4, 20, 4, 8);
        root.addView(footerView);

        setContentView(scroll);
        coopBtn.requestFocus();
    }

    private TextView createInfoCard(LinearLayout parent) {
        TextView tv = new TextView(this);
        tv.setBackgroundColor(Color.parseColor("#111A2E"));
        tv.setTextColor(Color.parseColor("#E2E8F0"));
        tv.setTextSize(14f);
        tv.setPadding(22, 18, 22, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        parent.addView(tv, lp);
        return tv;
    }

    private Button createActionButton(String label, boolean primary) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setAllCaps(false);
        btn.setTextSize(14f);
        btn.setPadding(20, 14, 20, 14);
        final int normalBg = Color.parseColor(primary ? "#10B981" : "#1E293B");
        final int focusBg = Color.parseColor(primary ? "#34D399" : "#3B82F6");
        btn.setBackgroundColor(normalBg);
        btn.setTextColor(Color.parseColor(primary ? "#052E16" : "#F8FAFC"));
        btn.setOnFocusChangeListener((v, hasFocus) -> v.setBackgroundColor(hasFocus ? focusBg : normalBg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 12;
        btn.setLayoutParams(lp);
        return btn;
    }

    private void updateUi() {
        CameraGuardController c = runtime.controller;
        String ip = GuardRuntime.resolveLocalIpv4();
        String token = runtime.authManager.getToken();

        statusTitleView.setText("模式：" + c.getGuardMode().name()
                + " | 狀態：" + c.getCameraState()
                + " | 系統禮讓次數：" + runtime.auditLog.getYieldCount());

        cameraInfoView.setText("【鏡頭監控與禮讓狀態】\n"
                + "Camera ID：" + (c.getTargetCameraId() != null ? c.getTargetCameraId() : "未偵測") + "\n"
                + "運作狀態：" + c.getCameraState() + "\n"
                + "影像規格：" + c.getActiveWidth() + "x" + c.getActiveHeight() + " @" + c.getTargetFps() + "fps\n"
                + "已擷取影格：" + c.getFramesCaptured()
                + " | 媒體播放：" + c.getActiveMediaSummary());

        String micHeadline = c.isExternalMicActive()
                ? "警告！外部程式錄音中"
                : (c.isSelfMicRecording()
                    ? "授權收音中 (音量 " + c.getMicLevelPercent() + "%)"
                    : (c.isMicCaptureEnabled() ? "待命（禮讓/按需中）" : "已關閉（安全靜默）"));
        micInfoView.setText("【雙麥克風控制與資安哨兵】\n"
                + "收音狀態：" + micHeadline + "\n"
                + "詳情：" + c.getActiveMicSummary() + "\n"
                + "異常統計：外部開鏡 " + runtime.auditLog.getExternalCameraCount()
                + " 次 | 外部錄音 " + runtime.auditLog.getMicActivationCount() + " 次\n"
                + "驗證失敗攔截：" + runtime.auditLog.getAuthFailCount() + " 次");

        endpointInfoView.setText("內網監控主控台："
                + "http://" + ip + ":" + GuardRuntime.HTTP_PORT + "/?token=" + token + "\n"
                + "VLC / NVR 影像："
                + "http://admin:" + token + "@" + ip + ":" + GuardRuntime.HTTP_PORT + "/stream.mjpg\n"
                + "即時音訊串流："
                + "http://admin:" + token + "@" + ip + ":" + GuardRuntime.HTTP_PORT + "/audio.wav\n"
                + "Access Token  ：" + token);

        micToggleBtn.setText("麥克風收音：" + (c.isMicCaptureEnabled() ? "開" : "關"));
        mediaYieldBtn.setText("觀影自動讓位：" + (c.isYieldOnMediaPlayback() ? "開" : "關"));
        bootAutoBtn.setText("開機自啟：" + (c.isBootAutoStartEnabled() ? "開" : "關"));

        List<SecurityAuditLog.AuditEvent> events = runtime.auditLog.getRecentEvents(10);
        StringBuilder sb = new StringBuilder();
        for (SecurityAuditLog.AuditEvent ev : events) {
            String t = ev.timestamp.length() >= 19 ? ev.timestamp.substring(11, 19) : ev.timestamp;
            sb.append('[').append(t).append("] [").append(ev.level).append("] ")
                    .append(ev.type).append(" — ").append(ev.detail).append('\n');
        }
        auditTextView.setText(sb.length() > 0 ? sb.toString() : "尚無事件");
    }
}
