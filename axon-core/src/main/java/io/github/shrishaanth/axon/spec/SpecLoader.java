package io.github.shrishaanth.axon.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringReader;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.DocumentStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingEndEvent;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.events.SequenceEndEvent;
import org.yaml.snakeyaml.events.SequenceStartEvent;

/**
 * Reads JSON or YAML text into a Jackson tree.
 *
 * <p>YAML is built straight from parser events. Going through SnakeYAML's node graph and Java maps first held
 * three copies of the document and needed about 1 GB for a 25 MB spec. Scalars follow JSON-like rules only: YAML
 * 1.1 would turn {@code yes}/{@code on} into booleans and {@code 2022-11-28} into a date, which corrupts enum
 * values and version strings.
 */
final class SpecLoader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Pattern BOOL = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");
    private static final Pattern INT = Pattern.compile("^[-+]?(?:0|[1-9][0-9]*)$");
    private static final Pattern FLOAT = Pattern.compile(
            "^[-+]?(?:(?:0|[1-9][0-9]*)(?:\\.[0-9]*)?(?:[eE][-+]?[0-9]+)?|\\.[0-9]+(?:[eE][-+]?[0-9]+)?)$");
    private static final Pattern NULL = Pattern.compile("^(?:~|null|Null|NULL|)$");

    private SpecLoader() {
    }

    static JsonNode load(String text) throws SpecParseException {
        String trimmed = text.stripLeading();
        if (trimmed.isEmpty()) {
            throw new SpecParseException(SpecParseException.Category.NOT_PARSEABLE, "empty document");
        }
        try {
            if (trimmed.charAt(0) == '{') {
                return JSON.readTree(text);
            }
            return yaml(text);
        } catch (Exception | StackOverflowError e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : firstLine(e.getMessage());
            throw new SpecParseException(SpecParseException.Category.NOT_PARSEABLE, message, e);
        }
    }

    private static JsonNode yaml(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(256 * 1024 * 1024);
        Yaml yaml = new Yaml(options);
        Iterator<Event> events = yaml.parse(new StringReader(text)).iterator();
        Map<String, JsonNode> anchors = new HashMap<>();
        while (events.hasNext()) {
            if (events.next() instanceof DocumentStartEvent) {
                return node(events.next(), events, anchors);
            }
        }
        throw new IllegalArgumentException("no YAML document");
    }

    private static JsonNode node(Event event, Iterator<Event> events, Map<String, JsonNode> anchors) {
        if (event instanceof AliasEvent alias) {
            JsonNode target = anchors.get(alias.getAnchor());
            if (target == null) {
                throw new IllegalArgumentException("unknown or recursive YAML alias *" + alias.getAnchor());
            }
            return target.deepCopy();
        }
        if (event instanceof ScalarEvent scalar) {
            JsonNode n = scalar(scalar);
            remember(anchors, scalar.getAnchor(), n);
            return n;
        }
        if (event instanceof SequenceStartEvent start) {
            ArrayNode array = NODES.arrayNode();
            while (true) {
                Event next = events.next();
                if (next instanceof SequenceEndEvent) {
                    break;
                }
                array.add(node(next, events, anchors));
            }
            remember(anchors, start.getAnchor(), array);
            return array;
        }
        if (event instanceof MappingStartEvent start) {
            ObjectNode object = NODES.objectNode();
            while (true) {
                Event key = events.next();
                if (key instanceof MappingEndEvent) {
                    break;
                }
                String name;
                boolean merge = false;
                if (key instanceof ScalarEvent k) {
                    name = k.getValue();
                    merge = name.equals("<<") && isPlain(k);
                } else if (key instanceof AliasEvent a && anchors.get(a.getAnchor()) != null
                        && anchors.get(a.getAnchor()).isValueNode()) {
                    name = anchors.get(a.getAnchor()).asText();
                } else {
                    throw new IllegalArgumentException("YAML mapping key is not a scalar");
                }
                JsonNode value = node(events.next(), events, anchors);
                if (merge) {
                    merge(object, value);
                } else {
                    object.set(name, value);
                }
            }
            remember(anchors, start.getAnchor(), object);
            return object;
        }
        throw new IllegalArgumentException("unexpected YAML event " + event);
    }

    /** YAML merge key: keys already present win. */
    private static void merge(ObjectNode into, JsonNode from) {
        if (from.isObject()) {
            from.fields().forEachRemaining(e -> {
                if (!into.has(e.getKey())) {
                    into.set(e.getKey(), e.getValue());
                }
            });
        } else if (from.isArray()) {
            for (JsonNode part : from) {
                merge(into, part);
            }
        }
    }

    private static void remember(Map<String, JsonNode> anchors, String anchor, JsonNode node) {
        if (anchor != null) {
            anchors.put(anchor, node);
        }
    }

    private static boolean isPlain(ScalarEvent s) {
        return s.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN && s.getImplicit().canOmitTagInPlainScalar();
    }

    private static JsonNode scalar(ScalarEvent s) {
        String v = s.getValue();
        if (!isPlain(s)) {
            return NODES.textNode(v);
        }
        if (NULL.matcher(v).matches()) {
            return NODES.nullNode();
        }
        if (BOOL.matcher(v).matches()) {
            return NODES.booleanNode(v.charAt(0) == 't' || v.charAt(0) == 'T');
        }
        if (INT.matcher(v).matches()) {
            String digits = v.startsWith("+") ? v.substring(1) : v;
            return digits.length() < 18 ? NODES.numberNode(Long.parseLong(digits))
                    : NODES.numberNode(new BigInteger(digits));
        }
        if (FLOAT.matcher(v).matches()) {
            return NODES.numberNode(Double.parseDouble(v));
        }
        return NODES.textNode(v);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
