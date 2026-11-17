package com.focuslife.app.ui.blocker;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.focuslife.app.R;
import com.focuslife.app.blocking.BlockDecisionEngine;
import com.focuslife.app.blocking.ModeController;
import com.focuslife.app.data.db.AppDatabase;
import com.focuslife.app.data.db.entity.BlockedAppEntity;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.ActivityAppPickerBinding;
import com.focuslife.app.util.PermissionManager;
import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses which installed apps to block.
 *
 * <h2>What is listed</h2>
 * Only apps with a launcher activity — that is, apps the user can actually open.
 * System services, input methods and background-only packages are excluded
 * because they cannot be "opened" in any meaningful sense and blocking them would
 * break the device.
 *
 * <h2>Safety</h2>
 * Anything in {@link BlockDecisionEngine}'s allow-list is filtered out before the
 * list is even built, so Settings, the dialer and the SMS app can never be
 * selected. That invariant is enforced in the decision engine as well; filtering
 * here is defence in depth, not the only line.
 *
 * <h2>Loading</h2>
 * Enumerating launchable apps and loading their icons touches the package manager
 * for every installed app, which on a Helio G85 takes long enough to be visible.
 * It runs on the shared IO executor behind a loading label rather than blocking
 * the first frame.
 */
public class AppPickerActivity extends AppCompatActivity {

    private static final String TAG = "AppPicker";

    /** The brief's named distracting apps, matched by package-name fragment. */
    private static final String[][] SUGGESTED = {
            {"YouTube", "youtube"},
            {"Instagram", "instagram"},
            {"Facebook", "facebook"},
            {"Free Fire", "freefire"},
            {"PUBG / BGMI", "pubg"},
            {"Snapchat", "snapchat"},
            {"Telegram", "telegram"},
            {"WhatsApp", "whatsapp"},
    };

    @Nullable
    private ActivityAppPickerBinding binding;

    @Nullable
    private AppPickerAdapter adapter;

    @Nullable
    private Repository repository;

    /** Loaded once and reused, so re-filtering does not re-read the package manager. */
    @NonNull
    private final List<AppPickerAdapter.AppEntry> allApps = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAppPickerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = Repository.get(getApplicationContext());

        ActivityAppPickerBinding b = binding;
        if (b == null) return;

        b.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new AppPickerAdapter();
        adapter.setListener(count -> updateSelectedCount(count));
        b.rvApps.setLayoutManager(new LinearLayoutManager(this));
        b.rvApps.setAdapter(adapter);

