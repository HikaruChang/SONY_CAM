// Powered by NyphexAI, Developed by Hikaru(i@rua.moe)
package com.sonycam.guard;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight authenticated LAN-only HTTP server serving the live MJPEG stream,
 * on-demand snapshots, watchdog status API, security audit feed, and web console UI.
 */
public final class MjpegHttpServer {

    public interface ControlDelegate {
        void onGuardModeRequested(CameraGuardController.GuardMode mode);
        void onMediaYieldRequested(boolean enabled);
        void onBootAutoStartRequested(boolean enabled);
        void onMicCaptureRequested(boolean enabled);
        void onProfileRequested(int width, int height, int fps);
        String getHostIpAddress();
    }

    private static final String TAG = "SonyCamGuard";
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 4096;

    private final int port;
    private final AuthManager authManager;
    private final CameraGuardController controller;
    private final SecurityAuditLog auditLog;
    private final ControlDelegate controlDelegate;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ThreadPoolExecutor executor;

    public MjpegHttpServer(
            int port,
            AuthManager authManager,
            CameraGuardController controller,
            SecurityAuditLog auditLog,
            ControlDelegate controlDelegate) {
        this.port = port;
        this.authManager = authManager;
        this.controller = controller;
        this.auditLog = auditLog;
        this.controlDelegate = controlDelegate;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket(port, 32, InetAddress.getByName("0.0.0.0"));
        serverSocket.setReuseAddress(true);
        executor = new ThreadPoolExecutor(
                4,
                10,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16),
                new ThreadPoolExecutor.AbortPolicy()
        );
        running = true;
        acceptThread = new Thread(this::acceptLoop, "MjpegHttpAccept");
        acceptThread.start();
        auditLog.log("INFO", "HTTP_SERVER_STARTED", "Listening on 0.0.0.0:" + port);
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                try {
                    executor.execute(() -> handleClient(client));
                } catch (Exception rejected) {
                    try {
                        writeSimpleResponse(client.getOutputStream(), 503, "Service Unavailable",
                                "text/plain; charset=utf-8", "Server busy".getBytes(StandardCharsets.UTF_8), null);
                    } catch (IOException ignored) {
                    }
                    safeClose(client);
                }
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(15_000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = socket.getOutputStream();

            String headerBlock = readHeaderBlock(in);
            if (headerBlock == null || headerBlock.isEmpty()) {
                return;
            }
            String[] lines = headerBlock.split("\r\n");
            if (lines.length == 0) {
                return;
            }
            String[] reqParts = lines[0].split(" ");
            if (reqParts.length < 2) {
                writeJsonError(out, 400, "Bad Request", "Malformed request line");
                return;
            }

            String method = reqParts[0].toUpperCase(Locale.US);
            String fullTarget = reqParts[1];
            String path = fullTarget;
            String query = "";
            int qIdx = fullTarget.indexOf('?');
            if (qIdx >= 0) {
                path = fullTarget.substring(0, qIdx);
                query = fullTarget.substring(qIdx + 1);
            }

            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.US);
                    String val = lines[i].substring(colon + 1).trim();
                    headers.put(name, val);
                }
            }

            Map<String, String> queryParams = parseQuery(query);
            AuthManager.AuthResult auth = authManager.authenticate(
                    socket.getInetAddress(),
                    headers,
                    queryParams.get("token")
            );

            if (auth.ipRejected) {
                writeJsonError(out, 403, "Forbidden", "LAN access only");
                return;
            }
            if (auth.rateLimited) {
                writeJsonError(out, 429, "Too Many Requests", "Rate limited; retry after 60s");
                return;
            }
            boolean isHead = "HEAD".equals(method);
            if (!auth.allowed) {
                if ("/".equals(path) && !isHead) {
                    byte[] loginHtml = buildLoginHtml().getBytes(StandardCharsets.UTF_8);
                    writeSimpleResponse(out, 401, "Unauthorized", "text/html; charset=utf-8",
                            loginHtml, "WWW-Authenticate: Basic realm=\"BraviaCam Guard\"\r\n");
                } else {
                    byte[] body = isHead ? new byte[0] : "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    writeSimpleResponse(out, 401, "Unauthorized", "application/json; charset=utf-8",
                            body, "WWW-Authenticate: Basic realm=\"BraviaCam Guard\"\r\n");
                }
                return;
            }

            String extraHeaders = null;
            if (auth.shouldSetCookie) {
                extraHeaders = "Set-Cookie: guard_sid=" + authManager.getSessionId()
                        + "; HttpOnly; SameSite=Strict; Path=/\r\n";
            }

            if ("GET".equals(method) && "/".equals(path)) {
                byte[] html = buildDashboardHtml().getBytes(StandardCharsets.UTF_8);
                writeSimpleResponse(out, 200, "OK", "text/html; charset=utf-8", html, extraHeaders);
            } else if ("GET".equals(method) && "/api/status".equals(path)) {
                String json = controller.buildStatusJson(controlDelegate.getHostIpAddress(), port).toString();
                writeSimpleResponse(out, 200, "OK", "application/json; charset=utf-8",
                        json.getBytes(StandardCharsets.UTF_8), extraHeaders);
            } else if ("GET".equals(method) && "/api/audit".equals(path)) {
                int limit = parseIntOrDefault(queryParams.get("limit"), 60, 1, 300);
                String json = auditLog.getRecentJson(limit).toString();
                writeSimpleResponse(out, 200, "OK", "application/json; charset=utf-8",
                        json.getBytes(StandardCharsets.UTF_8), extraHeaders);
            } else if (("GET".equals(method) || isHead) && "/snapshot.jpg".equals(path)) {
                handleSnapshotRequest(out, extraHeaders, isHead);
            } else if ("GET".equals(method) && "/motion/latest.jpg".equals(path)) {
                byte[] motionJpeg = controller.getLatestMotionJpeg();
                if (motionJpeg == null || motionJpeg.length == 0) {
                    writeJsonError(out, 404, "Not Found", "No motion snapshot captured yet");
                } else {
                    writeSimpleResponse(out, 200, "OK", "image/jpeg", motionJpeg, extraHeaders);
                }
            } else if (("GET".equals(method) || isHead) && "/stream.mjpg".equals(path)) {
                handleStreamRequest(out, socket, extraHeaders, isHead);
            } else if (("GET".equals(method) || isHead) && "/audio.wav".equals(path)) {
                handleAudioStreamRequest(out, socket, extraHeaders, isHead);
            } else if ("POST".equals(method) && "/api/control".equals(path)) {
                String body = readBody(in, headers.get("content-length"));
                handleControl(out, queryParams, body, extraHeaders);
            } else {
                writeJsonError(out, 404, "Not Found", "Unknown endpoint: " + path);
            }
        } catch (Exception e) {
            Log.d(TAG, "HTTP client finished: " + e.getMessage());
        } finally {
            safeClose(socket);
        }
    }

    private void handleSnapshotRequest(OutputStream out, String extraHeaders, boolean headOnly) throws IOException {
        if (controller.getGuardMode() == CameraGuardController.GuardMode.WATCHDOG_ONLY) {
            if (headOnly) {
                writeSimpleResponse(out, 503, "Service Unavailable", "application/json; charset=utf-8",
                        new byte[0], null);
            } else {
                writeJsonError(out, 503, "Service Unavailable", "Camera in WATCHDOG_ONLY mode");
            }
            return;
        }
        controller.notifyClientDemand();
        if (headOnly) {
            if (controller.isCaptureExpected()) {
                writeSimpleResponse(out, 200, "OK", "image/jpeg", new byte[0], extraHeaders);
            } else {
                writeSimpleResponse(out, 503, "Service Unavailable", "application/json; charset=utf-8",
                        new byte[0], null);
            }
            return;
        }
        CameraGuardController.FrameSnapshot snap = controller.getLatestFrame();
        if ((snap.jpeg == null || snap.jpeg.length == 0) && controller.isCaptureExpected()) {
            snap = controller.waitForNextFrame(snap.sequence, 3500L);
        }
        if (snap.jpeg == null || snap.jpeg.length == 0) {
            writeJsonError(out, 503, "Service Unavailable",
                    "No frame available (state=" + controller.getCameraState() + ")");
        } else {
            writeSimpleResponse(out, 200, "OK", "image/jpeg", snap.jpeg, extraHeaders);
        }
    }

    private void handleStreamRequest(OutputStream out, Socket socket, String extraHeaders, boolean headOnly) throws IOException {
        if (controller.getGuardMode() == CameraGuardController.GuardMode.WATCHDOG_ONLY) {
            if (headOnly) {
                writeSimpleResponse(out, 503, "Service Unavailable", "application/json; charset=utf-8",
                        new byte[0], null);
            } else {
                writeJsonError(out, 503, "Service Unavailable",
                        "Camera is in WATCHDOG_ONLY mode; switch to Cooperative or On-Demand mode");
            }
            return;
        }
        controller.notifyClientDemand();
        if (!controller.isCaptureExpected()) {
            if (headOnly) {
                writeSimpleResponse(out, 503, "Service Unavailable", "application/json; charset=utf-8",
                        new byte[0], null);
            } else {
                writeJsonError(out, 503, "Service Unavailable",
                        "Camera currently yielded (" + controller.getCameraState() + ")");
            }
            return;
        }
        if (headOnly) {
            writeSimpleResponse(out, 200, "OK", "multipart/x-mixed-replace; boundary=guardframe",
                    new byte[0], extraHeaders);
            return;
        }
        socket.setSoTimeout(0);
        streamMjpeg(out, extraHeaders);
    }

    private void handleAudioStreamRequest(OutputStream out, Socket socket, String extraHeaders, boolean headOnly)
            throws IOException {
        if (!controller.isMicCaptureEnabled()
                || controller.getGuardMode() == CameraGuardController.GuardMode.WATCHDOG_ONLY) {
            if (headOnly) {
                writeSimpleResponse(out, 503, "Service Unavailable", "application/json; charset=utf-8",
                        new byte[0], null);
            } else {
                writeJsonError(out, 503, "Service Unavailable",
                        "Mic capture is disabled or in Watchdog-Only mode");
            }
            return;
        }
        controller.notifyClientDemand();
        if (controller.isYieldOnMediaPlayback() && controller.isMediaPlaying()) {
            if (headOnly) {
                writeSimpleResponse(out, 503, "Service Unavailable", "application/json; charset=utf-8",
                        new byte[0], null);
            } else {
                writeJsonError(out, 503, "Service Unavailable", "Audio yielded during TV playback");
            }
            return;
        }
        if (headOnly) {
            writeSimpleResponse(out, 200, "OK", "audio/wav", new byte[0], extraHeaders);
            return;
        }
        socket.setSoTimeout(0);
        streamWavAudio(out, extraHeaders);
    }

    private void handleControl(
            OutputStream out,
            Map<String, String> queryParams,
            String body,
            String extraHeaders) throws IOException {
        Map<String, String> params = new HashMap<>(queryParams);
        if (body != null && !body.trim().isEmpty()) {
            String trimmed = body.trim();
            if (trimmed.startsWith("{")) {
                try {
                    JSONObject obj = new JSONObject(trimmed);
                    if (obj.has("mode")) {
                        params.put("mode", obj.optString("mode"));
                    }
                    if (obj.has("mediaYield")) {
                        params.put("mediaYield", String.valueOf(obj.optBoolean("mediaYield")));
                    }
                    if (obj.has("bootAutoStart")) {
                        params.put("bootAutoStart", String.valueOf(obj.optBoolean("bootAutoStart")));
                    }
                    if (obj.has("micEnabled")) {
                        params.put("micEnabled", String.valueOf(obj.optBoolean("micEnabled")));
                    }
                    if (obj.has("size")) {
                        params.put("size", obj.optString("size"));
                    }
                    if (obj.has("fps")) {
                        params.put("fps", String.valueOf(obj.optInt("fps")));
                    }
                } catch (Exception e) {
                    writeJsonError(out, 400, "Bad Request", "Invalid JSON control body");
                    return;
                }
            } else {
                params.putAll(parseQuery(trimmed));
            }
        }

        if (params.containsKey("mode")) {
            String mode = params.get("mode");
            if ("cooperative".equalsIgnoreCase(mode) || "lock".equalsIgnoreCase(mode)) {
                controlDelegate.onGuardModeRequested(CameraGuardController.GuardMode.COOPERATIVE);
            } else if ("ondemand".equalsIgnoreCase(mode) || "on_demand".equalsIgnoreCase(mode)) {
                controlDelegate.onGuardModeRequested(CameraGuardController.GuardMode.ON_DEMAND);
            } else if ("watchdog".equalsIgnoreCase(mode) || "watchdog_only".equalsIgnoreCase(mode)) {
                controlDelegate.onGuardModeRequested(CameraGuardController.GuardMode.WATCHDOG_ONLY);
            }
        }
        if (params.containsKey("mediaYield")) {
            controlDelegate.onMediaYieldRequested(Boolean.parseBoolean(params.get("mediaYield")));
        }
        if (params.containsKey("bootAutoStart")) {
            controlDelegate.onBootAutoStartRequested(Boolean.parseBoolean(params.get("bootAutoStart")));
        }
        if (params.containsKey("micEnabled")) {
            controlDelegate.onMicCaptureRequested(Boolean.parseBoolean(params.get("micEnabled")));
        }
        if (params.containsKey("size") || params.containsKey("fps")) {
            int width = controller.getActiveWidth();
            int height = controller.getActiveHeight();
            String size = params.get("size");
            if ("640x480".equals(size)) {
                width = 640;
                height = 480;
            } else if ("1280x720".equals(size)) {
                width = 1280;
                height = 720;
            } else if ("1920x1080".equals(size)) {
                width = 1920;
                height = 1080;
            }
            int fps = parseIntOrDefault(params.get("fps"), controller.getTargetFps(), 2, 30);
            controlDelegate.onProfileRequested(width, height, fps);
        }

        String json = controller.buildStatusJson(controlDelegate.getHostIpAddress(), port).toString();
        writeSimpleResponse(out, 200, "OK", "application/json; charset=utf-8",
                json.getBytes(StandardCharsets.UTF_8), extraHeaders);
    }

    private void streamMjpeg(OutputStream out, String extraHeaders) throws IOException {
        StringBuilder hdr = new StringBuilder();
        hdr.append("HTTP/1.1 200 OK\r\n");
        hdr.append("Content-Type: multipart/x-mixed-replace; boundary=guardframe\r\n");
        hdr.append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n");
        hdr.append("Pragma: no-cache\r\n");
        hdr.append("X-Content-Type-Options: nosniff\r\n");
        hdr.append("Connection: close\r\n");
        if (extraHeaders != null) {
            hdr.append(extraHeaders);
        }
        hdr.append("\r\n");
        out.write(hdr.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        long lastSeq = -1L;
        while (running) {
            controller.notifyClientDemand();
            if (!controller.isCaptureExpected()) {
                break;
            }
            CameraGuardController.FrameSnapshot snap = controller.waitForNextFrame(lastSeq, 2500L);
            if (!running || !controller.isCaptureExpected()) {
                break;
            }
            if (snap.jpeg == null || snap.jpeg.length == 0 || snap.sequence == lastSeq) {
                continue;
            }
            lastSeq = snap.sequence;
            String partHeader = "--guardframe\r\n"
                    + "Content-Type: image/jpeg\r\n"
                    + "Content-Length: " + snap.jpeg.length + "\r\n\r\n";
            out.write(partHeader.getBytes(StandardCharsets.US_ASCII));
            out.write(snap.jpeg);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    private void streamWavAudio(OutputStream out, String extraHeaders) throws IOException {
        int channels = Math.max(1, Math.min(2, controller.getMicChannelCount()));
        int sampleRate = CameraGuardController.MIC_SAMPLE_RATE;
        StringBuilder hdr = new StringBuilder();
        hdr.append("HTTP/1.1 200 OK\r\n");
        hdr.append("Content-Type: audio/wav\r\n");
        hdr.append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n");
        hdr.append("Pragma: no-cache\r\n");
        hdr.append("X-Content-Type-Options: nosniff\r\n");
        hdr.append("Connection: close\r\n");
        if (extraHeaders != null) {
            hdr.append(extraHeaders);
        }
        hdr.append("\r\n");
        out.write(hdr.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(buildStreamingWavHeader(sampleRate, channels, 16));
        out.flush();

        LinkedBlockingQueue<byte[]> queue = controller.registerAudioSubscriber();
        try {
            while (running && controller.isMicCaptureEnabled()
                    && controller.getGuardMode() != CameraGuardController.GuardMode.WATCHDOG_ONLY) {
                if (controller.isYieldOnMediaPlayback() && controller.isMediaPlaying()) {
                    break;
                }
                byte[] chunk;
                try {
                    chunk = queue.poll(2000L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (chunk == null) {
                    continue;
                }
                if (chunk.length == 0) {
                    break;
                }
                controller.notifyClientDemand();
                out.write(chunk);
                out.flush();
            }
        } finally {
            controller.unregisterAudioSubscriber(queue);
        }
    }

    private static byte[] buildStreamingWavHeader(int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);
        int dataSize = 0x7FFF0000;
        int riffSize = dataSize + 36;
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        writeLeInt(h, 4, riffSize);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        writeLeInt(h, 16, 16);
        writeLeShort(h, 20, 1);
        writeLeShort(h, 22, channels);
        writeLeInt(h, 24, sampleRate);
        writeLeInt(h, 28, byteRate);
        writeLeShort(h, 32, blockAlign);
        writeLeShort(h, 34, bitsPerSample);
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        writeLeInt(h, 40, dataSize);
        return h;
    }

    private static void writeLeInt(byte[] buf, int offset, int v) {
        buf[offset] = (byte) (v & 0xFF);
        buf[offset + 1] = (byte) ((v >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((v >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void writeLeShort(byte[] buf, int offset, int v) {
        buf[offset] = (byte) (v & 0xFF);
        buf[offset + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static String readHeaderBlock(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int prev3 = 0, prev2 = 0, prev1 = 0;
        while (buf.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            buf.write(b);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                break;
            }
            prev3 = prev2;
            prev2 = prev1;
            prev1 = b;
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String readBody(BufferedInputStream in, String contentLengthHeader) throws IOException {
        if (contentLengthHeader == null) {
            return "";
        }
        int len = parseIntOrDefault(contentLengthHeader.trim(), 0, 0, MAX_BODY_BYTES);
        if (len <= 0) {
            return "";
        }
        byte[] data = new byte[len];
        int offset = 0;
        while (offset < len) {
            int r = in.read(data, offset, len - offset);
            if (r == -1) {
                break;
            }
            offset += r;
        }
        return new String(data, 0, offset, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return map;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                k = URLDecoder.decode(k, "UTF-8");
                v = URLDecoder.decode(v, "UTF-8");
            } catch (Exception ignored) {
            }
            map.put(k, v);
        }
        return map;
    }

    private static int parseIntOrDefault(String s, int def, int min, int max) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            int val = Integer.parseInt(s.trim());
            return Math.max(min, Math.min(max, val));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void writeJsonError(OutputStream out, int code, String status, String msg) throws IOException {
        String safe = msg.replace("\"", "'");
        byte[] body = ("{\"error\":\"" + safe + "\"}").getBytes(StandardCharsets.UTF_8);
        writeSimpleResponse(out, code, status, "application/json; charset=utf-8", body, null);
    }

    private static void writeSimpleResponse(
            OutputStream out,
            int code,
            String status,
            String contentType,
            byte[] body,
            String extraHeaders) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Cache-Control: no-store, max-age=0\r\n");
        sb.append("X-Content-Type-Options: nosniff\r\n");
        sb.append("X-Frame-Options: DENY\r\n");
        sb.append("Connection: close\r\n");
        if (extraHeaders != null) {
            sb.append(extraHeaders);
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void safeClose(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static String buildLoginHtml() {
        return "<!DOCTYPE html><html lang=\"zh-Hant\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>BraviaCam Guard 驗證</title>"
                + "<style>body{margin:0;background:#0b1220;color:#e2e8f0;font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;}"
                + ".card{background:#111c33;border:1px solid #24344d;border-radius:14px;padding:28px;max-width:400px;width:90%;box-shadow:0 12px 40px rgba(0,0,0,.45);}"
                + "h2{margin:0 0 8px;color:#34d399;font-size:20px;}p{color:#94a3b8;font-size:14px;line-height:1.5;}"
                + "input{width:100%;box-sizing:border-box;padding:11px 12px;border-radius:8px;border:1px solid #334155;background:#0b1220;color:#fff;font-family:monospace;margin:12px 0;}"
                + "button{width:100%;padding:11px;border:0;border-radius:8px;background:#10b981;color:#052e16;font-weight:700;cursor:pointer;font-size:15px;}</style></head>"
                + "<body><div class=\"card\"><h2>BraviaCam Guard</h2>"
                + "<p>請輸入電視端顯示的 Access Token 以進入即時監控與零搶佔 Camera/麥克風監管主控台。</p>"
                + "<form method=\"GET\" action=\"/\"><input name=\"token\" type=\"password\" placeholder=\"貼上存取金鑰 (Token)\" autofocus required>"
                + "<button type=\"submit\">驗證並登入</button></form>"
                + "<div style=\"margin-top:18px;padding-top:12px;border-top:1px solid #1e293b;font-size:12px;color:#64748b;text-align:center\">Powered by NyphexAI, Developed by Hikaru(i@rua.moe)</div>"
                + "</div></body></html>";
    }

    private static String buildDashboardHtml() {
        return "<!DOCTYPE html><html lang=\"zh-Hant\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>SONY CMU-BC1 智慧禮讓監控與資安監管</title>"
                + "<style>"
                + "*{box-sizing:border-box}body{margin:0;background:#090f1c;color:#e2e8f0;font-family:system-ui,-apple-system,sans-serif;padding:18px;}"
                + ".wrap{max-width:1220px;margin:0 auto}.hdr{display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:12px;margin-bottom:16px;}"
                + "h1{margin:0;font-size:22px;color:#f8fafc}.sub{font-size:13px;color:#94a3b8;margin-top:4px}"
                + ".badge{padding:6px 12px;border-radius:999px;font-size:12px;font-weight:700;background:#064e3b;color:#34d399;border:1px solid #059669}"
                + ".badge.warn{background:#451a03;color:#fbbf24;border-color:#d97706}.badge.crit{background:#450a0a;color:#f87171;border-color:#dc2626}"
                + ".badge.idle{background:#172554;color:#93c5fd;border-color:#2563eb}"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:12px;margin-bottom:16px}"
                + ".card{background:#111a2e;border:1px solid #21304d;border-radius:12px;padding:14px}"
                + ".lbl{font-size:12px;color:#94a3b8;margin-bottom:6px}.val{font-size:18px;font-weight:700;color:#f1f5f9}.meta{font-size:12px;color:#64748b;margin-top:6px;line-height:1.4}"
                + ".main{display:grid;grid-template-columns:2fr 1fr;gap:16px}@media(max-width:900px){.main{grid-template-columns:1fr}}"
                + ".videoBox{background:#050911;border:1px solid #21304d;border-radius:12px;overflow:hidden;position:relative;min-height:280px;display:flex;align-items:center;justify-content:center}"
                + ".videoBox img{width:100%;height:auto;display:block}"
                + ".ctrls{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}"
                + "button,select{background:#1e293b;color:#f8fafc;border:1px solid #334155;border-radius:8px;padding:8px 12px;font-size:13px;cursor:pointer}"
                + "button.primary{background:#10b981;color:#052e16;border-color:#10b981;font-weight:700}"
                + "button.active{background:#2563eb;color:#eff6ff;border-color:#3b82f6;font-weight:700}"
                + "table{width:100%;border-collapse:collapse;font-size:12px;margin-top:8px}"
                + "th,td{padding:8px 6px;border-bottom:1px solid #1e293b;text-align:left;vertical-align:top}"
                + "th{color:#94a3b8;font-weight:600}.lvl-INFO{color:#34d399}.lvl-WARN{color:#fbbf24;font-weight:700}.lvl-CRITICAL{color:#f87171;font-weight:700}"
                + ".motionImg{width:100%;border-radius:8px;border:1px solid #24344d;margin-top:8px;display:none}"
                + "</style></head><body><div class=\"wrap\">"
                + "<div class=\"hdr\"><div><h1>SONY CMU-BC1 智慧禮讓監控與資安監管</h1>"
                + "<div class=\"sub\">純區網零雲端 | 麥克風預設關閉・可選開啟 | 絕不與 Sony 系統功能搶奪使用權</div></div>"
                + "<div id=\"topBadge\" class=\"badge\">載入中...</div></div>"
                + "<div class=\"grid\">"
                + "<div class=\"card\"><div class=\"lbl\">Camera 狀態 (模式與佔用)</div><div id=\"camVal\" class=\"val\">-</div><div id=\"camMeta\" class=\"meta\">-</div></div>"
                + "<div class=\"card\"><div class=\"lbl\">雙麥克風控制與資安哨兵</div><div id=\"micVal\" class=\"val\">-</div><div id=\"micMeta\" class=\"meta\">-</div>"
                + "<div style=\"height:6px;background:#1e293b;border-radius:4px;margin-top:8px;overflow:hidden\"><div id=\"micMeter\" style=\"height:100%;width:0%;background:#10b981;transition:width 0.15s\"></div></div>"
                + "<div id=\"audioBox\" style=\"display:none;margin-top:8px\"><audio id=\"audioPlayer\" controls preload=\"none\" style=\"width:100%;height:32px\"></audio></div></div>"
                + "<div class=\"card\"><div class=\"lbl\">系統禮讓與異常監管</div><div id=\"secVal\" class=\"val\">-</div><div id=\"secMeta\" class=\"meta\">-</div></div>"
                + "<div class=\"card\"><div class=\"lbl\">移動偵測與電視電源</div><div id=\"motVal\" class=\"val\">-</div><div id=\"motMeta\" class=\"meta\">-</div></div>"
                + "</div>"
                + "<div class=\"main\"><div>"
                + "<div class=\"videoBox\"><img id=\"streamImg\" src=\"/stream.mjpg\" alt=\"CMU-BC1 Stream\"><div id=\"noStream\" style=\"display:none;padding:24px;color:#94a3b8;text-align:center\"></div></div>"
                + "<div class=\"ctrls\">"
                + "<button id=\"btnCoop\" onclick=\"ctrl({mode:'cooperative'})\">智慧禮讓常駐 (Cooperative)</button>"
                + "<button id=\"btnDemand\" onclick=\"ctrl({mode:'ondemand'})\">按需喚醒零佔用 (On-Demand)</button>"
                + "<button id=\"btnWatch\" onclick=\"ctrl({mode:'watchdog'})\">純哨兵監管 (Watchdog)</button>"
                + "<button id=\"micToggleBtn\" onclick=\"toggleMicCapture()\">麥克風收音：關</button>"
                + "<button id=\"mediaYieldBtn\" onclick=\"toggleMediaYield()\">觀影自動讓位給系統：開</button>"
                + "<button id=\"bootAutoBtn\" onclick=\"toggleBootAuto()\">開機自動啟動：開</button>"
                + "<select id=\"resSel\" onchange=\"applyProfile()\"><option value=\"640x480\">640x480</option><option value=\"1280x720\" selected>1280x720</option><option value=\"1920x1080\">1920x1080</option></select>"
                + "<select id=\"fpsSel\" onchange=\"applyProfile()\"><option value=\"5\">5 fps</option><option value=\"10\" selected>10 fps</option><option value=\"15\">15 fps</option></select>"
                + "<button onclick=\"reloadStream()\">喚醒 / 重連串流</button>"
                + "</div></div>"
                + "<div class=\"card\"><div class=\"lbl\">最近移動偵測快照 (/motion/latest.jpg)</div><div id=\"motSnapInfo\" class=\"meta\">尚無移動事件</div>"
                + "<img id=\"motionImg\" class=\"motionImg\" alt=\"Motion Snapshot\">"
                + "<div class=\"lbl\" style=\"margin-top:14px\">資安與禮讓審計日誌</div>"
                + "<div style=\"max-height:360px;overflow:auto\"><table><thead><tr><th>時間</th><th>等級</th><th>事件</th></tr></thead><tbody id=\"auditBody\"></tbody></table></div>"
                + "</div></div>"
                + "<div style=\"margin-top:20px;padding:12px 0;text-align:center;font-size:12px;color:#64748b;border-top:1px solid #1e293b\">Powered by NyphexAI, Developed by Hikaru(i@rua.moe)</div>"
                + "</div>"
                + "<script>"
                + "let curStatus=null;"
                + "function esc(v){return String(v||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}"
                + "async function ctrl(payload){await fetch('/api/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});setTimeout(refresh,350);if(payload.mode==='cooperative'||payload.mode==='ondemand')setTimeout(reloadStream,800);}"
                + "function toggleMediaYield(){if(!curStatus)return;ctrl({mediaYield:!curStatus.yieldOnMediaPlayback});}"
                + "function toggleBootAuto(){if(!curStatus)return;ctrl({bootAutoStart:!curStatus.bootAutoStart});}"
                + "function toggleMicCapture(){if(!curStatus)return;ctrl({micEnabled:!curStatus.micCaptureEnabled});}"
                + "function applyProfile(){ctrl({size:document.getElementById('resSel').value,fps:parseInt(document.getElementById('fpsSel').value,10)});setTimeout(reloadStream,900);}"
                + "function reloadStream(){const img=document.getElementById('streamImg');img.src='/stream.mjpg?t='+Date.now();}"
                + "function stateHint(s){"
                + "if(s.cameraState==='ON_DEMAND_IDLE')return '目前為「按需喚醒零佔用」待機中（鏡頭已完全釋放給 Sony 系統功能），點「喚醒 / 重連串流」或請求 /snapshot.jpg 會即時喚醒，無人觀看 10 秒後自動釋放。';"
                + "if(s.cameraState==='YIELDED_MEDIA')return '偵測到電視正在播放影音/通話，已自動釋放鏡頭禮讓 Sony BRAVIA CAM 畫音質/環境優化功能。';"
                + "if(s.cameraState==='YIELDED_EXTERNAL')return '偵測到 Sony 系統或前景 App 正在使用鏡頭，已完全禮讓不搶佔。';"
                + "if(s.cameraState==='WATCHDOG_ONLY')return '目前為「純哨兵監管模式」，已釋放鏡頭僅監控系統調用狀態。';"
                + "return '目前無法串流 ('+s.cameraState+')';}"
                + "async function refresh(){try{const r=await fetch('/api/status');if(!r.ok)return;const s=await r.json();curStatus=s;"
                + "const b=document.getElementById('topBadge');let mTxt=s.externalMicActive?'外部麥克風警報!':(s.selfMicRecording?'麥克風收音中':'麥克風靜默');b.textContent=s.guardMode+' | '+s.cameraState+' | '+mTxt;"
                + "let bCls='badge';if(s.externalMicActive)bCls+=' crit';else if(s.cameraState==='YIELDED_MEDIA'||s.cameraState==='YIELDED_EXTERNAL'||s.cameraState==='WATCHDOG_ONLY')bCls+=' warn';else if(s.cameraState==='ON_DEMAND_IDLE')bCls+=' idle';b.className=bCls;"
                + "document.getElementById('camVal').textContent=s.cameraState+' (ID '+s.cameraId+')';"
                + "document.getElementById('camMeta').textContent='模式: '+s.guardMode+' | '+s.width+'x'+s.height+' @'+s.targetFps+'fps | 影格: '+s.framesCaptured+(s.lastError?' | '+s.lastError:'');"
                + "document.getElementById('micVal').textContent=s.externalMicActive?'警報：外部程式錄音中!':(s.selfMicRecording?'收音開啟中 (音量 '+s.micLevelPercent+'%)':(s.micCaptureEnabled?'待命（禮讓/按需中）':'已關閉（僅哨兵監管）'));"
                + "document.getElementById('micMeta').textContent=s.activeMicSummary+' | 外部警報: '+s.micActivationCount;"
                + "document.getElementById('micMeter').style.width=(s.micLevelPercent||0)+'%';"
                + "const ab=document.getElementById('audioBox'),ap=document.getElementById('audioPlayer');"
                + "if(s.micCaptureEnabled&&s.guardMode!=='WATCHDOG_ONLY'){ab.style.display='block';if(!ap.dataset.active){ap.dataset.active='1';ap.src='/audio.wav?t='+Date.now();}}else{ab.style.display='none';if(ap.dataset.active){ap.pause();ap.removeAttribute('src');ap.load();delete ap.dataset.active;}}"
                + "const mt=document.getElementById('micToggleBtn');mt.textContent='麥克風收音：'+(s.micCaptureEnabled?'開':'關');mt.className=s.micCaptureEnabled?'primary':'';"
                + "document.getElementById('secVal').textContent='禮讓 '+s.yieldCount+' | 外部調用 '+s.externalCameraCount;"
                + "document.getElementById('secMeta').textContent='媒體狀態: '+s.activeMediaSummary+' | 認證失敗: '+s.authFailCount;"
                + "document.getElementById('motVal').textContent=s.motionDetected?'偵測到畫面移動!':'無明顯移動 ('+s.motionCount+' 次)';"
                + "document.getElementById('motMeta').textContent='螢幕: '+(s.displayInteractive?'亮屏':'熄屏/待機')+' | 開機自啟: '+(s.bootAutoStart?'開啟':'關閉')+' | '+s.hostIp+':'+s.port;"
                + "document.getElementById('mediaYieldBtn').textContent='觀影自動讓位給系統：'+(s.yieldOnMediaPlayback?'開':'關');"
                + "document.getElementById('bootAutoBtn').textContent='開機自動啟動：'+(s.bootAutoStart?'開':'關');"
                + "document.getElementById('btnCoop').className=s.guardMode==='COOPERATIVE'?'primary':'';"
                + "document.getElementById('btnDemand').className=s.guardMode==='ON_DEMAND'?'active':'';"
                + "document.getElementById('btnWatch').className=s.guardMode==='WATCHDOG_ONLY'?'active':'';"
                + "const canShowStream=(s.cameraState==='GUARD_LOCKED'||s.cameraState==='OPENING');"
                + "document.getElementById('streamImg').style.display=canShowStream?'block':'none';"
                + "const ns=document.getElementById('noStream');ns.style.display=canShowStream?'none':'block';if(!canShowStream)ns.textContent=stateHint(s);"
                + "if(s.hasMotionSnapshot&&s.lastMotionTimestampMs>0){document.getElementById('motSnapInfo').textContent='最後觸發: '+new Date(s.lastMotionTimestampMs).toLocaleTimeString();const mi=document.getElementById('motionImg');mi.style.display='block';if(mi.dataset.ts!==String(s.lastMotionTimestampMs)){mi.dataset.ts=String(s.lastMotionTimestampMs);mi.src='/motion/latest.jpg?t='+s.lastMotionTimestampMs;}}"
                + "const ar=await fetch('/api/audit?limit=30');if(ar.ok){const evs=await ar.json();document.getElementById('auditBody').innerHTML=evs.map(e=>'<tr><td>'+esc(e.timestamp.slice(11,19))+'</td><td class=\"lvl-'+esc(e.level)+'\">'+esc(e.level)+'</td><td><b>'+esc(e.type)+'</b><br><span style=\"color:#94a3b8\">'+esc(e.detail)+'</span></td></tr>').join('');}"
                + "}catch(e){}}"
                + "refresh();setInterval(refresh,1500);"
                + "</script></body></html>";
    }
}
