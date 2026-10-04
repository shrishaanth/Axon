package io.github.shrishaanth.axon.eval.sim;

import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.Schema;
import io.github.shrishaanth.axon.spec.SpecParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The specs the generator runs over: the demo spec plus five public ones picked by the rule in generator.md. */
public final class SimSpecs {

    /** A spec with its source text, kept so that candidates can be made by mutating the raw document. */
    public record Entry(String name, String text, ApiSpec spec) {
    }

    private SimSpecs() {
    }

    public static List<Entry> load(Path demoSpec, Path publicDir) throws IOException {
        List<Entry> out = new ArrayList<>();
        out.add(read(demoSpec));
        List<Path> files;
        try (Stream<Path> s = Files.list(publicDir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            if (out.size() >= 6) {
                break;
            }
            if (Files.size(file) > 2_000_000) {
                continue;
            }
            Entry e;
            try {
                e = read(file);
            } catch (Exception ex) {
                continue;
            }
            if (eligible(e.spec())) {
                out.add(e);
            }
        }
        return out;
    }

    private static Entry read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return new Entry(file.getFileName().toString(), text, SpecParser.parse(text));
        } catch (Exception e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
    }

    static boolean eligible(ApiSpec spec) {
        int ops = spec.operations().size();
        if (ops < 5 || ops > 60) {
            return false;
        }
        int bodies = 0;
        for (Operation op : spec.operations()) {
            if (op.approximated() || op.partiallyAnalysed()) {
                return false;
            }
            if (op.path().contains("#") || op.path().contains("?")) {
                return false; // such a template cannot be told apart from a URL fragment or query in traffic
            }
            Schema s = op.requestBody() == null ? null : op.requestBody().jsonSchema();
            if (s != null && s.properties().size() >= 3) {
                bodies++;
            }
        }
        return bodies >= 3;
    }
}
