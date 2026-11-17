# Architecture

How FOCUS LIFE is put together, and why. The constraints that shaped every
decision are a **4 GB OPPO A18 on a Helio G85**, **Android 10+**, **offline only**,
and **no paid API, no ads**.

---

## Stack

| Choice | Why |
|---|---|
| **Java 17** | No Kotlin stdlib in the APK, no Compose runtime, faster cold builds. On a 4 GB device the smaller process is the point |
| **Single Activity + Fragments** | Five tabs share one process, one window and one fragment back stack. Tab switching is `hide`/`show`, so a tab never re-inflates |
| **ViewBinding** | Compile-time-checked view references with no reflection and no `findViewById` at runtime |
| **Room 2.6.1** | Local-only persistence with compile-time SQL verification. The schema is exported to `app/schemas` |
| **WorkManager 2.9.1** | The only sanctioned way to do deferrable background work that survives reboot and process death |
| **Hand-drawn Canvas charts** | Three views — `ProgressRingView`, `BarChartView`, `WeekStripView`. A charting library would add more weight than the entire feature set it serves |
| **No `INTERNET` permission** | Not a policy statement — an architectural constraint. Nothing can reach a network, so no dependency may need one |

Deliberately absent: MPAndroidChart, JitPack-hosted libraries, Retrofit/OkHttp,
Firebase, Dagger/Hilt, RxJava, Kotlin coroutines.

---

## Layering

```
ui/          Activities, Fragments, Adapters, custom Views
  ↓ calls
data/repo/   Repository — every query and aggregate lives here
  ↓
data/db/     Room: 17 entities, 10 DAOs, AppDatabase, DatabaseSeeder
data/prefs/  Prefs — two SharedPreferences files

blocking/    Decision engine + state cache + services (reads Repository)
stats/       FocusScoreCalculator (pure), UsageStatsHelper (system API)
util/        TimeUtils, RevisionSchedule, AppCategory (pure), PermissionManager
notify/      NotifHelper, receivers, RoutineAlarmScheduler
work/        Four Workers
```

Two rules keep this honest:

- **UI never touches a DAO.** Screens call `Repository` and nothing else, so there
  is one place where a query can be wrong.
- **Nothing below `ui/` holds a `View`.** Which is why the scoring, blocking and
  scheduling logic is unit-testable on a JVM with no emulator.

### Threading

`AppDatabase.io()` exposes a single shared serial executor. Every screen follows
the same shape:

```java
AppDatabase.io().execute(() -> {
    Payload p = collect(repo);          // all DB work, off the main thread
    runOnUiThread(() -> {
        if (binding == null) return;    // fragment/activity may be gone
        render(p);
    });
});
```

The `binding == null` guard is not defensive decoration. A fragment can be
detached while a query is in flight, and writing to a dead binding is how an
offline-first app crashes on rotation.

Room queries are never on the main thread; the accessibility hot path never
touches the database at all (see below).

---

## Data model

**17 entities, 10 DAOs.** Conventions that recur:

- **Every per-day table carries an explicit `day_key`** (`yyyy-MM-dd`), rather
  than deriving a day from a timestamp. This makes "all of Tuesday" a single
  indexed range query instead of a scan, and it makes day boundaries survive a
  timezone change.
- **A missed habit is an absent row.** There is no `failed` flag anywhere. An
  absent `HabitLogEntity` means "not done", which cannot be reported as a failure
  and cannot be shamed.
- **Built-ins store a string resource id** (`nameRes`, `titleRes`) alongside any
  user-typed text, so seeded habits, routine slots and exercises follow the
  device language. Typing your own title sets `titleRes = 0` and the literal wins.
- **Exam and subject constants are stored in English.** Display names come from
  `study_exam_*` / `study_subject_*`. Storing the localised label would corrupt
  historical data the moment the user switched language.
- **`DailySummaryEntity` is a materialised per-day report.** It is the single row
  the dashboard, the report screen and the 9 PM notification all read, so the
  three cannot disagree about a day.

Things that do *not* exist, on purpose:

- No `AppLimitEntity` — folded into `BlockedAppEntity.dailyLimitMin` /
  `usedTodayMin` / `usageDayKey`, because a limit is a property of a blocked app.
