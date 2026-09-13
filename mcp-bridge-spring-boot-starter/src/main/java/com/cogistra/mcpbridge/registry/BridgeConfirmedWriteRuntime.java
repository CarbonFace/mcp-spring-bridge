package com.cogistra.mcpbridge.registry;

import com.cogistra.mcpbridge.api.BridgePrincipal;
import java.util.Map;

/**
 * Host transaction boundary for one explicitly registered WRITE capability. The catalog owns
 * discovery, protocol schemas, identity, authorization, audit and the fixed proxy invocation.
 * Implementations own durable intent validation, revision invalidation, expiry, ownership and
 * idempotency. A submit must call the provided invocation only after revalidating the saved review,
 * in the same host transaction as its business receipt. It must never infer a human's chat consent.
 */
public interface BridgeConfirmedWriteRuntime {
  String capabilityName();

  Object prepare(BridgePrincipal principal, Map<String, Object> arguments, String requestId);

  Object revise(
      BridgePrincipal principal, String operationId, long revision, Map<String, Object> arguments);

  /** Exactly one of operationId or requestId is non-null. */
  Object status(BridgePrincipal principal, String operationId, String requestId);

  Object cancel(BridgePrincipal principal, String operationId, long revision);

  Object submit(
      BridgePrincipal principal,
      String operationId,
      long revision,
      String payloadHash,
      Map<String, Object> review,
      ConfirmedInvocation invocation);

  @FunctionalInterface
  interface ConfirmedInvocation {
    /** Canonical arguments must be derived from the owned, confirmed server snapshot. */
    Object invoke(Map<String, Object> canonicalArguments) throws Exception;
  }
}
