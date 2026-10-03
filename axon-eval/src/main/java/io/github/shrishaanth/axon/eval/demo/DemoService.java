package io.github.shrishaanth.axon.eval.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The recipes service behind dataset T-demo. It implements eval/demo/recipes-v1.yaml with these deliberate,
 * documented differences, which are the ground truth for drift detection on real traffic:
 *
 * <ul>
 *   <li>every recipe carries an undocumented {@code internal_score}</li>
 *   <li>the documented {@code subtitle} is never returned</li>
 *   <li>{@code servings} comes back as a string for every tenth recipe id (documented: integer)</li>
 *   <li>{@code POST /recipes} answers an undocumented 429 on every 40th call</li>
 *   <li>{@code GET /healthz} exists but is not documented</li>
 *   <li>the undocumented query parameter {@code debug} is accepted on {@code GET /recipes}</li>
 * </ul>
 *
 * <p>Usage: {@code demo-service <port>}
 */
public final class DemoService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<Integer, ObjectNode> recipes = new ConcurrentHashMap<>();
    private final Map<Integer, List<ObjectNode>> ratings = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicInteger nextRating = new AtomicInteger(1);
    private final AtomicInteger creates = new AtomicInteger();

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8087;
        HttpServer server = new DemoService().start(port);
        System.out.println("demo service on http://localhost:" + server.getAddress().getPort());
    }

    public HttpServer start(int port) throws IOException {
        String[] titles = {"Dal tadka", "Lemon rice", "Sambar", "Masala dosa", "Pongal", "Rasam", "Upma",
                "Curd rice", "Idli", "Veg biryani", "Poha", "Aloo paratha"};
        for (String title : titles) {
            ObjectNode seed = JSON.createObjectNode();
            seed.put("title", title);
            seed.put("minutes", 20 + title.length());
            seed.put("servings", 2 + title.length() % 4);
            seed.put("difficulty", title.length() % 2 == 0 ? "easy" : "medium");
            seed.putArray("tags").add("veg").add(title.length() % 3 == 0 ? "quick" : "classic");
            store(seed);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        return server;
    }

    private ObjectNode store(JsonNode input) {
        int id = nextId.getAndIncrement();
        ObjectNode r = JSON.createObjectNode();
        r.put("id", id);
        r.put("title", input.path("title").asText());
        copy(input, r, "minutes");
        copy(input, r, "servings");
        copy(input, r, "difficulty");
        copy(input, r, "tags");
        if (input.has("author_email")) {
            ObjectNode author = r.putObject("author");
            author.put("name", input.get("author_email").asText().split("@")[0]);
            author.put("email", input.get("author_email").asText());
        }
        r.put("legacy_slug", input.path("title").asText().toLowerCase().replace(' ', '-') + "-" + id);
        r.putNull("rating_avg");
        r.put("created_at", "2026-08-01T00:00:00Z");
        r.put("internal_score", (id * 37) % 100); // undocumented on purpose
        recipes.put(id, r);
        return r;
    }

    private static void copy(JsonNode from, ObjectNode to, String field) {
        if (from.has(field)) {
            to.set(field, from.get(field));
        }
    }

    /** What goes on the wire: every tenth recipe returns "servings" as a string. */
    private static ObjectNode view(ObjectNode recipe) {
        ObjectNode out = recipe.deepCopy();
        if (recipe.get("id").asInt() % 10 == 0 && out.has("servings")) {
            out.put("servings", out.get("servings").asText());
        }
        return out;
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            String method = x.getRequestMethod();
            String[] parts = x.getRequestURI().getPath().split("/");
            byte[] bodyBytes = x.getRequestBody().readAllBytes();
            JsonNode body = bodyBytes.length == 0 ? null : JSON.readTree(bodyBytes);

            if (parts.length == 2 && parts[1].equals("healthz") && method.equals("GET")) {
                send(x, 200, JSON.createObjectNode().put("ok", true));
            } else if (parts.length == 2 && parts[1].equals("recipes") && method.equals("GET")) {
                ObjectNode page = JSON.createObjectNode();
                ArrayNode items = page.putArray("items");
                String query = x.getRequestURI().getQuery();
                int limit = 5;
                if (query != null) {
                    for (String q : query.split("&")) {
                        if (q.startsWith("limit=")) {
                            limit = Math.max(1, Math.min(20, Integer.parseInt(q.substring(6))));
                        }
                    }
                }
                List<Integer> ids = new ArrayList<>(recipes.keySet());
                ids.sort(null);
                for (int i = 0; i < Math.min(limit, ids.size()); i++) {
                    items.add(view(recipes.get(ids.get(i))));
                }
                page.putNull("next_cursor");
                send(x, 200, page);
            } else if (parts.length == 2 && parts[1].equals("recipes") && method.equals("POST")) {
                if (creates.incrementAndGet() % 40 == 0) {
                    send(x, 429, error("slow down", "rate_limited")); // undocumented on purpose
                } else if (body == null || !body.hasNonNull("title")) {
                    send(x, 400, error("title is required", "invalid"));
                } else {
                    send(x, 201, view(store(body)));
                }
            } else if (parts.length == 3 && parts[1].equals("recipes") && parts[2].equals("featured")
                    && method.equals("GET")) {
                send(x, 200, view(recipes.get(1)));
            } else if (parts.length == 3 && parts[1].equals("export") && parts[2].equals("recipes")
                    && method.equals("GET")) {
                ObjectNode export = JSON.createObjectNode();
                ArrayNode all = export.putArray("recipes");
                recipes.values().stream().limit(20).forEach(r -> all.add(view(r)));
                export.put("generated_at", "2026-10-01T00:00:00Z");
                send(x, 200, export);
            } else if (parts.length == 3 && parts[1].equals("recipes")) {
                Integer id = parse(parts[2]);
                ObjectNode recipe = id == null ? null : recipes.get(id);
                if (recipe == null) {
                    send(x, 404, error("no such recipe", "not_found"));
                } else if (method.equals("GET")) {
                    send(x, 200, view(recipe));
                } else if (method.equals("PUT") && body != null) {
                    for (String f : List.of("title", "minutes", "servings", "difficulty", "tags")) {
                        copy(body, recipe, f);
                    }
                    send(x, 200, view(recipe));
                } else if (method.equals("DELETE")) {
                    if (id > 12) {
                        recipes.remove(id); // seed recipes stay so that later reads still work
                    }
                    send(x, 204, null);
                } else {
                    send(x, 405, error("method not allowed", "method"));
                }
            } else if (parts.length == 4 && parts[1].equals("recipes") && parts[3].equals("ratings")) {
                Integer id = parse(parts[2]);
                if (id == null || !recipes.containsKey(id)) {
                    send(x, 404, error("no such recipe", "not_found"));
                } else if (method.equals("GET")) {
                    ArrayNode list = JSON.createArrayNode();
                    ratings.getOrDefault(id, List.of()).forEach(list::add);
                    send(x, 200, list);
                } else if (method.equals("POST") && body != null) {
                    ObjectNode rating = JSON.createObjectNode();
                    rating.put("id", nextRating.getAndIncrement());
                    rating.put("stars", body.path("stars").asInt());
                    if (body.has("comment")) {
                        rating.set("comment", body.get("comment"));
                    }
                    List<ObjectNode> list = ratings.computeIfAbsent(id, k -> new ArrayList<>());
                    synchronized (list) {
                        list.add(rating);
                        if (list.size() > 5) {
                            list.remove(0);
                        }
                    }
                    send(x, 201, rating);
                } else {
                    send(x, 405, error("method not allowed", "method"));
                }
            } else {
                send(x, 404, error("no such route", "not_found"));
            }
        } catch (Exception e) {
            send(x, 500, error(e.toString(), "internal"));
        } finally {
            x.close();
        }
    }

    private static Integer parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ObjectNode error(String message, String code) {
        return JSON.createObjectNode().put("message", message).put("code", code);
    }

    private static void send(HttpExchange x, int status, JsonNode body) throws IOException {
        if (body == null) {
            x.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = JSON.writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }
}
