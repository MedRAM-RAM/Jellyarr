package com.jellyarr.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/**
 * Restarts the app process (used after a restore, so restored preferences are read from disk instead of being
 * overwritten by the copies still held in memory). Runs in its own ":restart" process: it ends the main process,
 * launches the app again and then ends itself.
 */
public class RestartActivity extends Activity {

    private static final String EXTRA_PID = "main_pid";

    public static void restart(Activity from) {
        Intent i = new Intent(from, RestartActivity.class);
        i.putExtra(EXTRA_PID, Process.myPid());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        from.startActivity(i);
        from.finishAffinity();
        Process.killProcess(Process.myPid());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pid = getIntent() != null ? getIntent().getIntExtra(EXTRA_PID, 0) : 0;
        if (pid > 0 && pid != Process.myPid()) {
            try { Process.killProcess(pid); } catch (Throwable ignored) {}
        }
        Context c = getApplicationContext();
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(launch);
        }
        finish();
        Process.killProcess(Process.myPid());
    }
}
