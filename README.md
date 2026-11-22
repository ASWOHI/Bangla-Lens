# Bangla Lens

Android app (Kotlin) that lets you drag over any text on screen, shows it in Bengali, and reads it aloud **only when you press Speaker**.

## Build the APK
**GitHub (no setup):** push this folder to a GitHub repo. Actions → "Build APK" runs automatically → download the
`BanglaLens-debug-apk` artifact → unzip → install `app-debug.apk` (allow "install unknown apps").

**Android Studio:** File → Open this folder → Build → Build APK(s).

## First run
1. Open the app → "Start Floating Magnifier".
2. Allow "Display over other apps".
3. When Android asks about screen sharing choose **Entire screen** → Start now.
4. Open any app → drag the green magnifier onto the text and let go (a green frame shows the area read) → press **Speaker**.
   * A single tap on the magnifier lets you draw your own box instead.
   * Dropping it at the far left/right edge only parks it.

## Notes
* English → Bengali uses an on-device ML Kit model (~30 MB), downloaded once; internet needed that one time.
* A Bengali TTS voice must be installed (Settings → Text-to-speech → Google → install Bengali voice data).
* Screens marked secure by an app (banking, some video apps) are captured as black by Android; the app says so.
* OCR reads Latin script (English / Banglish). ML Kit has no Bengali-script OCR.
