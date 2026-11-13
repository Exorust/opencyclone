# OpenCyclon

An open-source AI agent for a stock Android phone. You say or type what you need. The phone does it in your own apps, in the background, and asks you before it sends, pays, or deletes anything.

OpenCyclon needs no special phone and no changed system. It runs on Android 12 or later as a normal app.

## What it does

- **Tasks in your apps.** "Tell the group chat I'm running ten minutes late." The agent opens the app on a hidden display, reads the screen, and taps and types as a person does. Your visible screen stays free.
- **One approval before anything leaves.** Before a tap on Send, Pay, Delete, or a button it cannot read, the task stops. You see the recipient, the exact text or the attached items, and two buttons, for example "Send to Alex" and "Not now".
- **Notifications handled for you.** "Handle my notifications for the next hour." A message that asks for something becomes a prepared task that waits for your approval. Rules in plain words: "Hide NordVPN notifications."
- **Apps built on request.** "Build me an app to learn Spanish." The phone asks what matters, writes a small app, tests it, and puts it on your home page. Change it by asking. Share it as one file.
- **Widgets.** "Make a widget that shows my streak and how many tasks I ran today." You see a draft with live data, change it by asking, and save it.
- **History.** Swipe right for every past task with its approval, its result, and "Ask again".
- **Voice.** Tap to talk, or hold the power button if you make OpenCyclon the phone's assistant. Speech is recognised on the phone by default.
- **Your choice of model.** Sign in with ChatGPT or OpenRouter, use your own key, or use a model on your own network.
- **A home screen, if you want one.** The page can replace your launcher. This is optional.

## How it works

| Part | What it does |
|---|---|
| Helper process | A small process with the rights of Android's developer shell. It creates a hidden display, starts apps on it, and sends taps and text. It is started once through wireless debugging, in the way Shizuku does it. No root. |
| Accessibility service | Reads the buttons, fields, and texts of the hidden display. |
| Agent loop | Each step sends a picture and a numbered list of screen elements to the model. The model answers with one action. The app waits until the screen is quiet, checks the result, and repeats. |
| Approval gate | Code, not a prompt. It inspects every action before it runs. The model cannot skip it. |
| Notification listener | Reads incoming notifications. One-time codes and notifications from protected apps are dropped before the model sees anything. |

## Safety

- Every action that commits something is held for your approval: a tap on a button with a word such as Send, Pay, Order, Delete, or Confirm; a tap on a button without a readable label; the Enter key in a text field; a call to a connected service that is not read-only; a direct phone call.
- When you approve, the app reads the screen again. If the recipient, the text, or the number of attached items changed, nothing is sent and you are asked again.
- An approval needs an unlocked phone.
- Banking apps, authenticators, password managers, and the phone's settings are never operated. You can add apps to this list.
- Text that comes from another person, in a message or on a screen, is treated as data. It cannot give the agent instructions that pass the gate.
- A task stops after 30 steps or 10 minutes, and when it repeats itself.

## Privacy

- Pictures of the hidden display and the text of your request go to the model you chose. Nothing else leaves the phone.
- Speech is turned into text on the phone by default. If you choose a voice service on the internet, the Privacy screen says where your voice goes.
- Model keys and sign-in tokens are stored encrypted with a key from the Android Keystore.
- Notification text is not kept after the day ends, unless it became a task.

## Requirements

- A phone with Android 12 or later. Development and tests use a Pixel 7 with Android 17.
- A computer with `adb` for the first setup.
- A model that accepts pictures and tool calls.

## Install

Release packages are not published yet. Build from source:

```bash
git clone <repository address>
cd opencyclon/android
JAVA_HOME=<path to JDK 17> ./gradlew :app:assembleStandardDebug
cd ..
./setup.sh            # installs the package and grants the permissions over adb
```

Then open the app on the phone. The first run has a few short steps: connect once (wireless debugging), choose a model, allow the microphone, and try a first request. To let a long press of the power button open the listening screen, run `./setup.sh --assistant`.

## Limits

- A task takes about 4 seconds for each step. A reply in a messaging app is 6 to 10 steps.
- Long tasks across several apps fail more often than short ones.
- One task runs at a time. Others wait.
- An app that marks its screen as secure cannot be read.
- The bundled speech model exists for 64-bit ARM phones only. Android's own recognizer is the default.
- With a third-party home app, Android's swipe animations and recent-apps screen are plainer on some phones.

## Development

```bash
cd android
./gradlew :app:testStandardDebugUnitTest      # unit tests
cd ..
tests/walk/walk.sh setup                      # scripted model for checks on a real phone, no paid model needed
tests/walk/walk.sh all
tests/walk/walk.sh look home_idle             # checks of a screen's look from a screenshot
```

## Credits and licence

OpenCyclon is a fork of [OpenCyvis](https://github.com/opencyvis/opencyvis-phone) at commit `3acd0a5`, by the OpenCyvis contributors. The helper process, the hidden display, and the first agent loop come from that project. The wireless-debugging code comes from Shizuku. See `NOTICE`.

Licence: Apache-2.0.

OpenCyclon is an independent project. It is not affiliated with Cyclon or with OpenCyvis.
