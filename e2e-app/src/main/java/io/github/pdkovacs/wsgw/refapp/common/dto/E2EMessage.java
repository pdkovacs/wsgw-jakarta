package io.github.pdkovacs.wsgw.refapp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record E2EMessage(
        String testRunId,
        String id,
        String sender,
        List<String> recipients,
        String data,
        String sentAt,
        String destination,
        Map<String, String> traceData
) {
    public E2EMessage withSentAt(String sentAt) {
        return new E2EMessage(testRunId, id, sender, recipients, data, sentAt, destination, traceData);
    }

    public E2EMessage withTraceData(Map<String, String> traceData) {
        return new E2EMessage(testRunId, id, sender, recipients, data, sentAt, destination, traceData);
    }
}
