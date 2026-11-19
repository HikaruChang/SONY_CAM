// Powered by NyphexAI, Developed by Hikaru(i@rua.moe)
package com.sonycam.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles LAN-only IP enforcement, constant-time bearer/basic/query/cookie token auth,
 * and per-client brute-force rate limiting.
 */
public final class AuthManager {
    private static final String PREFS_NAME = "guard_prefs";
    private static final String KEY_TOKEN = "access_token";
    private static final int MAX_FAILURES_BEFORE_LOCK = 5;
    private static final long LOCKOUT_DURATION_MS = 60_000L;

    public static final class AuthResult {
        public final boolean allowed;
        public final boolean ipRejected;
        public final boolean rateLimited;
        public final boolean shouldSetCookie;

        private AuthResult(boolean allowed, boolean ipRejected, boolean rateLimited, boolean shouldSetCookie) {
            this.allowed = allowed;
            this.ipRejected = ipRejected;
            this.rateLimited = rateLimited;
            this.shouldSetCookie = shouldSetCookie;
        }

        public static AuthResult allow(boolean setCookie) {
            return new AuthResult(true, false, false, setCookie);
        }

        public static AuthResult rejectIp() {
            return new AuthResult(false, true, false, false);
        }

        public static AuthResult rateLimit() {
            return new AuthResult(false, false, true, false);
        }

        public static AuthResult unauthorized() {
            return new AuthResult(false, false, false, false);
        }
    }

    private static final class FailRecord {
        int count;
        long lockedUntilMs;
    }

    private final SharedPreferences prefs;
    private final SecurityAuditLog auditLog;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, FailRecord> failMap = new ConcurrentHashMap<>();

    private volatile String currentToken;
    private volatile String currentSessionId;

    public AuthManager(Context context, SecurityAuditLog auditLog) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.auditLog = auditLog;
        String stored = prefs.getString(KEY_TOKEN, null);
        if (stored == null || stored.length() < 16) {
            stored = generateRandomHexToken();
            prefs.edit().putString(KEY_TOKEN, stored).apply();
        }
        setTokenInternal(stored);
    }

    public synchronized String bootstrapTokenIfUnsetOrMatch(String candidate) {
        if (candidate == null) {
            return currentToken;
        }
        String clean = candidate.trim();
        if (clean.length() < 16 || clean.length() > 128) {
            return currentToken;
        }
        boolean alreadyBootstrapped = prefs.getBoolean("token_bootstrapped", false);
        if (!alreadyBootstrapped) {
            prefs.edit()
                    .putString(KEY_TOKEN, clean)
                    .putBoolean("token_bootstrapped", true)
                    .apply();
            setTokenInternal(clean);
            auditLog.log("INFO", "TOKEN_INITIALIZED", "Access token initialized during provisioning");
        }
        return currentToken;
    }

    public synchronized String rotateToken() {
        String next = generateRandomHexToken();
        prefs.edit()
                .putString(KEY_TOKEN, next)
                .putBoolean("token_bootstrapped", true)
                .apply();
        setTokenInternal(next);
        failMap.clear();
        auditLog.log("INFO", "TOKEN_ROTATED", "Access token rotated by operator");
        return next;
    }

    public String getToken() {
        return currentToken;
    }

    public String getSessionId() {
        return currentSessionId;
    }

    public boolean isAllowedLanAddress(InetAddress addr) {
        if (addr == null) {
            return false;
        }
        return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
    }

    public AuthResult authenticate(InetAddress remoteAddr, Map<String, String> headers, String queryToken) {
        if (!isAllowedLanAddress(remoteAddr)) {
            String host = remoteAddr != null ? remoteAddr.getHostAddress() : "unknown";
            auditLog.log("WARN", "AUTH_FAILED", "Rejected non-LAN client IP: " + host);
            return AuthResult.rejectIp();
        }

        String clientKey = remoteAddr.getHostAddress();
        long now = System.currentTimeMillis();
        FailRecord rec = failMap.get(clientKey);
        if (rec != null && rec.lockedUntilMs > now) {
            return AuthResult.rateLimit();
        }

        String token = currentToken;
        String expectedSid = currentSessionId;

        String authHeader = headers.get("authorization");
        if (authHeader != null) {
            String trimmed = authHeader.trim();
            if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String provided = trimmed.substring(7).trim();
                if (constantTimeEquals(provided, token)) {
                    clearFailure(clientKey);
                    return AuthResult.allow(false);
                }
            } else if (trimmed.regionMatches(true, 0, "Basic ", 0, 6)) {
                String b64 = trimmed.substring(6).trim();
                try {
                    String decoded = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
                    int colon = decoded.indexOf(':');
                    String secret = colon >= 0 ? decoded.substring(colon + 1) : decoded;
                    if (constantTimeEquals(secret, token)) {
                        clearFailure(clientKey);
                        return AuthResult.allow(false);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        if (queryToken != null && !queryToken.isEmpty() && constantTimeEquals(queryToken, token)) {
            clearFailure(clientKey);
            return AuthResult.allow(true);
        }

        String cookieHeader = headers.get("cookie");
        if (cookieHeader != null && expectedSid != null) {
            String[] parts = cookieHeader.split(";");
            for (String part : parts) {
                String kv = part.trim();
                if (kv.startsWith("guard_sid=")) {
                    String sid = kv.substring("guard_sid=".length()).trim();
                    if (constantTimeEquals(sid, expectedSid)) {
                        clearFailure(clientKey);
                        return AuthResult.allow(false);
                    }
                }
            }
        }

        boolean hadCredentialAttempt = (authHeader != null)
                || (queryToken != null && !queryToken.isEmpty())
                || (cookieHeader != null && cookieHeader.contains("guard_sid="));
        if (hadCredentialAttempt) {
            recordFailure(clientKey, now);
        }
        return AuthResult.unauthorized();
    }

    private void clearFailure(String clientKey) {
        failMap.remove(clientKey);
    }

    private void recordFailure(String clientKey, long now) {
        FailRecord rec = failMap.computeIfAbsent(clientKey, k -> new FailRecord());
        synchronized (rec) {
            if (rec.lockedUntilMs > 0 && rec.lockedUntilMs <= now) {
                rec.count = 0;
                rec.lockedUntilMs = 0;
            }
            rec.count++;
            if (rec.count >= MAX_FAILURES_BEFORE_LOCK) {
                rec.lockedUntilMs = now + LOCKOUT_DURATION_MS;
                auditLog.log("WARN", "AUTH_FAILED", "Rate-limited IP after 5 bad credentials: " + clientKey);
            } else {
                auditLog.log("WARN", "AUTH_FAILED", "Invalid credential from " + clientKey + " (" + rec.count + "/5)");
            }
        }
    }

    private void setTokenInternal(String token) {
        this.currentToken = token;
        this.currentSessionId = deriveSessionId(token);
    }

    private String deriveSessionId(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((token + ":sonycam_guard_sid_v1").getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (Exception e) {
            return token;
        }
    }

    private String generateRandomHexToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return toHex(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }
}
