# FOCUS LIFE — Personal Discipline & Distraction Control

An offline Android app for studying, running and staying off your phone. Built for
an **OPPO A18** (4 GB RAM, Helio G85) on **Android 10+**, in **English and Hindi**.

No account. No cloud. No ads. No trackers. **The app does not declare the
`INTERNET` permission at all**, so nothing it records can leave your phone except
through a report you explicitly choose to share.

---

## Getting the APK

You do not need Android Studio, a JDK, or the Android SDK. GitHub Actions builds a
signed debug APK on every push:

1. Open the repository on GitHub → **Actions** tab.
2. Select the **Build APK** workflow, then the most recent run.
3. Scroll to **Artifacts** and download **`FocusLife-debug-APK`**.
4. Unzip it and install `FocusLife-debug.apk` on the phone.

Full step-by-step instructions, including the OPPO-specific "install from unknown
sources" prompts, are in **[docs/INSTALL.md](docs/INSTALL.md)**. The APK is
**5.4 MB**.

The same workflow runs **164 JVM unit tests before** it assembles the APK, so a
regression in scoring, blocking or scheduling fails the build instead of shipping.
The pipeline is green: the tests pass and the APK builds.

---

## What it does

| Area | What you get |
|---|---|
| **Dashboard** | Focus Score (0–100), progress ring, study time, focus time, screen time, streak, running progress, remaining goals, daily message |
| **App Blocker** | Pick your own distracting apps, per-app daily limits, scheduled blocking windows, four modes (Study / Running / Sleep / Deep Focus), warning screen before a blocked app opens, emergency unlock with a 5-second hold and a 15-minute cooldown |
| **Deep Focus** | 25/5, 50/10 or custom timers, full-screen, pause and resume, session history, live notification |
| **Study Planner** | Railway ALP, Railway Group D, SSC CGL, SSC GD; Mathematics, Reasoning, Biology, Physics, Chemistry, GK; chapter-wise progress, a persisted study stopwatch, and spaced-repetition revision reminders at 1, 3, 7, 15 and 30 days |
| **Running & Fitness** | Daily distance target, duration and pace, an exercise checklist (pull-ups, push-ups, squats, sit-ups, plank), weekly chart, rest days, optional timed 1600 m with personal bests |
| **Habit Tracker** | Ten seeded habits, daily checklist, completion percentage, weekly calendar, streaks, and consistency analysis that never shames a missed day |
| **Usage Analyzer** | Total screen time, app-wise usage, most-used apps, app-open counts where the OS exposes them, weekly comparison, daily limit, and suggestions derived from your own numbers |
| **CONTROL YOURSELF** | A distraction diary with triggers, your own written reasons, ten 5-minute alternative activities, an emergency focus button, and the four-step reset message |
| **Daily Routine** | Thirteen editable slots from 04:30 wake-up to 20:30 sleep prep, with precise reminders for the next activity |
| **Motivation** | Twenty daily quotes, goals, a vision board, and eighteen milestones derived from your real history |
| **Reports** | A daily report for any past day and a weekly report with trend, both shareable as plain text |

---

## Design decisions worth knowing

**Blocking fails open.** Every ambiguous case — a null snapshot, an unknown mode, a
malformed schedule, an unreadable usage counter — resolves to *allow*. Locking
someone out of their own phone because of a bug is worse than letting one
distraction through.

**The safety allow-list cannot be overridden.** The dialer, SMS, System UI, the
permission controllers and this app itself are never blocked, even if you add them
to the block list with "always" and "strict" both on. An app that could block an
emergency call would be dangerous.

**Nothing is punitive.** A missed habit is an *absent* row, not a failure flag. A
rest day scores full fitness credit. A day with nothing to improve says so instead
of manufacturing a criticism. Analysis bars go green → accent → muted, never red.
A streak of zero reads "not started yet", because there is nothing yet to break.

**Unmeasurable is not zero.** Without Usage access the app reports screen time as
*not measured* and drops that component from the Focus Score denominator. It never
claims you used your phone for 0 minutes.

