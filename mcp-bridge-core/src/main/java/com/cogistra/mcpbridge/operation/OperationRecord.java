package com.cogistra.mcpbridge.operation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Store representation. Arguments and result must be protected as business data at rest. */
public record OperationRecord(
    String operationId,
    OperationOwner owner,
    String capabilityName,
    String requestId,
    long revision,
    String payloadHash,
    JsonNode arguments,
    long createdAtEpochMillis,
    long expiresAtEpochMillis,
    long updatedAtEpochMillis,
    OperationState state,
    JsonNode result,
    String errorCode) {
  public OperationRecord {
    Objects.requireNonNull(operationId);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(capabilityName);
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(payloadHash);
    Objects.requireNonNull(state);
    arguments = arguments == null ? null : arguments.deepCopy();
    result = result == null ? null : result.deepCopy();
  }

  @Override
  public JsonNode arguments() {
    return arguments == null ? null : arguments.deepCopy();
  }

  @Override
  public JsonNode result() {
    return result == null ? null : result.deepCopy();
  }

  public OperationRecord transition(OperationState next, JsonNode value, String code, long now) {
    return new OperationRecord(
        operationId,
        owner,
        capabilityName,
        requestId,
        revision,
        payloadHash,
        arguments,
        createdAtEpochMillis,
        expiresAtEpochMillis,
        now,
        next,
        value,
        code);
  }
}
