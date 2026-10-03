package io.github.shrishaanth.axon.traffic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the entries of a HAR 1.2 file as raw exchanges, ready for the {@link Sanitiser}. */
public final class HarReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HarReader() {
    }

    /**
     * @param skipped entries that could not be read (no method, URL or timestamp), counted rather than hidden
     */
    public record Result(List<Sanitiser.RawExchange> exchanges, int skipped) {
    }

    public static Result read(Path file) throws IOException {
        return read(JSON.readTree(file.toFile()));
    }

    public static Result read(JsonNode har) {
        List<Sanitiser.RawExchange> out = new ArrayList<>();
        int skipped = 0;
        for (JsonNode entry : har.path("log").path("entries")) {
            JsonNode request = entry.path("request");
            JsonNode response = entry.path("response");
            String method = request.path("method").asText(null);
            String url = request.path("url").asText(null);
            Instant ts = instant(entry.path("startedDateTime").asText(null));
            if (method == null || url == null || ts == null || !response.path("status").isInt()) {
                skipped++;
                continue;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (JsonNode h : request.path("headers")) {
                headers.putIfAbsent(h.path("name").asText(""), h.path("value").asText(""));
            }
            JsonNode post = request.path("postData");
            JsonNode content = response.path("content");
            String responseBody = content.path("text").asText(null);
            if (responseBody != null && "base64".equals(content.path("encoding").asText())) {
                try {
                    responseBody = new String(Base64.getDecoder().decode(responseBody), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    responseBody = null;
                }
            }
            JsonNode time = entry.path("time");
            out.add(new Sanitiser.RawExchange(ts, method, url, headers,
                    post.path("mimeType").asText(null), post.path("text").asText(null),
                    response.get("status").asInt(), content.path("mimeType").asText(null), responseBody,
                    time.isNumber() && time.asDouble() >= 0 ? time.asDouble() : null));
        }
        return new Result(out, skipped);
    }

    private static Instant instant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(text);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
