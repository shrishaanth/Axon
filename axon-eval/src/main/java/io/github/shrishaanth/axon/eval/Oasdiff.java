package io.github.shrishaanth.axon.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the oasdiff binary and reads its changelog (all levels) as {@link Unit}s. */
final class Oasdiff {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path binary;

    Oasdiff(Path binary) {
        this.binary = binary;
    }

    record Run(List<Unit> units, String error, long millis) {
    }

    String version() throws IOException, InterruptedException {
        Process p = new ProcessBuilder(binary.toString(), "--version").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }

    Run changelog(Path base, Path revision) throws IOException, InterruptedException {
        Path out = Files.createTempFile("oasdiff", ".json");
        Path err = Files.createTempFile("oasdiff", ".err");
        long start = System.nanoTime();
        try {
            Process p = new ProcessBuilder(binary.toString(), "changelog", base.toString(), revision.toString(),
                    "-f", "json")
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .start();
            if (!p.waitFor(20, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return new Run(List.of(), "timeout", (System.nanoTime() - start) / 1_000_000);
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            String stdout = Files.readString(out, StandardCharsets.UTF_8).trim();
            if (p.exitValue() != 0 && stdout.isEmpty()) {
                String message = Files.readString(err, StandardCharsets.UTF_8).trim();
                return new Run(List.of(), "exit " + p.exitValue() + ": "
                        + (message.length() > 300 ? message.substring(0, 300) : message), millis);
            }
            List<Unit> units = new ArrayList<>();
            if (!stdout.isEmpty() && !stdout.equals("null")) {
                JsonNode array = JSON.readTree(stdout);
                for (JsonNode entry : array) {
                    units.add(Unit.fromOasdiff(entry));
                }
            }
            return new Run(units, null, millis);
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }
}
