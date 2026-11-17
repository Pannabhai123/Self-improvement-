package com.focuslife.app.ui.blocker;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.focuslife.app.R;
import com.focuslife.app.blocking.BlockDecisionEngine;
import com.focuslife.app.data.db.entity.BlockedAppEntity;
import com.focuslife.app.databinding.ItemBlockedAppBinding;
import com.focuslife.app.util.PermissionManager;
import com.focuslife.app.util.TimeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The configured block list.
 *
 * <p>Each row summarises the whole policy for one app in a single line of text —
 * which modes, whether it is always blocked, and how much of today's budget is
 * left. Four separate switches per app would make the list unreadable at ten
 * apps, and the summary is what the user actually needs when scanning.</p>
 */
public class BlockedAppAdapter
        extends ListAdapter<BlockedAppEntity, BlockedAppAdapter.AppViewHolder> {

    public interface OnAppAction {
        void onOpen(@NonNull BlockedAppEntity app);

        void onToggle(@NonNull BlockedAppEntity app, boolean enabled);

        void onRemove(@NonNull BlockedAppEntity app);
    }

    @Nullable
    private OnAppAction listener;

    /** Whether usage can be measured at all, which decides what the row can say. */
    private boolean usageAvailable = false;

    public BlockedAppAdapter() {
        super(DIFF);
    }

    public void setListener(@Nullable OnAppAction listener) {
        this.listener = listener;
    }

    public void setUsageAvailable(boolean usageAvailable) {
        this.usageAvailable = usageAvailable;
    }

    private static final DiffUtil.ItemCallback<BlockedAppEntity> DIFF =
            new DiffUtil.ItemCallback<BlockedAppEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull BlockedAppEntity a,
                                               @NonNull BlockedAppEntity b) {
                    return a.packageName.equals(b.packageName);
                }

                @Override
                public boolean areContentsTheSame(@NonNull BlockedAppEntity a,
                                                  @NonNull BlockedAppEntity b) {
                    return a.enabled == b.enabled
                            && a.strict == b.strict
                            && a.blockAlways == b.blockAlways
                            && a.blockInFocus == b.blockInFocus
                            && a.blockInStudy == b.blockInStudy
                            && a.blockInRun == b.blockInRun
                            && a.blockInSleep == b.blockInSleep
                            && a.dailyLimitMin == b.dailyLimitMin
                            && a.usedTodayMin == b.usedTodayMin
                            && a.blockCount == b.blockCount
                            && a.appLabel.equals(b.appLabel)
                            && a.usageDayKey.equals(b.usageDayKey);
                }
            };

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBlockedAppBinding rowBinding = ItemBlockedAppBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new AppViewHolder(rowBinding);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        holder.bind(getItem(position), listener, usageAvailable);
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {

        private final ItemBlockedAppBinding binding;

        AppViewHolder(@NonNull ItemBlockedAppBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull BlockedAppEntity app, @Nullable OnAppAction listener,
                  boolean usageAvailable) {
            Context context = binding.getRoot().getContext();

            binding.tvAppLabel.setText(app.appLabel);
            binding.ivAppIcon.setImageDrawable(loadIcon(context, app.packageName));

            binding.tvAppState.setText(describe(context, app, usageAvailable));

            boolean overLimit = app.dailyLimitMin > 0
                    && app.dayKeyMatches(TimeUtils.today())
                    && app.usedTodayMin >= app.dailyLimitMin;
            binding.tvOverLimit.setVisibility(overLimit ? android.view.View.VISIBLE
                    : android.view.View.GONE);
            if (overLimit) {
                binding.tvOverLimit.setText(R.string.blocker_limit_reached_today);
            }

            // Dim rather than hide a disabled row: the configuration is still
            // there and one tap away from being live again.
            float alpha = app.enabled ? 1f : 0.5f;
            binding.tvAppLabel.setAlpha(alpha);
            binding.tvAppState.setAlpha(alpha);

            binding.switchEnabled.setOnCheckedChangeListener(null);
            binding.switchEnabled.setChecked(app.enabled);
            binding.switchEnabled.setOnCheckedChangeListener((view, checked) -> {
                if (listener != null) listener.onToggle(app, checked);
            });

            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onOpen(app);
            });
            binding.btnRemove.setOnClickListener(v -> {
                if (listener != null) listener.onRemove(app);
            });
        }

        @Nullable
        private static Drawable loadIcon(@NonNull Context context, @NonNull String pkg) {
            try {
                PackageManager pm = context.getPackageManager();
                return pm.getApplicationIcon(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException | OutOfMemoryError e) {
                // An uninstalled or memory-heavy icon must not blank the row.
                return null;
            }
        }

        /**
         * One line describing the whole policy for this app.
         *
         * <p>Says plainly when usage cannot be measured, because a limit shown
         * against an unmeasurable usage figure would be a number with no meaning
         * behind it.</p>
         */
        @NonNull
        static String describe(@NonNull Context context, @NonNull BlockedAppEntity app,
                               boolean usageAvailable) {
            StringBuilder sb = new StringBuilder();

            List<String> modes = new ArrayList<>(4);
            if (app.blockInFocus) modes.add(context.getString(R.string.mode_deep_focus));
            if (app.blockInStudy) modes.add(context.getString(R.string.mode_study));
            if (app.blockInRun) modes.add(context.getString(R.string.mode_run));
            if (app.blockInSleep) modes.add(context.getString(R.string.mode_sleep));

            if (app.blockAlways) {
                sb.append(context.getString(R.string.blocker_blocked_always_short));
            } else if (!modes.isEmpty()) {
                sb.append(context.getString(R.string.blocker_blocked_in_modes,
                        joinModes(context, modes)));
            } else if (!app.enabled) {
                sb.append(context.getString(R.string.blocker_paused));
            } else {
                sb.append(context.getString(R.string.blocker_no_modes_selected));
            }

            if (app.strict && (app.blockAlways || !modes.isEmpty())) {
                sb.append("  ").append(context.getString(R.string.separator_dot))
                        .append("  ").append(context.getString(R.string.blocker_strict_mode));
            }

            if (app.dailyLimitMin > 0) {
                sb.append("  ").append(context.getString(R.string.separator_dot)).append("  ");
                if (!usageAvailable) {
                    sb.append(context.getString(R.string.blocker_limit_unmeasured,
                            TimeUtils.formatMinutes(app.dailyLimitMin)));
                } else {
                    int remaining = app.remainingLimitMinutes();
                    sb.append(remaining > 0
                            ? context.getString(R.string.blocker_limit_remaining,
                                    TimeUtils.formatMinutes(remaining))
                            : context.getString(R.string.blocker_limit_reached));
                }
            }
            return sb.toString();
        }

        @NonNull
        /** Comma-joins mode names, with "and" before the last one. */
        @NonNull
        private static String joinModes(@NonNull Context context, @NonNull List<String> parts) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    sb.append(i == parts.size() - 1
                            ? context.getString(R.string.separator_and)
                            : ", ");
                }
                sb.append(parts.get(i));
            }
            return sb.toString();
        }
    }

    /** Exposed so the host screen can reuse the same wording elsewhere. */
    @NonNull
    public static String describeApp(@NonNull Context context, @NonNull BlockedAppEntity app) {
        return AppViewHolder.describe(context, app, PermissionManager.hasUsageAccess(context));
    }
}
