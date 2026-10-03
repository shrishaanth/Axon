package io.github.shrishaanth.axon.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal argument parsing: {@code --name value} options, bare {@code --flag}s and positionals. */
final class Args {

    private final List<String> positionals = new ArrayList<>();
    private final Map<String, String> options = new LinkedHashMap<>();

    Args(String[] args, int from) {
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String name = a.substring(2);
                int eq = name.indexOf('=');
                if (eq >= 0) {
                    options.put(name.substring(0, eq), name.substring(eq + 1));
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    options.put(name, args[++i]);
                } else {
                    options.put(name, "true");
                }
            } else {
                positionals.add(a);
            }
        }
    }

    String positional(int index, String what) {
        if (index >= positionals.size()) {
            throw new IllegalArgumentException("missing argument: " + what);
        }
        return positionals.get(index);
    }

    String option(String name) {
        return options.get(name);
    }

    String required(String name) {
        String v = options.get(name);
        if (v == null) {
            throw new IllegalArgumentException("missing option: --" + name);
        }
        return v;
    }

    String option(String name, String fallback) {
        return options.getOrDefault(name, fallback);
    }

    boolean flag(String name) {
        return options.containsKey(name);
    }
}
