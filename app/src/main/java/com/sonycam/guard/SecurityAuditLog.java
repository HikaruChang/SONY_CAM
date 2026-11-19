// Powered by NyphexAI, Developed by Hikaru(i@rua.moe)
package com.sonycam.guard;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Thread-safe persistent ring buffer for security watchdog and camera/mic audit events.
 */
public final class SecurityAuditLog {
    private static final String TAG = "SonyCamGuard";
    private static final int MAX_MEMORY_EVENTS = 500;
    private static final long MAX_LOG_FILE_BYTES = 512 * 1024;

    public static final class AuditEvent {
        public final long epochMs;
        public final String timestamp;
        public final String level;
        public final String type;
        public final String detail;

        public AuditEvent(long epochMs, String timestamp, String level, String type, String detail) {
            this.epochMs = epochMs;
            this.timestamp = timestamp;
            this.level = level;
            this.type = type;
            this.detail = detail;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("epochMs", epochMs);
                obj.put("timestamp", timestamp);
                obj.put("level", level);
                obj.put("type", type);
                obj.put("detail", detail);
            } catch (Exception ignored) {
            }
            return obj;
        }
    }

    private final File logFile;
    private final ArrayDeque<AuditEvent> events = new ArrayDeque<>(MAX_MEMORY_EVENTS);
    private final SimpleDateFormat isoFormat;

    private int preemptionCount = 0;
    private int yieldCount = 0;
    private int externalCameraCount = 0;
    private int micActivationCount = 0;
    private int authFailCount = 0;
    private int motionCount = 0;

    public SecurityAuditLog(Context context) {
        this.logFile = new File(context.getFilesDir(), "security_audit.jsonl");
        this.isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        this.isoFormat.setTimeZone(TimeZone.getDefault());
        loadPersistedEvents();
    }

    public synchronized void log(String level, String type, String detail) {
        long now = System.currentTimeMillis();
        String ts = isoFormat.format(new Date(now));
        AuditEvent ev = new AuditEvent(now, ts, level, type, detail);

        if (events.size() >= MAX_MEMORY_EVENTS) {
            events.removeFirst();
        }
        events.addLast(ev);
        incrementCounterForType(type);

        if ("CRITICAL".equals(level)) {
            Log.e(TAG, "[" + type + "] " + detail);
        } else if ("WARN".equals(level)) {
            Log.w(TAG, "[" + type + "] " + detail);
        } else {
            Log.i(TAG, "[" + type + "] " + detail);
        }

        appendToFile(ev);
    }

    private void incrementCounterForType(String type) {
        if ("CAMERA_PREEMPTED".equals(type)) {
            preemptionCount++;
        } else if ("CAMERA_YIELDED_EXTERNAL".equals(type) || "CAMERA_YIELDED_MEDIA".equals(type)) {
            yieldCount++;
        } else if ("EXTERNAL_CAMERA_ACTIVE".equals(type)) {
            externalCameraCount++;
        } else if ("MIC_RECORDING_ACTIVE".equals(type)) {
            micActivationCount++;
        } else if ("AUTH_FAILED".equals(type)) {
            authFailCount++;
        } else if ("MOTION_DETECTED".equals(type)) {
            motionCount++;
        }
    }

    public synchronized void recordMotionEvent() {
        log("INFO", "MOTION_DETECTED", "Luminance grid motion threshold exceeded");
    }

    public synchronized JSONArray getRecentJson(int limit) {
        JSONArray arr = new JSONArray();
        List<AuditEvent> list = getRecentEvents(limit);
        for (AuditEvent ev : list) {
            arr.put(ev.toJson());
        }
        return arr;
    }

    public synchronized List<AuditEvent> getRecentEvents(int limit) {
        int cap = Math.max(1, Math.min(limit, events.size()));
        List<AuditEvent> all = new ArrayList<>(events);
        List<AuditEvent> out = new ArrayList<>(cap);
        for (int i = all.size() - 1; i >= 0 && out.size() < cap; i--) {
            out.add(all.get(i));
        }
        return out;
    }

    public synchronized int getPreemptionCount() {
        return preemptionCount;
    }

    public synchronized int getYieldCount() {
        return yieldCount;
    }

    public synchronized int getExternalCameraCount() {
        return externalCameraCount;
    }

    public synchronized int getMicActivationCount() {
        return micActivationCount;
    }

    public synchronized int getAuthFailCount() {
        return authFailCount;
    }

    public synchronized int getMotionCount() {
        return motionCount;
    }

    private void loadPersistedEvents() {
        if (!logFile.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JSONObject obj = new JSONObject(line);
                    AuditEvent ev = new AuditEvent(
                            obj.optLong("epochMs", 0L),
                            obj.optString("timestamp", ""),
                            obj.optString("level", "INFO"),
                            obj.optString("type", "EVENT"),
                            obj.optString("detail", "")
                    );
                    if (events.size() >= MAX_MEMORY_EVENTS) {
                        events.removeFirst();
                    }
                    events.addLast(ev);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load persisted audit log", e);
        }
    }

    private void appendToFile(AuditEvent ev) {
        try {
            if (logFile.exists() && logFile.length() > MAX_LOG_FILE_BYTES) {
                File backup = new File(logFile.getParentFile(), "security_audit.prev.jsonl");
                if (backup.exists()) {
                    backup.delete();
                }
                logFile.renameTo(backup);
            }
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
                writer.write(ev.toJson().toString());
                writer.newLine();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist audit event", e);
        }
    }
}