- No `MotivationGoalEntity` — `GoalEntity.kind` distinguishes goal / vision /
  milestone.
- No milestone table — milestones are *facts about history*, so they are derived
  on read. A stored milestone could drift from the data it describes: deleting a
  focus session would leave "10 sessions completed" permanently marked as reached.

**Retention:** usage snapshots 180 days, summaries 400 days. Both are pruned by
the workers, so the database cannot grow without bound on a 4 GB phone.

**`WorkoutLogEntity` has a composite primary key** (`workout_id`, `day_key`),
which is what makes the checklist a single toggle rather than a
find-then-insert-then-dedupe sequence.

---

## Focus Score

`FocusScoreCalculator` is **pure Java with zero Android imports** — it is the most
heavily tested class in the project precisely because it is the number the user
judges the app by.

| Component | Weight |
|---|---|
| Habits | 28 |
| Study time | 26 |
| Focus sessions | 12 |
| Fitness | 12 |
| Tasks | 10 |
| Screen time (inverse) | 8 |
| Distraction control | 4 |
| **Total** | **100** |

Three guarantees, each pinned by a unit test:

1. **Additive, never punitive.** Every component contributes 0…weight. Nothing
   subtracts. The worst possible day scores exactly 0, never less.
2. **A target of 0 skips the component and renormalises.** Someone who never sets
   a running target still gets a fair 0–100 score instead of being permanently
   capped at 88. With nothing configured at all the score is 0 — a fresh install
   showing 100 would be a lie.
3. **Unmeasurable is dropped, not failed.** Without Usage access the screen-time
   component leaves the *denominator* entirely, so the same study effort scores
   higher rather than being dragged down by a number the app could never read.

Two special cases:

- **A rest day awards the full fitness weight.** A planned rest is a completed
  decision, not a miss.
- **Screen time is inverse-scaled**: full marks at or under the limit, zero at
  twice the limit.

`STREAK_THRESHOLD = 55` decides which days count toward a streak. The streak rule
is **forgiving by design**: if today has not yet qualified but yesterday did, the
streak is preserved rather than broken — the day is not over. Breaking a streak at
9 a.m. would turn a motivator into a source of shame.

`Result.topImprovement` names the single highest-leverage next action. It only
considers components the user actually configured; a bug where skipped components
reported their full weight as "available" made the app recommend completing habits
that did not exist.

---

## Blocking

This is the part of the app that can stand between a user and their own phone, so
it is built around two invariants.

### Invariant 1: fail open

Every ambiguous case resolves to **allow**. Null snapshot, null package, unknown
mode string, malformed schedule, unreadable usage counter, a `SecurityException`
mid-call. Locking someone out of their phone because of a bug is worse than
letting one distraction through.

### Invariant 2: safety first, not user-configurable

The dialer, SMS, System UI, the permission controllers, ColorOS's own management
apps and this app itself are **never** blocked — even when the user adds them to
the block list with `blockAlways` and `strict` both on. An app that could block an
emergency call would be genuinely dangerous, so this is not a setting.

### The decision pipeline

`BlockDecisionEngine.shouldBlock(snapshot, package)` is pure Java. It evaluates in
a fixed order, and the order is the precedence:

```
1. safety allow-list            → allow   (never overridable)
2. live emergency unlock        → allow   (this exact package only)
3. not on the enabled list      → allow
4. active mode covers this app  → block   REASON_MODE
5. blockAlways                  → block   REASON_ALWAYS
6. an open schedule window      → block   REASON_SCHEDULE
7. daily limit reached          → block   REASON_LIMIT
```

Step 7 additionally requires `dayKeyMatches(today)`. Without that guard,
yesterday's usage total would block the app all morning until the next snapshot
landed — a false positive caused by stale data rather than by the user's behaviour.

### The hot path does no I/O

`BlockState` holds an immutable `Snapshot`: a `HashMap` of enabled blocked apps
plus the enabled schedules plus the current mode, unlock state and clock fields.
It is rebuilt asynchronously whenever configuration changes and reused for every
foreground event.

So `BlockAccessibilityService.onAccessibilityEvent` does a **hash lookup and an
integer comparison — no database access, ever.** A service that queried Room on
every window change would be both slow and a battery drain.

