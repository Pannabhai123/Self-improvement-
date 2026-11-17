package com.focuslife.app.work;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.focuslife.app.data.db.entity.DailySummaryEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.notify.NotifHelper;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;

/**
 * Generates the end-of-day report and the weekly roll-up.
 *
 * <h2>The midnight problem</h2>
 * A report generated at 23:30 is missing the last half hour, and one generated
 * at 00:00 sharp may run before the day's usage stats are finalised by the
 * system. So this worker always targets <em>yesterday</em>: by the time it runs,
 * that day is complete and its usage figures are settled. Today's report is
 * generated live when the user opens the report screen, which is the only time a
 * partial day is useful to see.
 *
 * <h2>Guards</h2>
 * <ul>
 *   <li>A day is only generated once; re-running overwrites but does not
 *       re-notify.</li>
 *   <li>The notification fires only for a day that has not been notified and only
 *       when notifications are permitted.</li>
 *   <li>Old summaries are pruned at 400 days, keeping the streak history
 *       available for over a year without unbounded growth.</li>
 * </ul>
 */
public class DailyReportWorker extends Worker {

    private static final String TAG = "DailyReportWorker";

    public static final String UNIQUE_NAME = "focuslife_daily_report";

    /** Optional input: generate for this specific day instead of yesterday. */
    public static final String INPUT_DAY_KEY = "day_key";

    public DailyReportWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        try {
            Repository repo = Repository.get(context);

            String requested = getInputData().getString(INPUT_DAY_KEY);
            String dayKey = requested != null && !requested.isEmpty()
                    ? requested
                    : TimeUtils.dayOffsetFromToday(-1);

            // Refresh usage for that day first so the report's screen-time figure
            // is measured rather than stale.
            if (PermissionManager.hasUsageAccess(context)) {
                repo.refreshUsageSnapshot(dayKey);
            }

            boolean alreadyHad = repo.db().usageDao().findSummary(dayKey) != null;
            DailySummaryEntity summary = repo.generateDailySummary(dayKey);

            notifyIfAppropriate(context, repo, summary, alreadyHad);

            // Regenerate today's summary too, so the dashboard's streak and the
            // weekly report read a current row rather than recomputing every time.
            if (requested == null) {
                repo.generateDailySummary(TimeUtils.today());
            }

            // Prune. 400 days keeps more than a year of streak history.
            repo.db().usageDao().deleteSummariesOlderThan(TimeUtils.dayOffsetFromToday(-400));

            Prefs.setLastReportDay(context, dayKey);
            Log.i(TAG, "report generated for " + dayKey + " (score " + summary.focusScore + ")");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "report generation failed", e);
            return Result.retry();
        }
    }

    /**
     * Posts the report notification only when it is genuinely new information.
     *
     * <p>Re-notifying for a day the user has already been told about is exactly
     * the behaviour that gets an app's notifications switched off system-wide.</p>
     */
    private void notifyIfAppropriate(@NonNull Context context, @NonNull Repository repo,
                                     @NonNull DailySummaryEntity summary, boolean alreadyHad) {
        if (summary.notified) return;
        if (!PermissionManager.canPostNotifications(context)) return;
        // A day that already had a summary was presumably notified when it was
        // first created; do not resurrect it.
        if (alreadyHad) return;
        // Do not notify for a day so old that the information is no longer
        // actionable — e.g. after the phone has been off for a week.
        if (TimeUtils.daysBetween(summary.dayKey, TimeUtils.today()) > 2) return;

        NotifHelper.notifyDailyReport(context,
                summary.focusScore,
                TimeUtils.formatMinutes(summary.studyMinutes),
                summary.screenMinutes > 0
                        ? TimeUtils.formatMinutes(summary.screenMinutes)
                        : context.getString(com.focuslife.app.R.string.label_none),
                summary.dayKey);

        repo.db().usageDao().markSummaryNotified(summary.dayKey);
    }
}
