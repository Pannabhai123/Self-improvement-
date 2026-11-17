package com.focuslife.app.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Holds the application context for the few places that must resolve a system
 * intent without having a Context to hand (notably
 * {@link PermissionManager#safeSettingsIntent}).
 *
 * <p>Set once in {@code FocusLifeApp.onCreate}. This is an application-scoped
 * context, not an Activity, so it cannot leak a screen.</p>
 */
public final class AppContext {

    private static volatile Context context;

    private AppContext() {
    }

    public static void init(@NonNull Context appContext) {
        context = appContext.getApplicationContext();
    }

    @Nullable
    public static Context get() {
        return context;
    }

    /** Same as {@link #get()} but never null; throws only if used before init. */
    @NonNull
    public static Context require() {
        Context c = context;
        if (c == null) {
            throw new IllegalStateException("AppContext.init() was not called");
        }
        return c;
    }
}
