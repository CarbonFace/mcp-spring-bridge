package com.cogistra.mcpbridge.operation;

import com.fasterxml.jackson.databind.JsonNode;

/** Full confirmation preview; possession is not proof of human consent. */
public record OperationView(
    String operationId,
    String capabilityName,
    long revision,
    String payloadHash,
    JsonNode preview,
    long expiresAtEpochMillis,
    OperationState state,
    JsonNode result,
    String errorCode) {
  public OperationView {
    preview = preview == null ? null : preview.deepCopy();
    result = result == null ? null : result.deepCopy();
  }

  @Override
  public JsonNode preview() {
    return preview == null ? null : preview.deepCopy();
  }

  @Override
  public JsonNode result() {
    return result == null ? null : result.deepCopy();
  }

  public static OperationView from(OperationRecord record) {
    return new OperationView(
        record.operationId(),
        record.capabilityName(),
        record.revision(),
        record.payloadHash(),
        record.arguments(),
        record.expiresAtEpochMillis(),
        record.state(),
        record.result(),
        record.errorCode());
  }
}
