package br.com.iptvcaseiro.util;

import br.com.iptvcaseiro.data.Channel;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class M3uParser {
    public static final int MAX_BYTES = 50 * 1024 * 1024;
    public static final int MAX_CHANNELS = 50_000;
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w-]+)=\\\"([^\\\"]*)\\\"");

    private M3uParser() {}

    public static List<Channel> parse(InputStream input) throws IOException {
        byte[] content = readBounded(input);
        String text = decode(content);
        if (!text.replaceFirst("^[\\uFEFF\\s]+", "").startsWith("#EXTM3U")) {
            throw new IOException("O endereço não retornou uma playlist M3U válida.");
        }
        List<Channel> result = new ArrayList<>();
        Set<String> seenSources = new HashSet<>();
        String metadata = null;
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF:")) {
                    metadata = line;
                } else if (!line.isEmpty() && !line.startsWith("#") && metadata != null) {
                    if ((line.startsWith("http://") || line.startsWith("https://")) && seenSources.add(line)) {
                        result.add(from(metadata, line));
                        if (result.size() >= MAX_CHANNELS) break;
                    }
                    metadata = null;
                }
            }
        }
        if (result.isEmpty()) throw new IOException("Nenhum canal HTTP ou HTTPS válido foi encontrado.");
        return result;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_BYTES) throw new IOException("A playlist ultrapassa o limite de 50 MB.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String decode(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException ignored) {
            return new String(content, StandardCharsets.ISO_8859_1);
        }
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
