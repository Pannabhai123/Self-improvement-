package com.focuslife.app;

import android.app.Application;
import android.content.Context;
import android.os.StrictMode;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
// androidx.work.Configuration is imported and android.content.res.Configuration is
// fully qualified at its two use sites: both are named Configuration, and the
// WorkManager one appears three times (the Provider interface, the override and
// its Builder) against the framework one's onConfigurationChanged and the locale
// wrapper below. Importing both makes every bare `Configuration` ambiguous.
import androidx.work.Configuration;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.focuslife.app.blocking.BlockDecisionEngine;
import com.focuslife.app.blocking.BlockForegroundService;
import com.focuslife.app.blocking.BlockState;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.notify.NotifHelper;
import com.focuslife.app.util.AppContext;
import com.focuslife.app.work.DailyReportWorker;
import com.focuslife.app.work.ReminderWorker;
import com.focuslife.app.work.UsageSnapshotWorker;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Application entry point.
 *
 * <h2>Startup budget</h2>
 * The target device is an OPPO A18 with 4 GB RAM and a Helio G85, so cold-start
 * cost is treated as a feature. Everything here is either O(1) or pushed onto
 * the IO executor:
 * <ul>
 *   <li>Notification channels — cheap, must exist before any notification.</li>
 *   <li>Language override — must be applied before the first Activity inflates.</li>
 *   <li>WorkManager schedule — idempotent unique work, so no duplicate jobs.</li>
 *   <li>Block snapshot — loaded asynchronously; an empty snapshot blocks nothing,
 *       which is the correct fail-safe during the load window.</li>
 *   <li>Mode re-arm — if a mode was active when the process died, the foreground
 *       service is restarted so Sleep Mode survives a reboot or an OEM kill.</li>
 * </ul>
 *
 * <p>No analytics, no crash reporter, no ad SDK and no network initialisation,
 * because the app declares no INTERNET permission.</p>
 */
public class FocusLifeApp extends Application implements Configuration.Provider {

    private static final String TAG = "FocusLifeApp";

