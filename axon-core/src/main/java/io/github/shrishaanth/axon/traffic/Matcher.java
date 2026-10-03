package io.github.shrishaanth.axon.traffic;

import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.Operation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves a concrete request path to the operation that documents it.
 *
 * <p>A trie over path segments. At each level a literal segment is tried first, then a segment that mixes
 * literal text and parameters (such as {@code {id}.json}), then a bare {@code {param}}; the search backtracks, so
 * {@code /users/me} matches the literal template when there is one, and {@code /users/me/posts} still falls back
 * to {@code /users/{id}/posts} when the literal branch has no such child. Server base paths from the spec are
 * stripped before matching.
 */
public final class Matcher {

    /** A redacted path segment: matches a parameter, never a literal. */
    public static final String REDACTED = "{*}";

    private static final class Node {
        final Map<String, Node> literals = new HashMap<>();
        final List<Mixed> mixed = new ArrayList<>();
        Node param;
        final Map<String, Operation> operations = new LinkedHashMap<>();
    }

    private record Mixed(Pattern pattern, int literalChars, Node node, String template) {
    }

    private final Node root = new Node();
    private final List<String> basePaths;

    public Matcher(ApiSpec spec) {
        this.basePaths = spec.basePaths().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (Operation op : spec.operations()) {
            Node node = root;
            for (String segment : split(op.path())) {
                node = child(node, segment);
            }
            node.operations.putIfAbsent(op.method(), op);
        }
    }

    private static Node child(Node node, String segment) {
        int open = segment.indexOf('{');
        if (open < 0) {
            return node.literals.computeIfAbsent(segment, s -> new Node());
        }
        if (open == 0 && segment.indexOf('}') == segment.length() - 1 && segment.indexOf('{', 1) < 0) {
            if (node.param == null) {
                node.param = new Node();
            }
            return node.param;
        }
        for (Mixed m : node.mixed) {
            if (m.template().equals(blank(segment))) {
                return m.node();
            }
        }
        StringBuilder regex = new StringBuilder();
        int literal = 0;
        int i = 0;
        while (i < segment.length()) {
            int start = segment.indexOf('{', i);
            int end = start < 0 ? -1 : segment.indexOf('}', start);
            if (start < 0 || end < 0) {
                regex.append(Pattern.quote(segment.substring(i)));
                literal += segment.length() - i;
                break;
            }
            regex.append(Pattern.quote(segment.substring(i, start))).append("(.+?)");
            literal += start - i;
            i = end + 1;
        }
        Mixed m = new Mixed(Pattern.compile(regex.toString()), literal, new Node(), blank(segment));
        node.mixed.add(m);
        // more literal text first: the more specific template wins
        node.mixed.sort(Comparator.comparingInt(Mixed::literalChars).reversed());
        return m.node();
    }

    private static String blank(String segment) {
        return segment.replaceAll("\\{[^}]*}", "{}");
    }

    /** Returns the operation for this request, or null when nothing in the spec documents it. */
    public Operation match(String method, String rawPath) {
        String m = method.toUpperCase(Locale.ROOT);
        String path = clean(rawPath);
        for (String base : basePaths) {
            if (path.equals(base) || path.startsWith(base + "/")) {
                Operation op = find(root, split(path.substring(base.length())), 0, m);
                if (op != null) {
                    return op;
                }
            }
        }
        return find(root, split(path), 0, m);
    }

    private static Operation find(Node node, List<String> segments, int index, String method) {
        if (index == segments.size()) {
            return node.operations.get(method);
        }
        String segment = segments.get(index);
        boolean redacted = segment.equals(REDACTED);
        if (!redacted) {
            Node literal = node.literals.get(segment);
            if (literal != null) {
                Operation op = find(literal, segments, index + 1, method);
                if (op != null) {
                    return op;
                }
            }
            for (Mixed m : node.mixed) {
                if (m.pattern().matcher(segment).matches()) {
                    Operation op = find(m.node(), segments, index + 1, method);
                    if (op != null) {
                        return op;
                    }
                }
            }
        }
        if (node.param != null && !segment.isEmpty()) {
            return find(node.param, segments, index + 1, method);
        }
        return null;
    }

    private static String clean(String rawPath) {
        String path = rawPath;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    /** Splits on '/', ignoring a trailing slash; the root path is the empty list. */
    private static List<String> split(String path) {
        List<String> out = new ArrayList<>();
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            return out;
        }
        for (String s : p.split("/", -1)) {
            out.add(s);
        }
        return out;
    }
}
