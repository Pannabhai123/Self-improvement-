package com.focuslife.app.util;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.focuslife.app.R;
import com.focuslife.app.blocking.BlockAccessibilityService;

import java.util.List;

/**
 * Central authority for every permission Focus Life uses.
 *
 * <p>Three of these are "special app access" permissions that cannot be granted
 * through the normal runtime dialog — Usage access, Accessibility and Display
 * over other apps. Each opens a specific system screen, and each is checked
 * here rather than scattered through the UI so the checks stay consistent.</p>
 *
 * <p>Every check is non-throwing: a missing permission degrades a feature, it
 * never crashes the app.</p>
 */
public final class PermissionManager {

    // @SystemApi / @hide in the platform: absent from the public SDK, so they are
    // spelled out here rather than referenced as constants. Each is guarded by
    // safeSettingsIntent because support is OEM-dependent.
    private static final String ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";
    private static final String EXTRA_COMPONENT_NAME = "android.intent.extra.COMPONENT_NAME";
    private static final String EXTRA_FRAGMENT_ARGS_KEY = ":settings:fragment_args_key";

    private PermissionManager() {
    }

    // ------------------------------------------------------------------
    //  Usage access (PACKAGE_USAGE_STATS)
    // ------------------------------------------------------------------

    /**
     * Whether the app can read {@code UsageStatsManager}.
     *
     * <p>Checked through AppOps rather than {@code checkSelfPermission}, because
     * PACKAGE_USAGE_STATS is an appop-backed signature permission and
     * {@code checkSelfPermission} reports DENIED for it on some builds even when
     * the user has granted access.</p>
     */
    public static boolean hasUsageAccess(@NonNull Context context) {
        try {
            AppOpsManager aom = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;

            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = aom.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.getPackageName());
            } else {
                // Not reachable at minSdk 29, retained so the class compiles
                // unchanged if the floor is ever lowered.
                mode = AppOpsManager.MODE_DEFAULT;
            }