The service handles only `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOWS_CHANGED`,
reads **only `getPackageName()`**, and debounces at 2500 ms for the same package /
800 ms across packages. Its XML config requests exactly those two event types:
it previously requested `typeWindowContentChanged` — the chattiest event Android
has, firing on every text change anywhere on screen — and discarded all of them.
`canRetrieveWindowContent`, `flagRetrieveInteractiveWindows` and
`flagReportViewIds` are all off, so the service *cannot* read window content even
if the code tried.

### Enforcement levels

`ModeController.enforcementLevel(context)` reports what is actually possible, and
the UI states it plainly rather than implying blocking works when it does not:

| Level | Condition | Behaviour |
|---|---|---|
| `FULL` | Accessibility + overlay | Warning drawn over anything, including the lock screen |
| `NOTIFICATION` | Accessibility only | Warning opens as an activity |
| `NONE` | Neither | Alerts only; nothing is intercepted |

### Schedules

`BlockScheduleEntity.daysMask` is a bitfield over `SUN`…`SAT` where the bit for
`Calendar` day-of-week `d` is `1 << (d - 1)`. Windows may cross midnight
(`end <= start`), which needs two independent day checks: late *this evening*
belongs to today's mask, early *this morning* is the tail of yesterday's. Testing
today's bit first and returning early made the second branch unreachable, so a
Saturday-night-only window stopped blocking at midnight instead of running to its
configured 04:30 end. Both cases are now unit-tested.

### Emergency unlock

`EmergencyUnlockActivity` grants a **10-minute** unlock for one specific package
behind a **5-second press-and-hold**, with a **15-minute cooldown**
(`EMERGENCY_COOLDOWN_MS`). The hold and the cooldown are the point: an escape
hatch that is one tap away is not an escape hatch, it is a disabled blocker. The
unlock is scoped to the exact package, so unlocking Instagram does not unlock
anything else.

`BlockWarningActivity` rotates your own written reasons through
`leastShownReason()` + `incrementShown()`, because a reason you have never seen is
a reason that is not working. In strict mode there is no "open anyway" button at
all. Back calls `finishAndRemoveTask()` so no warning screen is left in Recents.

---

## Reminder delivery: two layers, by design

WorkManager has a **15-minute minimum** periodic interval, and its execution is
deferred by Doze and by ColorOS. A 5-minute lead time announced by a 30-minute
worker could arrive 25 minutes late — useless for "wake up at 04:30".

So delivery and punctuality are separate mechanisms:

| Layer | Mechanism | Role |
|---|---|---|
| **Delivery guarantee** | `ReminderWorker`, every 30 min | Scans for anything due and posts it. Survives reboot and process death |
| **Punctuality** | `RoutineAlarmScheduler`, one exact alarm | Fires the *next* due routine slot on time |

`scheduleNext()` is **idempotent** and looks ahead 8 hours (`LOOKAHEAD_MS`), so it
never stacks duplicate alarms. Where exact alarms are available it uses
`setExactAndAllowWhileIdle`; otherwise it falls back to `setWindow` with a
**±10-minute** tolerance, and the onboarding screen *tells the user* that
reminders will be approximate instead of promising punctuality it cannot keep.

`SCHEDULE_EXACT_ALARM` is **not declared**. On Android 12+ that means the inexact
fallback always applies — a deliberate trade, since requesting a privileged
special access for a study reminder would be asking for a capability the two-layer
design already covers.

The alarm is re-armed from four places: cold start, `BootReceiver`, every
`ReminderWorker` pass, and every routine mutation. `RoutineAlarmReceiver` uses
`goAsync()` on a shared IO executor and re-reads the slot before posting, so an
edit made after the alarm was set is honoured rather than announced stale.

**`DailyReportWorker` always reports yesterday.** Reporting "today" at 9 PM would
describe a day that is not over.

---

## Background work

Four workers, three of them unique-periodic with `ExistingPeriodicWorkPolicy.UPDATE`
so an app update re-registers them without duplicating:

| Worker | Interval | Unique name |
|---|---|---|
| `UsageSnapshotWorker` | 6 hours | `focuslife_usage_snapshot` |
| `ReminderWorker` | 30 minutes | `focuslife_reminders` |
| `DailyReportWorker` | 24 hours | `focuslife_daily_report` |
| `SnoozeWorker` | on demand | — |

