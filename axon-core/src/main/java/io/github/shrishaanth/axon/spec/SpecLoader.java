package io.github.shrishaanth.axon.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/** Reads JSON or YAML text into a Jackson tree. */
final class SpecLoader {

    private static final ObjectMapper JSON = new ObjectMapper();

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
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(256 * 1024 * 1024);
            options.setMaxAliasesForCollections(100_000);
            options.setNestingDepthLimit(500);
            DumperOptions dumper = new DumperOptions();
            Yaml yaml = new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options,
                    new JsonLikeResolver());
            Object loaded = yaml.load(text);
            return JSON.valueToTree(loaded);
        } catch (Exception | StackOverflowError e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : firstLine(e.getMessage());
            throw new SpecParseException(SpecParseException.Category.NOT_PARSEABLE, message, e);
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    /**
     * YAML 1.1 turns {@code yes}/{@code on} into booleans and {@code 2022-11-28} into a date, which corrupts
     * enum values and version strings. This resolver keeps only the JSON-like scalars.
     */
    private static final class JsonLikeResolver extends Resolver {
        private static final Pattern BOOL = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");
        private static final Pattern INT = Pattern.compile("^[-+]?(?:0|[1-9][0-9]*)$");
        private static final Pattern FLOAT = Pattern.compile(
                "^[-+]?(?:(?:0|[1-9][0-9]*)(?:\\.[0-9]*)?(?:[eE][-+]?[0-9]+)?|\\.[0-9]+(?:[eE][-+]?[0-9]+)?)$");
        private static final Pattern NULL = Pattern.compile("^(?:~|null|Null|NULL)$");
        private static final Pattern EMPTY = Pattern.compile("^$");
        private static final Pattern MERGE = Pattern.compile("^(?:<<)$");

        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOL, "tTfF");
            addImplicitResolver(Tag.INT, INT, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, MERGE, "<");
            addImplicitResolver(Tag.NULL, NULL, "~nN");
            addImplicitResolver(Tag.NULL, EMPTY, null);
        }
    }
}
