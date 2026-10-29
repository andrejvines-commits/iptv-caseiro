package br.com.iptvcaseiro.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Aggregate viewing time retained independently of resume progress. */
@Entity(tableName = "watch_history")
public class WatchHistory {
    @PrimaryKey
    @NonNull
    public String source = "";
    public String name = "";
    public String category = "Sem categoria";
    public String logoUrl = "";
    public String sourceType = "STREAM";
    /** CHANNEL for live/video items; SERIES for Xtream episodes. */
    public String contentKind = "CHANNEL";
    public long watchedMs;
    public long lastWatchedAt = System.currentTimeMillis();
}
