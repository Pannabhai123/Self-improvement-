# Installing FOCUS LIFE

Written for the **OPPO A18** (ColorOS, Android 13) but accurate for any phone on
**Android 10 or newer**. Menu wording varies slightly between ColorOS versions;
where it does, the setting is named for what it does rather than for an exact
label.

---

## 1. Get the APK

You do not need a computer with Android Studio, and you do not need to install
anything on the phone except the APK itself.

1. Open the repository on **GitHub** in a browser.
2. Click the **Actions** tab.
3. Click the **Build APK** workflow, then the most recent run (the top entry).
4. Wait for the run to finish. A green tick means the 159 unit tests passed *and*
   the APK was assembled. A red cross means something broke and no APK was
   produced — that is deliberate: the build refuses to publish an APK whose tests
   fail.
5. Scroll to the bottom of the run page to **Artifacts**.
6. Download **`FocusLife-debug-APK`**.
7. Unzip it. Inside is **`FocusLife-debug.apk`**.

> The artifact is retained for 90 days. If a run is older than that, trigger a new
> one with **Run workflow** on the same page — it needs no inputs.

### Why "debug"?

The debug APK is signed with the standard Android debug key. That makes it
installable on any phone without you generating or storing a signing key, which is
the right trade for a personal, offline app. A signed **release** build is
available through the same workflow's `release` job if repository secrets
(`FOCUSLIFE_KEYSTORE_B64`, `FOCUSLIFE_STORE_PASSWORD`, `FOCUSLIFE_KEY_ALIAS`,
`FOCUSLIFE_KEY_PASSWORD`) are configured; without them the debug APK is the
artifact.

---

## 2. Copy it to the phone

Any of these work:

- **Download it on the phone directly** — open the Actions run in the phone's
  browser and download the artifact there. This is the shortest path.
- **USB** — copy `FocusLife-debug.apk` into the phone's `Download` folder.
- **Files / Drive** — transfer it however you normally move files.

---

## 3. Allow the install

Android blocks installs from outside the Play Store until you allow them. This is
a one-time prompt per source.

1. Open the **Files** app and tap `FocusLife-debug.apk`.
2. When Android says installation from this source is not allowed, tap
   **Settings** in that prompt.
3. Turn on **Allow from this source** (ColorOS may word this *Install unknown
   apps* → *Allow from this source*).
4. Go back and tap **Install**.

If the prompt does not offer a Settings shortcut:
**Settings → Apps → App management → ⋮ (top right) → Special app access →
Install unknown apps**, then enable it for your browser or Files app.

### If ColorOS shows a security warning

ColorOS sometimes adds its own scan-and-warn step for sideloaded apps. It is
warning about the *source*, not about anything this app does. You can proceed.

Two things that make the warning unnecessary to worry about here:

- The app declares **no `INTERNET` permission**. It is not able to contact any
  server, so there is nothing for a scan to find and nothing that can be sent
  anywhere.
- The APK is built by a public GitHub Actions workflow from the source in this
  repository. You can read every line that runs.

---

## 4. First run

The app opens straight into a permissions screen. **Nothing on it is mandatory.**

Each row states what the permission is, what it enables, and **what happens if you
decline it**. Declining everything still leaves a fully working app — the Focus
Score simply reports screen time as *not measured* instead of guessing, and the
app blocker cannot intercept other apps.

See **[PERMISSIONS.md](PERMISSIONS.md)** for what each one does and how to grant
it on ColorOS.

After that:

1. **Language** — the app follows your phone's language automatically. To force
   English or Hindi for this app only: **Profile → Language**.
2. **Daily targets** — **Profile → Daily targets**. The defaults are 6 h study,
   4 focus sessions, 1600 m running, 3 h screen time, 3 exercise items. Setting
   any target to 0 switches that component off entirely rather than counting it as
   a failure.
3. **Your apps** — **Home → App Blocker → Select apps**. Only apps that appear in
   your launcher are listed. Search matches both the app name and its package
   name.
4. **Your routine** — **Home → Daily Routine**. Thirteen slots are pre-filled from
   04:30 to 20:30; every one is editable and can be switched off.

The database seeds ten habits, seven exercises, three starter goals, a vision
entry, and nine Railway Group D topics on first launch so no screen opens empty.

---

## 5. Battery settings that matter

The app is designed to be cheap: a 60-second handler tick with no wake lock, three
unique periodic WorkManager jobs, and one exact alarm for the next routine slot.
But ColorOS applies aggressive background management that can delay reminders.

Two settings fix almost all of it:

- **Allow background activity** — Settings → Battery → (app) → *Allow background
  activity* / *No restrictions*.
- **Lock the app in Recents** — open Recents, pull the Focus Life card down (or
  tap its menu) and choose **Lock**. This stops ColorOS killing it when you clear
  recent apps.

The permissions screen offers a shortcut to the battery-optimisation exemption.
Declining it is fine; reminders may just arrive up to 30 minutes late, because the
polling worker is the delivery guarantee and the exact alarm is the punctuality
layer.

---

## 6. Updating

Repeat step 1 for the new run and install over the top. **Your data is kept** —
the database is untouched by an install-over.

Note that an APK signed with a different key cannot be installed over an existing
one. The debug key is stable for a given machine, so CI-produced builds upgrade
each other cleanly. If Android refuses the update, uninstall first — which deletes
local data, so use **Profile → Export my data** beforehand.

---

## 7. Uninstalling

A normal uninstall removes the app and its database. Nothing was ever uploaded, so
there is no server-side copy to delete.

If you want your history first: **Profile → Export my data** writes a plain-text
summary to the app's own external folder, and the confirmation message shows the
full path.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "App not installed" | Usually a signature mismatch with an older install. Export your data, uninstall, reinstall. |
| Blocked apps still open | Accessibility is off. Settings → Accessibility → Focus Life → enable. See [PERMISSIONS.md](PERMISSIONS.md). |
| Screen time shows "not measured" | Usage access is off. The app refuses to invent a number. Grant it, then tap *Refresh now*. |
| No reminders arriving | ColorOS killed the app. Apply both battery settings in step 5. |
| Reminders arrive late | Expected without the battery exemption. The exact alarm needs `SCHEDULE_EXACT_ALARM`, which this app deliberately does not request, so Android 12+ falls back to an inexact window of ±10 minutes. |
| Hindi text looks cramped | Increase the app's font scale in system settings; layouts use a 16 sp body floor and 24 sp line height for Devanagari. |
| APK artifact missing from a run | The run failed before assembling. Check the *Unit tests* step log. |
