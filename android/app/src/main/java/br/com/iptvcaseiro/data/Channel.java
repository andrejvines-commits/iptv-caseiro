package br.com.iptvcaseiro.data;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "channels", indices = {@Index(value = {"source"}, unique = true)})
public class Channel {
    @PrimaryKey(autoGenerate = true) public long id;
    public String name = "";
    public String description = "";
    public String category = "Sem categoria";
    public String logoUrl = "";
    public String sourceType = "STREAM";
    public String source = "";
    public boolean active = true;
    public boolean favorite = false;
    public long createdAt = System.currentTimeMillis();

    public Channel() {}
}
