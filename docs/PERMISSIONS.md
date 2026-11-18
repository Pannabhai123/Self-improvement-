# Permissions

Every permission FOCUS LIFE uses, what it enables, what you lose without it, and
how to grant it on an OPPO A18 (ColorOS).

**Nothing here is mandatory.** The app runs with every one of them declined. What
changes is which numbers it can show you and whether it can intercept other apps.

---

## The short version

| Permission | Needed for | Without it |
|---|---|---|
| Notifications | Reminders, routine alerts, daily report, focus timer, mode status, limit warnings | The app still works and still records everything; you simply get no alerts |
| Usage access | Screen time, app-wise usage, per-app daily limits, the weekly comparison | Screen time is reported as *not measured* and dropped from the Focus Score. Per-app limits cannot be enforced |
| Accessibility | Actually intercepting a blocked app and showing the warning screen | Blocking degrades to notifications only. The app says so on the blocker screen and offers a fix button |
| Display over other apps | Showing the warning on top of a locked or full-screen app | The warning opens as a normal activity instead, which some apps can draw over |
| Battery optimisation exemption | Reminders arriving on time under ColorOS | Reminders may be delayed; delivery is still guaranteed by the periodic worker |
| Alarms & reminders (Android 12+) | Punctual routine reminders | A ±10-minute inexact window is used instead. **This app does not request the permission at all** — see below |