    /**
     * WorkManager's default executor is sized from the CPU count, which yields 2
     * threads on a Helio G85. That is exactly right here: the workers are IO
     * bound and more threads would only add contention for the 4 GB budget.
     */
    @NonNull
    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
                .setExecutor(AppDatabase.io())
                .setMinimumLoggingLevel(Log.INFO)
                .build();
    }

    @Override
    public void onCreate() {
        applyNightModeBeforeSuper();
        super.onCreate();

        AppContext.init(this);
        BlockState.get().attach(this);

        NotifHelper.createChannels(this);
        applyLanguageOverride();

        // Detect main-thread database access during development. Disabled in
        // release because the penalty itself costs frames on a low-end device.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .penaltyLog()
                    .build());
        }

        schedulePeriodicWork();
        warmCachesAsync();
        reArmActiveMode();

        // Alarms do not survive process death reliably on every OEM ROM, so the
        // next routine reminder is re-armed on each cold start. It is idempotent.
        com.focuslife.app.notify.RoutineAlarmScheduler.scheduleNext(this);
    }

    /**
     * The app is dark-only. Setting this before {@code super.onCreate()} avoids a
     * white flash on cold start when the system is in light mode.
     */
    private void applyNightModeBeforeSuper() {
        try {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } catch (Exception e) {
            Log.w(TAG, "could not force night mode", e);
        }
    }

    /**
     * Applies the per-app language override chosen in Profile.
     *
     * <p>Uses {@code AppCompatDelegate.setApplicationLocales} on Android 13+,
     * where per-app languages are a platform feature the user can also change in
     * system Settings, and falls back to a configuration override below 13.</p>
     */
    private void applyLanguageOverride() {
        String override = Prefs.languageOverride(this);
        if (Prefs.LANG_SYSTEM.equals(override)) return;

        try {
            Locale locale = new Locale(override);
            androidx.core.os.LocaleListCompat list =
                    androidx.core.os.LocaleListCompat.create(locale);
            AppCompatDelegate.setApplicationLocales(list);
        } catch (Exception e) {
            Log.w(TAG, "could not apply language override", e);
        }
    }

    /**
     * Re-applies the override when the app is already running and the user
     * changes it, and keeps {@code AppContext} usable for configuration queries.
     */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleSupport.wrap(base));
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // A system language change must not silently undo the user's override.
        applyLanguageOverride();
    }

    // ------------------------------------------------------------------
    //  Background work
    // ------------------------------------------------------------------

    /**
     * Schedules the three periodic workers as unique work.
     *
     * <p>Periods are chosen against Android's 15-minute minimum and the app's
     * actual needs rather than "as often as possible":</p>
     * <ul>
     *   <li><b>Usage snapshot</b> every 6 hours. The dashboard reads the snapshot
     *       and refreshes today live when it is empty, so a coarse period costs
     *       the user nothing visible.</li>
     *   <li><b>Reminders</b> every 30 minutes. Routine slots are spaced at least
     *       30 minutes apart in the default schedule, so this is fine-grained
     *       enough to never miss one while staying well inside Doze limits.</li>
     *   <li><b>Daily report</b> every 24 hours, with its own "already generated
     *       for this day" guard, plus a flexible 23:30 target.</li>
     * </ul>
     */
    private void schedulePeriodicWork() {
        try {
            WorkManager wm = WorkManager.getInstance(this);

            wm.enqueueUniquePeriodicWork(
                    UsageSnapshotWorker.UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    new PeriodicWorkRequest.Builder(UsageSnapshotWorker.class, 6, TimeUnit.HOURS)
                            .setInitialDelay(2, TimeUnit.MINUTES)
                            .build());

            wm.enqueueUniquePeriodicWork(
                    ReminderWorker.UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    new PeriodicWorkRequest.Builder(ReminderWorker.class, 30, TimeUnit.MINUTES)
                            .setInitialDelay(30, TimeUnit.SECONDS)
                            .build());

            wm.enqueueUniquePeriodicWork(
                    DailyReportWorker.UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    new PeriodicWorkRequest.Builder(DailyReportWorker.class, 24, TimeUnit.HOURS)
                            .setInitialDelay(millisUntilReportWindow(), TimeUnit.MILLISECONDS)
                            .build());
        } catch (Exception e) {
            // WorkManager can throw if it is not yet initialised after a restore.
            // The app remains fully usable; reminders simply will not fire until
            // the next successful schedule.
            Log.e(TAG, "could not schedule periodic work", e);
        }
    }

    /** Delay until the 23:30 report window. */
    private static long millisUntilReportWindow() {
        long untilMidnight = com.focuslife.app.util.TimeUtils.millisUntilMidnight();
        // millisUntilMidnight targets 00:00; the report should run 30 min earlier.
        long delay = untilMidnight - 30 * com.focuslife.app.util.TimeUtils.MINUTE_MS;
        return Math.max(com.focuslife.app.util.TimeUtils.MINUTE_MS, delay);
    }

    /**
     * Warms the database and the block snapshot off the main thread.
     *
     * <p>Room opens the database lazily on first query. Doing that here, in the
     * background, means the first screen the user sees does not pay the
     * open-and-seed cost — which on a cold install includes creating tables and
     * inserting the default habits, routine and exercises.</p>
     */
    private void warmCachesAsync() {
        Executor io = AppDatabase.io();
        io.execute(() -> {
            try {
                // Touching the database triggers onCreate → DatabaseSeeder.
                AppDatabase.get(this).habitDao().habitCount();
                BlockState.get().refreshSync(this);
            } catch (Exception e) {
                Log.w(TAG, "cache warm-up failed", e);
            }
        });
    }

    /**
     * Restarts the foreground service if a mode was active when the process died.
     *
     * <p>Without this, an OEM kill (common on ColorOS) would leave the mode
     * recorded as active in prefs while nothing enforces or displays it — a
     * silently broken commitment, which is the worst failure mode for a
     * discipline app.</p>
     */
    private void reArmActiveMode() {
        String mode = Prefs.activeMode(this);
        if (!BlockDecisionEngine.isKnownMode(mode)) return;

        try {
            BlockForegroundService.startMode(this, mode);
        } catch (Exception e) {
            // Android 12+ forbids starting a foreground service from the
            // background. The accessibility guard still enforces the mode
            // because it reads prefs directly; only the notification is missing.
            Log.w(TAG, "could not re-arm mode " + mode + " from Application.onCreate", e);
        }
    }

    /**
     * Applies a configuration-level locale override for API levels below 33.
     *
     * <p>Kept in a tiny helper so {@link FocusLifeApp} does not have to import
     * the whole locale machinery, and so the same wrapper is available to
     * activities that need it.</p>
     */
    public static final class LocaleSupport {

        private LocaleSupport() {
        }

        @NonNull
        public static Context wrap(@NonNull Context base) {
            try {
                String override = Prefs.languageOverride(base);
                if (Prefs.LANG_SYSTEM.equals(override)) return base;

                Locale locale = new Locale(override);
                Locale.setDefault(locale);

                android.content.res.Configuration config =
                        new android.content.res.Configuration(base.getResources().getConfiguration());
                config.setLocale(locale);
                config.setLayoutDirection(locale);
                return base.createConfigurationContext(config);
            } catch (Exception e) {
                // Falling back to the system locale is always acceptable.
                return base;
            }
        }

        /** Re-applies the override to a live activity context. */
        public static void apply(@NonNull Context context) {
            try {
                String override = Prefs.languageOverride(context);
                if (Prefs.LANG_SYSTEM.equals(override)) {
                    AppCompatDelegate.setApplicationLocales(
                            androidx.core.os.LocaleListCompat.getEmptyLocaleList());
                    return;
                }
                AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.create(new Locale(override)));
            } catch (Exception e) {
                Log.w(TAG, "could not re-apply locale", e);
            }
        }
    }
}
