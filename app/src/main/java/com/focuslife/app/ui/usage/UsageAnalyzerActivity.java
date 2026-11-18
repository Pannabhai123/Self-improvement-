package com.focuslife.app.ui.usage;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.focuslife.app.R;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.AppUsageSnapshotEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.ActivityUsageBinding;
import com.focuslife.app.databinding.DialogNumberBinding;
import com.focuslife.app.databinding.ItemAppUsageBinding;
import com.focuslife.app.databinding.ItemMetricCardBinding;
import com.focuslife.app.databinding.ItemTipBinding;
import com.focuslife.app.stats.UsageStatsHelper;
import com.focuslife.app.util.AppCategory;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Mobile Usage Analyzer — brief section 7: total screen time, app-wise usage,
 * most-used apps, app-open counts where supported, weekly comparison, the daily
 * limit, and practical reduction suggestions.
 *
 * <h2>What this screen is allowed to know</h2>
 * Everything comes from {@code UsageStatsManager} totals that this app snapshotted
 * itself: per-package foreground minutes and, on Android versions that expose it,
 * launch counts. Nothing else. No app content, no notifications, no messages, and
 * no network — the project declares no {@code INTERNET} permission at all, so
 * leaving the device is not merely declined, it is impossible.
 *
 * <h2>Denial is a state, not an error</h2>
 * Without usage access the screen shows the permission card and nothing else. It
 * never renders zeros, because "0 minutes of screen time" is a claim about the
 * user's day that the app cannot make. {@link Repository#screenMinutesForDay}
 * returns -1 in that case and this screen treats it as unknown.
 */
public class UsageAnalyzerActivity extends AppCompatActivity {

    private static final String TAG = "UsageAnalyzer";

    /** How many apps to list. The rest are reachable in the system settings. */
    private static final int MAX_APPS_LISTED = 15;

    private static final int DAYS_IN_WEEK = 7;

    /**
     * A category must hold at least this share of measured screen time before the
     * screen names it as "your largest category". Below that the claim is noise.
     */
    private static final double DOMINANT_CATEGORY_SHARE = 0.25;

    private static final SimpleDateFormat CLOCK_FORMAT =
            new SimpleDateFormat("h:mm a", Locale.getDefault());

    @Nullable
    private ActivityUsageBinding binding;

    @Nullable
    private Repository repository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUsageBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = Repository.get(getApplicationContext());

        ActivityUsageBinding b = binding;
        if (b == null) return;

        b.toolbar.setNavigationOnClickListener(v -> finish());
        b.btnGrantUsage.setOnClickListener(v -> openUsageAccess());
        b.btnRefresh.setOnClickListener(v -> refresh());
        b.btnEditLimit.setOnClickListener(v -> editDailyLimit());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void openUsageAccess() {
        Intent intent = PermissionManager.usageAccessIntent(this);
        if (PermissionManager.isResolvable(this, intent)) {
            startActivity(intent);
        } else {
            // The situation is "this device has no such settings screen", not
            // "usage access is off" - the user tapped this button because they
            // already know it is off. perm_not_available tells them what to do
            // instead, and is the same copy onboarding uses for this case.
            showMessage(getString(R.string.perm_not_available));
        }
    }

    // ------------------------------------------------------------------
    //  Data
    // ------------------------------------------------------------------

    private void load() {
        ActivityUsageBinding b = binding;
        Repository repo = repository;
        if (b == null || repo == null) return;

        boolean hasAccess = PermissionManager.hasUsageAccess(this);
        b.cardNoPermission.setVisibility(hasAccess ? View.GONE : View.VISIBLE);
        b.contentContainer.setVisibility(hasAccess ? View.VISIBLE : View.GONE);
        if (!hasAccess) return;

        AppDatabase.io().execute(() -> {
            Payload p;
            try {
                // Re-snapshot first: the stored rows are only as fresh as the last
                // periodic worker run, and a screen the user opened on purpose
                // should show the current hour, not one from up to 30 minutes ago.
                repo.refreshUsageSnapshot(TimeUtils.today());
                p = collect(repo);
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not analyse usage", e);
                return;
            }
            runOnUiThread(() -> {
                if (binding == null) return;
                render(p);
            });
        });
    }

    private void refresh() {
        Repository repo = repository;
        if (repo == null) return;
        if (!PermissionManager.hasUsageAccess(this)) {
            showMessage(getString(R.string.usage_refresh_failed));
            openUsageAccess();
            return;
        }
        load();
        showMessage(getString(R.string.usage_refreshed));
    }

