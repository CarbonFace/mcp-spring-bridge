package com.cogistra.mcpbridge.audit;

import com.cogistra.mcpbridge.api.BridgeIdentity;
import com.cogistra.mcpbridge.operation.OperationOwner;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Minimal metadata only: never put arguments, results, tokens, passwords or exception messages
 * here.
 */
public record AuditEvent(
    String eventId,
    long occurredAtEpochMillis,
    OperationOwner owner,
    String capabilityName,
    String operationId,
    Phase phase,
    Outcome outcome,
    String errorCode) {
  public enum Phase {
    DISCOVERY,
    PREPARE,
    REVISE,
    EXECUTE,
    STATUS,
    CANCEL,
    RECOVER,
    READ,
    UPLOAD,
    DOWNLOAD
  }

  public enum Outcome {
    ALLOWED,
    DENIED,
    PREPARED,
    SUCCEEDED,
    REJECTED,
    UNKNOWN,
    CANCELLED,
    EXPIRED,
    FAILED
  }

  public AuditEvent {
    requireId(eventId, "eventId", true);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(phase);
    Objects.requireNonNull(outcome);
    requireId(capabilityName, "capabilityName", true);
    requireId(operationId, "operationId", false);
    requireId(errorCode, "errorCode", false);
    if (occurredAtEpochMillis < 0)
      throw new IllegalArgumentException("Audit timestamp must be nonnegative");
    if (owner.issuer().length() > 2048
        || owner.subject().length() > 1024
        || owner.clientId().length() > 1024)
      throw new IllegalArgumentException("Audit identity is too long");
  }

  public static AuditEvent create(
      BridgeIdentity identity,
      String capabilityName,
      String operationId,
      Phase phase,
      Outcome outcome,
      String errorCode) {
    return new AuditEvent(
        UUID.randomUUID().toString(),
        Instant.now().toEpochMilli(),
        OperationOwner.from(identity),
        capabilityName,
        operationId,
        phase,
        outcome,
        errorCode);
  }

  private static void requireId(String value, String name, boolean required) {
    if (value == null && !required) return;
    if (value == null || !value.matches("[A-Za-z0-9_.:/-]{1,128}"))
      throw new IllegalArgumentException(
          "Invalid audit " + name + "; use a stable identifier, not free text");
  }
}