            if (mode == AppOpsManager.MODE_DEFAULT) {
                // Fall back to the permission check when the appop is unset.
                return context.checkCallingOrSelfPermission(
                        "android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    @NonNull
    public static Intent usageAccessIntent() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        // Some OEM builds (including ColorOS) land on a generic list; deep-link
        // to this package where the extra is honoured, and ignore it otherwise.
        intent.setData(Uri.fromParts("package", "", null));
        return safeSettingsIntent(intent, Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }

    // ------------------------------------------------------------------
    //  Accessibility (the Distraction Guard)
    // ------------------------------------------------------------------

    /** True when the blocking accessibility service is switched on in Settings. */
    public static boolean isAccessibilityEnabled(@NonNull Context context) {
        return BlockAccessibilityService.isRunning()
                || isServiceEnabledInSettings(context, BlockAccessibilityService.class);
    }

    /**
     * Reads the system's enabled-services string directly.
     *
     * <p>{@link BlockAccessibilityService#isRunning()} is the authoritative
     * signal but only becomes true once the service has actually been bound.
     * This covers the window between the user flipping the switch and the bind,
     * and the case where the service was killed by the OEM.</p>
     */
    public static boolean isServiceEnabledInSettings(@NonNull Context context,
                                                     @NonNull Class<?> serviceClass) {
        try {
            String expected = new ComponentName(context, serviceClass).flattenToString();
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;

            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            for (String name : splitter) {
                ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && expected.equalsIgnoreCase(cn.flattenToString())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Opens this service's own entry in the accessibility list where the platform
     * supports it, and the list itself otherwise.
     *
     * <p>Both the action and its component extra are {@code @SystemApi}, so they
     * are absent from the public SDK and have to be written out as literals. Not
     * every OEM Settings implements the specific action, which is why it goes
     * through {@link #safeSettingsIntent} with two fallbacks rather than being
     * started directly: an unresolved intent here would look like a dead button.</p>
     */
    @NonNull
    public static Intent accessibilityIntent(@NonNull Context context) {
        String component =
                new ComponentName(context, BlockAccessibilityService.class).flattenToString();

        Intent specific = new Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
        specific.putExtra(EXTRA_COMPONENT_NAME, component);
        specific.setData(Uri.fromParts("package", context.getPackageName(), null));

        // Fallback: the full accessibility list, with this service pre-highlighted.
        Intent list = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        list.putExtra(EXTRA_FRAGMENT_ARGS_KEY, component);

        return safeSettingsIntent(specific, list);
    }

    // ------------------------------------------------------------------
    //  Display over other apps (SYSTEM_ALERT_WINDOW)
    // ------------------------------------------------------------------

    public static boolean canDrawOverlays(@NonNull Context context) {
        try {
            return Settings.canDrawOverlays(context);
        } catch (Exception e) {
            return false;
        }
    }

    @NonNull
    public static Intent overlayIntent(@NonNull Context context) {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
        return safeSettingsIntent(intent, Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
    }

    // ------------------------------------------------------------------
    //  Notifications (runtime permission on Android 13+)
    // ------------------------------------------------------------------

    public static boolean canPostNotifications(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Below 33 the permission is granted at install and only revocable
            // through the app-info screen; the notification manager is the
            // reliable source of truth.
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        }
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean shouldShowNotificationRationale(@NonNull androidx.activity.ComponentActivity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false;
        return activity.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS);
    }

    @NonNull
    public static Intent notificationSettingsIntent(@NonNull Context context) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        return safeSettingsIntent(intent, appDetailsIntent(context));
    }

    // ------------------------------------------------------------------
    //  Battery optimisation (optional, OEM-specific)
    // ------------------------------------------------------------------

    public static boolean isIgnoringBatteryOptimizations(@NonNull Context context) {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The direct exemption dialog. Only usable when
     * {@code REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} is declared, which this app
     * does — but OEMs sometimes ignore the intent, hence the fallback.
     */
    @NonNull
    public static Intent batteryOptimisationIntent(@NonNull Context context) {
        Intent intent = new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.getPackageName()));
        return safeSettingsIntent(intent, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }

    // ------------------------------------------------------------------
    //  Generic helpers
    // ------------------------------------------------------------------

    @NonNull
    public static Intent appDetailsIntent(@NonNull Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName()));
    }

    /**
     * Returns {@code primary} when some activity can handle it, otherwise a
     * fallback that opens the relevant top-level settings screen.
     *
     * <p>This matters on OEM builds: ColorOS on the OPPO A18 removes or renames
     * several settings activities, and an {@code ActivityNotFoundException} on a
     * permission button would leave the user with no way to grant anything.</p>
     */
    @NonNull
    private static Intent safeSettingsIntent(@NonNull Intent primary, @NonNull String fallbackAction) {
        return safeSettingsIntent(primary, new Intent(fallbackAction));
    }

    @NonNull
    private static Intent safeSettingsIntent(@NonNull Intent primary, @NonNull Intent fallback) {
        Context context = AppContext.get();
        if (context == null) return primary;
        if (isResolvable(context, primary)) return primary;
        if (isResolvable(context, fallback)) return fallback;
        // Last resort: the global settings screen always exists.
        return new Intent(Settings.ACTION_SETTINGS);
    }

    public static boolean isResolvable(@NonNull Context context, @NonNull Intent intent) {
        try {
            List<?> resolved = context.getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return resolved != null && !resolved.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  Status summary for the Profile screen
    // ------------------------------------------------------------------

    /** Which of the five permissions are granted, as a boolean array. */
    @NonNull
    public static boolean[] statusSnapshot(@NonNull Context context) {
        return new boolean[]{
                canPostNotifications(context),
                hasUsageAccess(context),
                isAccessibilityEnabled(context),
                canDrawOverlays(context),
                isIgnoringBatteryOptimizations(context)
        };
    }

    /** Count of granted permissions among the four that unlock real features. */
    public static int grantedCount(@NonNull Context context) {
        boolean[] s = statusSnapshot(context);
        int n = 0;
        // Battery optimisation is optional and excluded from the count.
        for (int i = 0; i < 4; i++) {
            if (s[i]) n++;
        }
        return n;
    }

    /** Localised label for a permission row. */
    @NonNull
    public static String labelFor(@NonNull Context context, int index) {
        int[] labels = {
                R.string.onboarding_step_notifications,
                R.string.onboarding_step_usage,
                R.string.onboarding_step_accessibility,
                R.string.onboarding_step_overlay,
                R.string.onboarding_step_battery
        };
        if (index < 0 || index >= labels.length) return "";
        return context.getString(labels[index]);
    }

    /** The intent that grants permission {@code index}, or null for notifications. */
    @Nullable
    public static Intent intentFor(@NonNull Context context, int index) {
        switch (index) {
            case 0: return null; // runtime permission, requested by the activity
            case 1: return usageAccessIntent();
            case 2: return accessibilityIntent(context);
            case 3: return overlayIntent(context);
            case 4: return batteryOptimisationIntent(context);
            default: return null;
        }
    }

    /** What to show when a permission was declined, index by index. */
    public static int deniedTitleRes(int index) {
        switch (index) {
            case 0: return R.string.perm_denied_notifications_title;
            case 1: return R.string.perm_denied_usage_title;
            case 2: return R.string.perm_denied_accessibility_title;
            case 3: return R.string.perm_denied_overlay_title;
            default: return R.string.perm_denied_notifications_title;
        }
    }

    public static int deniedBodyRes(int index) {
        switch (index) {
            case 0: return R.string.perm_denied_notifications_desc;
            case 1: return R.string.perm_denied_usage_desc;
            case 2: return R.string.perm_denied_accessibility_desc;
            case 3: return R.string.perm_denied_overlay_desc;
            default: return R.string.perm_denied_notifications_desc;
        }
    }
}
