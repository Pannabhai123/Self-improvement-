# Feature Testing Report

**Read this first.** This report separates three things that are easy to conflate:

- **A — Verified by automated tests in CI.** Runs on every push, before the APK is
  assembled. A failure blocks the build.
- **B — Verified by static analysis.** Cross-referenced by `tools/check_resources.py`
  on every commit.
- **C — Requires a physical phone.** Not yet verified. Behaviour is implemented and
  reasoned about, but has not been observed running on a device.

Category C is stated plainly rather than optimistically.

**Current status:** CI is green. All 159 unit tests pass, the debug APK assembles,
and it is published as an artifact (5.4 MB). See "The build log" below for what it
took to get there and what that process proved.

**What has still never happened:** this app has not been run on an Android device
or an emulator. The development environment had no JDK, no Android SDK and no
network access to any binary mirror, so the only compilation that has ever occurred
is inside GitHub Actions — which executes tests but cannot exercise a UI, an
accessibility service or a notification. Everything in category C below is
implemented and reasoned about, not observed. Claiming otherwise would violate the
brief's own rule about never presenting an untested feature as working.

---

## How to build and verify it yourself

```bash
./gradlew testDebugUnitTest     # 159 tests — must all pass
./gradlew assembleDebug         # produces the APK
python3 tools/check_resources.py # static audit — exits non-zero on any problem
```

CI runs the first two on every push (see `.github/workflows/build-apk.yml`). The
APK artifact is only uploaded if the tests pass.

---

## A. Verified by automated tests (159 tests, 6 suites)

### `BlockDecisionEngineTest` — 39 tests

The engine is weighted toward what it must **allow**, because a false block is far
worse than a missed one.

| Behaviour | Result |
|---|---|
| Null snapshot → allow | ✅ |
| Null or empty package → allow | ✅ |
| Unknown mode string normalised to `none`, does not block | ✅ |
| Null app/schedule collections tolerated | ✅ |
| Own package never blocked | ✅ |
| Dialer, SMS, in-call UI never blocked — **even with `blockAlways` + `strict` + an exhausted limit** | ✅ |
| System UI, `android`, Settings, permission controllers never blocked | ✅ |
| ColorOS safecenter / phonemanager / `com.oppo.safe` never blocked | ✅ |
| An ordinary social app is *not* on the allow-list | ✅ |
| Live unlock allows exactly one package and does not leak to others | ✅ |
| Expired unlock stops allowing | ✅ |
| Unlisted package allowed; `isOnBlockList` / `app()` agree | ✅ |
| Disabled app excluded from the lookup map | ✅ |
| Empty package name ignored; null list entries ignored | ✅ |
| Each of the four modes blocks only its own per-app flag | ✅ |
| Opting out of a mode actually opts out | ✅ |
| No mode active → no mode block | ✅ |
| `blockAlways` works with no mode active | ✅ |
| Open schedule blocks with no mode active, reporting the schedule's mode | ✅ |
| Closed schedule does not block; window end is exclusive | ✅ |
| Disabled schedule ignored; day mask respected | ✅ |
| Midnight-crossing window open on both sides | ✅ |
| Midnight-crossing window checks *yesterday* against the mask | ✅ |
| Null schedule entries ignored | ✅ |
| Limit blocks once reached; allows while under | ✅ |
| **Stale usage counter from another day cannot block** | ✅ |
| Limit of 0 means unlimited, not blocked | ✅ |
| Precedence: mode > always > schedule > limit | ✅ |
| `strict` flag surfaces on the decision | ✅ |
| Allowed decision carries no app, label or package | ✅ |
| Snapshot retains only the enabled rows it was built from | ✅ |
| All `ALL_MODES` entries recognised; `null` and near-misses rejected | ✅ |

### `FocusScoreCalculatorTest` — 23 tests

| Guarantee | Result |
|---|---|
| Component weights total exactly 100 | ✅ |
| A perfect day scores 100 over an applicable weight of 100 | ✅ |
| A fresh install with nothing configured scores 0, not 100 | ✅ |
| `compute(null)` does not throw | ✅ |
| **The worst possible day floors at 0 and never goes negative** | ✅ |
| Over-achieving one component cannot exceed 100 | ✅ |
| 1 of 10 habits earns partial credit rather than truncating to 0 | ✅ |
| **A user with only habits configured can still reach 100** (unset targets renormalise) | ✅ |
| A rest day awards the full fitness weight; the same day unrested scores 0 | ✅ |
| **Unmeasurable screen time is dropped from the denominator** — identical study effort scores 50 vs 38 | ✅ |
| A perfect day has no improvement to suggest | ✅ |
| **Advice never names a component the user has not configured** | ✅ |
| Advice picks the largest available gain | ✅ |
| Streak counts consecutive qualifying days ending today | ✅ |
| **Streak survives an incomplete today** (the forgiving rule) | ✅ |
| Streak is 0 once the chain has broken; 0 for empty and null input | ✅ |
| Best streak finds the longest run even when it is not current | ✅ |
| Best streak ignores duplicate day keys; 0 for empty input | ✅ |
| A single qualifying day is a streak of one | ✅ |
| `clamp` bounds both ends | ✅ |
| Streak threshold sits below a perfect day and above an empty one | ✅ |

