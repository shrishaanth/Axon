package io.github.shrishaanth.axon.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.SpecParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Builds a candidate spec by applying several random breaking edits to a baseline document. */
public final class Mutations {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Mutations() {
    }

    /**
     * Applies up to {@code count} edits, cycling through the breaking mutation kinds; an edit with no eligible
     * site in the spec is skipped. Edits can land on the same operation; the result is whatever the diff then
     * reports.
     */
    public static ApiSpec candidate(String baselineText, int count, Random random) throws Exception {
        JsonNode tree = SpecParser.loadTree(baselineText);
        ObjectNode copy = tree.deepCopy();
        List<Mutator.Kind> breaking = new ArrayList<>();
        for (Mutator.Kind k : Mutator.Kind.values()) {
            if (k.breaking && k != Mutator.Kind.ADD_REQUIRED_REQUEST_FIELD) {
                breaking.add(k);
            }
        }
        for (int i = 0; i < count; i++) {
            new Mutator(copy, random).apply(breaking.get(i % breaking.size()));
        }
        return SpecParser.parse(JSON.writeValueAsString(copy));
    }
}
