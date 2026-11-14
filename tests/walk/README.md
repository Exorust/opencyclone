# Walk harness

Walk flows on a real phone without a paid model and without touching the phone. A scripted model server runs on the Mac. The phone reaches it through `adb reverse`. The app is driven by `adb shell am broadcast` commands to a receiver that exists only in debug builds and only accepts senders that hold `DUMP` (the adb shell user).

## Commands, in order

Run them from the repository root.

```bash
# 1. Build and install the debug app and the test app.
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleStandardDebug :testapp:assembleDebug && cd ..
adb install -r android/app/build/outputs/apk/standard/debug/app-standard-debug.apk
adb install -r android/testapp/build/outputs/apk/debug/testapp-debug.apk

# 2. Once per phone: the usual setup (helper process, accessibility service, notification access).
#    The hidden display and the accessibility service must be working; `walk.sh state` shows both.

# 3. Start the mock server, add the adb reverse, open the app, point it at the mock.
tests/walk/walk.sh setup

# 4. Check the app state: service_running, backend, accessibility_connected.
tests/walk/walk.sh state

# 5. Run scenarios (each prints PASS/FAIL per expectation and saves evidence).
tests/walk/walk.sh list
tests/walk/walk.sh run send_approve --out /tmp/walk/send_approve
tests/walk/walk.sh run send_reject
tests/walk/walk.sh run icon_only_unreadable
tests/walk/walk.sh run finish_without_commit
tests/walk/walk.sh run ask_owner
tests/walk/walk.sh run notification_proposal
tests/walk/walk.sh all --out /tmp/walk/all          # every scenario in a row

# 6. Drive by hand while a task runs.
tests/walk/walk.sh approve        # refused when the phone is locked or Approve is not offered
tests/walk/walk.sh reject
tests/walk/walk.sh edit Send it to Sam instead
tests/walk/walk.sh answer Alex
tests/walk/walk.sh stop
tests/walk/walk.sh start Open Gate Test
tests/walk/walk.sh notify ai.opencyclone.testapp Alex Are we still on for dinner at 7

# 7. Stop the mock server and remove the adb reverse.
tests/walk/walk.sh teardown
```

Without `--out`, evidence goes to `tests/walk/evidence/<time>-<scenario>/`: `result.json` (checks and events), `state.json` (the app state at the end), `mock_requests.json` (what the model was asked and what it answered), `screen.png` (the main display, not the hidden one), `logcat.txt`.

The same commands without the script:

```bash
adb shell am broadcast -n ai.opencyclone.standard/ai.opencyclone.debug.DebugCommandReceiver \
    -a ai.opencyclone.debug.CMD --es cmd state
adb shell cat /sdcard/Android/data/ai.opencyclone.standard/files/walk/state.json
adb logcat -s WALK
```

## Notes

- The phone must be unlocked for approve and edit, the same as the card's buttons.
- `send_approve` uses the test app's Chat screen. It has a heading ("Alex"), so the card can name the target and offer Approve. The Labeled screen has no heading, so its Send button is held with Review only.
- `notification_proposal` waits about 30 seconds for the triage batch. It expects a proposal, so no notification window may be running and the test app must not be allowed to act on its own. Its proposal card stays until dismissed; a repeat run updates the same proposal.
- Real notifications that arrive during a walk also go to the mock. They get `[]` (summarize), and the scripted triage reply waits for the walk's own notification.
- After teardown the app still points at `http://localhost:8765/v1`. Set a real model in Settings before a real walk.
- Environment: `WALK_PORT` (8765), `WALK_PACKAGE` (`ai.opencyclone.standard`), `ANDROID_SERIAL`, `ADB`, `PYTHON`. The mock server exits by itself after `WALK_SERVER_MAX_SECONDS` (4 hours) even if teardown never runs.
- Offline tests of the harness: `python3 -m unittest discover -s tests/walk`.

## Look check (`walk.sh look`)

Checks the look of the screen in front against `UI.md` (sections 9, 10 and 11 override earlier ones). It takes a screenshot (`adb exec-out screencap -p`), a view listing (`uiautomator dump`), the density and size (`wm density`, `wm size`), the resumed activity (`dumpsys activity activities`) and the system bar heights (`dumpsys window`), then prints one PASS, FAIL or SKIP line for each rule with the measured value and the `UI.md` section. It never taps: navigating to the screen is the PM's job.

```bash
tests/walk/walk.sh look --list                       # screens and how many rules each has
tests/walk/walk.sh look home_idle                    # check the screen in front as home_idle
tests/walk/walk.sh look home_approval --out /tmp/look/approval
tests/walk/walk.sh look all                          # detect the screen in front, run its rules that need no other state
tests/walk/walk.sh look --from tests/walk/evidence/<folder> home_idle   # re-check saved evidence, no phone needed
python3 tests/walk/look.py --from <folder> all --density 420            # the same without the script
```

Evidence goes to `tests/walk/evidence/<time>-look-<screen>/` (or `--out`): `screen.png`, `window.xml`, `device.json` (density, size, activity, bar heights) and `look.json` (every result). The exit code is 0 when no rule failed (skipped rules do not fail), 1 when a rule failed, 2 when the capture failed or the screen is unknown. `--from` reads `screen.png` and `window.xml`; without `device.json` it needs `--density` (otherwise it assumes the screen is 412 dp wide) and the activity rule fails.

- **Rules** are in `look_rules.json`: tokens (the section 11 palette), `global` rules for every screen, reusable `groups`, and for each screen `detect` (activity plus marker nodes, used by `look all`) and `rules`. A rule's own name is `id`; `rid` limits it to nodes with that resource id. Sizes are dp (px = dp x density / 160); colours compare by the largest channel difference.
- **needs_state**: a rule that applies only in one state (for example "pill visible"). With `when` / `when_absent` (a node selector) it is skipped when the state is not on screen. Without them it runs when the screen is named, and is skipped by `look all`.
- **Rule kinds**: `activity`, `text_present`, `text_absent`, `no_class`, `count_primary`, `left_edge`, `colour_at`, `text_colours`, `wash_top` (alias `not_flat`), `no_emoji_or_bang`, `sentence_rule`, `touch_target`, `no_overlap_systembars`, `order` (also `"axis": "x"`), `footer`, `no_lines`. Each is a small function `k_<kind>` in `look.py`.
- **Limits**: only the app's own nodes are checked (package from `WALK_PACKAGE`). Text colour is estimated from the darkest 5% of the pixels in a text node (the lightest on a primary or dark background), so an icon or photo inside a text node's bounds gives a false FAIL; add it to the rule's `except_ids`. A row partly scrolled off screen can fail `touch_target`. `uiautomator dump` fails while the screen animates; run it again.
- Pillow is used when it is installed; otherwise a pure-Python PNG reader decodes the screenshot (about 1 second for 1080 x 2400).
- Offline tests: `python3 -m unittest discover -s tests/walk` (fixtures in `fixtures/look/`).
## Step scenarios (version 2)

`scenarios_v2.json` holds scenarios made of steps (see its `_format`). Run one with `walk.sh run2 <name>`, all with
`walk.sh all2`, list them with `walk.sh list2`. Since version 2, `start`, `approve` and `reject` go through the home
page's own entry (`FeedLocator.commands`), so the old scenarios exercise the same routing as typing on the home page.
New commands: `confirm` (a CONFIRM row: proposal, widget, Set up; or `--item draft` for a widget draft card), `page`
(home, history, widgets), `app_seed` (a built app with bridge data) and `widget_seed` (a widget without a model).