    private static final class Payload {
        boolean measured;
        int todayMinutes;
        int yesterdayMinutes;
        int targetMinutes;
        int launchesToday;
        boolean launchesSupported;
        long snapshotAt;

        List<AppUsageSnapshotEntity> apps = new ArrayList<>();
        int[] weekMinutes = new int[0];
        List<String> weekDayKeys = new ArrayList<>();

        int socialMinutes;
        int gamesMinutes;
        int videoMinutes;
        int blockedMinutes;
    }

    @NonNull
    private Payload collect(@NonNull Repository repo) {
        Payload p = new Payload();
        String today = TimeUtils.today();

        p.todayMinutes = repo.screenMinutesForDay(today);
        p.measured = p.todayMinutes >= 0;

        List<String> week = TimeUtils.lastSevenDayKeys();
        if (week != null && week.size() >= 2) {
            // Index 6 is today, 5 is yesterday, in the ordering lastSevenDayKeys
            // returns (oldest first, ending today).
            p.yesterdayMinutes = repo.screenMinutesForDay(week.get(week.size() - 2));
        }

        p.targetMinutes = Prefs.targetScreenMinutes(this);
        p.launchesSupported = UsageStatsHelper.supportsLaunchCounts(this);
        p.launchesToday = p.launchesSupported
                ? UsageStatsHelper.totalLaunchesForDay(this, today) : 0;
        p.snapshotAt = Prefs.lastUsageSnapshotAt(this);

        List<AppUsageSnapshotEntity> snapshots = repo.topApps(today, MAX_APPS_LISTED);
        if (snapshots != null) {
            for (AppUsageSnapshotEntity snapshot : snapshots) {
                if (snapshot != null && snapshot.minutes > 0) p.apps.add(snapshot);
            }
        }

        // Categories are measured over every snapshotted app, not just the top
        // fifteen, so a long tail of small apps still counts toward its category.
        List<AppUsageSnapshotEntity> all = repo.db().usageDao().snapshotsForDay(today);
        p.socialMinutes = AppCategory.minutesInCategory(all, AppCategory.SOCIAL);
        p.gamesMinutes = AppCategory.minutesInCategory(all, AppCategory.GAMES);
        p.videoMinutes = AppCategory.minutesInCategory(all, AppCategory.VIDEO);
        p.blockedMinutes = minutesOnBlockedApps(all);

        int[] weekly = UsageStatsHelper.weeklyMinutes(this, DAYS_IN_WEEK);
        p.weekMinutes = weekly == null ? new int[0] : weekly;
        List<String> keys = UsageStatsHelper.weeklyDayKeys(DAYS_IN_WEEK);
        p.weekDayKeys = keys == null ? new ArrayList<>() : keys;
        return p;
    }

    private static int minutesOnBlockedApps(@Nullable List<AppUsageSnapshotEntity> snapshots) {
        if (snapshots == null) return 0;
        int total = 0;
        for (AppUsageSnapshotEntity snapshot : snapshots) {
            if (snapshot != null && snapshot.isBlockedApp) {
                total += Math.max(0, snapshot.minutes);
            }
        }
        return total;
    }

    // ------------------------------------------------------------------
    //  Rendering
    // ------------------------------------------------------------------

    private void render(@NonNull Payload p) {
        ActivityUsageBinding b = binding;
        if (b == null) return;

        renderToday(b, p);
        renderOpenings(b, p);
        renderCategories(b, p);
        renderApps(b, p);
        renderWeek(b, p);
        renderLimit(b, p);
        renderTips(b, p);
    }

