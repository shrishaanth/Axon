package io.github.shrishaanth.axon.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.diff.Change;
import io.github.shrishaanth.axon.diff.SpecDiff;
import io.github.shrishaanth.axon.drift.DriftEngine;
import io.github.shrishaanth.axon.drift.DriftFinding;
import io.github.shrishaanth.axon.impact.ImpactConfig;
import io.github.shrishaanth.axon.impact.ImpactEngine;
import io.github.shrishaanth.axon.impact.ImpactReport;
import io.github.shrishaanth.axon.impact.ImpactReport.Evidence;
import io.github.shrishaanth.axon.impact.ImpactReport.Exposure;
import io.github.shrishaanth.axon.impact.ImpactReport.Row;
import io.github.shrishaanth.axon.impact.ReportWriter;
import io.github.shrishaanth.axon.impact.Severity;
import io.github.shrishaanth.axon.observe.Aggregate;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.Explorer;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.SpecParseException;
import io.github.shrishaanth.axon.spec.SpecParser;
import io.github.shrishaanth.axon.traffic.HarReader;
import io.github.shrishaanth.axon.traffic.Sanitiser;
import io.github.shrishaanth.axon.traffic.TrafficEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Command-line entry point. Exit codes: 0 ok, 1 gate failed, 2 usage or input error. */
public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            out.println(usage());
            return args.length == 0 ? 2 : 0;
        }
        try {
            Args a = new Args(args, 1);
            return switch (args[0]) {
                case "explore" -> explore(a, out);
                case "diff" -> diff(a, out);
                case "sanitise", "sanitize" -> sanitise(a, out, err);
                case "impact" -> impact(a, out, err);
                case "check" -> check(a, out, err);
                case "bundle" -> bundle(a, err);
                default -> {
                    err.println("unknown command: " + args[0]);
                    err.println(usage());
                    yield 2;
                }
            };
        } catch (SpecParseException e) {
            err.println("cannot read spec (" + e.category() + "): " + e.getMessage());
            return 2;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("i/o error: " + e.getMessage());
            return 2;
        }
    }

    private static int explore(Args a, PrintStream out) throws SpecParseException, IOException {
        ApiSpec spec = SpecParser.parse(Path.of(a.positional(0, "spec file")));
        String wanted = a.option("operation");
        if (wanted == null) {
            write(a.option("out"), Explorer.toJson(Explorer.describe(spec)), out);
            return 0;
        }
        int space = wanted.indexOf(' ');
        if (space < 0) {
            throw new IllegalArgumentException("--operation takes \"METHOD /path\", for example \"GET /users/{id}\"");
        }
        Operation op = spec.operation(wanted.substring(0, space).toUpperCase(Locale.ROOT),
                wanted.substring(space + 1).trim());
        if (op == null) {
            throw new IllegalArgumentException("no such operation in the spec: " + wanted);
        }
        write(a.option("out"), Explorer.toJson(Explorer.describe(op)), out);
        return 0;
    }

    private static int diff(Args a, PrintStream out) throws SpecParseException, IOException {
        ApiSpec baseline = SpecParser.parse(Path.of(a.required("baseline")));
        ApiSpec candidate = SpecParser.parse(Path.of(a.required("candidate")));
        SpecDiff.Result result = SpecDiff.diff(baseline, candidate);
        if (a.flag("json") || a.option("out") != null) {
            ObjectNode node = JSON.createObjectNode();
            node.put("changes_total", result.changes().size());
            node.put("breaking_total", result.breaking().size());
            node.put("truncated", result.truncated());
            ArrayNode changes = node.putArray("changes");
            for (Change c : result.changes()) {
                ObjectNode n = changes.addObject();
                n.put("kind", c.kind().wire());
                n.put("breaking", c.breaking());
                n.put("operation", c.operationKey());
                n.put("part", c.part());
                if (c.status() != null) {
                    n.put("status", c.status());
                }
                if (c.field() != null) {
                    n.put("field", c.field());
                }
                n.put("description", c.description());
                if (c.source() != null) {
                    n.put("source", c.source());
                }
            }
            write(a.option("out"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node), out);
            return 0;
        }
        out.println(result.changes().size() + " changes, " + result.breaking().size() + " breaking"
                + (result.truncated() ? " (list cut: a shared schema had more than 300 changes)" : ""));
        for (Change c : result.changes()) {
            out.println((c.breaking() ? "  BREAKING  " : "  safe      ") + c.description());
        }
        return 0;
    }

    private static int sanitise(Args a, PrintStream out, PrintStream err) throws SpecParseException, IOException {
        ApiSpec spec = a.option("spec") == null ? null : SpecParser.parse(Path.of(a.option("spec")));
        Sanitiser sanitiser = new Sanitiser(identity(a), spec).withTimeHeader(a.option("time-header"));
        HarReader.Result har = HarReader.read(Path.of(a.required("har")));
        Path target = Path.of(a.required("out"));
        if (target.toAbsolutePath().getParent() != null) {
            Files.createDirectories(target.toAbsolutePath().getParent());
        }
        int matched = 0;
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            for (Sanitiser.RawExchange raw : har.exchanges()) {
                TrafficEvent e = sanitiser.sanitise(raw);
                if (e.operation() != null) {
                    matched++;
                }
                w.write(e.toJson());
                w.write('\n');
            }
        }
        err.println(har.exchanges().size() + " events written to " + target
                + (spec == null ? " (no spec given: paths were redacted heuristically, review them before sharing)"
                : ", " + matched + " matched an operation")
                + (har.skipped() > 0 ? "; " + har.skipped() + " unreadable HAR entries skipped" : ""));
        return 0;
    }

    private static Sanitiser.Config identity(Args a) {
        String header = a.option("identity-header");
        String mode = a.option("identity", header != null ? "header" : "none");
        if (mode.equals("header") && header == null) {
            throw new IllegalArgumentException("--identity header needs --identity-header <name>");
        }
        if (!Set.of("header", "api_key_hash", "none").contains(mode)) {
            throw new IllegalArgumentException("--identity must be header, api_key_hash or none");
        }
        String salt = a.option("salt", System.getenv().getOrDefault("AXON_SALT", ""));
        return new Sanitiser.Config(mode, header, salt);
    }

    private record Analysis(ImpactReport report, List<DriftFinding> drift, ObjectNode json) {
    }

    private static int impact(Args a, PrintStream out, PrintStream err) throws SpecParseException, IOException {
        Analysis analysis = analyse(a, err);
        if (a.option("out") != null) {
            write(a.option("out"), ReportWriter.toJson(analysis.json()), out);
        }
        if (a.flag("json")) {
            out.println(ReportWriter.toJson(analysis.json()));
        } else {
            printSummary(analysis.report(), analysis.drift(), out);
        }
        return 0;
    }

    /**
     * The CI gate. Exit 1 when a breaking change reaches the threshold, 0 otherwise.
     *
     * <p>The threshold is a percentage of the clients active in the recent window. Only observed rows count,
     * unless {@code --include-potential} is given: a potential row lists every caller of an operation, and most
     * of them do not depend on the changed part.
     */
    private static int check(Args a, PrintStream out, PrintStream err) throws SpecParseException, IOException {
        double threshold;
        try {
            threshold = Double.parseDouble(a.option("threshold", "10"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--threshold takes a percentage, for example 10");
        }
        if (threshold < 0 || threshold > 100) {
            throw new IllegalArgumentException("--threshold must be between 0 and 100");
        }
        Severity failOn = null;
        if (a.option("fail-on") != null) {
            try {
                failOn = Severity.valueOf(a.option("fail-on").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("--fail-on takes CRITICAL, HIGH, MEDIUM or LOW");
            }
        }
        boolean includePotential = a.flag("include-potential");
        Analysis analysis = analyse(a, err);
        ImpactReport report = analysis.report();
        if (a.option("out") != null) {
            write(a.option("out"), ReportWriter.toJson(analysis.json()), out);
        }
        List<Row> breaking = report.breaking().stream().sorted(Comparator.comparingInt(Row::rank)).toList();
        if (breaking.isEmpty()) {
            out.println("PASS: no breaking changes.");
            return 0;
        }
        List<Row> failing = new java.util.ArrayList<>();
        String rule;
        if (report.events() == 0) {
            // no traffic: nothing can be said about impact, so every breaking change blocks
            failing.addAll(breaking);
            rule = "no usage data was given, so any breaking change fails the check";
        } else if (!report.hasIdentity()) {
            for (Row r : breaking) {
                if ((r.evidence() == Evidence.OBSERVED || includePotential) && r.exposure().requests() > 0) {
                    failing.add(r);
                }
            }
            rule = "the traffic has no client identity, so any breaking change with affected requests fails the check";
        } else {
            for (Row r : breaking) {
                if (r.evidence() != Evidence.OBSERVED && !includePotential) {
                    continue;
                }
                double share = r.exposure().shareRecent() == null ? 0 : r.exposure().shareRecent() * 100;
                boolean overShare = r.exposure().recent() != null && r.exposure().recent() > 0 && share >= threshold;
                boolean overSeverity = failOn != null && r.severity().ordinal() <= failOn.ordinal();
                if (overShare || overSeverity) {
                    failing.add(r);
                }
            }
            rule = String.format(Locale.ROOT, "a%s breaking change affecting at least %s%% of the %d clients active in "
                            + "the last %s days%s", includePotential ? "" : "n observed", trim(threshold),
                    report.activeClients(), trim(report.config().recentDays()),
                    failOn == null ? "" : ", or rated " + failOn + " or worse");
        }
        out.println((failing.isEmpty() ? "PASS" : "FAIL") + ": " + breaking.size() + " breaking changes, "
                + failing.size() + " over the threshold.");
        out.println("Rule: " + rule + ".");
        for (Row r : failing) {
            Exposure e = r.exposure();
            out.printf(Locale.ROOT, "  %-10s %s%n", r.evidence() == Evidence.POTENTIAL ? "potential" : r.severity().name(),
                    r.change().description());
            if (e.clients() != null) {
                out.printf(Locale.ROOT, "             %d client%s (%s recently active, %.0f%% of active), %d requests%n",
                        e.clients(), e.clients() == 1 ? "" : "s", e.recent(),
                        e.shareRecent() == null ? 0 : e.shareRecent() * 100, e.requests());
            } else if (report.events() > 0) {
                out.printf(Locale.ROOT, "             %d requests%n", e.requests());
            }
        }
        long potential = breaking.stream().filter(r -> r.evidence() == Evidence.POTENTIAL).count();
        if (potential > 0 && !includePotential && report.events() > 0) {
            out.println(potential + " response-side changes are not gated: traffic shows who calls the operation, "
                    + "not who reads the field. Use --include-potential to gate on callers.");
        }
        return failing.isEmpty() ? 0 : 1;
    }

    private static Analysis analyse(Args a, PrintStream err) throws SpecParseException, IOException {
        Path baselinePath = Path.of(a.required("baseline"));
        Path candidatePath = Path.of(a.required("candidate"));
        ApiSpec baseline = SpecParser.parse(baselinePath);
        ApiSpec candidate = SpecParser.parse(candidatePath);
        Instant from = a.option("window-start") == null ? null : Instant.parse(a.option("window-start"));
        Instant to = a.option("window-end") == null ? null : Instant.parse(a.option("window-end"));
        Aggregate aggregate = new Aggregate(baseline, from, to);

        String source;
        int rejected = 0;
        Sanitiser.Config identity = identity(a);
        if (a.option("usage") != null) {
            source = "jsonl";
            try (BufferedReader r = Files.newBufferedReader(Path.of(a.option("usage")), StandardCharsets.UTF_8)) {
                String line;
                int number = 0;
                while ((line = r.readLine()) != null) {
                    number++;
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        aggregate.add(TrafficEvent.fromJson(line));
                    } catch (IllegalArgumentException e) {
                        if (rejected++ < 3) {
                            err.println("usage line " + number + " rejected: " + e.getMessage());
                        }
                    }
                }
            }
        } else if (a.option("har") != null) {
            source = "har";
            Sanitiser sanitiser = new Sanitiser(identity, baseline).withTimeHeader(a.option("time-header"));
            for (Sanitiser.RawExchange raw : HarReader.read(Path.of(a.option("har"))).exchanges()) {
                aggregate.add(sanitiser.sanitise(raw));
            }
        } else {
            source = "none";
        }
        if (rejected > 0) {
            err.println(rejected + " usage lines rejected in total");
        }

        ImpactConfig config = ImpactConfig.defaults()
                .withIdentity(aggregate.hasIdentity() ? a.option("identity", "header") : "none",
                        a.option("identity-header"))
                .withServerErrors(a.option("server-errors", "info"));
        if (a.option("critical") != null) {
            Set<String> critical = new HashSet<>();
            for (String c : a.option("critical").split(",")) {
                // accept either a pseudonym or a raw client name, which is hashed with the same salt
                String t = c.trim();
                critical.add(t.matches("[0-9a-f]{16}") ? t : Sanitiser.pseudonym(identity.salt(), t));
            }
            config = config.withCriticalClients(critical);
        }

        ImpactReport report = ImpactEngine.analyse(SpecDiff.diff(baseline, candidate), aggregate, config);
        List<DriftFinding> drift = DriftEngine.analyse(aggregate, config);
        ObjectNode json = ReportWriter.write(report, drift,
                new ReportWriter.SpecRef(baselinePath.toString().replace('\\', '/'), sha256(baselinePath), baseline),
                new ReportWriter.SpecRef(candidatePath.toString().replace('\\', '/'), sha256(candidatePath), candidate),
                source, Instant.now());
        return new Analysis(report, drift, json);
    }

    /**
     * Writes everything the web UI shows as static JSON files, so a demo works with no server: report.json,
     * contract.json, clients.json and explore.json (the spec summary with each operation's detail).
     */
    private static int bundle(Args a, PrintStream err) throws SpecParseException, IOException {
        Path baselinePath = Path.of(a.required("baseline"));
        Path candidatePath = Path.of(a.required("candidate"));
        ApiSpec baseline = SpecParser.parse(baselinePath);
        ApiSpec candidate = SpecParser.parse(candidatePath);
        Aggregate aggregate = new Aggregate(baseline, null, null);
        try (BufferedReader r = Files.newBufferedReader(Path.of(a.required("usage")), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    aggregate.add(TrafficEvent.fromJson(line));
                }
            }
        }
        ImpactConfig config = ImpactConfig.defaults()
                .withIdentity(aggregate.hasIdentity() ? "header" : "none", a.option("identity-header"));
        ImpactReport report = ImpactEngine.analyse(SpecDiff.diff(baseline, candidate), aggregate, config);
        ObjectNode json = ReportWriter.write(report, DriftEngine.analyse(aggregate, config),
                new ReportWriter.SpecRef(baselinePath.getFileName().toString(), sha256(baselinePath), baseline),
                new ReportWriter.SpecRef(candidatePath.getFileName().toString(), sha256(candidatePath), candidate),
                "jsonl", Instant.now());
        ObjectNode explore = Explorer.describe(baseline);
        ObjectNode details = explore.putObject("details");
        for (Operation op : baseline.operations()) {
            details.set(op.key(), Explorer.describe(op));
        }
        String dir = a.required("out");
        write(dir + "/report.json", ReportWriter.toJson(json), null);
        write(dir + "/contract.json", JSON.writeValueAsString(io.github.shrishaanth.axon.observe.Views.contract(aggregate)), null);
        write(dir + "/clients.json", JSON.writeValueAsString(io.github.shrishaanth.axon.observe.Views.clients(aggregate)), null);
        write(dir + "/explore.json", JSON.writeValueAsString(explore), null);
        err.println("bundle written to " + dir);
        return 0;
    }

    static void printSummary(ImpactReport report, List<DriftFinding> drift, PrintStream out) {
        out.println(report.breaking().size() + " breaking changes of " + report.rows().size() + "; "
                + report.matchedEvents() + " of " + report.events() + " events matched"
                + (report.hasIdentity() ? "; " + report.activeClients() + " clients active in the last "
                + trim(report.config().recentDays()) + " days" : "; no client identity"));
        out.println();
        List<Row> rows = report.breaking().stream().sorted(Comparator.comparingInt(Row::rank)).toList();
        for (Row r : rows) {
            Exposure e = r.exposure();
            String who;
            if (e.clients() == null) {
                who = e.requests() + " requests";
            } else {
                who = e.clients() + " client" + (e.clients() == 1 ? "" : "s") + ", " + e.requests() + " requests";
            }
            String label = r.evidence() == Evidence.POTENTIAL ? "potential " + r.severity() : r.severity().name();
            out.printf(Locale.ROOT, "%3d. %-24s %s%n", r.rank(), label, r.change().description());
            out.printf(Locale.ROOT, "     %s: %s%s   (spec-only rank %d)%n",
                    r.evidence() == Evidence.POTENTIAL ? "potentially affected" : "observed affected", who,
                    e.lastSeen() == null ? "" : ", last seen " + e.lastSeen(), r.specOnlyRank());
            if (r.note() != null) {
                out.println("     note: " + r.note());
            }
        }
        long warnings = drift.stream().filter(d -> d.severity().equals("warning")).count();
        out.println();
        out.println("drift: " + warnings + " warnings, " + (drift.size() - warnings) + " informational");
        for (DriftFinding d : drift) {
            if (d.severity().equals("warning")) {
                out.println("  " + d.kind() + "  " + (d.operationKey() == null ? d.path() : d.operationKey())
                        + (d.field() == null ? "" : "  " + d.field()) + "  " + d.detail());
            }
        }
        out.println();
        for (String l : report.limitations()) {
            out.println("limit: " + l);
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static String sha256(Path file) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void write(String file, String text, PrintStream out) throws IOException {
        if (file == null) {
            out.println(text);
        } else {
            Path p = Path.of(file);
            if (p.toAbsolutePath().getParent() != null) {
                Files.createDirectories(p.toAbsolutePath().getParent());
            }
            Files.writeString(p, text + "\n", StandardCharsets.UTF_8);
        }
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "axon: API change-impact analysis",
                "",
                "  axon check --baseline v1.yaml --candidate v2.yaml [--usage usage.jsonl | --har traffic.har]",
                "             [--threshold 10] [--fail-on HIGH] [--include-potential] [--out report.json]",
                "      CI gate: exit 1 if an observed breaking change affects at least --threshold percent of the",
                "      recently active clients. Without usage data, any breaking change fails.",
                "",
                "  axon impact --baseline v1.yaml --candidate v2.yaml (--usage usage.jsonl | --har traffic.har)",
                "              [--identity-header NAME] [--salt S] [--critical a,b] [--server-errors info|drift]",
                "              [--window-start ISO] [--window-end ISO] [--out report.json] [--json]",
                "      Rank the breaking changes between two specs by who they would affect, and list drift.",
                "",
                "  axon bundle --baseline v1.yaml --candidate v2.yaml --usage usage.jsonl --out dir",
                "      Write the report and the views as static JSON for the web UI.",
                "",
                "  axon diff --baseline v1.yaml --candidate v2.yaml [--json] [--out file]",
                "      List the changes between two specs, breaking or safe.",
                "",
                "  axon sanitise --har traffic.har --out usage.jsonl [--spec v1.yaml] [--identity-header NAME] [--salt S]",
                "      Reduce captured traffic to shapes. Values never reach the output.",
                "",
                "  axon explore <spec> [--operation \"METHOD /path\"] [--out file]",
                "      Print what the spec declares, or the fields of one operation.",
                "",
                "  The salt can also be given as the AXON_SALT environment variable.",
                "  --time-header NAME (sanitise, impact --har) takes event times from a request header,",
                "  for replayed or simulated traffic.");
    }
}
