package com.cogistra.mcpbridge.operation;

@FunctionalInterface
public interface OperationCallback {
  Object execute(OperationExecution execution) throws Exception;
}