Declared in the manifest but requiring no user action: `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `VIBRATE`,
`RECEIVE_BOOT_COMPLETED`, `QUERY_ALL_PACKAGES`.

**Not declared: `INTERNET`.** Not "declared but unused" — absent. The app has no
code path that can reach a network, and no dependency that needs one.

---

## 1. Notifications

**Manifest:** `POST_NOTIFICATIONS` (runtime prompt on Android 13+; automatic below)

**Used for:** routine reminders, revision-due alerts, workout reminders, the
habits-remaining nudge, the daily report, the live focus-timer notification, the
active-mode status, per-app limit warnings, and block-attempt notices.

**Four channels**, so you can silence one without losing the rest:

| Channel | Importance | Contents |
|---|---|---|
| `fl_modes` | Low, silent, hidden on the lock screen | The persistent "Study Mode is active" status |
| `fl_reminders` | Default, vibrates | Routine, revision, workout and habit reminders |
| `fl_reports` | Low | The daily report |
| `fl_limits` | High, vibrates | An app hit its daily limit; a block was attempted |

**If you decline:** everything still records and every screen still works. The app
checks `canPostNotifications()` before posting, so there is no crash and no silent
failure — it just does not notify. If the app cannot read the notification state at
all, it now reports *not granted* rather than assuming it is allowed; an unknown is
not a yes, and guessing yes would let the app skip reminders it believed were
arriving.

**The request always goes through Android's own dialog first.** Tapping the
notifications row shows the system prompt. Only *after* you decline does the app
work out whether that decline was permanent — `shouldShowRequestPermissionRationale`
returning false at that point means Android will never show the dialog again — and
if so the explanation offers an **Open Settings** button, because tapping the row
again would now do nothing at all.

> This order matters and was not the original behaviour. That flag is false both
> *before* the first request and *after* a permanent denial, so an earlier version
> that branched on it up front skipped Android's prompt entirely on a fresh
> install and dropped you straight into a settings screen. The dialog has to come
> first; it is the only thing that distinguishes the two cases.

**Grant it:** Settings → Notifications → Focus Life → Allow.

---

## 2. Usage access

**Not a manifest permission.** `PACKAGE_USAGE_STATS` is a *special app access*
that can only be granted through Settings — there is no runtime dialog for it.

**Used for:** total screen time, app-wise usage, most-used apps, app-open counts
(where the OS exposes them), the weekly comparison, and the per-app daily limits
that make blocking enforceable.

**What is read:** per-package **foreground minutes** and **launch counts** from
`UsageStatsManager`. That is all. The snapshot table stores a package name, a
label, minutes and a launch count — no content, no screen captures, no
notification text, no messages.

**If you decline:** the usage analyzer shows the permission card and nothing else.
It never renders zeros, because "0 minutes of screen time" is a claim about your
day that the app cannot make. `screenMinutesForDay` returns **-1**, the dashboard
shows *not measured*, and the Focus Score **drops the screen-time component from
its denominator** rather than scoring you down for a number it could not read.
Per-app daily limits cannot be enforced, since enforcement depends on knowing how
much an app has been used.

**Grant it:** Settings → Apps → App management → ⋮ → Special app access →
**Usage access** → Focus Life → Allow.

The app opens that screen for you, deep-linked to Focus Life's own package so
stock Android lands directly on its toggle. It used to deep-link to an *empty*
package name, which is why it sometimes arrived at a list with no obvious entry —
the filter matched nothing. On ColorOS you may still need to find Focus Life in
the list manually, because that build ignores the deep link and shows every app;
that is now a genuine fallback rather than the only behaviour.

---

## 3. Accessibility

**Not a manifest permission** either — it is a service the user enables in
Settings. This is the only way Android allows one app to notice that another app
came to the foreground, and it is why the app blocker can work at all.

**Used for:** detecting that a blocked app opened, and showing the warning screen.

**What is read — this is the important part:** the service handles only
`TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOWS_CHANGED`, and reads **only
`getPackageName()`**. It never reads text, view content, screen contents,
notifications or messages. The decision is a single `HashMap` lookup against a
cached block list — **no database access on the hot path** — because a service
that queried a database on every window change would be both slow and a battery
drain on a Helio G85.

**Safeguards built into it:**

- **Fails open.** Any ambiguity — a null event, an unreadable package, a stale
  cache — resolves to *allow*.
- **Debounced.** 2500 ms for the same package, 800 ms across packages, so a
  flickering window cannot produce a storm of decisions.
- **Safety allow-list that cannot be overridden.** The dialer, SMS, System UI, the
  permission controllers, ColorOS's own management apps, and this app itself are
  never blocked — even if you add them to the block list with "always" and
  "strict" both enabled. An app that could block an emergency call would be
  dangerous, so this is not user-configurable.
- **Own package excluded.** It never intercepts itself.

**If you decline:** blocking degrades to *notification only* — you get an alert
when you go over a limit, but nothing stands in the way. The blocker screen states
the current enforcement level at the top and offers a button to fix it. There is a
permanent card in the app picker explaining that blocking needs this, so it is
never a surprise.

**Grant it:** Settings → Accessibility → Focus Life → enable.
The app shows an explanatory dialog *before* opening that screen, because Android
itself warns that accessibility services can observe your actions — and you
deserve to know exactly what this one observes, which is package names only.

**A technical note, because this one is invisible from inside the app.** The
service is declared `android:exported="true"`, the single exception to the rule
every other component in the manifest follows. It has to be: an accessibility
service is bound by `system_server`'s `AccessibilityManagerService`, a different
process with a different UID. Declared `exported="false"`, the framework cannot
bind it — you can still toggle the service on, the toggle stays on, and nothing is
ever blocked. `BIND_ACCESSIBILITY_SERVICE` is what keeps that openness safe: only
the OS can bind, no other app can.

The app's own reporting cannot detect this either. `isAccessibilityEnabled()`
reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, which lists the service as
enabled whether or not it ever bound — so the toggle would read *on* while
blocking silently did nothing. `tools/check_resources.py` §7b now asserts the
manifest stays this way, since neither the compiler nor a unit test can see it.

---

## 4. Display over other apps

**Manifest:** `SYSTEM_ALERT_WINDOW`

**Used for:** drawing the warning screen on top of whatever is open, including
full-screen apps and the lock screen.

**If you decline:** the warning still appears, but as a normal activity that
another app could draw over. Blocking still works; it is simply less reliable at
getting your attention.

**Grant it:** Settings → Apps → App management → ⋮ → Special app access →
**Display over other apps** → Focus Life.

---

## 5. Battery optimisation exemption

**Manifest:** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

**Used for:** letting reminders and the usage snapshot run on schedule under
ColorOS's aggressive background management.

**If you decline:** reminders may be delayed. This is survivable by design — see
the two-layer delivery model below. Declining it now explains the battery
consequence specifically; that row previously fell through to the *notifications*
copy and told you reminders were off, which is a different permission's problem.
Battery is optional and is deliberately left out of the "4 of 4" progress count,
so it can never be the reason onboarding looks incomplete.

**Grant it:** the permissions screen offers the system dialog directly.
Alternatively Settings → Battery → More settings → Optimise battery use →
Focus Life → *Don't optimise*.

**Also recommended on ColorOS**, though these are not permissions:
- Settings → Battery → Focus Life → **Allow background activity**
- In Recents, pull the Focus Life card down and choose **Lock**

---

## 6. Alarms & reminders (Android 12+)

**This app does not declare `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`.**

That is a deliberate choice, not an oversight. Exact-alarm permission is a
special access on Android 12+ that shows up as a user-visible toggle, and
requesting it for a study reminder would be asking for a privileged capability the
app does not need — because delivery is already guaranteed another way.

**Two-layer reminder delivery:**

1. **A periodic worker (every 30 minutes)** is the *delivery guarantee*. It scans
   for anything due and posts it. WorkManager survives reboots and process death.
2. **One exact alarm for the next due routine slot** is the *punctuality layer*.

Where exact alarms are available — **Android 10 and 11 without any permission, or
Android 12+ if you have granted Alarms & reminders** — the app uses
`setExactAndAllowWhileIdle` and reminders land on time.

Where they are not, it falls back to `setWindow` with a **±10-minute** tolerance.
So on Android 12+ with the toggle off, a reminder can be up to ten minutes off,
and if the worker was the one that delivered it, up to thirty. The onboarding
screen **tells you this explicitly** and names the missing permission rather than
promising punctuality it cannot deliver.

The alarm is re-armed from four places — cold start, boot, every reminder pass,
and every routine edit — so a slot you change at 20:00 is reflected immediately
rather than at the next boot.

---

## 7. Permissions that need no action

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the block guard alive with a visible status notification. `specialUse` is the honest type: it guards other apps rather than playing media or navigating |
| `WAKE_LOCK` | Lets WorkManager complete a snapshot or a report without being suspended mid-write |
| `VIBRATE` | Haptic feedback on reminders and on the emergency-unlock hold |
| `RECEIVE_BOOT_COMPLETED` | Re-arms the three periodic workers, the block guard and the next routine alarm after a reboot |
| `QUERY_ALL_PACKAGES` + a `<queries>` block for `MAIN`/`LAUNCHER` | Lets the app picker list your installed apps so you can choose which to block. Android 11+ hides the package list without it |

**`QUERY_ALL_PACKAGES` deserves a note.** It is a broad permission, and this app
uses it narrowly: to build a list of launchable apps, with names and icons, so you
can pick which ones to block. It reads **only** `packageName`, the app label and
the icon. Nothing else is inspected, and nothing leaves the device.

---

## Reading the status in Profile

Profile shows **Distraction Guard: on / limited / off**, matching the three
enforcement levels the blocker screen already used:

| Profile says | Meaning | Colour |
|---|---|---|
| **on** | Accessibility *and* overlay granted — blocked apps are stopped and the reason screen appears over them | green |
| **limited** | Accessibility granted, overlay not — apps are stopped, but without the on-screen message | amber |
| **off** | Accessibility not granted — nothing is actually blocked, only warned about | red |

"limited" is a real state, not a cosmetic one. An earlier version collapsed it
into "on", which told users the guard was fully working at exactly the moment the
warning screen could not appear — and Profile is where someone goes to check.

The count above it (`3 of 4`) covers notifications, usage access, accessibility
and overlay. Battery optimisation is intentionally excluded: it is a
recommendation for ColorOS, not a requirement.

---

## Revoking anything

Every permission can be revoked at any time from
**Settings → Apps → Focus Life → Permissions**, or for the special accesses from
**Settings → Apps → App management → ⋮ → Special app access**.

The app re-checks on every resume. Revoking Usage access mid-session makes screen
time show as *not measured* rather than freezing on the last number; revoking
Accessibility downgrades enforcement to notification-only and the blocker screen
says so at the top.

**Profile → Permissions** shows the current state as "*n* of 4 granted" and the
enforcement level as on or off, and tapping it reopens the onboarding screen where
each row explains itself again.

---

## What the app never does

- **No network access.** `INTERNET` is not declared. There is no analytics SDK, no
  crash reporter, no ad library, and no paid API.
- **No secret monitoring.** The accessibility service reads package names and
  nothing else; it is user-enabled and revocable, and its scope is documented in
  the service's own configuration.
- **No private content.** No messages, notifications, photos, contacts, location,
  microphone or camera. None of those permissions are declared.
- **No data leaving the device,** except a report you explicitly share, which
  hands plain text to an app you choose.
- **No hidden collection.** Every stored field is visible somewhere in the UI: the
  usage analyzer shows per-app minutes, the distraction diary shows what you
  logged, and Profile can export all of it as text.
