package io.github.shrishaanth.axon.server;

import io.github.shrishaanth.axon.observe.Cells;
import io.github.shrishaanth.axon.traffic.TrafficEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** All SQL. Plain JDBC: the tables are counters and an append-only log, which need no mapping layer. */
@Repository
public class Store {

    /**
     * @param specText null until a spec has been uploaded
     */
    public record Workspace(UUID id, String name, String adminKeyHash, String specName, String specSha256,
                            String specText, long eventCount, long maxEvents, int retentionDays, Instant createdAt) {
    }

    public record StoredEvent(long id, String payload) {
    }

    private static final RowMapper<Workspace> WORKSPACE = (rs, i) -> new Workspace(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("admin_key_hash"),
            rs.getString("spec_name"), rs.getString("spec_sha256"), rs.getString("spec_text"),
            rs.getLong("event_count"), rs.getLong("max_events"), rs.getInt("retention_days"),
            rs.getTimestamp("created_at").toInstant());

    private static final String UPSERT_CELL = """
            insert into agg_cell (workspace_id, key_hash, day, kind, operation, part, status, field, detail, client,
                                  count, first_ts, last_ts)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (workspace_id, key_hash) do update
               set count    = agg_cell.count + excluded.count,
                   first_ts = least(agg_cell.first_ts, excluded.first_ts),
                   last_ts  = greatest(agg_cell.last_ts, excluded.last_ts)
            """;

    private final JdbcTemplate jdbc;

    public Store(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- workspaces

    public long countWorkspaces() {
        return jdbc.queryForObject("select count(*) from workspace", Long.class);
    }

    public void createWorkspace(UUID id, String name, String ingestKeyHash, String adminKeyHash, long maxEvents,
                                int retentionDays) {
        jdbc.update("insert into workspace (id, name, ingest_key_hash, admin_key_hash, max_events, retention_days) "
                + "values (?, ?, ?, ?, ?, ?)", id, name, ingestKeyHash, adminKeyHash, maxEvents, retentionDays);
    }

    public Optional<Workspace> workspace(UUID id) {
        return jdbc.query("select * from workspace where id = ?", WORKSPACE, id).stream().findFirst();
    }

    public Optional<Workspace> workspaceByIngestKey(String ingestKeyHash) {
        return jdbc.query("select * from workspace where ingest_key_hash = ?", WORKSPACE, ingestKeyHash).stream()
                .findFirst();
    }

    public void setSpec(UUID id, String name, String sha256, String text) {
        jdbc.update("update workspace set spec_name = ?, spec_sha256 = ?, spec_text = ? where id = ?", name, sha256,
                text, id);
    }

    /** Serialises writers of one workspace for the length of the transaction (ingest, replay, expiry). */
    public void lock(UUID workspace) {
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> {
        }, workspace.toString());
    }

    public long eventCount(UUID workspace) {
        return jdbc.queryForObject("select event_count from workspace where id = ?", Long.class, workspace);
    }

    // ---- events

    public void insertEvents(UUID workspace, List<TrafficEvent> events) {
        jdbc.batchUpdate("insert into traffic_event (workspace_id, ts, payload) values (?, ?, ?)", events, 1000,
                (ps, e) -> {
                    ps.setObject(1, workspace);
                    ps.setTimestamp(2, Timestamp.from(e.ts()));
                    ps.setString(3, e.toJson());
                });
        jdbc.update("update workspace set event_count = event_count + ? where id = ?", events.size(), workspace);
    }

    /** Stored events after {@code afterId}, in id order. */
    public List<StoredEvent> events(UUID workspace, long afterId, int limit) {
        return jdbc.query("select id, payload from traffic_event where workspace_id = ? and id > ? order by id limit ?",
                (rs, i) -> new StoredEvent(rs.getLong(1), rs.getString(2)), workspace, afterId, limit);
    }

    // ---- cells

