package com.focuslife.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.focuslife.app.R;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * The permission-index bookkeeping in {@link PermissionManager}.
 *
 * <p>Only the parts that need no {@code android.content.Context} are covered here.
 * {@link PermissionManager#deniedTitleRes(int)} and
 * {@link PermissionManager#deniedBodyRes(int)} are plain switches over a permission
 * index, and they are exactly the kind of code where a missing case fails silently:
 * a row that falls through to another row's copy still compiles, still runs, and
 * still shows a sentence that reads plausibly. The battery row used to explain that
 * reminders were off.</p>
 *
 * <p>The index order is the order {@link PermissionManager#statusSnapshot} returns:
 * 0 notifications, 1 usage, 2 accessibility, 3 overlay, 4 battery. These run as
 * plain JVM tests with no Robolectric, so they assert on resource ids rather than
 * on rendered text.</p>
 */
public class PermissionManagerTest {

    private static final int PERM_NOTIFICATIONS = 0;
    private static final int PERM_USAGE = 1;
    private static final int PERM_ACCESSIBILITY = 2;
    private static final int PERM_OVERLAY = 3;
    private static final int PERM_BATTERY = 4;

    /** Every index {@code statusSnapshot} reports on. */
    private static final int[] ALL = {
            PERM_NOTIFICATIONS, PERM_USAGE, PERM_ACCESSIBILITY, PERM_OVERLAY, PERM_BATTERY
    };

    // ------------------------------------------------------------------
    //  Each row explains its own permission
    // ------------------------------------------------------------------

    @Test
    public void everyPermissionHasItsOwnDenialTitle() {
        Set<Integer> seen = new HashSet<>();
        for (int index : ALL) {
            assertTrue("duplicate denial title at index " + index,
                    seen.add(PermissionManager.deniedTitleRes(index)));
        }
        assertEquals("each of the five rows needs distinct copy", ALL.length, seen.size());
    }

    @Test
    public void everyPermissionHasItsOwnDenialBody() {
        Set<Integer> seen = new HashSet<>();
        for (int index : ALL) {
            assertTrue("duplicate denial body at index " + index,
                    seen.add(PermissionManager.deniedBodyRes(index)));
        }
        assertEquals(ALL.length, seen.size());
    }

    /**
     * The regression this file exists for: there was no {@code case 4}, so
     * declining battery optimisation produced the notifications copy.
     */
    @Test
    public void batteryRowExplainsBatteryNotNotifications() {
        assertEquals(R.string.perm_denied_battery_title,
                PermissionManager.deniedTitleRes(PERM_BATTERY));
        assertEquals(R.string.perm_denied_battery_desc,
                PermissionManager.deniedBodyRes(PERM_BATTERY));

        assertNotEquals("battery must not reuse the notifications title",
                PermissionManager.deniedTitleRes(PERM_NOTIFICATIONS),
                PermissionManager.deniedTitleRes(PERM_BATTERY));
        assertNotEquals("battery must not reuse the notifications body",
                PermissionManager.deniedBodyRes(PERM_NOTIFICATIONS),
                PermissionManager.deniedBodyRes(PERM_BATTERY));
    }

    @Test
    public void eachRowMapsToTheCopyNamedForThatPermission() {
        assertEquals(R.string.perm_denied_notifications_title,
                PermissionManager.deniedTitleRes(PERM_NOTIFICATIONS));
        assertEquals(R.string.perm_denied_usage_title,
                PermissionManager.deniedTitleRes(PERM_USAGE));
        assertEquals(R.string.perm_denied_accessibility_title,
                PermissionManager.deniedTitleRes(PERM_ACCESSIBILITY));
        assertEquals(R.string.perm_denied_overlay_title,
                PermissionManager.deniedTitleRes(PERM_OVERLAY));

        assertEquals(R.string.perm_denied_notifications_desc,
                PermissionManager.deniedBodyRes(PERM_NOTIFICATIONS));
        assertEquals(R.string.perm_denied_usage_desc,
                PermissionManager.deniedBodyRes(PERM_USAGE));
        assertEquals(R.string.perm_denied_accessibility_desc,
                PermissionManager.deniedBodyRes(PERM_ACCESSIBILITY));
        assertEquals(R.string.perm_denied_overlay_desc,
                PermissionManager.deniedBodyRes(PERM_OVERLAY));
    }

    // ------------------------------------------------------------------
    //  Out-of-range input
    // ------------------------------------------------------------------

    /**
     * A denial dialog is shown mid-onboarding, so an unexpected index must still
     * produce a real resource rather than 0, which would crash when inflated.
     */
    @Test
    public void unknownIndexFallsBackInsteadOfReturningZero() {
        for (int index : new int[]{-1, 5, 99, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertTrue("no title resource for index " + index,
                    PermissionManager.deniedTitleRes(index) != 0);
            assertTrue("no body resource for index " + index,
                    PermissionManager.deniedBodyRes(index) != 0);
        }
    }
}
