package br.com.iptvcaseiro.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Channel.class, PlaybackProgress.class}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase instance;
    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS playback_progress (source TEXT NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, logoUrl TEXT NOT NULL, sourceType TEXT NOT NULL, positionMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(source))");
        }
    };

    public abstract ChannelDao channelDao();

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                        context.getApplicationContext(), AppDatabase.class, "iptv-caseiro.db"
                    ).addMigrations(MIGRATION_1_2).build();
                }
            }
        }
        return instance;
    }
}