### `TimeUtilsTest` — 31 tests

Day-key format and round-tripping; local midnight; `endOfDay` exactly one
millisecond short; forward, backward and zero offsets; month, year and **leap-day**
boundaries (2024 vs 2026); signed whole-day differences; `isToday`/`isYesterday`
agreeing with offsets; **a malformed day key falling back instead of crashing**;
`lastSevenDayKeys` ending today with every step exactly one day;
`lastNDayKeys` for 1, 3, 7 and 30; `currentWeekRange` starting Monday and spanning
seven days with today inside it; the rolling window and calendar week being
distinguished **without failing on a Sunday**; minute-of-day conversion and
clamping; `millisForMinute` landing on the requested minute; `millisUntilMidnight`
always positive and within a day; all four `dayPart` buckets; and every
locale-independent formatter compared exactly — minutes, distance, duration,
seconds, pace, and both clock formats including midnight and noon.

### `RevisionScheduleTest` — 22 tests

The `{1, 3, 7, 15, 30}` table and its strict expansion; pass count matching the
table; per-pass intervals; **the longest interval repeating forever past the end of
the schedule** — a topic is never dropped; intervals never shortening as passes
increase; negative pass counts treated as the first interval; `nextRevisionAt`
agreeing with `intervalDaysFor` for 41 pass counts; whole-day and negative
`daysUntil`; truncation toward zero so twelve hours reads as today; **an
unscheduled revision sorting last** via `MAX_VALUE`; due exactly at the scheduled
instant, overdue, not-yet-due, and never due when nothing is scheduled;
`isDue` agreeing with `nextRevisionAt` at the exact boundary for 11 pass counts;
and `isFullySpaced` not meaning "dropped".

### `AppCategoryTest` — 18 tests

Twelve social/messaging packages, nine video packages and thirteen games
classified correctly; **everything unrecognised falling to `OTHER`** — including
system packages, this app itself, browsers, shopping apps and audio services;
`null`, empty and whitespace packages; case insensitivity; the four category
constants being distinct; only the three real categories being reportable;
`OTHER` having no label so the UI shows nothing rather than a guess; per-category
minute totals; null-safety; **negative minutes unable to corrupt a total**; the
dominant category being the largest above the share floor; **a thin lead refusing
to be named**; no dominant category when nothing was measured; and unclassified
time not inventing a category.

### `MilestoneTrackerTest` — 26 tests

All eighteen milestones present, each with a distinct non-zero label; **a fresh
install having reached nothing**; `reachedCount` matching the flags; session
milestones at the exact 1 / 10 / 50 / 100 boundaries across nine cases;
**streak milestones using best rather than current streak**, so a slipped streak
does not take an earned milestone away; all five streak thresholds at exactly
one-below and exactly-at; an hour of focus in a single day; a day with every habit
counting while **an empty checklist does not**; ten hours of study needing a full
seven-day window; **the window sliding** rather than only checking the latest week;
a missing day contributing zero; seven days under the limit counting *at* the
limit; an over-limit day breaking the run; **an unmeasured day neither extending
nor breaking it**; too few measured days; a limit of 0 meaning nothing to stay
under; all three 1600 m thresholds including exactly-eight-minutes *not* being
under eight; no recorded run; both topic thresholds; null summaries tolerated; and
a fully achieved history reaching all eighteen.

---

## B. Verified by static analysis

`tools/check_resources.py` passes clean. Current coverage:

| Check | Count |
|---|---|
| Declared resource names across 13 types | 1,830 |
| `R.<type>.<name>` references resolving | 680 |
| XML documents parsing as well-formed | 145 |
| `@<type>/<name>` references resolving | 431 |
| Own-namespace style parents resolving | all |
| Strings, en/hi, in exact parity | 872 each |
| Positional placeholders matching across locales | all |
| `getString`/`setText` format arguments matching | all |
| Manifest components with a source file | all |
| `com.focuslife.app.*` imports resolving | all |
| Custom `prv_*`/`bcv_*`/`wsv_*` attributes declared | all |
| ViewBinding fields matching a layout `android:id` | all |
| Java sources with balanced delimiters | 89 |
| CJK contamination in the Hindi file | none |

