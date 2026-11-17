package com.focuslife.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.focuslife.app.R;
import com.focuslife.app.FocusLifeApp;
import com.focuslife.app.blocking.BlockState;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.databinding.ActivityMainBinding;
import com.focuslife.app.ui.habits.HabitsFragment;
import com.focuslife.app.ui.home.HomeFragment;
import com.focuslife.app.ui.onboarding.OnboardingActivity;
import com.focuslife.app.ui.profile.ProfileFragment;
import com.focuslife.app.ui.study.StudyFragment;
import com.focuslife.app.ui.focus.FocusFragment;

/**
 * Hosts the five destinations and owns the bottom navigation.
 *
 * <h2>Fragment strategy</h2>
 * All five fragments are added once and then shown/hidden rather than being
 * replaced on each tab switch. Two reasons, both specific to this app:
 * <ul>
 *   <li><b>State survival.</b> The Study tab can have a timer running and the
 *       Habits tab a scroll position. Replacement destroys both.</li>
 *   <li><b>Perceived speed.</b> On a Helio G85 inflating five non-trivial
 *       layouts is noticeable; showing an already-inflated view is not.</li>
 * </ul>
 * The trade-off is that all five view hierarchies stay in memory. At roughly a
 * few hundred KB each this is well inside a 4 GB budget, and it is the reason
 * the layouts avoid nested weights and large bitmaps.
 *
 * <h2>Refresh on resume</h2>
 * Data changes while another activity is in front (a focus session finishes, a
 * mode starts, an app is blocked), so each visible fragment is asked to refresh
 * in {@link #onResume}. Fragments implement {@link Refreshable} for this.
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_TAB = "extra_open_tab";

    public static final int TAB_HOME = 0;
    public static final int TAB_FOCUS = 1;
    public static final int TAB_STUDY = 2;
    public static final int TAB_HABITS = 3;
    public static final int TAB_PROFILE = 4;

    private static final String STATE_TAB = "state_tab";

    private static final String TAG_HOME = "tab_home";
    private static final String TAG_FOCUS = "tab_focus";
    private static final String TAG_STUDY = "tab_study";
    private static final String TAG_HABITS = "tab_habits";
    private static final String TAG_PROFILE = "tab_profile";

    private ActivityMainBinding binding;

    @Nullable
    private HomeFragment homeFragment;
    @Nullable
    private FocusFragment focusFragment;
    @Nullable
    private StudyFragment studyFragment;
    @Nullable
    private HabitsFragment habitsFragment;
    @Nullable
    private ProfileFragment profileFragment;

    private int currentTab = TAB_HOME;

    /** Opens the app on a specific tab. */
    @NonNull
    public static Intent forTab(@NonNull Context context, int tab) {
        return new Intent(context, MainActivity.class)
                .putExtra(EXTRA_OPEN_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        // Applies the per-app language override on API levels below 33.
        super.attachBaseContext(FocusLifeApp.LocaleSupport.wrap(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // First launch goes through the permission explanation screens, because
        // requesting Usage access or Accessibility without context is both
        // discourteous and much less likely to be granted.
        if (!Prefs.onboardingComplete(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }

        restoreFragments(savedInstanceState);
        setupNavigation();

        int requested = getIntent().getIntExtra(EXTRA_OPEN_TAB, TAB_HOME);
        int initial = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_TAB, TAB_HOME)
                : clampTab(requested);
        showTab(initial, false);

        // Refresh the block snapshot on launch so a change made in system
        // Settings (e.g. revoking the guard) is reflected immediately.
        BlockState.get().refreshAsync(this);
    }

    /**
     * Recovers fragments after a configuration change or process death instead of
     * creating duplicates, which would otherwise stack two of each tab.
     */
    private void restoreFragments(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        homeFragment = (HomeFragment) getSupportFragmentManager().findFragmentByTag(TAG_HOME);
        focusFragment = (FocusFragment) getSupportFragmentManager().findFragmentByTag(TAG_FOCUS);
        studyFragment = (StudyFragment) getSupportFragmentManager().findFragmentByTag(TAG_STUDY);
        habitsFragment = (HabitsFragment) getSupportFragmentManager().findFragmentByTag(TAG_HABITS);
        profileFragment = (ProfileFragment) getSupportFragmentManager().findFragmentByTag(TAG_PROFILE);
    }

    private void setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                showTab(TAB_HOME, true);
                return true;
            }
            if (id == R.id.nav_focus) {
                showTab(TAB_FOCUS, true);
                return true;
            }
            if (id == R.id.nav_study) {
                showTab(TAB_STUDY, true);
                return true;
            }
            if (id == R.id.nav_habits) {
                showTab(TAB_HABITS, true);
                return true;
            }
            if (id == R.id.nav_profile) {
                showTab(TAB_PROFILE, true);
                return true;
            }
            return false;
        });

        // Re-selecting the current tab scrolls its list back to the top, which is
        // the behaviour users expect from a bottom navigation bar.
        binding.bottomNav.setOnItemReselectedListener(item -> {
            Fragment f = currentFragment();
            if (f instanceof ScrollToTop) {
                ((ScrollToTop) f).scrollToTop();
            }
        });
    }

    /**
     * Shows one tab and hides the rest.
     *
     * @param animate unused for the transaction itself — a 120 ms window fade is
     *                applied at the activity level, and animating fragment swaps
     *                inside a tab bar feels sluggish rather than smooth.
     */
    private void showTab(int tab, boolean animate) {
        currentTab = clampTab(tab);

        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();

        hideIfPresent(tx, homeFragment, TAG_HOME);
        hideIfPresent(tx, focusFragment, TAG_FOCUS);
        hideIfPresent(tx, studyFragment, TAG_STUDY);
        hideIfPresent(tx, habitsFragment, TAG_HABITS);
        hideIfPresent(tx, profileFragment, TAG_PROFILE);

        switch (currentTab) {
            case TAB_FOCUS:
                focusFragment = showOrCreate(tx, focusFragment, TAG_FOCUS, FocusFragment::new);
                break;
            case TAB_STUDY:
                studyFragment = showOrCreate(tx, studyFragment, TAG_STUDY, StudyFragment::new);
                break;
            case TAB_HABITS:
                habitsFragment = showOrCreate(tx, habitsFragment, TAG_HABITS, HabitsFragment::new);
                break;
            case TAB_PROFILE:
                profileFragment = showOrCreate(tx, profileFragment, TAG_PROFILE, ProfileFragment::new);
                break;
            case TAB_HOME:
            default:
                homeFragment = showOrCreate(tx, homeFragment, TAG_HOME, HomeFragment::new);
                break;
        }

        // setReorderingAllowed keeps the hide/show sequence deterministic, which
        // matters because two fragments cannot be shown into the same container
        // without it.
        tx.setReorderingAllowed(true).commitNowAllowingStateLoss();

        syncNavSelection();
    }

    private void hideIfPresent(@NonNull FragmentTransaction tx, @Nullable Fragment f, @NonNull String tag) {
        if (f == null) {
            Fragment found = getSupportFragmentManager().findFragmentByTag(tag);
            if (found != null) tx.hide(found);
            return;
        }
        if (f.isAdded()) tx.hide(f);
    }

    @NonNull
    private <T extends Fragment> T showOrCreate(@NonNull FragmentTransaction tx,
                                                @Nullable T existing,
                                                @NonNull String tag,
                                                @NonNull FragmentFactory<T> factory) {
        Fragment found = existing != null
                ? existing
                : getSupportFragmentManager().findFragmentByTag(tag);
        if (found == null) {
            found = factory.create();
            tx.add(R.id.fragment_container, found, tag);
        } else {
            tx.show(found);
        }
        //noinspection unchecked
        return (T) found;
    }

    private interface FragmentFactory<T extends Fragment> {
        T create();
    }

    private void syncNavSelection() {
        int menuId;
        switch (currentTab) {
            case TAB_FOCUS:   menuId = R.id.nav_focus; break;
            case TAB_STUDY:   menuId = R.id.nav_study; break;
            case TAB_HABITS:  menuId = R.id.nav_habits; break;
            case TAB_PROFILE: menuId = R.id.nav_profile; break;
            case TAB_HOME:
            default:          menuId = R.id.nav_home; break;
        }
        if (binding.bottomNav.getSelectedItemId() != menuId) {
            binding.bottomNav.setSelectedItemId(menuId);
        }
    }

    @Nullable
    private Fragment currentFragment() {
        switch (currentTab) {
            case TAB_FOCUS:   return focusFragment;
            case TAB_STUDY:   return studyFragment;
            case TAB_HABITS:  return habitsFragment;
            case TAB_PROFILE: return profileFragment;
            case TAB_HOME:
            default:          return homeFragment;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // A focus session, a mode change or a blocking edit can all have
        // happened while this activity was paused.
        Fragment f = currentFragment();
        if (f instanceof Refreshable) {
            ((Refreshable) f).refreshData();
        }
        BlockState.get().refreshAsync(this);

        // Honour a tab request that arrives while already running, e.g. from a
        // notification tap.
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra(EXTRA_OPEN_TAB)) {
            int requested = clampTab(intent.getIntExtra(EXTRA_OPEN_TAB, currentTab));
            intent.removeExtra(EXTRA_OPEN_TAB);
            if (requested != currentTab) {
                showTab(requested, false);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra(EXTRA_OPEN_TAB)) {
            showTab(clampTab(intent.getIntExtra(EXTRA_OPEN_TAB, currentTab)), false);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    private static int clampTab(int tab) {
        if (tab < TAB_HOME) return TAB_HOME;
        return Math.min(tab, TAB_PROFILE);
    }

    /** Switches tab programmatically, used by dashboard quick actions. */
    public void goToTab(int tab) {
        showTab(clampTab(tab), false);
    }

    /** Fragments that can reload their data when they become visible. */
    public interface Refreshable {
        void refreshData();
    }

    /** Fragments whose list can be scrolled back to the top on re-selection. */
    public interface ScrollToTop {
        void scrollToTop();
    }
}
