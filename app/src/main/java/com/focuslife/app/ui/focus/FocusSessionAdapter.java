package com.focuslife.app.ui.focus;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.focuslife.app.R;
import com.focuslife.app.data.db.entity.FocusSessionEntity;
import com.focuslife.app.databinding.ItemFocusSessionBinding;
import com.focuslife.app.util.TimeUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Completed focus sessions.
 *
 * <p>{@link ListAdapter} with a {@link DiffUtil} callback rather than
 * {@code notifyDataSetChanged}: the list is refreshed on every resume, and
 * animating only the rows that actually changed avoids a full rebind flash on a
 * device where frame budget is tight.</p>
 *
 * <p>An abandoned session is shown with a neutral marker and its real duration,
 * never as a failure. The four minutes someone managed before giving up are
 * still four minutes, and the UI treats them that way.</p>
 */
public class FocusSessionAdapter
        extends ListAdapter<FocusSessionEntity, FocusSessionAdapter.SessionViewHolder> {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    public FocusSessionAdapter() {
        super(DIFF);
    }

    private static final DiffUtil.ItemCallback<FocusSessionEntity> DIFF =
            new DiffUtil.ItemCallback<FocusSessionEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull FocusSessionEntity oldItem,
                                               @NonNull FocusSessionEntity newItem) {
                    return oldItem.id == newItem.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull FocusSessionEntity oldItem,
                                                  @NonNull FocusSessionEntity newItem) {
                    return oldItem.completed == newItem.completed
                            && oldItem.focusedSec == newItem.focusedSec
                            && oldItem.blockHits == newItem.blockHits
                            && oldItem.taskLabel.equals(newItem.taskLabel)
                            && oldItem.startTime == newItem.startTime;
                }
            };

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemFocusSessionBinding b = ItemFocusSessionBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new SessionViewHolder(b);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {

        private final ItemFocusSessionBinding binding;

        SessionViewHolder(@NonNull ItemFocusSessionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull FocusSessionEntity session) {
            var context = binding.getRoot().getContext();

            String task = session.taskLabel == null || session.taskLabel.isEmpty()
                    ? context.getString(R.string.quick_deep_focus)
                    : session.taskLabel;
            binding.tvSessionTask.setText(task);

            int minutes = Math.max(1, session.focusedSec / 60);
            binding.tvSessionDuration.setText(TimeUtils.formatMinutes(minutes));

            StringBuilder meta = new StringBuilder();
            meta.append(TimeUtils.formatMinutes(session.durationSec / 60));
            meta.append("  ").append(context.getString(R.string.separator_dot)).append("  ");
            meta.append(TIME_FORMAT.format(new Date(session.startTime)));

            if (session.blockHits > 0) {
                meta.append("  ").append(context.getString(R.string.separator_dot)).append("  ");
                meta.append(session.blockHits == 1
                        ? context.getString(R.string.focus_block_hits_during, session.blockHits)
                        : context.getString(R.string.focus_block_hits_during_plural, session.blockHits));
            }
            binding.tvSessionMeta.setText(meta.toString());

            // Completed sessions get the accent check; abandoned ones get a muted
            // timer. Same size, same weight — only the colour differs, so a short
            // session does not look visually "worse" in the list.
            binding.ivStatus.setImageResource(session.completed
                    ? R.drawable.ic_check_circle_filled
                    : R.drawable.ic_timer);
            binding.ivStatus.setImageTintList(android.content.res.ColorStateList.valueOf(
                    context.getColor(session.completed
                            ? R.color.success_500
                            : R.color.text_tertiary)));

            binding.tvSessionDuration.setTextColor(context.getColor(session.completed
                    ? R.color.accent_500
                    : R.color.text_secondary));
        }
    }
}