        b.etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (adapter != null) adapter.setQuery(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        b.btnAddSelected.setOnClickListener(v -> saveSelected());
        b.btnEnableAccessibility.setOnClickListener(v -> {
            Intent intent = PermissionManager.accessibilityIntent(this);
            if (intent != null) startActivity(intent);
        });

        b.tvLoading.setVisibility(View.VISIBLE);
        b.rvApps.setVisibility(View.GONE);

        loadAppsAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ActivityAppPickerBinding b = binding;
        if (b == null) return;

        // Blocking is impossible without the accessibility service, so say so
        // here rather than after the user has finished selecting apps.
        boolean canBlock = ModeController.enforcementLevel(this)
                != ModeController.ENFORCEMENT_NONE;
        b.cardAccessibilityMissing.setVisibility(canBlock ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------
    //  Loading the app list
    // ------------------------------------------------------------------

    private void loadAppsAsync() {
        AppDatabase.io().execute(() -> {
            List<AppPickerAdapter.AppEntry> entries;
            Set<String> alreadyBlocked = new HashSet<>();
            try {
                entries = enumerateLaunchableApps();
                Repository repo = repository;
                if (repo != null) {
                    for (BlockedAppEntity app : repo.loadBlockedApps()) {
                        if (app != null) alreadyBlocked.add(app.packageName);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not list installed apps", e);
                entries = new ArrayList<>();
            }

            final List<AppPickerAdapter.AppEntry> result = entries;
            final Set<String> blocked = alreadyBlocked;
            runOnUiThread(() -> {
                ActivityAppPickerBinding b = binding;
                if (b == null || adapter == null) return;

                allApps.clear();
                allApps.addAll(result);
                adapter.setApps(allApps);
                adapter.setSelected(blocked);

                b.tvLoading.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                if (result.isEmpty()) {
                    b.tvLoading.setText(R.string.blocker_no_apps_found);
                }
                b.rvApps.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);

                buildSuggestedChips(b, blocked);
                updateSelectedCount(adapter.getSelectedCount());
            });
        });
    }

    /**
     * Every app the user can open, sorted alphabetically by label.
     *
     * <p>Uses a LAUNCHER intent query rather than {@code getInstalledApplications}:
     * the latter returns hundreds of system packages the user cannot open and
     * cannot meaningfully block.</p>
     */
    @NonNull
    private List<AppPickerAdapter.AppEntry> enumerateLaunchableApps() {
        PackageManager pm = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(launcherIntent, 0);
        if (resolved == null || resolved.isEmpty()) return new ArrayList<>();

        String self = getPackageName();
        List<AppPickerAdapter.AppEntry> entries = new ArrayList<>(resolved.size());
        Set<String> seen = new HashSet<>();

        for (ResolveInfo info : resolved) {
            if (info == null || info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg == null || seen.contains(pkg)) continue;
            // Our own package is in the engine's allow-list and can never be
            // blocked, so it is not offered as a choice.
            if (self.equals(pkg)) continue;
            if (BlockDecisionEngine.isAlwaysAllowed(pkg)) continue;
            seen.add(pkg);

            String label;
            Drawable icon;
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                CharSequence loaded = pm.getApplicationLabel(appInfo);
                label = loaded == null ? pkg : loaded.toString();
                icon = pm.getApplicationIcon(appInfo);
            } catch (PackageManager.NameNotFoundException | OutOfMemoryError e) {
                // Icon loading is the memory-heavy part of this loop on a 4 GB
                // device; a failure here must not lose the app from the list.
                label = pkg;
                icon = null;
            }
            entries.add(new AppPickerAdapter.AppEntry(pkg, label, icon));
        }

        Collections.sort(entries, (x, y) ->
                x.label.compareToIgnoreCase(y.label));
        return entries;
    }

    // ------------------------------------------------------------------
    //  Suggestions
    // ------------------------------------------------------------------

    /**
     * Chips for the brief's named apps, shown only when installed.
     *
     * <p>Matching is on a package-name fragment rather than an exact package,
     * because these apps ship regional variants ({@code .instagram.lite},
     * {@code .youtube.go}) and an exact match would silently miss them.</p>
     */
    private void buildSuggestedChips(@NonNull ActivityAppPickerBinding b,
                                     @NonNull Set<String> alreadyBlocked) {
        b.chipGroupSuggested.removeAllViews();

        List<AppPickerAdapter.AppEntry> matches = new ArrayList<>();
        for (String[] suggestion : SUGGESTED) {
            String fragment = suggestion[1];
            for (AppPickerAdapter.AppEntry entry : allApps) {
                if (entry.packageName.contains(fragment)) {
                    matches.add(entry);
                    break;
                }
            }
        }

        boolean any = !matches.isEmpty();
        b.tvSuggested.setVisibility(any ? View.VISIBLE : View.GONE);
        b.suggestedScroll.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) return;

        for (AppPickerAdapter.AppEntry entry : matches) {
            Chip chip = new Chip(this);
            chip.setText(entry.label);
            chip.setCheckable(true);
            chip.setChecked(alreadyBlocked.contains(entry.packageName));
            chip.setId(View.generateViewId());
            chip.setChipMinHeight(getResources().getDimension(R.dimen.touch_target));
            chip.setOnClickListener(v -> {
                if (adapter == null) return;
                boolean nowSelected = !adapter.isSelected(entry.packageName);
                adapter.select(entry.packageName, nowSelected);
                chip.setChecked(nowSelected);
                adapter.notifyDataSetChanged();
            });
            b.chipGroupSuggested.addView(chip);
        }
    }

    private void updateSelectedCount(int count) {
        ActivityAppPickerBinding b = binding;
        if (b == null) return;

        b.tvSelectedCount.setText(count == 1
                ? getString(R.string.blocker_selected_count, count)
                : getString(R.string.blocker_selected_count_plural, count));
        b.btnAddSelected.setEnabled(count > 0);
    }

    // ------------------------------------------------------------------
    //  Saving
    // ------------------------------------------------------------------

    private void saveSelected() {
        AppPickerAdapter current = adapter;
        Repository repo = repository;
        if (current == null || repo == null) return;

        final Set<String> chosen = current.getSelected();
        if (chosen.isEmpty()) return;

        final List<String[]> toAdd = new ArrayList<>();
        for (AppPickerAdapter.AppEntry entry : allApps) {
            if (chosen.contains(entry.packageName)) {
                toAdd.add(new String[]{entry.packageName, entry.label});
            }
        }

        AppDatabase.io().execute(() -> {
            int added = 0;
            try {
                for (String[] pair : toAdd) {
                    // addBlockedApp is idempotent, so re-selecting an app that is
                    // already blocked updates it rather than duplicating the row.
                    repo.addBlockedApp(pair[0], pair[1]);
                    added++;
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "could not save the selection", e);
            }

            final int count = added;
            final String singleLabel = toAdd.isEmpty() ? "" : toAdd.get(0)[1];
            runOnUiThread(() -> {
                showMessage(count == 1
                        ? getString(R.string.blocker_added, singleLabel)
                        : getString(R.string.blocker_added_plural, count));
                // Take the user to the limits screen: selecting apps is only half
                // of the decision, and per-app limits are the other half.
                startActivity(new Intent(AppPickerActivity.this, AppLimitActivity.class));
                finish();
            });
        });
    }

    private void showMessage(@NonNull String message) {
        ActivityAppPickerBinding b = binding;
        if (b == null) return;
        Snackbar.make(b.getRoot(), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (binding != null) binding.rvApps.setAdapter(null);
        adapter = null;
        binding = null;
    }
}