Three of these were added *because* CI found what they missed: XML well-formedness
(run 3), exact style resolution plus parent resolution (run 4), and delimiter
balance (run 5). Test sources are now scanned by every Java section too — they were
previously not scanned at all, even though CI compiles them in the same pass.

The auditor's format-argument check has been self-tested against three injected
bugs and its import check against an injected fake class, a fake ViewBinding and a
legitimate nested-class import, so it is known to fail when it should.

**Known limitation:** the auditor cannot verify that a Java method exists or that
an argument count matches. Every API a new screen depends on was therefore read
from source before being called. That practice caught four real mismatches during
development — a `resetRoutine` call attributed to the wrong class, a
`Theme_FocusLife_Dialog` style that does not exist, a `btnDeleteSlot` binding field
whose layout id was `btn_slot_delete`, and a `MaterialSwitch` text attribute.

---

## The build log

The project was written without a compiler, so the first real build was also the
first real verification. It took **seven runs** to go green, and the sequence is
worth recording because it shows exactly what static analysis could not see.

| Run | Failed at | Cause |
|---|---|---|
| 1 | Set up Android SDK | A third-party action re-downloaded an SDK the runner already had, and was a single point of failure that aborted before compilation |
| 2 | Generate Gradle wrapper | `gradle wrapper` in the project root evaluates the Android build script, so it tried to resolve AGP before the wrapper existed |
| 3 | mergeDebugResources | A style appended *after* `</resources>` made `styles.xml` malformed |
| 4 | processDebugResources | `Widget.FocusLife.TextInput.ExposedDropdown` was referenced by four sites and declared nowhere |
| 5 | compileDebugJavaWithJavac | An unbalanced parenthesis, plus a batch of type errors |
| 6 | compileDebugJavaWithJavac | Three errors hidden behind Room's warnings in the log |
| 7 | testDebugUnitTest | **158 of 159 tests passed**; one real logic bug |

Run 8 is green: 159 tests pass and the APK assembles.

### What the resource merger caught (runs 3–4)

Both were invisible to every check the auditor performed. A style declared after
`</resources>` is still declared, still referenced correctly and still counted —
only a real XML parse sees it. And the missing dropdown style survived because the
auditor's own resolution logic was broken: it seeded the declared-style pool with
every dotted *prefix* of every declared style, so `Widget` alone was in the pool
and **every** reference to any `Widget.*` or `Theme.*` style resolved against it.
The style check had been passing unconditionally. Both holes are now closed, and
the auditor parses all 145 XML documents.

### What javac caught (runs 5–6)

Twenty-two errors across thirteen files. The classes:

- **An unbalanced parenthesis** in `AppLimitActivity`, calling a two-parameter
  helper with one argument inside it.
- **Two imports of the same simple name** — `android.content.res.Configuration` and
  `androidx.work.Configuration` — making every bare use ambiguous. Removing one
  import then silently retyped three more uses in a nested class.
- **Four missing imports**: `androidx.room.Transaction`, `SnoozeWorker` from a
  different package, `NotifHelper`, and `ViewGroup`.
- **Two duplicated `@NonNull` annotations**, the first copy stranded above a
  javadoc comment.
- **A call to `View.setAccessibilityClassName`**, which does not exist; custom
  views override the getter, which `View` declares `public` so the override cannot
  narrow it to `protected`.
- **`Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS`**, which is `@SystemApi` and
  `@hide` — absent from the public SDK at any `compileSdk`. Now a literal, still
  guarded by a resolvability check with two fallbacks.
- **A variable that never existed** (`minutes[0]`), leaving a dialog blank.
- **A method called with the wrong arity** (`showReasonEditor()` for
  `showReasonEditor(ReasonEntity)`).
- **A ternary mixing an `int` resource id with a `String`** in a `setText`.
- **A constant referenced from another class that was never declared**
  (`ReportActivity.EXTRA_DAY_KEY`), which also meant a notification could not open
  the day it was reporting on.
- **Seven places where a `Fragment` passed `this`** where a `Context` is required.

### What the tests caught (run 7)

One failure, and the most interesting bug in the whole list because nothing static
could ever have found it:

```
FocusScoreCalculatorTest > nullDayIsHandled FAILED
java.lang.AssertionError: expected:<0> but was:<14>
```

