# Installing UltraX 26 on your phone

UltraX 26 is not in the Play Store. You install it as an APK file, which Android calls "installing
from an unknown source". Any of the three routes below ends with the same APK on the phone.

## Route A — download from GitHub Releases (no computer needed)

Once the repository is on GitHub, every push to `main` builds the app and republishes it at a stable
address:

```
https://github.com/MossesX/UltraX-26/releases/latest/download/UltraX26-debug.apk
```

1. Open that link in the phone's browser. The download starts.
2. Tap the finished download. Android asks to allow installs from your browser the first time:
   **Settings → Install unknown apps → (your browser) → Allow**, then go back.
3. Tap **Install**, then **Open**. Grant camera, microphone and notification access.

The first build takes about 15 minutes after the push; until then the link shows "Not Found".
Reinstalling a newer build over the old one keeps your settings (same signing key).

## Route B — build it yourself from the zip or the repository

Requirements: Android Studio (Ladybug or newer) with Android SDK 36 and JDK 17, or just the command
line SDK.

```bash
unzip UltraX-26.zip && cd UltraX-26        # or: git clone https://github.com/MossesX/UltraX-26
./gradlew assembleDebug                    # first run downloads ~35 MB of MediaPipe models + dependencies
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone (USB, Drive, email to
yourself…) and open it, or install over USB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For `adb`: enable **Settings → About phone → Software information → tap Build number 7×**, then
**Developer options → USB debugging**, plug in, and accept the fingerprint prompt on the phone.

## Route C — Android Studio ▸ Run

Open the folder in Android Studio, plug in the phone with USB debugging on, pick it in the device
dropdown and press **Run ▸ Run 'app'**. Studio builds, installs and launches it.

## After installing

- The app id is `com.ultrax26.recorder.debug` for the debug build and `com.ultrax26.recorder` for the
  release build; both can be installed side by side.
- Samsung: if the camera preview is black on first start, allow the camera permission then reopen the
  app; One UI sometimes delivers the grant after the camera has been opened.
- Video calls need internet; recording and gesture detection work offline.
- Updating: install the new APK over the old one. Settings, presets and trained voice phrases are kept.
