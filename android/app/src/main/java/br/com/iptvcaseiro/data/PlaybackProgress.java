package br.com.iptvcaseiro.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "playback_progress")
public class PlaybackProgress {
    @PrimaryKey
    public String source = "";
    public String name = "";
    public String category = "Sem categoria";
    public String logoUrl = "";
    public String sourceType = "STREAM";
    public long positionMs;
    public long durationMs;
    public long updatedAt = System.currentTimeMillis();
}