`compute(null)` substituted `new Day()` — but `Day` is not blank on construction.
It carries the seeded defaults the dashboard needs (study target 360, four
sessions, 1600 m, screen limit 180), so a day that never happened was scored as
one where every target was configured and nothing was done. All 14 points came
from screen time: zero minutes against a 180-minute limit read as a *measured,
perfect* day. That is precisely the mistake the `screenTimeUnknown` flag exists to
prevent, arriving from the other direction. A null Day now zeroes every target, so
nothing is applicable and the score is 0.

### What this proves, and what it does not

Proves: the project compiles, the resources link, Room's annotation processor
accepts all 17 entities and 10 DAOs, ViewBinding generates for all 54 layouts, and
159 behavioural assertions about scoring, blocking, scheduling, time arithmetic,
categorisation and milestones hold.

Does not prove: anything about running the app. No screen has been drawn, no
service has started, no notification has appeared. That is category C.

## C. Requires a physical phone

Implemented and reasoned about, **not observed running**. Each item below is what
needs checking on the OPPO A18 and why it cannot be checked any other way.

### C1. Accessibility blocking

The single most consequential unverifiable behaviour. Android's accessibility
delivery, ColorOS's per-app management and the timing of window-state events cannot
be simulated on a JVM.

**Check:** enable the service, add an app to the block list with a mode active, open
that app, and confirm the warning appears. Then verify the dialer still opens
normally. Confirm no noticeable battery or scroll-jank cost from the guard.

### C2. Usage access and `UsageStatsManager` on ColorOS

Whether ColorOS grants real per-package foreground minutes, and whether it exposes
launch counts at all (`supportsLaunchCounts` may legitimately return false).

**Check:** grant Usage access, use a few apps, open the Usage Analyzer and tap
*Refresh now*. Confirm the totals are plausible and that app-open counts either
appear or report as unsupported rather than showing zero.

### C3. Per-app daily limit enforcement

Depends on C1 and C2 together: the limit is only enforceable once usage is
measurable and the guard is live.

**Check:** set a 1-minute limit on an app, use it past the limit, and confirm it
blocks with `REASON_LIMIT` and that the block-attempt notification arrives.

### C4. Foreground service survival

Whether `BlockForegroundService` survives ColorOS's task killer over hours, and
whether `onTaskRemoved` re-arms it as intended.

**Check:** start a mode, swipe the app from Recents, wait 30+ minutes, and confirm
blocking still works and the status notification is present.

### C5. Reminder punctuality

The two-layer model is verified in code, but actual delivery timing depends on
Doze, ColorOS background restrictions and whether the battery exemption was
granted.

**Check:** set a routine slot two minutes ahead and observe the delay. Repeat with
the screen off for 30 minutes. Repeat with the battery exemption granted and
revoked, and note the difference.

### C6. Exact-alarm availability

`SCHEDULE_EXACT_ALARM` is not declared, so Android 12+ is expected to take the
inexact `setWindow` fallback. The OPPO A18 ships on Android 13.

**Check:** confirm the onboarding screen reports reminders as approximate rather
than claiming exact timing, and that reminders still arrive.

### C7. Notification channels and lock-screen behaviour

Channel importance, the `VISIBILITY_SECRET` setting on `fl_modes`, vibration, and
badge behaviour are all OEM-customised on ColorOS.

**Check:** trigger one notification per channel and confirm importance, sound and
lock-screen visibility match the intent.

### C8. Boot re-arming

**Check:** reboot with a mode active and routine slots configured. Confirm
`BootReceiver` re-registers all three periodic workers, restarts the guard and
re-arms the next routine alarm.

### C9. Language switching and Hindi rendering

Parity is statically verified, but actual Devanagari layout — ascender clipping,
line height, button widths, and the seven day-toggles fitting a 360 dp row — needs
to be seen.

**Check:** switch to Hindi in Profile, walk every screen, and confirm the activity
recreates with no mixed-language leftovers and no clipped text.

### C10. Rendering and performance

Canvas charts, the 200 dp progress ring, RecyclerView scrolling, and cold-start
time on 4 GB of RAM.

**Check:** open each screen with a populated database and watch for jank. Confirm
cold start is acceptable and that memory stays reasonable after an hour of use.

### C11. Data export

**Check:** Profile → Export my data, then confirm the file exists at the reported
path and contains readable text.

### C12. Both destructive resets

**Check:** *Reset today's data* must clear today and preserve history — this is the
regression that a `deleteSummariesOlderThan("0000-00-00")` bug would have caused,
and it was fixed in code but never observed. *Erase all data* must require typing
`RESET` and then restore seeded defaults.

