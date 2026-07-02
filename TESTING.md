# Standalone Call Recorder — Install & Test

This is the **standalone** call recorder (kept separate from the merged
Dialer-main app so you can test the pure recorder in isolation).

- **Package:** `com.whtsagent.recorder.debug`  (the merged dialer app is `com.whtsagent.crm.debug` — they can coexist)
- **APK:** `app/build/outputs/apk/debug/app-debug.apk`
- **Records calls via Shizuku** (no root). It does NOT need to be the default dialer — it auto-detects calls from any dialer.

---

## 0. One-time: make `adb` reachable in your terminal
`adb` lives inside the Android SDK. This line adds that folder to your shell's
`PATH` **for the current terminal session** so you can type `adb` instead of the
full path:

```bash
export PATH="/Users/alalmadarat/Downloads/main_files/whats-mobile/.android-sdk/platform-tools:$PATH"
adb devices        # should list your phone
```
(It only lasts for this terminal window. Add the line to `~/.zshrc` to make it permanent.)

---

## 1. Rebuild (optional — APK already built)
```bash
cd /Users/alalmadarat/Projects/whtsagent-recorder
./gradlew :app:assembleDebug
```

## 2. Install on the phone
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. Set up Shizuku (required — this is how it records both call sides)
1. Install **Shizuku** from Google Play (package `moe.shizuku.privileged.api`).
2. Start the Shizuku service:
   - **Android 11+ (no PC):** in the Shizuku app, use *Start via Wireless debugging*.
   - **Via this computer:** in the Shizuku app tap *Start via computer*; it shows
     the exact `adb shell sh .../start.sh` command — paste it in your terminal.
3. Open the recorder app → complete onboarding → when prompted, **grant Shizuku**
   permission to the app, and allow the runtime permissions it asks for
   (microphone, phone, contacts, notifications).

## 4. Record a test call
- Place or receive a normal phone call (any dialer).
- The recorder auto-detects the call and records. A recording notification appears.

## 5. Watch what it's doing (live log)
All recorder activity logs under a single tag, `Callrec`:
```bash
adb logcat -c && adb logcat -s Callrec:V
```
Key lines to expect: `[Service] onStartCommand action=...CALL_START` →
`kickoff: starting recorder` → (call ends) `stopRecording reason=call_ended`.

## 6. Find the saved recordings
```bash
adb shell ls -l /sdcard/Android/data/com.whtsagent.recorder.debug/files/recordings/
# pull them to your Mac:
adb pull /sdcard/Android/data/com.whtsagent.recorder.debug/files/recordings/ ./recordings
```

## 7. Uninstall (when done)
```bash
adb uninstall com.whtsagent.recorder.debug
```

---

### Notes
- If recording produces silent/empty files, Shizuku isn't bound — re-check step 3
  (the log will show it never reached `DaemonHealth.Bound`).
- A "this app may be unsafe / from an unknown source" prompt on first launch is
  your phone's OEM (e.g. MIUI) sideload warning, not the app — allow it.
- This standalone build uses the recorder's own call detection (PHONE_STATE +
  overlay). The **merged Dialer-main** app instead triggers recording from its
  default-dialer `InCallService`, so its test flow requires setting it as the
  default phone app first.