    private void renderToday(@NonNull ActivityUsageBinding b, @NonNull Payload p) {
        if (!p.measured) {
            b.tvScreenToday.setText(R.string.label_empty_no_data);
            b.tvScreenVsYesterday.setText(R.string.usage_no_permission);
            b.tvScreenLimit.setVisibility(View.GONE);
            b.tvLastSnapshot.setVisibility(View.GONE);
            return;
        }

        b.tvScreenToday.setText(TimeUtils.formatMinutes(p.todayMinutes));

        int delta = p.todayMinutes - Math.max(0, p.yesterdayMinutes);
        if (delta < 0) {
            b.tvScreenVsYesterday.setText(getString(R.string.usage_down,
                    TimeUtils.formatMinutes(-delta)));
        } else if (delta > 0) {
            b.tvScreenVsYesterday.setText(getString(R.string.usage_up,
                    TimeUtils.formatMinutes(delta)));
        } else {
            b.tvScreenVsYesterday.setText(R.string.usage_flat);
        }

        if (p.targetMinutes > 0) {
            int remaining = p.targetMinutes - p.todayMinutes;
            b.tvScreenLimit.setVisibility(View.VISIBLE);
            b.tvScreenLimit.setText(remaining >= 0
                    ? getString(R.string.usage_under_limit,
                            TimeUtils.formatMinutes(remaining))
                    : getString(R.string.usage_over_limit,
                            TimeUtils.formatMinutes(-remaining)));
        } else {
            b.tvScreenLimit.setVisibility(View.GONE);
        }

        if (p.snapshotAt > 0) {
            b.tvLastSnapshot.setVisibility(View.VISIBLE);
            b.tvLastSnapshot.setText(getString(R.string.usage_last_snapshot,
                    CLOCK_FORMAT.format(new Date(p.snapshotAt))));
        } else {
            b.tvLastSnapshot.setVisibility(View.GONE);
        }
    }

    private void renderOpenings(@NonNull ActivityUsageBinding b, @NonNull Payload p) {
        ItemMetricCardBinding card = b.cardOpenings;
        card.ivIcon.setImageResource(R.drawable.ic_phone_android);
        card.tvLabel.setText(R.string.usage_openings);

        if (!p.launchesSupported) {
            card.tvValue.setText(R.string.label_none);
            card.tvSub.setText(R.string.usage_openings_unsupported);
            card.pbMetric.setVisibility(View.GONE);
            return;
        }

        card.tvValue.setText(getString(R.string.usage_openings_count, p.launchesToday));
        card.tvSub.setText(R.string.label_today);

        // Scale the bar against a 120-opening day, which is roughly where heavy
        // use starts to cost study time. Past that the bar simply stays full.
        card.pbMetric.setVisibility(View.VISIBLE);
        card.pbMetric.setProgress(p.todayMinutes > 0
                ? clampPercent(p.launchesToday * 100 / 120) : 0);
        card.pbMetric.getProgressDrawable().setTint(getColor(R.color.info_500));
    }

    private void renderCategories(@NonNull ActivityUsageBinding b, @NonNull Payload p) {
        b.categoryContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        int total = Math.max(0, p.todayMinutes);
        addCategoryRow(inflater, b, AppCategory.SOCIAL, p.socialMinutes, total);
        addCategoryRow(inflater, b, AppCategory.GAMES, p.gamesMinutes, total);
        addCategoryRow(inflater, b, AppCategory.VIDEO, p.videoMinutes, total);

        if (p.blockedMinutes > 0 && total > 0) {
            TextView blocked = new TextView(this);
            blocked.setTextAppearance(R.style.TextAppearance_FocusLife_Body_Secondary);
            blocked.setPadding(0,
                    getResources().getDimensionPixelSize(R.dimen.space_m), 0, 0);
            blocked.setText(getString(R.string.usage_blocked_share) + "  "
                    + getString(R.string.separator_dot) + "  "
                    + TimeUtils.formatMinutes(p.blockedMinutes)
                    + " (" + getString(R.string.motivation_progress_percent,
                    clampPercent(p.blockedMinutes * 100 / total)) + ")");
            b.categoryContainer.addView(blocked);
        } else {
            TextView none = new TextView(this);
            none.setTextAppearance(R.style.TextAppearance_FocusLife_Caption);
            none.setPadding(0,
                    getResources().getDimensionPixelSize(R.dimen.space_m), 0, 0);
            none.setText(R.string.usage_blocked_apps_none);
            b.categoryContainer.addView(none);
        }
    }

    private void addCategoryRow(@NonNull LayoutInflater inflater,
                                @NonNull ActivityUsageBinding b,
                                int category, int minutes, int totalMinutes) {
        ItemAppUsageBinding row = ItemAppUsageBinding
                .inflate(inflater, b.categoryContainer, false);

        // Category rows have no rank; the rank cell exists only on app rows.
        row.tvAppRank.setVisibility(View.GONE);
        row.tvAppLabel.setText(AppCategory.labelRes(category));
        row.tvAppMinutes.setText(TimeUtils.formatMinutes(minutes));

        int percent = totalMinutes > 0 ? clampPercent(minutes * 100 / totalMinutes) : 0;
        row.pbApp.setProgress(percent);
        row.pbApp.getProgressDrawable().setTint(getColor(categoryColor(category)));

        row.tvAppMeta.setText(minutes == 0
                ? getString(R.string.usage_category_none_today)
                : getString(R.string.motivation_progress_percent, percent));

        b.categoryContainer.addView(row.getRoot());
    }

