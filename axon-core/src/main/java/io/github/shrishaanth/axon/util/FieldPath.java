package io.github.shrishaanth.axon.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Field paths of the form {@code $.a.b[].c}. Array indexes collapse to {@code []}; a name that is not a plain
 * identifier is written {@code ['odd name']}; {@code .*} stands for "any undeclared key" of a map-like object.
 */
public final class FieldPath {

    public static final String ROOT = "$";

    /** One step of a path: a property name, or (when {@code name} is null) "each array item". */
    public record Segment(String name) {
        public boolean isItems() {
            return name == null;
        }
    }

    private FieldPath() {
    }

    public static String child(String parent, String name) {
        if (isPlain(name)) {
            return parent + "." + name;
        }
        return parent + "['" + name.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    public static String items(String parent) {
        return parent + "[]";
    }

    public static String wildcard(String parent) {
        return parent + ".*";
    }

    private static boolean isPlain(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Parses a path produced by this class. Throws {@link IllegalArgumentException} on anything else. */
    public static List<Segment> parse(String path) {
        if (path == null || !path.startsWith(ROOT)) {
            throw new IllegalArgumentException("not a field path: " + path);
        }
        List<Segment> out = new ArrayList<>();
        int i = 1;
        int n = path.length();
        while (i < n) {
            char c = path.charAt(i);
            if (c == '.') {
                int j = i + 1;
                while (j < n && path.charAt(j) != '.' && path.charAt(j) != '[') {
                    j++;
                }
                if (j == i + 1) {
                    throw new IllegalArgumentException("empty segment in " + path);
                }
                out.add(new Segment(path.substring(i + 1, j)));
                i = j;
            } else if (c == '[' && i + 1 < n && path.charAt(i + 1) == ']') {
                out.add(new Segment(null));
                i += 2;
            } else if (c == '[' && i + 1 < n && path.charAt(i + 1) == '\'') {
                StringBuilder name = new StringBuilder();
                int j = i + 2;
                boolean closed = false;
                while (j < n) {
                    char d = path.charAt(j);
                    if (d == '\\' && j + 1 < n) {
                        name.append(path.charAt(j + 1));
                        j += 2;
                    } else if (d == '\'' && j + 1 < n && path.charAt(j + 1) == ']') {
                        closed = true;
                        j += 2;
                        break;
                    } else {
                        name.append(d);
                        j++;
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("unterminated quoted segment in " + path);
                }
                out.add(new Segment(name.toString()));
                i = j;
            } else {
                throw new IllegalArgumentException("unexpected '" + c + "' at " + i + " in " + path);
            }
        }
        return out;
    }

    /** The path one level up, or null for the root. */
    public static String parent(String path) {
        List<Segment> segments = parse(path);
        if (segments.isEmpty()) {
            return null;
        }
        String out = ROOT;
        for (int i = 0; i < segments.size() - 1; i++) {
            Segment s = segments.get(i);
            out = s.isItems() ? items(out) : child(out, s.name());
        }
        return out;
    }
}
