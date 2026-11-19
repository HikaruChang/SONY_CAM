// Powered by NyphexAI, Developed by Hikaru(i@rua.moe)
package com.sonycam.guard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Invisible 450ms trampoline activity launched after cold boot (with SYSTEM_ALERT_WINDOW BAL exemption)
 * so Android 14 grants PROC_STATE_TOP while GuardMonitorService elevates to FOREGROUND_SERVICE_TYPE_CAMERA.
 */
public final class BootTrampolineActivity extends Activity {
    private static final long CLOSE_DELAY_MS = 450L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        Intent svc = new Intent(this, GuardMonitorService.class);
        svc.setAction(GuardMonitorService.ACTION_RESTORE_SAVED_MODE);
        try {
            startForegroundService(svc);
        } catch (Exception e) {
            GuardRuntime.get(this).auditLog.log("WARN", "BOOT_TRAMPOLINE_SVC_ERR", e.getMessage());
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) {
                finish();
                overridePendingTransition(0, 0);
            }
        }, CLOSE_DELAY_MS);
    }
}
