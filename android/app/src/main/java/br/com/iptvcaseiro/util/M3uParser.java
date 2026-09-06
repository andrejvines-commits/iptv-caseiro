package br.com.iptvcaseiro.util;

import br.com.iptvcaseiro.data.Channel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class M3uParser {
    public static final int MAX_BYTES = 25 * 1024 * 1024;
    public static final int MAX_CHANNELS = 10_000;
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)=\\\"([^\\\"]*)\\\"");

    private M3uParser() {}

    public static List<Channel> parse(InputStream input) throws IOException {
        List<Channel> result = new ArrayList<>();
        int bytes = 0;
        String metadata = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (bytes > MAX_BYTES) throw new IOException("A playlist ultrapassa o limite de 25 MB.");
                line = line.trim();
                if (line.startsWith("#EXTINF:")) {
                    metadata = line;
                } else if (!line.isEmpty() && !line.startsWith("#") && metadata != null) {
                    if (result.size() >= MAX_CHANNELS) {
                        throw new IOException("A playlist ultrapassa o limite de 10.000 canais.");
                    }
                    if (line.startsWith("http://") || line.startsWith("https://")) {
                        result.add(from(metadata, line));
                    }
                    metadata = null;
                }
            }
        }
        if (result.isEmpty()) throw new IOException("Nenhum canal HTTP ou HTTPS válido foi encontrado.");
        return result;
    }

    private static Channel from(String metadata, String source) {
        Channel channel = new Channel();
        channel.source = source;
        int comma = metadata.indexOf(',');
        channel.name = comma >= 0 ? metadata.substring(comma + 1).trim() : "Canal";
        Matcher matcher = ATTRIBUTE.matcher(metadata);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2).trim();
            if ("tvg-name".equalsIgnoreCase(key) && !value.isEmpty()) channel.name = value;
            if ("group-title".equalsIgnoreCase(key) && !value.isEmpty()) channel.category = value;
            if ("tvg-logo".equalsIgnoreCase(key)) channel.logoUrl = value;
        }
        if (channel.name.isEmpty()) channel.name = "Canal";
        return channel;
    }
}
