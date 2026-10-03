package io.github.shrishaanth.axon.eval.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.eval.Truth;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Thirteen client programs for the demo service. Each sends real HTTP requests (through a recording proxy when
 * one is given) and records, as ground truth, exactly what it sent and which response fields its code read.
 *
 * <p>Requests are real; their timestamps are not. Nobody can wait sixty days for a capture, so every request
 * carries its simulated time in the {@code X-Demo-Time} header and the sanitiser is told to use it.
 *
 * <p>Usage: {@code demo-clients <service url> <truth.json> [proxy host:port] [--days 60] [--seed 1]}
 */
public final class DemoClients {

    public static final String CLIENT_HEADER = "X-Client-Id";
    public static final String TIME_HEADER = "X-Demo-Time";
    /** The simulated "now": the end of the observation window. */
    public static final Instant END = Instant.parse("2026-09-30T23:00:00Z");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String base;
    private final Truth truth = new Truth();
    private final Random random;
    private int requests;

    private DemoClients(String base, String proxy, long seed) {
        HttpClient.Builder b = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10));
        if (proxy != null) {
            String[] hp = proxy.split(":");
            b.proxy(ProxySelector.of(new InetSocketAddress(hp[0], Integer.parseInt(hp[1]))));
        }
        this.http = b.build();
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.random = new Random(seed);
    }

    /** One client acting at one simulated instant. */
    private final class Session {
        final String client;
        final Instant now;

        Session(String client, Instant now) {
            this.client = client;
            this.now = now;
        }

        Reply call(String method, String operation, String path, Map<String, String> query, ObjectNode body) {
            try {
                StringBuilder url = new StringBuilder(base).append(path);
                char sep = '?';
                for (Map.Entry<String, String> q : query.entrySet()) {
                    url.append(sep).append(q.getKey()).append('=').append(q.getValue());
                    sep = '&';
                }
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url.toString()))
                        .timeout(Duration.ofSeconds(30))
                        .header(CLIENT_HEADER, client)
                        .header(TIME_HEADER, now.toString());
                if (body != null) {
                    rb.header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
                } else {
                    rb.method(method, HttpRequest.BodyPublishers.noBody());
                }
                HttpResponse<String> response = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                requests++;
                if (operation != null) {
                    truth.call(client, operation, now, response.statusCode(), body, query);
                }
                JsonNode parsed = null;
                if (response.body() != null && !response.body().isBlank()) {
                    try {
                        parsed = JSON.readTree(response.body());
                    } catch (Exception e) {
                        parsed = null;
                    }
                }
                return new Reply(this, operation, response.statusCode(), parsed);
            } catch (Exception e) {
                throw new IllegalStateException(method + " " + path + " failed: " + e, e);
            }
        }

        Reply get(String operation, String path, Map<String, String> query) {
            return call("GET", operation, path, query, null);
        }

        Reply get(String operation, String path) {
            return call("GET", operation, path, Map.of(), null);
        }
    }

    /** A response whose field accesses are recorded: this is how "who reads what" becomes ground truth. */
    private final class Reply {
        final Session session;
        final String operation;
        final int status;
        final JsonNode body;

        Reply(Session session, String operation, int status, JsonNode body) {
            this.session = session;
            this.operation = operation;
            this.status = status;
            this.body = body;
        }

        /** Reads fields by path ({@code $.items[].title}); for arrays, the first element. */
        void read(String... paths) {
            if (operation == null) {
                return;
            }
            for (String path : paths) {
                truth.read(session.client, operation, status, path);
                JsonNode node = body;
                for (String step : path.substring(1).split("(?=\\[\\])|\\.")) {
                    if (node == null || step.isEmpty()) {
                        continue;
                    }
                    node = step.equals("[]") ? node.get(0) : node.get(step);
                }
            }
        }
    }

    private record Client(String name, int fromDaysAgo, int toDaysAgo, int everyDays, int sessionsPerDay,
                          Consumer<Session> behaviour) {
    }

    private ObjectNode recipe(boolean servings, boolean notes, Object minutes, String difficulty) {
        ObjectNode r = JSON.createObjectNode();
        r.put("title", "Recipe " + random.nextInt(1000));
        if (minutes instanceof Integer i) {
            r.put("minutes", i);
        } else if (minutes instanceof Double d) {
            r.put("minutes", d);
        }
        if (servings) {
            r.put("servings", 2 + random.nextInt(4));
        }
        if (difficulty != null) {
            r.put("difficulty", difficulty);
        }
        r.putArray("tags").add("veg");
        if (notes) {
            r.put("notes", "family recipe");
        }
        return r;
    }

    private String seedId() {
        return Integer.toString(1 + random.nextInt(12));
    }

    private <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    private List<Client> clients() {
        Consumer<Session> web = s -> {
            s.get("GET /recipes", "/recipes", ordered("limit", "10", "sort", "rating"))
                    .read("$.items[].id", "$.items[].title", "$.items[].rating_avg", "$.items[].tags");
            s.get("GET /recipes/{id}", "/recipes/" + seedId())
                    .read("$.id", "$.title", "$.minutes", "$.servings", "$.difficulty");
            if (random.nextInt(4) == 0) {
                s.call("POST", "POST /recipes", "/recipes", Map.of(),
                        recipe(true, false, 20 + random.nextInt(40), pick(List.of("easy", "medium", "hard"))))
                        .read("$.id");
            }
            if (random.nextInt(3) == 0) {
                ObjectNode rating = JSON.createObjectNode().put("stars", 1 + random.nextInt(5)).put("comment", "ok");
                s.call("POST", "POST /recipes/{id}/ratings", "/recipes/" + seedId() + "/ratings", Map.of(), rating)
                        .read("$.id");
            }
        };
        return List.of(
                new Client("web-app", 59, 0, 1, 30, web),
                new Client("web-app-canary", 10, 0, 1, 5, web),
                new Client("ios-4.2", 59, 0, 1, 12, s -> {
                    s.get("GET /recipes/featured", "/recipes/featured").read("$.id", "$.title", "$.minutes");
                    if (random.nextInt(3) == 0) {
                        s.call("POST", "POST /recipes", "/recipes", Map.of(), recipe(true, false, 30, "easy"))
                                .read("$.id", "$.title");
                    }
                }),
                // the old app: still sends "notes", never sends "servings", reads the legacy slug
                new Client("ios-3.9", 59, 3, 1, 4, s -> {
                    s.get("GET /recipes/{id}", "/recipes/" + seedId()).read("$.id", "$.title", "$.legacy_slug");
                    s.call("POST", "POST /recipes", "/recipes", Map.of(),
                            recipe(false, true, 45, pick(List.of("easy", "expert")))).read("$.id");
                }),
                new Client("android-5", 59, 0, 1, 10, s -> {
                    s.get("GET /recipes", "/recipes", ordered("limit", "5")).read("$.items[].id", "$.items[].title");
                    String id = seedId();
                    s.get("GET /recipes/{id}/ratings", "/recipes/" + id + "/ratings").read("$[].stars", "$[].comment");
                    s.call("POST", "POST /recipes/{id}/ratings", "/recipes/" + id + "/ratings", Map.of(),
                            JSON.createObjectNode().put("stars", 1 + random.nextInt(5))).read("$.id", "$.stars");
                }),
                // stopped twelve days ago: fractional minutes, "expert", reads the slug from the create response
                new Client("android-4", 59, 12, 1, 3, s -> s.call("POST", "POST /recipes", "/recipes", Map.of(),
                        recipe(true, false, 12.5, "expert")).read("$.id", "$.legacy_slug")),
                new Client("partner-acme", 59, 0, 1, 2, s -> s.get("GET /export/recipes", "/export/recipes",
                                ordered("since", "2026-01-01"))
                        .read("$.recipes[].id", "$.recipes[].title", "$.recipes[].legacy_slug")),
                new Client("partner-globex", 59, 0, 1, 1, s -> s.get("GET /recipes", "/recipes",
                                ordered("tag", "veg", "sort", "newest"))
                        .read("$.items[].id", "$.items[].legacy_slug")),
                new Client("cron-nightly", 59, 0, 1, 1, s -> {
                    s.get(null, "/healthz");
                    s.get("GET /export/recipes", "/export/recipes").read("$.recipes[].id", "$.generated_at");
                }),
                new Client("admin-tool", 58, 4, 6, 2, s -> {
                    ObjectNode update = recipe(true, true, 25, "medium");
                    s.call("PUT", "PUT /recipes/{id}", "/recipes/" + seedId(), Map.of(), update).read("$.id");
                    s.call("DELETE", "DELETE /recipes/{id}", "/recipes/" + (13 + random.nextInt(50)), Map.of(), null);
                }),
                // three bursts, the last one 41 days ago
                new Client("importer-script", 55, 41, 7, 20, s -> s.call("POST", "POST /recipes", "/recipes",
                        Map.of(), recipe(false, true, 7.5, "expert")).read("$.id")),
                // sends an undocumented parameter and reads an undocumented field
                new Client("analytics-bot", 59, 0, 1, 40, s -> s.get("GET /recipes", "/recipes",
                                ordered("limit", "20", "debug", "1"))
                        .read("$.items[].rating_avg", "$.items[].internal_score")),
                // one pass over the API thirty days ago; reads nothing
                new Client("qa-smoke", 30, 30, 1, 1, s -> {
                    s.get("GET /recipes", "/recipes");
                    s.get("GET /recipes/featured", "/recipes/featured");
                    s.get("GET /recipes/{id}", "/recipes/1");
                    s.get("GET /recipes/{id}", "/recipes/99999");
                    s.get("GET /recipes/{id}/ratings", "/recipes/1/ratings");
                    s.get("GET /export/recipes", "/export/recipes");
                }));
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private void run(int days) {
        for (Client c : clients()) {
            for (int daysAgo = Math.min(c.fromDaysAgo(), days - 1); daysAgo >= c.toDaysAgo(); daysAgo -= c.everyDays()) {
                for (int i = 0; i < c.sessionsPerDay(); i++) {
                    Instant when = END.minus(Duration.ofDays(daysAgo)).minusSeconds(random.nextInt(80_000));
                    c.behaviour().accept(new Session(c.name(), when));
                }
            }
            System.err.println(c.name() + " done, " + requests + " requests so far");
        }
    }

    public static void main(String[] args) throws Exception {
        String base = args[0];
        Path truthFile = Path.of(args[1]);
        String proxy = null;
        int days = 60;
        long seed = 1;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--days")) {
                days = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--seed")) {
                seed = Long.parseLong(args[++i]);
            } else {
                proxy = args[i];
            }
        }
        DemoClients clients = new DemoClients(base, proxy, seed);
        clients.run(days);
        clients.truth.write(truthFile);
        System.out.println(clients.requests + " requests sent; truth written to " + truthFile);
    }
}