    public void upsertCells(UUID workspace, Map<Cells.Key, Cells.Value> cells) {
        record Row(String hash, Cells.Key key, Cells.Value value) {
        }
        List<Row> rows = new ArrayList<>(cells.size());
        cells.forEach((k, v) -> rows.add(new Row(hash(k), k, v)));
        // a fixed order keeps concurrent upserts from deadlocking
        rows.sort(Comparator.comparing(Row::hash));
        jdbc.batchUpdate(UPSERT_CELL, rows, 1000, (ps, r) -> {
            Cells.Key k = r.key();
            ps.setObject(1, workspace);
            ps.setString(2, r.hash());
            ps.setObject(3, k.day());
            ps.setString(4, k.kind().name());
            ps.setString(5, k.operation());
            ps.setString(6, k.part());
            ps.setInt(7, k.status());
            ps.setString(8, k.field());
            ps.setString(9, k.detail());
            ps.setString(10, k.client());
            ps.setLong(11, r.value().count);
            ps.setTimestamp(12, Timestamp.from(r.value().first.truncatedTo(ChronoUnit.MICROS)));
            ps.setTimestamp(13, Timestamp.from(r.value().last.truncatedTo(ChronoUnit.MICROS)));
        });
    }

    public void deleteCells(UUID workspace) {
        jdbc.update("delete from agg_cell where workspace_id = ?", workspace);
    }

    public long cellCount(UUID workspace) {
        return jdbc.queryForObject("select count(*) from agg_cell where workspace_id = ?", Long.class, workspace);
    }

    /** Cells of the workspace whose day lies in {@code [from, to]}; null means unbounded. */
    public List<Map.Entry<Cells.Key, Cells.Value>> cells(UUID workspace, LocalDate from, LocalDate to) {
        return jdbc.query("select day, kind, operation, part, status, field, detail, client, count, first_ts, last_ts "
                        + "from agg_cell where workspace_id = ? and day >= ? and day <= ?",
                (rs, i) -> new AbstractMap.SimpleImmutableEntry<>(
                        new Cells.Key(Cells.Kind.valueOf(rs.getString("kind")), rs.getString("operation"),
                                rs.getString("part"), rs.getInt("status"), rs.getString("field"),
                                rs.getString("detail"), rs.getString("client"), rs.getObject("day", LocalDate.class)),
                        new Cells.Value(rs.getLong("count"), rs.getTimestamp("first_ts").toInstant(),
                                rs.getTimestamp("last_ts").toInstant())),
                workspace, from == null ? LocalDate.of(1970, 1, 1) : from, to == null ? LocalDate.of(9999, 12, 31) : to);
    }

    /** An order-independent fingerprint of a workspace's cells, to compare live aggregation with replay. */
    public String cellFingerprint(UUID workspace) {
        return jdbc.queryForObject("""
                select coalesce(md5(string_agg(key_hash || ':' || count || ':' || extract(epoch from first_ts)
                       || ':' || extract(epoch from last_ts), ',' order by key_hash)), 'empty')
                  from agg_cell where workspace_id = ?""", String.class, workspace);
    }

    // ---- expiry

    /** Deletes events and cells older than each workspace's retention; returns the number of events removed. */
    public int expire(Instant now) {
        int removed = 0;
        for (Workspace w : jdbc.query("select * from workspace", WORKSPACE)) {
            Instant cutoff = now.minus(w.retentionDays(), ChronoUnit.DAYS);
            int n = jdbc.update("delete from traffic_event where workspace_id = ? and ts < ?", w.id(),
                    Timestamp.from(cutoff));
            if (n > 0) {
                jdbc.update("update workspace set event_count = greatest(0, event_count - ?) where id = ?", n, w.id());
                jdbc.update("delete from agg_cell where workspace_id = ? and day < ?", w.id(),
                        LocalDate.ofInstant(cutoff, java.time.ZoneOffset.UTC));
                removed += n;
            }
        }
        return removed;
    }

    // ---- helpers

    static String hash(Cells.Key k) {
        String text = k.kind().name() + '\u001f' + k.operation() + '\u001f' + k.part() + '\u001f' + k.status()
                + '\u001f' + k.field() + '\u001f' + k.detail() + '\u001f' + k.client() + '\u001f' + k.day();
        return hex("MD5", text);
    }

    public static String sha256(String text) {
        return hex("SHA-256", text);
    }

    private static String hex(String algorithm, String text) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
