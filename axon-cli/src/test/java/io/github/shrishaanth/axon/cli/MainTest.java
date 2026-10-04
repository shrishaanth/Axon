package io.github.shrishaanth.axon.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the CLI on the real demo capture (eval/demo). */
class MainTest {

    private static final String V1 = "../eval/demo/recipes-v1.yaml";
    private static final String V2 = "../eval/demo/recipes-v2.yaml";
    private static final String USAGE = "../eval/demo/usage.jsonl";

    private record Run(int code, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void gateFailsWhenAnObservedChangeReachesTheThreshold() {
        // 'sort' removed: 3 of the 10 recently active clients send it
        Run r = run("check", "--baseline", V1, "--candidate", V2, "--usage", USAGE, "--threshold", "10");
        assertEquals(1, r.code(), r.out());
        assertTrue(r.out().startsWith("FAIL: 17 breaking changes"), r.out());
        assertTrue(r.out().contains("Query parameter 'sort' removed"), r.out());
        assertTrue(r.out().contains("response-side changes are not gated"), r.out());
    }

    @Test
    void gatePassesWhenNoObservedChangeReachesTheThreshold() {
        Run r = run("check", "--baseline", V1, "--candidate", V2, "--usage", USAGE, "--threshold", "50");
        assertEquals(0, r.code(), r.out());
        assertTrue(r.out().startsWith("PASS: 17 breaking changes, 0 over the threshold"), r.out());
    }

    @Test
    void potentialRowsCountOnlyWhenAskedFor() {
        // 6 of 10 active clients call GET /recipes, whose response loses a field
        Run r = run("check", "--baseline", V1, "--candidate", V2, "--usage", USAGE, "--threshold", "50",
                "--include-potential");
        assertEquals(1, r.code(), r.out());
        assertTrue(r.out().contains("potential"), r.out());
    }

    @Test
    void severityRuleCanFailTheGateOnItsOwn() {
        Run r = run("check", "--baseline", V1, "--candidate", V2, "--usage", USAGE, "--threshold", "100",
                "--fail-on", "high");
        assertEquals(1, r.code(), r.out());
    }

    @Test
    void withoutUsageAnyBreakingChangeFails() {
        Run r = run("check", "--baseline", V1, "--candidate", V2);
        assertEquals(1, r.code(), r.out());
        assertTrue(r.out().contains("no usage data was given"), r.out());
        Run same = run("check", "--baseline", V1, "--candidate", V1);
        assertEquals(0, same.code(), same.out());
        assertTrue(same.out().startsWith("PASS: no breaking changes"), same.out());
    }

    @Test
    void usageErrorsExitWithTwo() {
        assertEquals(2, run("check", "--baseline", V1).code());
        assertEquals(2, run("check", "--baseline", V1, "--candidate", V2, "--threshold", "lots").code());
        assertEquals(2, run("check", "--baseline", "nope.yaml", "--candidate", V2).code());
        assertEquals(2, run("frobnicate").code());
        assertEquals(0, run("--help").code());
    }

    @Test
    void bundleWritesTheFilesTheWebUiLoads(@TempDir Path dir) throws Exception {
        Run r = run("bundle", "--baseline", V1, "--candidate", V2, "--usage", USAGE, "--out", dir.toString());
        assertEquals(0, r.code(), r.err());
        for (String name : new String[] {"report.json", "contract.json", "clients.json", "explore.json"}) {
            assertTrue(Files.size(dir.resolve(name)) > 100, name);
        }
        assertTrue(Files.readString(dir.resolve("report.json")).contains("\"breaking_total\" : 17"));
    }

    @Test
    void diffAndExplorePrintSomethingUseful() {
        Run diff = run("diff", "--baseline", V1, "--candidate", V2);
        assertEquals(0, diff.code());
        assertTrue(diff.out().startsWith("24 changes, 17 breaking"), diff.out());
        Run explore = run("explore", V1, "--operation", "GET /recipes/{id}");
        assertEquals(0, explore.code());
        assertTrue(explore.out().contains("legacy_slug"), explore.out());
    }
}
