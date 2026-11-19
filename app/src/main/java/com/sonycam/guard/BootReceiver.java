// Powered by NyphexAI, Developed by Hikaru(i@rua.moe)
package com.sonycam.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts Watchdog-Only protection immediately after boot and launches BootTrampolineActivity
 * (via SYSTEM_ALERT_WINDOW BAL exemption) to restore camera foreground mode unattended.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        GuardRuntime runtime = GuardRuntime.get(context);
        if (!runtime.controller.isBootAutoStartEnabled()) {
            runtime.auditLog.log("INFO", "BOOT_AUTOSTART_SKIPPED",
                    "Boot completed; auto-start disabled by user");
            return;
        }

        CameraGuardController.GuardMode savedMode = runtime.controller.getSavedGuardMode();
        runtime.auditLog.log("INFO", "BOOT_WATCHDOG_START",
                "Boot completed; restoring savedMode=" + savedMode.name());

        Intent svc = new Intent(context, GuardMonitorService.class);
        svc.setAction(GuardMonitorService.ACTION_START_WATCHDOG);
        svc.putExtra("boot_fallback", true);
        try {
            context.startForegroundService(svc);
        } catch (Exception e) {
            runtime.auditLog.log("WARN", "BOOT_START_FAILED", e.getMessage());
        }

        if (savedMode != CameraGuardController.GuardMode.WATCHDOG_ONLY) {
            Intent trampoline = new Intent(context, BootTrampolineActivity.class);
            trampoline.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            try {
                context.startActivity(trampoline);
                runtime.auditLog.log("INFO", "BOOT_TRAMPOLINE_LAUNCHED",
                        "Launched BootTrampolineActivity to restore " + savedMode.name());
            } catch (Exception e) {
                runtime.auditLog.log("WARN", "BOOT_TRAMPOLINE_DENIED",
                        "Staying in WATCHDOG_ONLY until MainActivity opened: " + e.getMessage());
            }
        }
    }
}
