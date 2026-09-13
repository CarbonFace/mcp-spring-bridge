package com.cogistra.mcpbridge.operation;

@FunctionalInterface
public interface OperationRecoveryResolver {
  /**
   * Query the host's durable receipt/transaction by operationId; do not execute the business
   * command.
   */
  RecoveryDecision resolve(OperationExecution execution) throws Exception;
}