    private static int categoryColor(int category) {
        switch (category) {
            case AppCategory.SOCIAL:
                return R.color.chart_1;
            case AppCategory.GAMES:
                return R.color.chart_3;
            default:
                return R.color.chart_5;
        }
    }

    private void renderApps(@NonNull ActivityUsageBinding b, @NonNull Payload p) {
        b.appContainer.removeAllViews();
        b.tvNoApps.setVisibility(p.apps.isEmpty() ? View.VISIBLE : View.GONE);
        if (p.apps.isEmpty()) {
            b.tvAppCount.setText("");
            return;
        }

        b.tvAppCount.setText(getString(R.string.usage_app_count, p.apps.size()));

        // Bars are scaled against the heaviest app, so a quiet day with a 20-minute
        // top app still shows meaningful proportions instead of seven stubs.
        int max = 0;
        for (AppUsageSnapshotEntity app : p.apps) {
            max = Math.max(max, app.minutes);
        }
        if (max <= 0) max = 1;

        LayoutInflater inflater = LayoutInflater.from(this);
        int rank = 1;
        for (AppUsageSnapshotEntity app : p.apps) {
            ItemAppUsageBinding row = ItemAppUsageBinding
                    .inflate(inflater, b.appContainer, false);

            row.tvAppRank.setText(rank + ".");
            row.tvAppLabel.setText(app.appLabel);
            row.tvAppMinutes.setText(TimeUtils.formatMinutes(app.minutes));

            row.pbApp.setProgress(clampPercent(app.minutes * 100 / max));
            row.pbApp.getProgressDrawable().setTint(getColor(app.isBlockedApp
                    ? R.color.warning_500 : R.color.accent_500));

            row.tvAppMeta.setText(appMeta(app));
            b.appContainer.addView(row.getRoot());
            rank++;
        }
    }

    @NonNull
    private String appMeta(@NonNull AppUsageSnapshotEntity app) {
        List<String> parts = new ArrayList<>(2);

        int category = AppCategory.of(app.packageName);
        int labelRes = AppCategory.labelRes(category);
        if (labelRes != 0) parts.add(getString(labelRes));

        if (app.launchCount > 0) {
            parts.add(getString(R.string.usage_openings_count, app.launchCount));
        }
        return parts.isEmpty() ? "" : join(parts);
    }