`FocusLifeApp` is a `Configuration.Provider`, so WorkManager is initialised
on-demand rather than by `androidx.startup` — the manifest strips
`WorkManagerInitializer` from `InitializationProvider` explicitly.

**`BlockForegroundService`** is `START_STICKY`, ticks on a 60-second `Handler`,
holds **no wake lock**, and re-arms itself in `onTaskRemoved`. A foreground service
with a visible notification is the only reliable way to keep a guard alive on
ColorOS; the missing wake lock is deliberate, since a wake lock held for the whole
day would be the single biggest battery cost in the app.

Battery efficiency overall: no polling faster than 30 minutes, no wake lock in the
hot path, no network, no content-retrieving accessibility flags, and charts drawn
with a single `Canvas` pass instead of a view hierarchy.

---

## Localisation

`values/strings.xml` (English base) and `values-hi/strings.xml` are kept at
**872 keys in exact parity**. The app follows the system language by default;
Profile offers a per-app override in `{system, en, hi}`.

`FocusLifeApp.attachBaseContext` wraps the base context through
`LocaleSupport.wrap`, and `onConfigurationChanged` re-applies the override so a
system language change cannot silently undo it. Changing the override recreates
the activity — already-inflated views would otherwise keep their old strings until
each screen happened to be rebuilt.

Two rules the static auditor enforces:

- **Identical positional placeholders** in both locales, and no non-positional
  `%s` where a string takes two or more arguments (word order differs between
  English and Hindi, so `%1$d`/`%2$s` is mandatory).
- **No format string used as a static label.** `setText(R.string.x)` where `x`
  contains `%1$d` renders the raw placeholder on screen. This bug shipped five
  times before the check existed; the fix is always to add a dedicated static
  label rather than pass a dummy argument.

Hindi text uses a **16 sp body floor and 24 sp line height** because Devanagari has
ascenders and descenders that Latin does not, and clipping them makes text
genuinely hard to read.

---

## Design tokens

Deep navy `#070B16` → `#31405F`, cyan accent `#22D3EE`, success `#34D399`,
warning `#FBBF24`, danger `#FB7185`, info `#818CF8`, three text tiers, and six
chart colours. Card radius 20 dp, dashboard ring 200 dp with a 14 dp stroke,
48 dp minimum touch target, 64 dp timer text.

One colour rule carries meaning: **analysis bars are green ≥ 70, accent ≥ 40,
muted below — never red.** Red is reserved for an actual breach. Colouring a
low-consistency habit red would make a reflection tool into a scorecard, which is
the behaviour the brief explicitly forbids.

Motion is minimal by design: two animation resources. Nothing animates on a
scroll path.

---

## The static auditor

This project was written in a sandbox with **no JDK, no Android SDK and every
binary mirror firewalled**, so `aapt2` and `javac` could not run at all.
`tools/check_resources.py` reproduces the cheapest, highest-yield part of the
build:

- every `R.<type>.<name>` in Java and `@<type>/<name>` in XML resolves
- every custom `prv_*` / `bcv_*` / `wsv_*` attribute is declared in `attrs.xml`
- every `binding.<field>` matches an `android:id` in the correct layout, resolved
  **per declared variable** and following nested `<include>`s
- en/hi string parity, placeholder equality, duplicate detection, CJK screening
- **format-argument verification** — infers `%d` from literals and arithmetic and
  `%s` from string expressions, then checks each `getString`/`setText` call
- every manifest component, custom view class and `com.focuslife.app.*` import has
  a source file

It verifies 1,844 declared resource names, 680 `R.*` references, 431 `@type/name`
references and 872 strings per locale, and exits non-zero so it can gate a commit.

**What it cannot check:** Java method existence and arity. That gap is covered by
the 159 unit tests and by CI compilation — and it is why every API a new screen
depends on is read from source before it is called, rather than assumed.

The tool has its own hard-won fixes recorded in its comments: excluding
`android.R.attr.*`, harvesting `@+id/` declarations from XML, a `camel_to_snake`
that must not emit a leading underscore or split runs of capitals, an allowlist
for library resources, and implicit dotted style parents.
