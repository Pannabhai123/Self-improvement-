package com.focuslife.app.ui.fitness;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.focuslife.app.R;
import com.focuslife.app.data.db.entity.WorkoutEntity;
import com.focuslife.app.data.db.entity.WorkoutLogEntity;
import com.focuslife.app.databinding.ItemWorkoutBinding;

/**
 * The daily exercise checklist.
 *
 * <p>A tap records the workout at its target in one action; a long-press opens a
 * prompt for a different number. A checklist that asks "how many?" on every tap
 * stops being a checklist and becomes a form, and forms do not get filled in at
 * six in the morning.</p>
 */
public class WorkoutAdapter extends ListAdapter<WorkoutAdapter.WorkoutRow, WorkoutAdapter.WorkoutViewHolder> {

    /** A workout definition plus today's log for it, if any. */
    public static final class WorkoutRow {
        @NonNull
        public final WorkoutEntity workout;
        @Nullable
        public final WorkoutLogEntity log;
        public final int personalBest;

        WorkoutRow(@NonNull WorkoutEntity workout, @Nullable WorkoutLogEntity log, int personalBest) {
            this.workout = workout;
            this.log = log;
            this.personalBest = personalBest;
        }

        public boolean isDone() {
            return log != null;
        }

        /** What was actually done today, in reps or seconds. */
        public int amountDone() {
            if (log == null) return 0;
            return log.repsDone > 0 ? log.repsDone : log.secDone;
        }
    }

    public interface OnWorkoutAction {
        /** Record at the target, one tap. */
        void onQuickDone(@NonNull WorkoutRow row);

        /** Ask for a specific number. */
        void onCustomAmount(@NonNull WorkoutRow row);

        void onUndo(@NonNull WorkoutRow row);
    }

    @Nullable
    private OnWorkoutAction listener;

    public WorkoutAdapter() {
        super(DIFF);
    }

    public void setListener(@Nullable OnWorkoutAction listener) {
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<WorkoutRow> DIFF =
            new DiffUtil.ItemCallback<WorkoutRow>() {
                @Override
                public boolean areItemsTheSame(@NonNull WorkoutRow a, @NonNull WorkoutRow b) {
                    return a.workout.id == b.workout.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull WorkoutRow a, @NonNull WorkoutRow b) {
                    boolean sameLog = (a.log == null) == (b.log == null);
                    if (!sameLog) return false;
                    if (a.log != null && (a.log.repsDone != b.log.repsDone
                            || a.log.secDone != b.log.secDone)) {
                        return false;
                    }
                    return a.personalBest == b.personalBest
                            && a.workout.targetReps == b.workout.targetReps
                            && a.workout.targetSec == b.workout.targetSec;
                }
            };

    @NonNull
    public static WorkoutRow row(@NonNull WorkoutEntity workout, @Nullable WorkoutLogEntity log,
                                 int personalBest) {
        return new WorkoutRow(workout, log, personalBest);
    }

    @NonNull
    @Override
    public WorkoutViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemWorkoutBinding rowBinding = ItemWorkoutBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new WorkoutViewHolder(rowBinding);
    }

    @Override
    public void onBindViewHolder(@NonNull WorkoutViewHolder holder, int position) {
        holder.bind(getItem(position), listener);
    }

    static class WorkoutViewHolder extends RecyclerView.ViewHolder {

        private final ItemWorkoutBinding binding;

        WorkoutViewHolder(@NonNull ItemWorkoutBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull WorkoutRow row, @Nullable OnWorkoutAction listener) {
            Context context = binding.getRoot().getContext();
            com.focuslife.app.data.repo.Repository repo =
                    com.focuslife.app.data.repo.Repository.get(context);

            String name = repo.resolveString(row.workout.nameRes, row.workout.name);
            binding.tvWorkoutName.setText(name);

            boolean timed = row.workout.targetSec > 0 && row.workout.targetReps <= 0;

            if (row.workout.targetReps > 0 || row.workout.targetSec > 0) {
                String target = timed
                        ? context.getString(R.string.fitness_seconds, row.workout.targetSec)
                        : context.getString(R.string.fitness_reps_count, row.workout.targetReps);
                String best = row.personalBest > 0
                        ? (timed
                                ? context.getString(R.string.fitness_seconds, row.personalBest)
                                : context.getString(R.string.fitness_reps_count, row.personalBest))
                        : context.getString(R.string.label_empty_no_data);
                binding.tvWorkoutTarget.setText(
                        context.getString(R.string.fitness_workout_target, target, best));
            } else {
                binding.tvWorkoutTarget.setText(R.string.fitness_workout_no_target);
            }

            boolean done = row.isDone();
            binding.ivCheckMark.setVisibility(done ? View.VISIBLE : View.INVISIBLE);
            binding.ivCheckBg.setImageState(
                    done ? new int[]{android.R.attr.state_checked} : new int[0], true);

            binding.tvWorkoutDone.setVisibility(done ? View.VISIBLE : View.GONE);
            if (done) {
                binding.tvWorkoutDone.setText(String.valueOf(row.amountDone()));
            }

            binding.getRoot().setOnClickListener(v -> {
                if (listener == null) return;
                // Tapping a completed row undoes it, which is the only way to
                // correct a mistap without leaving the screen.
                if (row.isDone()) listener.onUndo(row);
                else listener.onQuickDone(row);
            });
            binding.getRoot().setOnLongClickListener(v -> {
                if (listener != null && !row.isDone()) {
                    listener.onCustomAmount(row);
                    return true;
                }
                return false;
            });

            binding.getRoot().setContentDescription(
                    context.getString(R.string.cd_workout_toggle, name));
        }
    }
}
