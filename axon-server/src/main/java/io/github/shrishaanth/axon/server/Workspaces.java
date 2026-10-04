package io.github.shrishaanth.axon.server;

import io.github.shrishaanth.axon.observe.Aggregate;
import io.github.shrishaanth.axon.observe.Cells;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.SpecParseException;
import io.github.shrishaanth.axon.spec.SpecParser;
import io.github.shrishaanth.axon.traffic.TrafficEvent;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Workspace lifecycle, ingestion and replay.
 *
 * <p>Ingestion sits behind this one class so that a queue could be put in front of it later; today a request
 * stores its events and adds its counter cells in one transaction.
 */
@Service
public class Workspaces {

    private static final Logger LOG = LoggerFactory.getLogger(Workspaces.class);
    private static final String NO_SPEC =
            "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"(no spec uploaded)\",\"version\":\"0\"},\"paths\":{}}";
    private static final int REPLAY_PAGE = 5000;

    /** Keys are shown once, at creation; only their hashes are stored. */
    public record Created(UUID id, String ingestKey, String adminKey) {
    }

    public record IngestResult(int accepted, int rejected, List<String> errors) {
    }

    public record ReplayResult(long events, long cells, long millis) {
    }

    private record Parsed(String sha256, ApiSpec spec) {
    }

    private final Store store;
    private final AxonProperties properties;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Parsed> specs = new ConcurrentHashMap<>();
    private final ApiSpec emptySpec;

    public Workspaces(Store store, AxonProperties properties, TransactionTemplate transactions) {
        this.store = store;
        this.properties = properties;
        this.transactions = transactions;
        try {
            this.emptySpec = SpecParser.parse(NO_SPEC);
        } catch (SpecParseException e) {
            throw new IllegalStateException(e);
        }
    }

    public Created create(String name) {
        if (store.countWorkspaces() >= properties.getMaxWorkspaces()) {
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "workspace_limit",
                    "This server holds its maximum number of workspaces.");
        }
        UUID id = UUID.randomUUID();
        String ingestKey = "axi_" + token();
        String adminKey = "axa_" + token();
        store.createWorkspace(id, name, Store.sha256(ingestKey), Store.sha256(adminKey),
                properties.getMaxEventsPerWorkspace(), properties.getRetentionDays());
        return new Created(id, ingestKey, adminKey);
    }

    private String token() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The workspace, if {@code adminKey} is its admin key. The same error for "no such workspace" and "wrong key". */
    public Store.Workspace authorise(UUID id, String adminKey) {
        Store.Workspace w = store.workspace(id).orElse(null);
        if (w == null || adminKey == null || !constantTimeEquals(w.adminKeyHash().trim(), Store.sha256(adminKey))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorised", "Unknown workspace or wrong admin key.");
        }
        return w;
    }

    public Store.Workspace byIngestKey(String ingestKey) {
        if (ingestKey == null || ingestKey.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorised", "Missing X-Axon-Key header.");
        }
        return store.workspaceByIngestKey(Store.sha256(ingestKey)).orElseThrow(
                () -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorised", "Unknown ingest key."));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    /** The parsed current spec of a workspace; an empty spec until one is uploaded. */
    public ApiSpec spec(Store.Workspace w) {
        if (w.specText() == null) {
            return emptySpec;
        }
        Parsed cached = specs.get(w.id());
        if (cached != null && cached.sha256().equals(w.specSha256().trim())) {
            return cached.spec();
        }
        ApiSpec parsed = parse(w.specText());
        specs.put(w.id(), new Parsed(w.specSha256().trim(), parsed));
        return parsed;
    }

    public ApiSpec parse(String text) {
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_spec", "The spec is empty.");
        }
        if (text.length() > properties.getMaxSpecBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "spec_too_large",
                    "The spec is larger than " + properties.getMaxSpecBytes() + " bytes.");
        }
        try {
            return SpecParser.parse(text);
        } catch (SpecParseException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "spec_" + e.category().name().toLowerCase(),
                    e.getMessage());
        }
    }

    /** Stores a new baseline spec and replays the stored events against it, since matching depends on the spec. */
    public ReplayResult setSpec(Store.Workspace w, String name, String text) {
        ApiSpec parsed = parse(text);
        String sha = Store.sha256(text);
        return transactions.execute(tx -> {
            store.lock(w.id());
            store.setSpec(w.id(), name, sha, text);
            specs.put(w.id(), new Parsed(sha, parsed));
            return replayLocked(w.id(), parsed);
        });
    }

    public IngestResult ingest(Store.Workspace w, List<String> lines) {
        if (lines.size() > properties.getMaxEventsPerRequest()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_many_events",
                    "At most " + properties.getMaxEventsPerRequest() + " events per request.");
        }
        List<TrafficEvent> events = new ArrayList<>(lines.size());
        List<String> errors = new ArrayList<>();
        int rejected = 0;
        for (int i = 0; i < lines.size(); i++) {
            try {
                // parsing keeps only the fields of the event schema: anything else in the line is dropped here
                events.add(TrafficEvent.fromJson(lines.get(i)));
            } catch (IllegalArgumentException e) {
                rejected++;
                if (errors.size() < 5) {
                    errors.add("event " + (i + 1) + ": " + e.getMessage());
                }
            }
        }
        if (events.isEmpty()) {
            return new IngestResult(0, rejected, errors);
        }
        ApiSpec spec = spec(w);
        Cells cells = new Cells(spec);
        events.forEach(cells::add);
        int rejectedFinal = rejected;
        return transactions.execute(tx -> {
            store.lock(w.id());
            long held = store.eventCount(w.id());
            if (held + events.size() > w.maxEvents()) {
                throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "event_limit",
                        "This workspace holds " + held + " events; its limit is " + w.maxEvents()
                                + ". Old events expire after " + w.retentionDays() + " days.");
            }
            store.insertEvents(w.id(), events);
            store.upsertCells(w.id(), cells.cells());
            return new IngestResult(events.size(), rejectedFinal, errors);
        });
    }

    /** Rebuilds every counter of the workspace from its stored events. */
    public ReplayResult replay(Store.Workspace w) {
        ApiSpec spec = spec(w);
        return transactions.execute(tx -> {
            store.lock(w.id());
            return replayLocked(w.id(), spec);
        });
    }

    private ReplayResult replayLocked(UUID workspace, ApiSpec spec) {
        long start = System.nanoTime();
        store.deleteCells(workspace);
        long after = 0;
        long events = 0;
        while (true) {
            List<Store.StoredEvent> page = store.events(workspace, after, REPLAY_PAGE);
            if (page.isEmpty()) {
                break;
            }
            Cells cells = new Cells(spec);
            for (Store.StoredEvent e : page) {
                cells.add(TrafficEvent.fromJson(e.payload()));
            }
            store.upsertCells(workspace, cells.cells());
            events += page.size();
            after = page.get(page.size() - 1).id();
        }
        return new ReplayResult(events, store.cellCount(workspace), (System.nanoTime() - start) / 1_000_000);
    }

    /** The observed traffic of a workspace between two UTC days (inclusive; null for unbounded). */
    public Aggregate aggregate(Store.Workspace w, LocalDate from, LocalDate to) {
        return Cells.toAggregate(spec(w), store.cells(w.id(), from, to), from, to);
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void expire() {
        int removed = transactions.execute(tx -> store.expire(Instant.now()));
        if (removed > 0) {
            LOG.info("expired {} events past retention", removed);
        }
    }
}
