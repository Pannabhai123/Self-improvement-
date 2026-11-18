package com.focuslife.app.ui.habits;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.focuslife.app.R;
import com.focuslife.app.data.repo.Repository;
import com.focuslife.app.databinding.ItemHabitBinding;

/**
 * The daily habit checklist.
 *
 * <p>Rows are compared by content in {@link DiffUtil} so that ticking one habit
 * animates only that row. On a 4 GB device a full-list rebind on every tick is
 * visibly janky when ten habits are on screen.</p>
 */
public class HabitAdapter extends ListAdapter<Repository.HabitRow, HabitAdapter.HabitViewHolder> {

    /** Invoked with the habit id when a row is tapped. */
    public interface OnHabitClick {
        void onToggle(long habitId, int position);

        void onEdit(@NonNull Repository.HabitRow row);
    }

    @Nullable
    private OnHabitClick listener;

    public HabitAdapter() {
        super(DIFF);
    }

    public void setListener(@Nullable OnHabitClick listener) {
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<Repository.HabitRow> DIFF =
            new DiffUtil.ItemCallback<Repository.HabitRow>() {
                @Override
                public boolean areItemsTheSame(@NonNull Repository.HabitRow a,
                                               @NonNull Repository.HabitRow b) {
                    return a.habit.id == b.habit.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull Repository.HabitRow a,
                                                  @NonNull Repository.HabitRow b) {
                    return a.done == b.done
                            && a.streak == b.streak
                            && a.totalCompletions == b.totalCompletions
                            && a.displayName.equals(b.displayName);
                }
            };

    @NonNull
    @Override
    public HabitViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHabitBinding rowBinding = ItemHabitBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new HabitViewHolder(rowBinding);
    }

    @Override
    public void onBindViewHolder(@NonNull HabitViewHolder holder, int position) {
        holder.bind(getItem(position), listener);
    }

    static class HabitViewHolder extends RecyclerView.ViewHolder {

        private final ItemHabitBinding binding;

        HabitViewHolder(@NonNull ItemHabitBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Repository.HabitRow row, @Nullable OnHabitClick listener) {
            var context = binding.getRoot().getContext();

            binding.tvHabitName.setText(row.displayName);

            StringBuilder meta = new StringBuilder();
            if (row.streak > 0) {
                meta.append(row.streak == 1
                        ? context.getString(R.string.streak_days_one)
                        : context.getString(R.string.streak_days, row.streak));
            }
            if (row.totalCompletions > 0) {
                if (meta.length() > 0) {
                    meta.append("  ").append(context.getString(R.string.separator_dot)).append("  ");
                }
                meta.append(context.getString(R.string.habits_total_completions,
                        row.totalCompletions));
            }
            binding.tvHabitMeta.setText(meta.length() == 0
                    ? context.getString(R.string.label_empty_no_data)
                    : meta.toString());
            binding.tvHabitMeta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);

            binding.ivCheckMark.setVisibility(row.done ? View.VISIBLE : View.INVISIBLE);
            binding.ivCheckBg.setImageState(
                    row.done ? new int[]{android.R.attr.state_checked} : new int[0], true);

            // Strike-through would read as "cancelled" rather than "done", and
            // dimming the name makes completed habits look unimportant. The check
            // mark plus the filled circle carries the state, so the text keeps
            // full contrast either way.
            binding.tvHabitName.setTextColor(context.getColor(R.color.text_primary));

            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onToggle(row.habit.id, getBindingAdapterPosition());
            });

            binding.btnHabitMenu.setOnClickListener(v -> {
                if (listener != null) listener.onEdit(row);
            });

            binding.getRoot().setContentDescription(
                    context.getString(R.string.cd_habit_toggle, row.displayName));
        }
    }
}
