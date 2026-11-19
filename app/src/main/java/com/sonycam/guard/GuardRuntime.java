// Powered by NyphexAI, Developed by Hikaru(i@rua.moe)
package com.sonycam.guard;

import android.content.Context;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Shared application-scoped runtime components and listener registry.
 */
public final class GuardRuntime {
    public static final int HTTP_PORT = 8943;

    private static volatile GuardRuntime instance;

    public final SecurityAuditLog auditLog;
    public final AuthManager authManager;
    public final CameraGuardController controller;

    private volatile CameraGuardController.AlertListener activeListener;

    private GuardRuntime(Context appContext) {
        this.auditLog = new SecurityAuditLog(appContext);
        this.authManager = new AuthManager(appContext, auditLog);
        this.controller = new CameraGuardController(appContext, auditLog, new CameraGuardController.AlertListener() {
            @Override
            public void onSecurityAlert(String level, String message) {
                CameraGuardController.AlertListener l = activeListener;
                if (l != null) {
                    l.onSecurityAlert(level, message);
                }
            }

            @Override
            public void onStateChanged() {
                CameraGuardController.AlertListener l = activeListener;
                if (l != null) {
                    l.onStateChanged();
                }
            }
        });
    }

    public static GuardRuntime get(Context context) {
        GuardRuntime current = instance;
        if (current == null) {
            synchronized (GuardRuntime.class) {
                current = instance;
                if (current == null) {
                    current = new GuardRuntime(context.getApplicationContext());
                    instance = current;
                }
            }
        }
        return current;
    }

    public void setAlertListener(CameraGuardController.AlertListener listener) {
        this.activeListener = listener;
    }

    public static String resolveLocalIpv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return "127.0.0.1";
            }
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