**Only package names are read.** The accessibility service reads
`getPackageName()` and nothing else — no screen content, no messages, no titles.
Usage analysis classifies apps by package-name fragments, and anything
unrecognised is reported as "Other" and excluded from every category claim rather
than guessed at.

**Charts are hand-drawn.** `ProgressRingView`, `BarChartView` and `WeekStripView`
are three Canvas views totalling a few hundred lines. No charting library, no
JitPack dependency, no transitive weight — which matters on a 4 GB device.

---

## Project layout

```
app/src/main/java/com/focuslife/app/
├── FocusLifeApp.java          Application: WorkManager, locales, channels, warm-up
├── blocking/                  Decision engine, state cache, services, warning UI
├── data/
│   ├── db/                    17 Room entities, 10 DAOs, database, seeder
│   ├── prefs/                 Settings and daily targets (two prefs files)
│   └── repo/                  Repository: every query and aggregate
├── notify/                    Notifications, boot receiver, precise routine alarms
├── stats/                     FocusScoreCalculator, UsageStatsHelper
├── ui/                        MainActivity + 5 tabs, 10 feature screens
├── util/                      TimeUtils, PermissionManager, RevisionSchedule, …
└── work/                      4 workers: snapshot, reminders, report, snooze
app/src/main/res/              54 layouts, 72 drawables, 875 strings ×2 locales
app/src/test/                  164 JVM unit tests
tools/check_resources.py       Static cross-reference auditor (see below)
docs/                          Install, permissions, architecture, testing report
```

Java 17 · Room 2.6.1 · WorkManager 2.9.1 · Material 1.12.0 · ViewBinding ·
minSdk 29 · targetSdk 34. Roughly 21,800 lines of app code and 2,200 of tests.

---

## Building it yourself

The repository deliberately does **not** commit `gradle-wrapper.jar`. CI bootstraps
Gradle 8.7 and uploads the generated wrapper as an artifact, so a local build works
after one download:

```bash
# Option A — Android Studio: just open the folder and let it sync.

# Option B — command line, after downloading the `gradle-wrapper` artifact
# from any Actions run and unzipping it into gradle/wrapper/:
./gradlew testDebugUnitTest     # 164 unit tests
./gradlew assembleDebug         # APK at app/build/outputs/apk/debug/
```

### The static auditor

`tools/check_resources.py` exists because this project was written in an
environment with no JDK and no Android SDK, and every binary mirror was
firewalled — so `aapt2` and `javac` could not run at all. It reproduces the part
of the build that fails most often:

- every XML document **parses** — a style appended after `</resources>` is still a
  declared, correctly referenced resource, and only a real parse sees the problem
- every `R.<type>.<name>` in Java and `@<type>/<name>` in XML resolves
- every style reference resolves exactly, and every own-namespace `parent=` and
  implicit dotted parent resolves
- every custom view attribute is declared in `attrs.xml`
- every `binding.<field>` matches an `android:id` in the right layout, including
  nested `<include>`s
- English and Hindi strings are in parity, with identical positional placeholders,
  no duplicates and no CJK contamination
- **no format string is used where a static label is needed** — `setText` on a
  string containing `%1$d` would render the raw placeholder
- every manifest component and every `com.focuslife.app.*` import has a source file
- every Java source, **tests included**, has balanced delimiters

```bash
python3 tools/check_resources.py    # exits non-zero on any problem
```

It currently verifies 1,830 declared resource names, 680 `R.*` references, 145 XML
documents, 431 `@type/name` references, 90 Java sources and 875 strings per locale.
It cannot check Java method existence or arity — that is what the unit tests and CI
compilation are for, and [docs/TESTING.md](docs/TESTING.md) records what each of
them caught.

---

## Documentation

- **[docs/INSTALL.md](docs/INSTALL.md)** — installing on the OPPO A18, first run,
  and what each permission does
- **[docs/PERMISSIONS.md](docs/PERMISSIONS.md)** — every permission, why it is
  needed, what breaks without it, and how to grant it on ColorOS
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — data model, blocking
  pipeline, reminder delivery, scoring
- **[docs/TESTING.md](docs/TESTING.md)** — what is verified by CI and unit tests,
  and what still needs a physical phone

---

## Licence

See [LICENSE](LICENSE).
