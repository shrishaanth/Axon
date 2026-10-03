package io.github.shrishaanth.axon.cli;

import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.Explorer;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.SpecParseException;
import io.github.shrishaanth.axon.spec.SpecParser;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Command-line entry point. Exit codes: 0 ok, 1 gate failed, 2 usage or input error. */
public final class Main {

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
        } catch (java.io.IOException e) {
            err.println("i/o error: " + e.getMessage());
            return 2;
        }
    }

    private static int explore(Args a, PrintStream out) throws SpecParseException, java.io.IOException {
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
        Operation op = spec.operation(wanted.substring(0, space).toUpperCase(java.util.Locale.ROOT),
                wanted.substring(space + 1).trim());
        if (op == null) {
            throw new IllegalArgumentException("no such operation in the spec: " + wanted);
        }
        write(a.option("out"), Explorer.toJson(Explorer.describe(op)), out);
        return 0;
    }

    static void write(String file, String text, PrintStream out) throws java.io.IOException {
        if (file == null) {
            out.println(text);
        } else {
            Path p = Path.of(file);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, text + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "axon: API change-impact analysis",
                "",
                "  axon explore <spec> [--operation \"METHOD /path\"] [--out file]",
                "      Print what the spec declares: operations and unsupported constructs,",
                "      or with --operation the parameters and fields of one operation.");
    }
}
