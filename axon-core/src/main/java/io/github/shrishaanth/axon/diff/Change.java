package io.github.shrishaanth.axon.diff;

/**
 * One atomic difference between a baseline and a candidate spec.
 *
 * @param method            HTTP method of the affected operation
 * @param path              path template as written in the baseline (or the candidate, for an added operation)
 * @param part              where: {@code operation}, {@code request.path|query|header|cookie|body},
 *                          {@code response.body}, {@code response.status}
 * @param status            response status key, for response changes
 * @param mediaType         media type, for body changes
 * @param field             field path such as {@code $.user.phone}; null when the change is not about a field
 * @param from              baseline value in words (types, enum values, ...); may be null
 * @param to                candidate value in words; may be null
 * @param source            JSON pointer of the baseline schema where the change was found. Several changes with
 *                          the same source are one edit seen from several operations.
 * @param partiallyAnalysed the operation reaches a construct the model ignores
 * @param approximated      the operation reaches a construct the model approximates
 */
public record Change(
        ChangeKind kind,
        boolean breaking,
        String method,
        String path,
        String operationId,
        String part,
        String status,
        String mediaType,
        String field,
        String description,
        String from,
        String to,
        String source,
        boolean partiallyAnalysed,
        boolean approximated) {

    public String operationKey() {
        return method + " " + path;
    }
}
