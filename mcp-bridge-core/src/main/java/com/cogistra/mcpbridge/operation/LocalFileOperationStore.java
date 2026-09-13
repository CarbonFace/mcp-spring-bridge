package com.cogistra.mcpbridge.operation;

import com.cogistra.mcpbridge.api.BridgeException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** Bounded single-process store. Keeps dedupe receipts until an explicitly governed migration. */
public final class LocalFileOperationStore implements OperationStore {
  public record Snapshot(int formatVersion, List<OperationRecord> records) {}

  private final AtomicPrivateJsonFile<Snapshot> file;
  private final int maxRecords;
  private Map<String, OperationRecord> records = new LinkedHashMap<>();
  private boolean closed;

  public LocalFileOperationStore(Path directory, int maxRecords, long maxBytes) {
    if (maxRecords < 1 || maxRecords > 100_000)
      throw new IllegalArgumentException("Operation record limit must be 1 to 100000");
    this.maxRecords = maxRecords;
    file = new AtomicPrivateJsonFile<>(directory, "operations.json", maxBytes, Snapshot.class);
    try {
      Snapshot snapshot = file.readOr(new Snapshot(1, List.of()));
      if (snapshot == null
          || snapshot.formatVersion() != 1
          || snapshot.records() == null
          || snapshot.records().size() > maxRecords) throw invalid();
      Set<String> requests = new HashSet<>();
      boolean recovered = false;
      for (OperationRecord record : snapshot.records()) {
        if (record == null
            || record.revision() < 1
            || record.arguments() == null
            || record.operationId() == null
            || records.containsKey(record.operationId())) throw invalid();
        String requestKey =
            record.owner().issuer().length()
                + ":"
                + record.owner().issuer()
                + record.owner().subject().length()
                + ":"
                + record.owner().subject()
                + record.owner().clientId().length()
                + ":"
                + record.owner().clientId()
                + record.requestId();
        if (!requests.add(requestKey)) throw invalid();
        if (record.state() == OperationState.EXECUTING) {
          record =
              record.transition(
                  OperationState.UNKNOWN, null, "PROCESS_INTERRUPTED", System.currentTimeMillis());
          recovered = true;
        }
        records.put(record.operationId(), record);
      }
      if (recovered) persist(records);
    } catch (RuntimeException failure) {
      file.close();
      throw failure;
    }
  }

  @Override
  public synchronized <T> T locked(Function<Session, T> action) {
    if (closed) throw new BridgeException("STORAGE_CLOSED", "Operation storage is closed");
    Session session =
        new Session() {
          private void requireLock() {
            if (!Thread.holdsLock(LocalFileOperationStore.this) || closed)
              throw new IllegalStateException("Store session escaped its lock");
          }

          public OperationRecord find(String id) {
            requireLock();
            return records.get(id);
          }

          public OperationRecord findRequest(OperationOwner owner, String requestId) {
            requireLock();
            return records.values().stream()
                .filter(r -> r.owner().equals(owner) && r.requestId().equals(requestId))
                .findFirst()
                .orElse(null);
          }

          public List<OperationRecord> records() {
            requireLock();
            return List.copyOf(records.values());
          }

          public void put(OperationRecord record) {
            requireLock();
            if (!records.containsKey(record.operationId()) && records.size() >= maxRecords)
              throw new BridgeException(
                  "STORAGE_FULL", "Operation storage reached its record limit");
            OperationRecord existing = findRequest(record.owner(), record.requestId());
            if (existing != null && !existing.operationId().equals(record.operationId()))
              throw new BridgeException("REQUEST_CONFLICT", "Request identifier is already in use");
            Map<String, OperationRecord> changed = new LinkedHashMap<>(records);
            changed.put(record.operationId(), record);
            persist(changed);
            records = changed;
          }
        };
    return action.apply(session);
  }

  private void persist(Map<String, OperationRecord> state) {
    file.write(new Snapshot(1, List.copyOf(state.values())));
  }

  private static BridgeException invalid() {
    return new BridgeException(
        "STORAGE_INVALID", "Operation storage is invalid; existing data was preserved");
  }

  @Override
  public synchronized void close() {
    closed = true;
    file.close();
  }
}