### C13. Onboarding under denial

**Check:** decline every permission and confirm the app remains fully usable, that
no screen crashes, that screen time reads as *not measured*, and that each screen
explains what is missing rather than failing silently.

### C14. Room migration behaviour

The schema is exported and `DatabaseSeeder` is idempotent, but an actual
install-over-an-existing-version has not been exercised.

**Check:** install a build, add data, install the next build over it, and confirm
the data survives.

---

## Bugs found and fixed during development

These are recorded because each one was invisible to the compiler and would have
shipped.

| Bug | Impact if shipped | How found |
|---|---|---|
| `Repository.resetToday()` called `deleteSummariesOlderThan("0000-00-00")` | **Every historical report erased** by a "reset today" button | Code review |
| `BlockScheduleEntity.isOpenAt` tested the day mask before the overnight branch | A single-day overnight window stopped blocking at midnight | Deriving test expectations |
| `FocusScoreCalculator.pickTop` counted skipped components as having points available | Recommended completing habits that did not exist | Deriving test expectations |
| `loadLifetimeStats` counted abandoned sessions toward "sessions completed" | Inflated milestones and report figures | Code review |
| `buildFeatures` omitted `buildConfig` | **Compilation failure** — `BuildConfig` does not exist under AGP 8 by default, and two files referenced it | Reading `build.gradle` |
| Accessibility config requested `typeWindowContentChanged` | The chattiest Android event delivered continuously and discarded — pure battery cost on a Helio G85 | Comparing config to handler |
| Accessibility config omitted `typeWindowsChanged` | A handled branch that could never run | Comparing config to handler |
| `AppCategory` listed Edge as social, Amazon Shopping and Spotify as video, plus one non-existent package | Untrue category labels shown to the user as fact | Re-reading the fragment list |
| A format string used as a static label (five separate occurrences) | Raw `%1$d` rendered on screen | Wrote checker section 6b after the first three |
| Duplicate `<string>` key colliding with an existing one | Silent override of an existing translation | Wrote a pre-insert grep rule |
| `TimePickerDialog` given a `ThemeOverlay` with no window attributes | Picker fell back to the light platform default in a dark app | Reading theme definitions |
| A `RecyclerView` nested in a `NestedScrollView` having children added directly | Run rows would not render — a `LayoutManager` owns its children | Reading the layout back |
| An entity loop variable and a ViewBinding variable both named `row` | Ambiguous to read and to check | Static analysis flagged it |
| A test asserting the opposite of what it demonstrated | False confidence in the null-day handling | Re-deriving expectations |
| A test that failed one Sunday in seven | Flaky CI | Reviewing date dependencies |
| `compute(null)` substituting a `Day` carrying seeded default targets | **A day that never happened scored 14/100**, all of it from zero screen time reading as measured and perfect | Unit test, first CI execution |
| The auditor resolving every `Widget.*` / `Theme.*` style against the bare prefix | Style checking silently disabled; four layouts referenced an undeclared style | aapt2, run 4 |
| `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` (`@SystemApi`, not in the public SDK) | Compilation failure at any `compileSdk` | javac, run 5 |
| Two imports of the same simple name `Configuration` | Every bare use ambiguous; the app could not compile | javac, run 5 |
| A `Fragment` passing `this` as a `Context`, seven times | Compilation failure | javac, run 5 |
| A style appended after `</resources>` | `mergeDebugResources` failure — malformed XML | aapt2, run 3 |
| A comment placed inside an XML start tag | Malformed XML; the service config would not parse | Added XML parse check |
| `tools/check_resources.py` reporting wrong line numbers | Comment stripping consumed newlines, so every reported line was wrong | Self-testing the scanner |

---

## What "working" means for this delivery

**Delivered and verified:** the complete source tree; a green CI pipeline that runs
159 tests and assembles an installable 5.4 MB APK on every push; a static auditor
that parses every XML document and gates each commit; and full documentation.

**Verified by execution:** the project compiles, resources link, Room accepts the
schema, ViewBinding generates for every layout, and the behavioural guarantees
listed in section A hold.

**Not verified:** everything in section C. No screen has been drawn, no service has
started, no notification has appeared on a phone. The accessibility guard — the
feature with the most consequence for the user — cannot be validated without a
device, and its failure modes (an OEM that does not deliver window events, a
service the OS kills) are exactly the ones a JVM cannot reproduce.

The honest summary: the logic that can be tested without a device has been tested
hard and now passes in CI, and the parts that cannot be are listed here with what
to check rather than claimed as working.
