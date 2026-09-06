package br.com.iptvcaseiro.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY favorite DESC, category COLLATE NOCASE, name COLLATE NOCASE")
    List<Channel> all();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(Channel channel);

    @Update
    void update(Channel channel);

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    int deleteIds(List<Long> ids);

    @Query("UPDATE channels SET active = :active WHERE id IN (:ids)")
    int setActive(List<Long> ids, boolean active);

    @Query("DELETE FROM channels")
    void deleteAll();
}
