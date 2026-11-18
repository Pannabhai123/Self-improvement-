package com.focuslife.app.data.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.focuslife.app.data.db.dao.BlockerDao;
import com.focuslife.app.data.db.dao.ControlDao;
import com.focuslife.app.data.db.dao.FitnessDao;
import com.focuslife.app.data.db.dao.FocusSessionDao;
import com.focuslife.app.data.db.dao.GoalDao;
import com.focuslife.app.data.db.dao.HabitDao;
import com.focuslife.app.data.db.dao.RoutineDao;
import com.focuslife.app.data.db.dao.StudyDao;
import com.focuslife.app.data.db.dao.TaskDao;
import com.focuslife.app.data.db.dao.UsageDao;
import com.focuslife.app.data.db.entity.AppUsageSnapshotEntity;
import com.focuslife.app.data.db.entity.BlockScheduleEntity;
import com.focuslife.app.data.db.entity.BlockedAppEntity;
import com.focuslife.app.data.db.entity.DailySummaryEntity;
import com.focuslife.app.data.db.entity.DistractionLogEntity;
import com.focuslife.app.data.db.entity.FocusSessionEntity;
import com.focuslife.app.data.db.entity.GoalEntity;
import com.focuslife.app.data.db.entity.HabitEntity;
import com.focuslife.app.data.db.entity.HabitLogEntity;
import com.focuslife.app.data.db.entity.ReasonEntity;
import com.focuslife.app.data.db.entity.RoutineSlotEntity;
import com.focuslife.app.data.db.entity.RunLogEntity;
import com.focuslife.app.data.db.entity.StudyLogEntity;
import com.focuslife.app.data.db.entity.StudyTopicEntity;
import com.focuslife.app.data.db.entity.TaskEntity;
import com.focuslife.app.data.db.entity.WorkoutEntity;
import com.focuslife.app.data.db.entity.WorkoutLogEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The single local database. Everything Focus Life knows lives here — there is
 * no network layer at all, so the app is offline-first by construction rather
 * than by fallback.
 *
 * <p>Built with {@code fallbackToDestructiveMigration} disabled and an explicit
 * {@link DatabaseSeeder} callback so that a schema change can never silently
 * wipe a user's streaks and history.</p>
 */
@Database(
        entities = {
                FocusSessionEntity.class,
                TaskEntity.class,
                StudyTopicEntity.class,
                StudyLogEntity.class,
                HabitEntity.class,
                HabitLogEntity.class,
                BlockedAppEntity.class,
                BlockScheduleEntity.class,
                RunLogEntity.class,
                WorkoutEntity.class,
                WorkoutLogEntity.class,
                DistractionLogEntity.class,
                ReasonEntity.class,
                RoutineSlotEntity.class,
                GoalEntity.class,
                AppUsageSnapshotEntity.class,
                DailySummaryEntity.class
        },
        version = 1,
        exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {

    public static final String NAME = "focuslife.db";

    /**
     * Two threads: enough for the accessibility service's synchronous block
     * check to run concurrently with UI queries, small enough to stay inside
     * the 4 GB device budget.
     */
    private static final int IO_THREADS = 2;

    public abstract FocusSessionDao focusSessionDao();

    public abstract TaskDao taskDao();

    public abstract StudyDao studyDao();

    public abstract HabitDao habitDao();

    public abstract BlockerDao blockerDao();

    public abstract FitnessDao fitnessDao();

    public abstract ControlDao controlDao();

    public abstract RoutineDao routineDao();

    public abstract GoalDao goalDao();

    public abstract UsageDao usageDao();

    // ------------------------------------------------------------------
    //  Singleton
    // ------------------------------------------------------------------

    private static volatile AppDatabase instance;
    private static final ExecutorService IO = Executors.newFixedThreadPool(IO_THREADS);

    @NonNull
    public static AppDatabase get(@NonNull Context context) {
        AppDatabase local = instance;
        if (local == null) {
            synchronized (AppDatabase.class) {
                local = instance;
                if (local == null) {
                    local = build(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private static AppDatabase build(Context appContext) {
        return Room.databaseBuilder(appContext, AppDatabase.class, NAME)
                // Never destructively migrate: user history is the product.
                .addCallback(new Callback() {
                    @Override
                    public void onCreate(@NonNull SupportSQLiteDatabase db) {
                        super.onCreate(db);
                        // Seed on the IO pool; onCreate is called on the thread that
                        // first opens the database, which may be the main thread.
                        IO.execute(() -> DatabaseSeeder.seedFirstLaunch(appContext));
                    }
                })
                .build();
    }

    /** Shared IO executor so every repository avoids the main thread. */
    @NonNull
    public static ExecutorService io() {
        return IO;
    }

    /** Test hook: a fresh in-memory database with seeding disabled. */
    @VisibleForTesting
    @NonNull
    public static AppDatabase inMemory(@NonNull Context context) {
        return Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    /** Test hook: reset the singleton. */
    @VisibleForTesting
    public static void resetInstance() {
        instance = null;
    }
}
