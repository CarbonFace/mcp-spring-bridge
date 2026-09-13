package com.cogistra.mcpbridge.operation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Callback gets only the persisted, revision-bound payload. Use operationId as a host dedupe key.
 */
public record OperationExecution(
    String operationId,
    String capabilityName,
    long revision,
    String payloadHash,
    JsonNode arguments) {
  public OperationExecution {
    arguments = arguments == null ? null : arguments.deepCopy();
  }

  @Override
  public JsonNode arguments() {
    return arguments == null ? null : arguments.deepCopy();
  }

  static OperationExecution from(OperationRecord record) {
    return new OperationExecution(
        record.operationId(),
        record.capabilityName(),
        record.revision(),
        record.payloadHash(),
        record.arguments());
  }
}
