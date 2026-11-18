package com.focuslife.app.ui.study;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.focuslife.app.R;
import com.focuslife.app.data.db.entity.StudyTopicEntity;
import com.focuslife.app.databinding.ItemStudyTopicBinding;
import com.focuslife.app.util.RevisionSchedule;
import com.focuslife.app.util.TimeUtils;

/**
 * Chapter-wise study topics.
 *
 * <p>Each row carries its own progress bar and its own revision state, because
 * the two decisions a user makes here — "how much of this chapter is left" and
 * "is it time to revise" — are answered visually rather than by reading
 * numbers.</p>
 */
public class StudyTopicAdapter
        extends ListAdapter<StudyTopicEntity, StudyTopicAdapter.TopicViewHolder> {

    public interface OnTopicAction {
        void onOpen(@NonNull StudyTopicEntity topic);

        void onEdit(@NonNull StudyTopicEntity topic);

        void onToggleComplete(@NonNull StudyTopicEntity topic);

        void onRevise(@NonNull StudyTopicEntity topic);
    }

    @Nullable
    private OnTopicAction listener;

    public StudyTopicAdapter() {
        super(DIFF);
    }

    public void setListener(@Nullable OnTopicAction listener) {
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<StudyTopicEntity> DIFF =
            new DiffUtil.ItemCallback<StudyTopicEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull StudyTopicEntity a, @NonNull StudyTopicEntity b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull StudyTopicEntity a, @NonNull StudyTopicEntity b) {
                    return a.completed == b.completed
                            && a.studiedMinutes == b.studiedMinutes
                            && a.targetMinutes == b.targetMinutes
                            && a.revisionsDone == b.revisionsDone
                            && a.nextRevisionAt == b.nextRevisionAt
                            && a.confidence == b.confidence
                            && a.chapter.equals(b.chapter)
                            && a.subject.equals(b.subject)
                            && a.exam.equals(b.exam);
                }
            };

    @NonNull
    @Override
    public TopicViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemStudyTopicBinding rowBinding = ItemStudyTopicBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new TopicViewHolder(rowBinding);
    }

    @Override
    public void onBindViewHolder(@NonNull TopicViewHolder holder, int position) {
        holder.bind(getItem(position), listener);
    }

    static class TopicViewHolder extends RecyclerView.ViewHolder {

        private final ItemStudyTopicBinding binding;

        TopicViewHolder(@NonNull ItemStudyTopicBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull StudyTopicEntity topic, @Nullable OnTopicAction listener) {
            var context = binding.getRoot().getContext();

            binding.tvTopicChapter.setText(topic.chapter);

            String meta = topic.exam + "  " + context.getString(R.string.separator_dot)
                    + "  " + topic.subject + "  " + context.getString(R.string.separator_dot)
                    + "  " + topic.studiedMinutes + " / " + topic.targetMinutes + " "
                    + context.getString(R.string.label_minutes_short);
            binding.tvTopicMeta.setText(meta);

            int percent = topic.targetMinutes <= 0
                    ? (topic.completed ? 100 : 0)
                    : Math.min(100, Math.round(topic.studiedMinutes * 100f / topic.targetMinutes));
            if (topic.completed) percent = 100;
            binding.pbTopic.setProgress(percent);
            binding.pbTopic.getProgressDrawable().setTint(context.getColor(
                    topic.completed ? R.color.success_500 : R.color.accent_500));

            binding.ivTopicStatus.setImageResource(topic.completed
                    ? R.drawable.ic_check_circle_filled
                    : R.drawable.ic_radio_unchecked);
            binding.ivTopicStatus.setImageTintList(android.content.res.ColorStateList.valueOf(
                    context.getColor(topic.completed ? R.color.success_500 : R.color.text_tertiary)));

            binding.btnTopicComplete.setText(topic.completed
                    ? R.string.action_undo
                    : R.string.study_mark_complete);

            // The revision button only appears when a revision is actually due.
            // A permanently visible "revise" button is noise on every completed
            // topic and trains the user to ignore it.
            boolean due = RevisionSchedule.isDue(topic.nextRevisionAt, System.currentTimeMillis());
            binding.btnTopicRevise.setVisibility(due ? View.VISIBLE : View.GONE);

            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onOpen(topic);
            });
            binding.btnTopicEdit.setOnClickListener(v -> {
                if (listener != null) listener.onEdit(topic);
            });
            binding.btnTopicComplete.setOnClickListener(v -> {
                if (listener != null) listener.onToggleComplete(topic);
            });
            binding.btnTopicRevise.setOnClickListener(v -> {
                if (listener != null) listener.onRevise(topic);
            });
        }
    }

    /** Formats a topic's progress for use outside the list, e.g. in a toast. */
    @NonNull
    public static String describe(@NonNull android.content.Context context,
                                  @NonNull StudyTopicEntity topic) {
        return topic.chapter + " · " + TimeUtils.formatMinutes(topic.studiedMinutes);
    }
}
