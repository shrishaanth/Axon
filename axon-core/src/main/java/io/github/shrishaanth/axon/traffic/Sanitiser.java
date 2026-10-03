package io.github.shrishaanth.axon.traffic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.Body;
import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.Parameter;
import io.github.shrishaanth.axon.spec.Response;
import io.github.shrishaanth.axon.spec.Schema;
import io.github.shrishaanth.axon.util.FieldPath;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a raw HTTP exchange into a {@link TrafficEvent}: shapes are kept, values are dropped. Meant to run where
 * the traffic was captured (CLI or browser) so that raw bodies never reach a server.
 *
 * <p>With a spec, the request is matched locally and only the operation key leaves the machine. Without one, the
 * path is kept with value-like segments replaced by <code>{*}</code>; that is a heuristic, and a short word used
 * as an identifier (a username in the path) will survive it.
 */
public final class Sanitiser {

    /**
     * @param mode   {@code header}, {@code api_key_hash} or {@code none}
     * @param header header that identifies the client; for {@code api_key_hash} defaults to Authorization
     * @param salt   per-workspace secret mixed into the hash
     */
    public record Config(String mode, String header, String salt) {
        public static Config none() {
            return new Config("none", null, "");
        }
    }

    /**
     * @param url             full URL or just path and query
     * @param requestHeaders  header names in any case
     */
    public record RawExchange(Instant ts, String method, String url, Map<String, String> requestHeaders,
                              String requestContentType, String requestBody, int status,
                              String responseContentType, String responseBody, Double latencyMs) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern HEX = Pattern.compile("^(?=.*[0-9])[0-9a-fA-F]{8,}$");

    private final Config config;
    private final Matcher matcher;
    private String timeHeader;

    /** {@code spec} may be null. */
    public Sanitiser(Config config, ApiSpec spec) {
        this.config = config;
        this.matcher = spec == null ? null : new Matcher(spec);
    }

    /**
     * Takes each event's time from this request header (an ISO-8601 instant) instead of the capture time. For
     * replayed or simulated traffic whose real send time is not the time it stands for.
     */
    public Sanitiser withTimeHeader(String header) {
        this.timeHeader = header;
        return this;
    }

    private Instant time(RawExchange raw) {
        if (timeHeader != null && raw.requestHeaders() != null) {
            for (Map.Entry<String, String> e : raw.requestHeaders().entrySet()) {
                if (e.getKey().equalsIgnoreCase(timeHeader)) {
                    try {
                        return Instant.parse(e.getValue().trim());
                    } catch (java.time.format.DateTimeParseException ex) {
                        return raw.ts();
                    }
                }
            }
        }
        return raw.ts();
    }

    public TrafficEvent sanitise(RawExchange raw) {
        String pathAndQuery = pathAndQuery(raw.url());
        int q = pathAndQuery.indexOf('?');
        String path = q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        String queryString = q < 0 ? "" : pathAndQuery.substring(q + 1);
        String method = raw.method().toUpperCase(Locale.ROOT);

        Operation op = matcher == null ? null : matcher.match(method, path);
        Schema requestSchema = null;
        Schema responseSchema = null;
        if (op != null) {
            Body body = op.requestBody();
            requestSchema = body == null ? null : body.jsonSchema();
            Response response = op.responseFor(raw.status());
            responseSchema = response == null ? null : response.body().jsonSchema();
        }

        ShapeExtractor.Shape request = shape(raw.requestContentType(), raw.requestBody(), requestSchema);
        ShapeExtractor.Shape response = shape(raw.responseContentType(), raw.responseBody(), responseSchema);
        return new TrafficEvent(
                time(raw),
                method,
                op == null ? null : op.key(),
                op == null ? redact(path) : null,
                raw.status(),
                raw.latencyMs(),
                client(raw.requestHeaders()),
                query(queryString, op),
                request == null ? null : request.fields(),
                response == null ? null : response.fields(),
                (request != null && request.truncated()) || (response != null && response.truncated()));
    }

    private static ShapeExtractor.Shape shape(String contentType, String body, Schema schema) {
        if (body == null || body.isBlank()) {
            return null;
        }
        boolean declaredJson = contentType != null && Body.isJson(contentType) && !contentType.contains("*");
        char first = body.stripLeading().charAt(0);
        if (!declaredJson && first != '{' && first != '[') {
            return null;
        }
        try {
            JsonNode tree = JSON.readTree(body);
            return tree == null ? null : ShapeExtractor.extract(tree, schema);
        } catch (Exception e) {
            return null; // not JSON after all: the body is simply not analysed
        }
    }

    private static List<TrafficEvent.Field> query(String queryString, Operation op) {
        Map<String, List<String>> seen = new LinkedHashMap<>();
        if (!queryString.isEmpty()) {
            for (String pair : queryString.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String name = decode(eq < 0 ? pair : pair.substring(0, eq));
                String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
                seen.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
            }
        }
        List<TrafficEvent.Field> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : seen.entrySet()) {
            List<String> values = null;
            Parameter declared = op == null ? null : op.parameter("query", e.getKey());
            if (declared != null && declared.schema() != null) {
                Schema s = declared.schema();
                boolean isEnum = s.enumValues() != null || (s.items() != null && s.items().enumValues() != null);
                if (isEnum) {
                    values = e.getValue().stream().distinct().limit(TrafficEvent.MAX_VALUES).toList();
                }
            }
            // a query string carries text only, so the observed type is always string
            out.add(new TrafficEvent.Field(FieldPath.child(FieldPath.ROOT, e.getKey()),
                    EnumSet.of(JsonType.STRING), values));
        }
        return out;
    }

    private String client(Map<String, String> headers) {
        if (headers == null || config.mode().equals("none")) {
            return null;
        }
        String wanted = config.header();
        if (wanted == null || wanted.isBlank()) {
            wanted = config.mode().equals("api_key_hash") ? "authorization" : null;
        }
        if (wanted == null) {
            return null;
        }
        String value = null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(wanted)) {
                value = e.getValue();
                break;
            }
        }
        if (value == null && config.mode().equals("api_key_hash")) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase("x-api-key")) {
                    value = e.getValue();
                    break;
                }
            }
        }
        return value == null || value.isEmpty() ? null : pseudonym(config.salt(), value);
    }

    /** {@code hex(sha256(salt || value))[0:16]}: a stable pseudonym, not anonymisation. */
    public static String pseudonym(String salt, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replaces value-like path segments by <code>{*}</code>. */
    public static String redact(String path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                out.append('/').append(valueLike(segment) ? Matcher.REDACTED : segment);
            }
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    private static boolean valueLike(String segment) {
        return DIGITS.matcher(segment).matches()
                || UUID.matcher(segment).matches()
                || HEX.matcher(segment).matches()
                || segment.length() >= 20
                || segment.contains("@")
                || segment.contains("%");
    }

    private static String pathAndQuery(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url.startsWith("/") ? url : "/" + url;
        }
        int slash = url.indexOf('/', scheme + 3);
        if (slash < 0) {
            int q = url.indexOf('?', scheme + 3);
            return q < 0 ? "/" : "/" + url.substring(q);
        }
        return url.substring(slash);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
