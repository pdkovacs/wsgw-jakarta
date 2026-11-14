package io.github.pdkovacs.wsgw.e2e.common.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record E2EMessage(
        String runId,
        String id,
        String sender,
        List<String> recipients,
        String data,
        String sentAt,
        String destination, // aka. userDevice or connectionId
        Map<String, String> traceData
) {

    /**
     * Records are immutable, so instead of Go's {@code SetSentAt()} this returns a copy stamped with the current UTC time.
     */
    public E2EMessage withSentAtNow() {
        return new E2EMessage(runId, id, sender, recipients, data, Instant.now().toString(), destination, traceData);
    }

    /**
     * @throws java.time.format.DateTimeParseException if {@code sentAt} is missing or not an ISO-8601 instant
     */
    public Instant getSentAt() {
        return Instant.parse(sentAt);
    }
}
