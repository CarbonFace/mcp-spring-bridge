package com.cogistra.mcpbridge.operation;

/**
 * A host-authorized recovery answer based on durable business evidence, never a model assertion.
 */
public record RecoveryDecision(Outcome outcome, Object result, String errorCode) {
  public enum Outcome {
    COMMITTED,
    NO_EFFECT,
    UNRESOLVED
  }

  public static RecoveryDecision committed(Object result) {
    return new RecoveryDecision(Outcome.COMMITTED, result, null);
  }

  public static RecoveryDecision noEffect(String code) {
    return new RecoveryDecision(Outcome.NO_EFFECT, null, code);
  }

  public static RecoveryDecision unresolved() {
    return new RecoveryDecision(Outcome.UNRESOLVED, null, null);
  }
}
