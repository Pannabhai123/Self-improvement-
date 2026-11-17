package com.focuslife.app.ui.blocker;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.focuslife.app.databinding.ItemAppRowBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The installed-app list in the picker.
 *
 * <p>Selection state lives in a {@link Set} of package names held by the adapter,
 * not in the row objects. Filtering rebuilds the visible list on every keystroke,
 * and a set survives that without needing to re-derive which rows were checked.</p>
 *
 * <p>Deliberately not a {@code ListAdapter}: the whole list is replaced on each
 * filter pass, and {@code DiffUtil} over several hundred apps costs more than the
 * rebind it saves when the text changes on every character.</p>
 */
public class AppPickerAdapter extends RecyclerView.Adapter<AppPickerAdapter.AppViewHolder> {

    /** One installed, launchable app. */
    public static final class AppEntry {
        @NonNull
        public final String packageName;
        @NonNull
        public final String label;
        @Nullable
        public final Drawable icon;

        AppEntry(@NonNull String packageName, @NonNull String label, @Nullable Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    public interface OnSelectionChanged {
        void onSelectionChanged(int count);
    }

    @NonNull
    private final List<AppEntry> all = new ArrayList<>();

    @NonNull
    private final List<AppEntry> visible = new ArrayList<>();

    @NonNull
    private final Set<String> selected = new HashSet<>();

    @Nullable
    private OnSelectionChanged listener;

    private String query = "";

    public void setListener(@Nullable OnSelectionChanged listener) {
        this.listener = listener;
    }

    /** Replaces the whole app list, keeping any selection already made. */
    public void setApps(@Nullable List<AppEntry> apps) {
        all.clear();
        if (apps != null) all.addAll(apps);
        applyFilter();
    }

    /** Pre-selects packages, used to show what is already blocked. */
    public void setSelected(@Nullable Set<String> packages) {
        selected.clear();
        if (packages != null) selected.addAll(packages);
        notifyDataSetChanged();
        notifyCount();
    }

    public void setQuery(@Nullable String query) {
        String next = query == null ? "" : query.trim().toLowerCase(java.util.Locale.getDefault());
        if (next.equals(this.query)) return;
        this.query = next;
        applyFilter();
    }

    private void applyFilter() {
        visible.clear();
        if (query.isEmpty()) {
            visible.addAll(all);
        } else {
            for (AppEntry entry : all) {
                // Matching on the package name as well as the label matters for
                // apps whose display name is localised or is a brand the user
                // knows by a different name ("FF" for Free Fire).
                if (entry.label.toLowerCase(java.util.Locale.getDefault()).contains(query)
                        || entry.packageName.contains(query)) {
                    visible.add(entry);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    public Set<String> getSelected() {
        return new HashSet<>(selected);
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public boolean isSelected(@NonNull String packageName) {
        return selected.contains(packageName);
    }

    public void select(@NonNull String packageName, boolean select) {
        boolean changed = select ? selected.add(packageName) : selected.remove(packageName);
        if (changed) notifyCount();
    }

    /** Adds every currently visible app to the selection. */
    public int selectAllVisible() {
        int added = 0;
        for (AppEntry entry : visible) {
            if (selected.add(entry.packageName)) added++;
        }
        if (added > 0) {
            notifyDataSetChanged();
            notifyCount();
        }
        return added;
    }

    private void notifyCount() {
        if (listener != null) listener.onSelectionChanged(selected.size());
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAppRowBinding rowBinding = ItemAppRowBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new AppViewHolder(rowBinding);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppEntry entry = visible.get(position);
        holder.bind(entry, selected.contains(entry.packageName), (app, checked) -> {
            boolean changed = checked ? selected.add(app.packageName)
                    : selected.remove(app.packageName);
            if (changed) notifyCount();
        });
    }

    interface OnCheckChanged {
        void onCheck(@NonNull AppEntry entry, boolean checked);
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {

        private final ItemAppRowBinding binding;

        AppViewHolder(@NonNull ItemAppRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull AppEntry entry, boolean checked, @NonNull OnCheckChanged onCheck) {
            binding.tvAppLabel.setText(entry.label);
            binding.tvAppPackage.setText(entry.packageName);

            if (entry.icon != null) {
                binding.ivAppIcon.setImageDrawable(entry.icon);
            } else {
                binding.ivAppIcon.setImageResource(
                        com.focuslife.app.R.drawable.ic_phone_android);
            }

            // Detach first: a recycled row would otherwise fire the listener with
            // the previous app's package name when setChecked animates.
            binding.checkApp.setOnCheckedChangeListener(null);
            binding.checkApp.setChecked(checked);
            binding.checkApp.setOnCheckedChangeListener((view, isChecked) ->
                    onCheck.onCheck(entry, isChecked));

            binding.getRoot().setOnClickListener(v -> binding.checkApp.toggle());
        }
    }
}
