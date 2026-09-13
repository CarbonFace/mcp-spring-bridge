package com.cogistra.mcpbridge.operation;

import com.cogistra.mcpbridge.api.BridgeException;
import com.cogistra.mcpbridge.api.BridgeIdentity;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Coordinates confirmation and durable execution receipts, not host business authorization. */
public final class OperationCoordinator {
  private final OperationStore store;
  private final Clock clock;
  private final long ttlMillis;
  private final int maxArgumentBytes;
  private final int maxResultBytes;
  private final ObjectMapper mapper =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

  public OperationCoordinator(
      OperationStore store, Clock clock, Duration ttl, int maxArgumentBytes, int maxResultBytes) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
    if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(7)) > 0)
      throw new IllegalArgumentException("Operation TTL must be positive and at most 7 days");
    if (maxArgumentBytes < 1
        || maxArgumentBytes > 16 * 1024 * 1024
        || maxResultBytes < 1
        || maxResultBytes > 16 * 1024 * 1024)
      throw new IllegalArgumentException("Payload limits must be 1 byte to 16 MiB");
    this.ttlMillis = ttl.toMillis();
    if (ttlMillis < 1)
      throw new IllegalArgumentException("Operation TTL must be at least 1 millisecond");
    this.maxArgumentBytes = maxArgumentBytes;
    this.maxResultBytes = maxResultBytes;
  }

  public OperationView prepare(
      BridgeIdentity identity, String capabilityName, Object canonicalArguments, String requestId) {
    OperationOwner owner = OperationOwner.from(identity);
    requireText(capabilityName, 128, "capability name");
    if (!capabilityName.matches("[A-Za-z0-9_.:/-]+"))
      throw error("INVALID_ARGUMENT", "Invalid capability name");
    requireText(requestId, 256, "request identifier");
    JsonNode arguments = bounded(canonicalArguments, maxArgumentBytes, "ARGUMENTS_TOO_LARGE");
    String hash = hash(capabilityName, arguments);
    return store.locked(
        session -> {
          OperationRecord previous = session.findRequest(owner, requestId);
          if (previous != null) {
            if (!previous.capabilityName().equals(capabilityName)
                || !previous.payloadHash().equals(hash))
              throw error(
                  "REQUEST_CONFLICT",
                  "The same request identifier cannot describe different content");
            return OperationView.from(expire(session, previous));
          }
          long now = clock.millis();
          OperationRecord created =
              new OperationRecord(
                  UUID.randomUUID().toString(),
                  owner,
                  capabilityName,
                  requestId,
                  1,
                  hash,
                  arguments,
                  now,
                  Math.addExact(now, ttlMillis),
                  now,
                  OperationState.PREPARED,
                  null,
                  null);
          session.put(created);
          return OperationView.from(created);
        });
  }

  /**
   * Replaces only an unexecuted preparation. Old revision/hash confirmations become unusable
   * atomically.
   */
  public OperationView revise(
      BridgeIdentity identity, String id, long expectedRevision, Object newCanonicalArguments) {
    OperationOwner owner = OperationOwner.from(identity);
    JsonNode arguments = bounded(newCanonicalArguments, maxArgumentBytes, "ARGUMENTS_TOO_LARGE");
    return store.locked(
        session -> {
          OperationRecord current = expire(session, owned(session, owner, id));
          requireRevision(current, expectedRevision);
          if (current.state() != OperationState.PREPARED)
            throw error(
                "OPERATION_NOT_PREPARED", "Only a current unexecuted preparation can be revised");
          String hash = hash(current.capabilityName(), arguments);
          if (hash.equals(current.payloadHash())) return OperationView.from(current);
          long now = clock.millis();
          OperationRecord next =
              new OperationRecord(
                  id,
                  owner,
                  current.capabilityName(),
                  current.requestId(),
                  Math.addExact(current.revision(), 1),
                  hash,
                  arguments,
                  current.createdAtEpochMillis(),
                  Math.addExact(now, ttlMillis),
                  now,
                  OperationState.PREPARED,
                  null,
                  null);
          session.put(next);
          return OperationView.from(next);
        });
  }

  /**
   * Caller must refresh trusted identity and capability/domain authorization before this call,
   * including replays.
   */
  public OperationView execute(
      BridgeIdentity identity,
      String id,
      long revision,
      String payloadHash,
      OperationCallback callback) {
    Objects.requireNonNull(callback);
    OperationOwner owner = OperationOwner.from(identity);
    boolean[] claimed = {false};
    OperationRecord record =
        store.locked(
            session -> {
              OperationRecord current = expire(session, owned(session, owner, id));
              requireConfirmation(current, revision, payloadHash);
              if (current.state() != OperationState.PREPARED) return current;
              OperationRecord executing =
                  current.transition(OperationState.EXECUTING, null, null, clock.millis());
              session.put(executing);
              claimed[0] = true;
              return executing;
            });
    if (!claimed[0]) return OperationView.from(record);

    OperationRecord finished;
    try {
      Object result = callback.execute(OperationExecution.from(record));
      finished =
          record.transition(
              OperationState.SUCCEEDED,
              bounded(result, maxResultBytes, "RESULT_TOO_LARGE"),
              null,
              clock.millis());
    } catch (OperationRejectedException rejected) {
      finished =
          record.transition(
              OperationState.REJECTED, null, safeCode(rejected.code()), clock.millis());
    } catch (Exception unknown) {
      String code =
          unknown instanceof BridgeException bridge
              ? safeCode(bridge.code())
              : "EXECUTION_OUTCOME_UNKNOWN";
      finished = record.transition(OperationState.UNKNOWN, null, code, clock.millis());
    } catch (Error fatal) {
      persistOutcome(
          record.transition(OperationState.UNKNOWN, null, "EXECUTION_INTERRUPTED", clock.millis()));
      throw fatal;
    }
    return OperationView.from(persistOutcome(finished));
  }

  public OperationView status(BridgeIdentity identity, String id) {
    OperationOwner owner = OperationOwner.from(identity);
    return store.locked(
        session -> {
          OperationRecord record = expire(session, owned(session, owner, id));
          return OperationView.from(record);
        });
  }

  public OperationView cancel(BridgeIdentity identity, String id, long revision) {
    OperationOwner owner = OperationOwner.from(identity);
    return store.locked(
        session -> {
          OperationRecord current = expire(session, owned(session, owner, id));
          requireRevision(current, revision);
          if (current.state() == OperationState.CANCELLED
              || current.state() == OperationState.EXPIRED) return OperationView.from(current);
          if (current.state() != OperationState.PREPARED)
            throw error(
                "CANNOT_CANCEL",
                "Execution has started; cancellation cannot undo business effects");
          OperationRecord cancelled =
              current.transition(OperationState.CANCELLED, null, null, clock.millis());
          session.put(cancelled);
          return OperationView.from(cancelled);
        });
  }

  /** Explicit host recovery. Does not execute or automatically retry a business command. */
  public OperationView recover(
      BridgeIdentity identity, String id, OperationRecoveryResolver resolver) {
    Objects.requireNonNull(resolver);
    OperationOwner owner = OperationOwner.from(identity);
    OperationRecord current = store.locked(session -> owned(session, owner, id));
    if (current.state() != OperationState.UNKNOWN)
      throw error("RECOVERY_NOT_REQUIRED", "Only an unknown outcome can be reconciled");
    RecoveryDecision decision;
    try {
      decision = Objects.requireNonNull(resolver.resolve(OperationExecution.from(current)));
    } catch (Exception failure) {
      return OperationView.from(current);
    }
    if (decision.outcome() == null || decision.outcome() == RecoveryDecision.Outcome.UNRESOLVED)
      return OperationView.from(current);
    OperationRecord recovered;
    if (decision.outcome() == RecoveryDecision.Outcome.COMMITTED) {
      recovered =
          current.transition(
              OperationState.SUCCEEDED,
              bounded(decision.result(), maxResultBytes, "RESULT_TOO_LARGE"),
              null,
              clock.millis());
    } else {
      recovered =
          current.transition(
              OperationState.REJECTED, null, safeCode(decision.errorCode()), clock.millis());
    }
    return store.locked(
        session -> {
          OperationRecord latest = owned(session, owner, id);
          if (latest.state() != OperationState.UNKNOWN) return OperationView.from(latest);
          session.put(recovered);
          return OperationView.from(recovered);
        });
  }

  private OperationRecord persistOutcome(OperationRecord outcome) {
    try {
      return store.locked(
          session -> {
            OperationRecord latest = session.find(outcome.operationId());
            if (latest == null
                || latest.revision() != outcome.revision()
                || latest.state() != OperationState.EXECUTING)
              throw error(
                  "OPERATION_CONFLICT", "Operation state changed while recording its outcome");
            session.put(outcome);
            return outcome;
          });
    } catch (RuntimeException persistenceFailure) {
      // This invocation knows its own callback has ended. A reader must never make this
      // inference from process-local state because another coordinator may still be executing.
      OperationRecord unknown =
          outcome.transition(OperationState.UNKNOWN, null, "RECEIPT_NOT_CONFIRMED", clock.millis());
      try {
        store.locked(
            session -> {
              OperationRecord latest = session.find(outcome.operationId());
              if (latest != null
                  && latest.owner().equals(outcome.owner())
                  && latest.revision() == outcome.revision()
                  && latest.state() == OperationState.EXECUTING) session.put(unknown);
              return null;
            });
      } catch (RuntimeException stillUnavailable) {
        // A failed UNKNOWN receipt leaves EXECUTING non-retryable until authoritative store
        // recovery. Do not claim a later status read can prove this execution has stopped.
      }
      return unknown;
    }
  }

  private OperationRecord expire(OperationStore.Session session, OperationRecord record) {
    if (record.state() == OperationState.PREPARED
        && clock.millis() >= record.expiresAtEpochMillis()) {
      record = record.transition(OperationState.EXPIRED, null, "PREVIEW_EXPIRED", clock.millis());
      session.put(record);
    }
    return record;
  }

  private static OperationRecord owned(
      OperationStore.Session session, OperationOwner owner, String id) {
    OperationRecord record = session.find(id);
    if (record == null || !record.owner().equals(owner))
      throw error("OPERATION_NOT_FOUND", "Operation was not found for this identity and client");
    return record;
  }

  private static void requireRevision(OperationRecord record, long revision) {
    if (revision != record.revision())
      throw error(
          "CONFIRMATION_CHANGED", "Operation changed; review and confirm its current revision");
  }

  private static void requireConfirmation(OperationRecord record, long revision, String hash) {
    requireRevision(record, revision);
    if (hash == null
        || !MessageDigest.isEqual(
            record.payloadHash().getBytes(StandardCharsets.US_ASCII),
            hash.getBytes(StandardCharsets.US_ASCII)))
      throw error(
          "CONFIRMATION_CHANGED",
          "Operation content changed; review and confirm the current preview");
  }

  private JsonNode bounded(Object value, int limit, String code) {
    try {
      JsonNode node = canonical(mapper.valueToTree(value), 0);
      if (mapper.writeValueAsBytes(node).length > limit)
        throw error(code, "Operation content exceeds its configured limit");
      return node;
    } catch (IllegalArgumentException | java.io.IOException invalid) {
      throw error("INVALID_OPERATION_CONTENT", "Operation content must be bounded JSON data");
    }
  }

  private JsonNode canonical(JsonNode node, int depth) {
    if (depth > 64)
      throw error("INVALID_OPERATION_CONTENT", "Operation content exceeds the nesting limit");
    if (node == null || node.isNull()) return NullNode.instance;
    if (node.isObject()) {
      ObjectNode sorted = mapper.createObjectNode();
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      for (String name : names) sorted.set(name, canonical(node.get(name), depth + 1));
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode array = mapper.createArrayNode();
      for (JsonNode child : node) array.add(canonical(child, depth + 1));
      return array;
    }
    if ((node.numberValue() instanceof Double || node.numberValue() instanceof Float)
        && !Double.isFinite(node.doubleValue()))
      throw error("INVALID_OPERATION_CONTENT", "Non-finite numbers are not valid operation data");
    if (!node.isValueNode() || node.isPojo() || node.isMissingNode())
      throw error("INVALID_OPERATION_CONTENT", "Operation content must be JSON data");
    return node.deepCopy();
  }

  private String hash(String capability, JsonNode arguments) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(capability.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
      return HexFormat.of().formatHex(digest.digest(mapper.writeValueAsBytes(arguments)));
    } catch (GeneralSecurityException | java.io.IOException failure) {
      throw new IllegalStateException("Cannot hash operation", failure);
    }
  }

  private static void requireText(String value, int max, String name) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.chars().anyMatch(Character::isISOControl))
      throw error("INVALID_ARGUMENT", "Invalid " + name);
  }

  private static String safeCode(String code) {
    return code != null && code.matches("[A-Za-z0-9_.:-]{1,96}") ? code : "OPERATION_REJECTED";
  }

  private static BridgeException error(String code, String message) {
    return new BridgeException(code, message);
  }
}
