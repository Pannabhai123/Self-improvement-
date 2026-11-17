package com.focuslife.app.ui.routine;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.focuslife.app.R;
import com.focuslife.app.data.db.entity.RoutineSlotEntity;
import com.focuslife.app.data.prefs.Prefs;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.ItemRoutineSlotBinding;
import com.focuslife.app.util.TimeUtils;

/**
 * The daily routine list, ordered by clock time.
 *
 * <p>The switch on each row writes straight through to the database rather than
 * opening the editor: turning a slot off is a one-second decision and should cost
 * one tap. Everything else — time, category, lead time, days — goes through the
 * editor dialog.</p>
 */
public class RoutineSlotAdapter
        extends ListAdapter<RoutineSlotEntity, RoutineSlotAdapter.SlotViewHolder> {

    public interface OnSlotAction {
        void onOpen(@NonNull RoutineSlotEntity slot);

        void onToggleEnabled(@NonNull RoutineSlotEntity slot, boolean enabled);

        void onDelete(@NonNull RoutineSlotEntity slot);
    }

    @Nullable
    private OnSlotAction listener;

    /**
     * Id of the slot currently in progress, or {@link #NO_CURRENT_SLOT}.
     *
     * <p>Resolved by the host rather than per row, because "in progress" means
     * the latest enabled slot that has already started — a fact only the whole
     * list can answer, not an individual view holder.</p>
     */
    private long currentSlotId = NO_CURRENT_SLOT;

    public static final long NO_CURRENT_SLOT = -1L;

    private boolean notificationsOn = true;

    public RoutineSlotAdapter() {
        super(DIFF);
    }

    public void setListener(@Nullable OnSlotAction listener) {
        this.listener = listener;
    }

    public void setCurrentSlotId(long currentSlotId) {
        if (this.currentSlotId != currentSlotId) {
            this.currentSlotId = currentSlotId;
            notifyDataSetChanged();
        }
    }

    public void setNotificationsOn(boolean notificationsOn) {
        this.notificationsOn = notificationsOn;
    }

    private static final DiffUtil.ItemCallback<RoutineSlotEntity> DIFF =
            new DiffUtil.ItemCallback<RoutineSlotEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull RoutineSlotEntity a,
                                               @NonNull RoutineSlotEntity b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull RoutineSlotEntity a,
                                                  @NonNull RoutineSlotEntity b) {
                    return a.minuteOfDay == b.minuteOfDay
                            && a.enabled == b.enabled
                            && a.remindBeforeMin == b.remindBeforeMin
                            && a.daysMask == b.daysMask
                            && a.sortOrder == b.sortOrder
                            && a.titleRes == b.titleRes
                            && a.category.equals(b.category)
                            && a.title.equals(b.title);
                }
            };

    @NonNull
    @Override
    public SlotViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemRoutineSlotBinding slotBinding = ItemRoutineSlotBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new SlotViewHolder(slotBinding);
    }

    @Override
    public void onBindViewHolder(@NonNull SlotViewHolder holder, int position) {
        RoutineSlotEntity slot = getItem(position);
        holder.bind(slot, listener, slot.id == currentSlotId, notificationsOn);
    }

    static class SlotViewHolder extends RecyclerView.ViewHolder {

        private final ItemRoutineSlotBinding binding;

        SlotViewHolder(@NonNull ItemRoutineSlotBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull RoutineSlotEntity slot, @Nullable OnSlotAction listener,
                  boolean inProgress, boolean notificationsOn) {
            var context = binding.getRoot().getContext();
            Repository repo = Repository.get(context);

            binding.tvSlotTitle.setText(repo.resolveString(slot.titleRes, slot.title));

            binding.tvSlotTime.setText(Prefs.use24HourClock(context)
                    ? TimeUtils.formatClock24(slot.minuteOfDay)
                    : TimeUtils.formatClock12(slot.minuteOfDay));

            // The lead time is only meaningful if notifications can actually be
            // delivered, so say so plainly rather than promising a reminder that
            // will never arrive.
            if (!notificationsOn) {
                binding.tvSlotRemind.setText(R.string.routine_notifications_off);
                binding.tvSlotRemind.setTextColor(context.getColor(R.color.warning_500));
            } else if (slot.remindBeforeMin <= 0) {
                binding.tvSlotRemind.setText(R.string.routine_remind_0);
                binding.tvSlotRemind.setTextColor(context.getColor(R.color.text_tertiary));
            } else {
                binding.tvSlotRemind.setText(context.getString(
                        R.string.routine_remind_before_at, slot.remindBeforeMin));
                binding.tvSlotRemind.setTextColor(context.getColor(R.color.text_tertiary));
            }

            binding.slotAccent.getBackground().setTint(context.getColor(
                    inProgress ? R.color.success_500 : categoryColor(slot.category)));
            // Dimming rather than striking through: a disabled slot is "not
            // today", not "cancelled".
            binding.slotAccent.setAlpha(slot.enabled ? 1f : 0.35f);
            binding.tvSlotTitle.setAlpha(slot.enabled ? 1f : 0.45f);
            binding.tvSlotTime.setAlpha(slot.enabled ? 1f : 0.45f);

            // Detach before setting, so a recycled row cannot fire the listener
            // for the slot it previously held.
            binding.switchSlotEnabled.setOnCheckedChangeListener(null);
            binding.switchSlotEnabled.setChecked(slot.enabled);
            binding.switchSlotEnabled.setOnCheckedChangeListener((view, checked) -> {
                if (listener != null) listener.onToggleEnabled(slot, checked);
            });

            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onOpen(slot);
            });
            binding.btnSlotDelete.setOnClickListener(v -> {
                if (listener != null) listener.onDelete(slot);
            });
        }

        /** Accent colour per category, so the day reads at a glance. */
        private static int categoryColor(@NonNull String category) {
            switch (category) {
                case RoutineSlotEntity.CATEGORY_WAKE:
                    return R.color.warning_500;
                case RoutineSlotEntity.CATEGORY_FITNESS:
                    return R.color.success_500;
                case RoutineSlotEntity.CATEGORY_STUDY:
                    return R.color.accent_500;
                case RoutineSlotEntity.CATEGORY_MEALS:
                    return R.color.chart_3;
                case RoutineSlotEntity.CATEGORY_REST:
                    return R.color.chart_4;
                case RoutineSlotEntity.CATEGORY_SLEEP:
                    return R.color.info_500;
                default:
                    return R.color.text_tertiary;
            }
        }
    }
}
