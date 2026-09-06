package br.com.iptvcaseiro.util;

import static org.junit.Assert.assertEquals;

import br.com.iptvcaseiro.data.Channel;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class M3uParserTest {
    @Test public void parsesMetadataAndKeepsPrivateUrl() throws Exception {
        String content = "#EXTM3U\n#EXTINF:-1 tvg-name=\"Canal A\" group-title=\"Filmes\" tvg-logo=\"https://img/logo.png\",Outro\n" +
            "https://usuario:senha@exemplo.test/live.m3u8?token=secreto\n";
        List<Channel> channels = M3uParser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, channels.size());
        assertEquals("Canal A", channels.get(0).name);
        assertEquals("Filmes", channels.get(0).category);
        assertEquals("https://usuario:senha@exemplo.test/live.m3u8?token=secreto", channels.get(0).source);
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsPlaylistWithoutPlayableUrls() throws Exception {
        M3uParser.parse(new ByteArrayInputStream("#EXTM3U\n#EXTINF:-1,Inválido\nfile:///video.mp4".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void acceptsLatin1AndSkipsDuplicateSources() throws Exception {
        String content = "#EXTM3U\n#EXTINF:-1 group-title=\"Notícias\",Televisão\nhttps://example.test/live\n" +
            "#EXTINF:-1,Repetido\nhttps://example.test/live\n";
        List<Channel> channels = M3uParser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(1, channels.size());
        assertEquals("Televisão", channels.get(0).name);
        assertEquals("Notícias", channels.get(0).category);
    }
}