    @NonNull
    private String join(@NonNull List<String> parts) {
        StringBuilder sb = new StringBuilder();
        String separator = "  " + getString(R.string.separator_dot) + "  ";
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private void renderWeek(@NonNull ActivityUsageBinding b, @NonNull Payload p) {
        if (p.weekMinutes.length > 0) {
            b.chartWeek.setWeekData(p.weekMinutes, p.weekDayKeys);
            b.chartWeek.setValueSuffix(getString(R.string.label_minutes_short));
            b.chartWeek.setHighlightLast(true);
            b.chartWeek.setVisibility(View.VISIBLE);
        } else {
            b.chartWeek.setVisibility(View.GONE);
        }

        b.weekStatContainer.removeAllViews();
        if (p.weekMinutes.length == 0) return;

        int total = 0;
        int worst = 0;
        int daysUnderLimit = 0;
        for (int minutes : p.weekMinutes) {
            int value = Math.max(0, minutes);
            total += value;
            worst = Math.max(worst, value);
            if (p.targetMinutes > 0 && value > 0 && value <= p.targetMinutes) {
                daysUnderLimit++;
            }
        }

        addWeekStat(b, getString(R.string.usage_avg_week),
                TimeUtils.formatMinutes(total / p.weekMinutes.length));
        addWeekStat(b, getString(R.string.usage_worst_day),
                TimeUtils.formatMinutes(worst));
        if (p.targetMinutes > 0) {
            addWeekStat(b, getString(R.string.usage_week_days_below, daysUnderLimit), "");
        }
    }

    private void addWeekStat(@NonNull ActivityUsageBinding b,
                             @NonNull String label, @NonNull String value) {
        LayoutInflater inflater = LayoutInflater.from(this);
        ItemAppUsageBinding row = ItemAppUsageBinding
                .inflate(inflater, b.weekStatContainer, false);

        row.tvAppRank.setVisibility(View.GONE);
        row.tvAppLabel.setText(label);
        row.tvAppMinutes.setText(value);
        row.tvAppMinutes.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        row.pbApp.setVisibility(View.GONE);
        row.tvAppMeta.setVisibility(View.GONE);

        b.weekStatContainer.addView(row.getRoot());
    }

    private void renderLimit(@NonNull ActivityUsageBinding b, @NonNull Payload p) {
        if (p.targetMinutes <= 0) {
            b.tvLimitValue.setText(R.string.usage_limit_not_set);
            b.pbLimit.setVisibility(View.GONE);
            return;
        }

        b.tvLimitValue.setText(getString(R.string.usage_limit_set,
                TimeUtils.formatMinutes(p.targetMinutes)));
        b.pbLimit.setVisibility(View.VISIBLE);

        int percent = p.todayMinutes > 0
                ? clampPercent(p.todayMinutes * 100 / p.targetMinutes) : 0;
        b.pbLimit.setProgress(percent);
        // Amber as the target is approached, red only once it is passed. Going
        // straight to red would make the bar read as a punishment for using a
        // phone at all.
        b.pbLimit.getProgressDrawable().setTint(getColor(
                p.todayMinutes > p.targetMinutes ? R.color.danger_500
                        : percent >= 80 ? R.color.warning_500 : R.color.accent_500));
    }

    /**
     * Builds the suggestion list from what was actually measured today.
     *
     * <p>Every tip is phrased as an option. Nothing here says the user failed, and
     * a day under the limit produces an encouraging line rather than an empty
     * section — an empty suggestions area on a good day would read as withheld
     * criticism.</p>
     */
    private void renderTips(@NonNull ActivityUsageBinding b, @NonNull Payload p) {
        b.tipContainer.removeAllViews();

        List<Integer> tips = new ArrayList<>(6);

        if (p.measured && p.targetMinutes > 0 && p.todayMinutes <= p.targetMinutes) {
            tips.add(R.string.usage_tip_good);
        }

        int dominant = AppCategory.dominantCategory(
                p.apps, Math.max(0, p.todayMinutes), DOMINANT_CATEGORY_SHARE);
        if (dominant == AppCategory.SOCIAL) tips.add(R.string.usage_tip_high_social);
        else if (dominant == AppCategory.GAMES) tips.add(R.string.usage_tip_high_games);
        else if (dominant == AppCategory.VIDEO) tips.add(R.string.usage_tip_high_video);

        if (p.launchesSupported && p.launchesToday >= 80) {
            tips.add(R.string.usage_tip_2);
        }
        if (p.measured && p.targetMinutes > 0 && p.todayMinutes > p.targetMinutes) {
            tips.add(R.string.usage_tip_night);
        }

        // Always finish with a fixed set of practical options so the section is
        // never empty and never looks like a verdict on today specifically.
        for (int res : new int[]{R.string.usage_tip_1, R.string.usage_tip_3,
                R.string.usage_tip_4, R.string.usage_tip_5, R.string.usage_tip_6,
                R.string.usage_tip_7}) {
            if (!tips.contains(res)) tips.add(res);
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int res : tips) {
            ItemTipBinding tipRow = ItemTipBinding.inflate(inflater, b.tipContainer, false);
            tipRow.tvTipText.setText(res);
            b.tipContainer.addView(tipRow.getRoot());
        }
    }

    // ------------------------------------------------------------------
    //  Daily limit
    // ------------------------------------------------------------------

    /**
     * Edits the whole-device daily screen-time target.
     *
     * <p>This is a different setting from the per-app limits in the blocker: those
     * are enforced, while this one drives the Focus Score's screen-time component
     * and the comparisons on this screen. It is not enforced, and the screen does
     * not claim otherwise.</p>
     */
    private void editDailyLimit() {
        DialogNumberBinding d = DialogNumberBinding.inflate(LayoutInflater.from(this));
        d.etNumber.setText(String.valueOf(Prefs.targetScreenMinutes(this)));
        d.fieldNumber.setHint(R.string.label_minutes_short);
        d.tvNumberHint.setText(R.string.usage_limit_hint);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.usage_daily_limit)
                .setView(d.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String raw = d.etNumber.getText() == null
                            ? "" : d.etNumber.getText().toString().trim();
                    int value;
                    try {
                        value = Integer.parseInt(raw);
                    } catch (NumberFormatException e) {
                        showMessage(getString(R.string.validation_number));
                        return;
                    }
                    if (value < 0 || value > 1440) {
                        showMessage(getString(R.string.usage_limit_range));
                        return;
                    }
                    Prefs.setTargetScreenMinutes(this, value);
                    load();
                })
                .show();
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private void showMessage(@NonNull String message) {
        ActivityUsageBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
