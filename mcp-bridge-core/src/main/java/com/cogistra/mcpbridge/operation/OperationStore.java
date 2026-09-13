package com.cogistra.mcpbridge.operation;

import java.util.List;
import java.util.function.Function;

/**
 * Each locked action is mutually exclusive across every writer. put must durably complete before
 * returning.
 */
public interface OperationStore extends AutoCloseable {
  <T> T locked(Function<Session, T> action);

  interface Session {
    OperationRecord find(String operationId);

    OperationRecord findRequest(OperationOwner owner, String requestId);

    List<OperationRecord> records();

    void put(OperationRecord record);
  }

  @Override
  default void close() {}
}
