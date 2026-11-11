# Recall

**A tutor that remembers you.**

Runs on a free Gemini or Groq key that you paste on the phone. No subscription, no account, no server.

Every AI chat starts cold. You re-explain who you are, what you are studying, and what you already tried, every single time. Recall keeps a persistent model of the learner on the phone: which concepts they have hit, how solid each one is, the specific misconceptions they have shown, and when each is due for another look. Every session opens with the app setting the agenda from that model, not with a blank box.

The conversation is the interface. The memory is the product.

## Setup

1. Install `Recall.apk`, or build it with the steps below.
2. The first launch is three cards: what Recall is, a provider and a key, then your name and a nudge time.
3. Create a free key at [Google AI Studio](https://aistudio.google.com) or [console.groq.com](https://console.groq.com). Neither asks for a card.
4. Paste the key in the app and tap **Test this key**. You cannot continue until that call succeeds. **Skip for now** leaves you on the home screen, which will not tutor until a key is saved.
5. The key is stored in the app's private preferences. It is sent only to the provider you picked. Free tiers may keep what you send, and the setup screen says so.

Do not put a key in source, the README, the project page, or git. There is no backend proxy. If you fork this, you still bring your own key.

## How it works

1. **Home** names one target, chosen from the local model: most overdue, then least confident, with an open misconception breaking the tie. An explicit "drill this" pins that concept without backdating it.
2. **Session** opens with the tutor speaking first. The learner model is injected into the system prompt on every turn.
3. **End session** runs a second, structured call. The transcript goes back under a JSON schema: concepts and confidences, gaps opened, gaps resolved, and a summary written to the learner as "you".
4. **Consolidation** folds that into SQLite and reschedules each concept by confidence (1, 2, 4, 8, or 16 days). Confidence is judged from what the transcript demonstrates, not from what the learner was told.
5. If that call fails, the conversation stays on the phone. You can try again, save the transcript without updating memory, or keep going. Discarding a reopened session drops only the new part.
6. **Mind** shows the model back to the learner. **Progress** stays empty until two readings exist.

Concepts, gaps, sessions, and transcripts live in SQLite. Nothing but the model call needs a network.

## Build

No Gradle, no Kotlin, no AndroidX, no third-party libraries. `HttpURLConnection`, `org.json`, `SQLiteOpenHelper`, and framework views only.

```bash
bash build.sh          # writes Recall.apk
```

Requires:

- **JDK 21** with `javac` and `jar`. The script uses `JAVA_HOME` when that directory is valid, then a local JDK 21 if one is already installed.
- **Android SDK** with build-tools `36.0.0` and `platforms/android-36`. The script uses `ANDROID_HOME` or `ANDROID_SDK_ROOT`, then the usual SDK directory on this machine.
- A debug keystore at `~/.android/debug.keystore` (the standard Android debug keystore). The script signs with it. The keystore is not part of the repo.

`minSdk 24`, `targetSdk 36`. The script prints the APK size when it finishes.

## Layout

```
AndroidManifest.xml
build.sh                       aapt2 -> javac -> d8 -> zipalign -> apksigner
docs/                          project page (GitHub Pages, /docs)
src/com/recall/
  MainActivity.java            home: agenda, stats, session sheet
  OnboardActivity.java         three cards, once, including a tested key
  SessionActivity.java         conversation, voice, end-of-session save
  MindActivity.java            the learner model
  ConceptActivity.java         one concept, notes, rename, merge, forget
  ProgressActivity.java        trend, only once it means something
  SettingsActivity.java        key, model, theme, export, wipe
  data/Db.java                 SQLite, scheduling, export
  data/Model.java              plain data holders
  net/Ai.java                  provider dispatch and the update schema
  net/Gemini.java, Groq.java   the two wire formats
  net/Http.java                shared JSON-over-HTTPS poster
  net/Tutor.java               the persona and the two system prompts
  util/                        preferences, themes, alarms, background work
res/                           layouts, colors, drawables, widget
```

## What has and has not been verified

The project was rebuilt from this tree with `bash build.sh` (JDK 21, build-tools 36.0.0, `platforms/android-36`). That produced a signed `Recall.apk`. The APK is gitignored. It is not attached to a release here, because publishing one needs `gh auth login` and that command is not logged in on this machine.

Code paths for a missing key, a rejected key, a failed connection, an empty database, a failed save, reopening a session, and a schema that already migrates from version 1 were read and fixed where they were wrong. Earlier work on this app drove an invalid key against both providers and got back "That API key was rejected". This pass did not repeat that call, and it did not run a live tutoring exchange.

**Not verified:** a live tutoring turn or the consolidation round trip. No free-tier key was available, and none was added. Paste a key into Setup and tap **Test connection** before trusting a model id. If the model is not on your free tier, the app says so and another entry in the list can be selected.

## License

MIT. Copyright (c) 2026 Ashwin R.
