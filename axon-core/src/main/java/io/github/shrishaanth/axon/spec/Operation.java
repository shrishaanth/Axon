package io.github.shrishaanth.axon.spec;

import java.util.List;
import java.util.Map;

/**
 * One (method, path template) pair.
 *
 * @param partiallyAnalysed true when the operation reaches a construct the model ignores
 * @param approximated      true when the operation reaches a construct the model approximates
 */
public record Operation(
        String method,
        String path,
        String operationId,
        boolean deprecated,
        List<Parameter> parameters,
        Body requestBody,
        Map<String, Response> responses,
        boolean partiallyAnalysed,
        boolean approximated) {

    public String key() {
        return method + " " + path;
    }

    public Parameter parameter(String in, String name) {
        for (Parameter p : parameters) {
            if (p.in().equals(in) && p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /**
     * The declared response that applies to a concrete status code: exact code, then range ("2XX"), then
     * "default". Null if none applies.
     */
    public Response responseFor(int status) {
        Response r = responses.get(Integer.toString(status));
        if (r == null) {
            String range = (status / 100) + "XX";
            r = responses.get(range);
            if (r == null) {
                r = responses.get(range.toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (r == null) {
            r = responses.get("default");
        }
        return r;
    }
}
