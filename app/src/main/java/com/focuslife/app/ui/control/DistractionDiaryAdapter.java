package com.focuslife.app.ui.control;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.focuslife.app.R;
import com.focuslife.app.data.db.entity.DistractionLogEntity;
import com.focuslife.app.databinding.ItemDistractionBinding;
import com.focuslife.app.util.TimeUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The distraction diary.
 *
 * <p>A resisted urge and a lapse share one row style and one colour weight; only
 * the marker differs. Colouring a lapse red would turn a reflection tool into a
 * scorecard, and the value of the diary is noticing the trigger, not grading the
 * outcome.</p>
 */
public class DistractionDiaryAdapter
        extends ListAdapter<DistractionLogEntity, DistractionDiaryAdapter.EntryViewHolder> {

    public interface OnEntryAction {
        void onDelete(@NonNull DistractionLogEntity entry);
    }

    @Nullable
    private OnEntryAction listener;

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    public DistractionDiaryAdapter() {
        super(DIFF);
    }

    public void setListener(@Nullable OnEntryAction listener) {
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<DistractionLogEntity> DIFF =
            new DiffUtil.ItemCallback<DistractionLogEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull DistractionLogEntity a,
                                               @NonNull DistractionLogEntity b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull DistractionLogEntity a,
                                                  @NonNull DistractionLogEntity b) {
                    return a.resisted == b.resisted
                            && a.minutesLost == b.minutesLost
                            && a.urgeLevel == b.urgeLevel
                            && a.loggedAt == b.loggedAt
                            && a.trigger.equals(b.trigger)
                            && a.intendedTask.equals(b.intendedTask)
                            && a.alternativeUsed.equals(b.alternativeUsed)
                            && a.packageName.equals(b.packageName);
                }
            };

    @NonNull
    @Override
    public EntryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDistractionBinding rowBinding = ItemDistractionBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new EntryViewHolder(rowBinding);
    }

    @Override
    public void onBindViewHolder(@NonNull EntryViewHolder holder, int position) {
        holder.bind(getItem(position), listener);
    }

    /** Localises a stored trigger key, leaving free text untouched. */
    @NonNull
    public static String triggerLabel(@NonNull Context context, @NonNull String trigger) {
        switch (trigger) {
            case DistractionLogEntity.TRIGGER_SOCIAL_MEDIA:
                return context.getString(R.string.trigger_social_media);
            case DistractionLogEntity.TRIGGER_GAMES:
                return context.getString(R.string.trigger_games);
            case DistractionLogEntity.TRIGGER_VIDEO:
                return context.getString(R.string.trigger_video);
            case DistractionLogEntity.TRIGGER_MESSAGES:
                return context.getString(R.string.trigger_messages);
            case DistractionLogEntity.TRIGGER_BOREDOM:
                return context.getString(R.string.trigger_boredom);
            case DistractionLogEntity.TRIGGER_STRESS:
                return context.getString(R.string.trigger_stress);
            case DistractionLogEntity.TRIGGER_HUNGER:
                return context.getString(R.string.trigger_hunger);
            case DistractionLogEntity.TRIGGER_SLEEPINESS:
                return context.getString(R.string.trigger_sleepiness);
            case DistractionLogEntity.TRIGGER_OTHER:
                return context.getString(R.string.trigger_other);
            default:
                // A user-typed trigger is stored verbatim and shown verbatim.
                return trigger;
        }
    }

    static class EntryViewHolder extends RecyclerView.ViewHolder {

        private final ItemDistractionBinding binding;

        EntryViewHolder(@NonNull ItemDistractionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull DistractionLogEntity entry, @Nullable OnEntryAction listener) {
            Context context = binding.getRoot().getContext();

            binding.tvEntryTrigger.setText(triggerLabel(context, entry.trigger));
            binding.tvEntryTime.setText(TIME_FORMAT.format(new Date(entry.loggedAt)));

            binding.ivEntryMarker.setImageResource(entry.resisted
                    ? R.drawable.ic_check_circle_filled
                    : R.drawable.ic_info);
            binding.ivEntryMarker.setImageTintList(
                    android.content.res.ColorStateList.valueOf(context.getColor(
                            entry.resisted ? R.color.success_500 : R.color.text_tertiary)));

            binding.tvEntryDetail.setText(describe(context, entry));

            binding.btnEntryDelete.setOnClickListener(v -> {
                if (listener != null) listener.onDelete(entry);
            });
        }

        /**
         * One line of context: what was intended, what it cost, how strong the
         * urge was, and what was done instead.
         */
        @NonNull
        static String describe(@NonNull Context context, @NonNull DistractionLogEntity entry) {
            StringBuilder sb = new StringBuilder();
            String dot = context.getString(R.string.separator_dot);

            if (!entry.intendedTask.isEmpty()) {
                sb.append(context.getString(R.string.control_intended_task))
                        .append(": ").append(entry.intendedTask);
            }
            if (entry.minutesLost > 0) {
                if (sb.length() > 0) sb.append("  ").append(dot).append("  ");
                sb.append(TimeUtils.formatMinutes(entry.minutesLost));
            }
            if (entry.urgeLevel > 0) {
                if (sb.length() > 0) sb.append("  ").append(dot).append("  ");
                sb.append(context.getString(R.string.control_urge_level_short, entry.urgeLevel));
            }
            if (!entry.alternativeUsed.isEmpty()) {
                if (sb.length() > 0) sb.append("  ").append(dot).append("  ");
                sb.append(entry.alternativeUsed);
            }
            if (sb.length() == 0) {
                sb.append(entry.resisted
                        ? context.getString(R.string.control_urge_resisted)
                        : context.getString(R.string.control_urge_logged));
            }
            return sb.toString();
        }
    }
}
