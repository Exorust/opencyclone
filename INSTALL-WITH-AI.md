# Install OpenCyclon with an AI coding agent

This file is written for an AI coding agent that can run shell commands on the user's computer (for example Claude Code, Codex, Cursor, or Gemini CLI). The user gives the agent one sentence:

> Install OpenCyclon on my Android phone. Follow https://raw.githubusercontent.com/Exorust/opencyclon/main/INSTALL-WITH-AI.md

The rest of this file is for the agent.

## Rules for the agent

- Do one step at a time. After each step, read the command output and tell the user in one sentence what happened.
- The user does the steps on the phone. Tell them exactly what to tap, then wait for their answer. Do not send taps to the phone with `adb shell input`.
- Never ask for a model key, a password, or a pairing code in the chat. The user types these on the phone.
- Do not change the screen lock, do not remove other apps, and do not turn off Google Play Protect.
- Install missing tools only after the user agrees. Say what you will install and how.
- If a step fails twice, stop and show the user the exact error text.

## Step 1. Find `adb`

Run `adb version`. If it is missing:
- macOS: `brew install --cask android-platform-tools`
- Debian or Ubuntu: `sudo apt install android-tools-adb`
- Windows: `winget install Google.PlatformTools`

## Step 2. Connect the phone

Ask the user to do this on the phone:
1. Open Settings > About phone and tap "Build number" seven times.
2. Open Settings > System > Developer options. Turn on "USB debugging" and "Wireless debugging". Wi-Fi must be on.
3. Connect the phone with a USB cable and accept "Allow USB debugging".

Run `adb devices`. Continue only when exactly one line ends with `device`. If the line ends with `unauthorized`, the user must accept the question on the phone. If there are several devices, ask which one and set `ANDROID_SERIAL`.

Check the Android version: `adb shell getprop ro.build.version.sdk` must print 31 or more (Android 12).

## Step 3. Get the package

Try the published package first:

```bash
mkdir -p opencyclon && cd opencyclon
curl -fL -o opencyclon.apk https://github.com/Exorust/opencyclon/releases/latest/download/opencyclon-standard-release.apk
curl -fL -o setup.sh https://github.com/Exorust/opencyclon/releases/latest/download/setup.sh && chmod +x setup.sh
```

If `curl` fails with error 404, no release exists yet. Build from source instead:

1. Requirements: Git, JDK 17, and the Android SDK with platform 36. Check `java -version` and the folder `$ANDROID_HOME` (macOS default `~/Library/Android/sdk`, Linux default `~/Android/Sdk`). If the SDK is missing, tell the user that the build needs about 5 GB and ask before you install it.
2. Build:
   ```bash
   git clone https://github.com/Exorust/opencyclon.git && cd opencyclon
   echo "sdk.dir=<path of the Android SDK>" > android/local.properties
   (cd android && JAVA_HOME=<path of JDK 17> ./gradlew --console=plain :app:assembleStandardDebug)
   ```
   The first build can take 10 minutes. The package is `android/app/build/outputs/apk/standard/debug/app-standard-debug.apk`.

## Step 4. Install and grant the permissions

- With the downloaded package: `./setup.sh opencyclon.apk`
- With a source build: `./setup.sh`

Every output line starts with `ok:` or `FAILED:`. All lines must be `ok:` and the last line must be `Done`.

Known failures:
- `FAILED: Package installation` with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: another build with a different signature is installed. Ask the user before you run `adb uninstall ai.opencyclon.standard`, because this deletes the app's data.
- `FAILED: Write secure settings` on a Xiaomi, Redmi, or POCO phone: the user must turn on "USB debugging (Security settings)" in Developer options. Then run the script again.
- `FAILED: Accessibility service (the setting did not hold ...)`: run the script once more. If it fails again, the phone's maker blocks this setting; the user can turn the service on by hand in Settings > Accessibility.

## Step 5. First run, done by the user

Tell the user to open OpenCyclon and to follow its steps:
1. "Connect once": the app pairs with the phone's wireless debugging. The cable can be removed after this step.
2. Choose a model: sign in with ChatGPT or OpenRouter, or enter a key. The user does this on the phone.
3. Permissions and voice.
4. A first request.

## Step 6. Check

Run:

```bash
adb shell dumpsys package ai.opencyclon.standard | grep -m1 versionName
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure enabled_notification_listeners
```

Both settings must contain `ai.opencyclon.standard`. Tell the user the version and that the installation is complete. Tell them also: after a restart of the phone they must open the app with Wi-Fi on and tap "Connect" again; no computer is needed for that.
