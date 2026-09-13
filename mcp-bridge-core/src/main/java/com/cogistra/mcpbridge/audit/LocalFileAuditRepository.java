package com.cogistra.mcpbridge.audit;

import com.cogistra.mcpbridge.api.BridgeException;
import com.cogistra.mcpbridge.api.BridgeIdentity;
import com.cogistra.mcpbridge.operation.AtomicPrivateJsonFile;
import com.cogistra.mcpbridge.operation.OperationOwner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/**
 * Local single-writer metadata audit. No eviction: a full repository rejects new appends visibly.
 */
public final class LocalFileAuditRepository implements AuditRepository {
  public record Entry(long sequence, AuditEvent event) {}

  public record Snapshot(int formatVersion, List<Entry> entries) {}

  private final AtomicPrivateJsonFile<Snapshot> file;
  private final int maxEvents;
  private List<Entry> entries;
  private boolean closed;

  public LocalFileAuditRepository(Path directory, int maxEvents, long maxBytes) {
    if (maxEvents < 1 || maxEvents > 100_000)
      throw new IllegalArgumentException("Audit count limit must be 1 to 100000");
    this.maxEvents = maxEvents;
    file = new AtomicPrivateJsonFile<>(directory, "audit.json", maxBytes, Snapshot.class);
    try {
      Snapshot snapshot = file.readOr(new Snapshot(1, List.of()));
      if (snapshot == null
          || snapshot.formatVersion() != 1
          || snapshot.entries() == null
          || snapshot.entries().size() > maxEvents) throw invalid();
      long previous = 0;
      Set<String> ids = new HashSet<>();
      for (Entry entry : snapshot.entries()) {
        if (entry == null
            || entry.event() == null
            || entry.sequence() <= previous
            || !ids.add(entry.event().eventId())) throw invalid();
        previous = entry.sequence();
      }
      entries = List.copyOf(snapshot.entries());
    } catch (RuntimeException failure) {
      file.close();
      throw failure;
    }
  }

  @Override
  public synchronized void append(AuditEvent event) {
    requireOpen();
    Objects.requireNonNull(event);
    Optional<Entry> duplicate =
        entries.stream().filter(e -> e.event().eventId().equals(event.eventId())).findFirst();
    if (duplicate.isPresent()) {
      if (!duplicate.get().event().equals(event))
        throw new BridgeException(
            "AUDIT_EVENT_CONFLICT", "Audit event identifier is already in use");
      return;
    }
    if (entries.size() >= maxEvents)
      throw new BridgeException("AUDIT_FULL", "Audit storage reached its configured record limit");
    long sequence =
        entries.isEmpty() ? 1 : Math.addExact(entries.get(entries.size() - 1).sequence(), 1);
    List<Entry> next = new ArrayList<>(entries);
    next.add(new Entry(sequence, event));
    file.write(new Snapshot(1, next));
    entries = List.copyOf(next);
  }

  @Override
  public synchronized AuditPage query(BridgeIdentity identity, String cursor, int limit) {
    requireOpen();
    OperationOwner owner = OperationOwner.from(identity);
    if (limit < 1 || limit > 200)
      throw new BridgeException("INVALID_PAGE_LIMIT", "Audit page size must be 1 to 200");
    long after = decodeCursor(owner, cursor);
    List<Entry> page =
        entries.stream()
            .filter(e -> e.sequence() > after && e.event().owner().equals(owner))
            .limit((long) limit + 1)
            .toList();
    boolean more = page.size() > limit;
    List<Entry> selected = more ? page.subList(0, limit) : page;
    String next = more ? encodeCursor(owner, selected.get(selected.size() - 1).sequence()) : null;
    return new AuditPage(selected.stream().map(Entry::event).toList(), next);
  }

  private String encodeCursor(OperationOwner owner, long sequence) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((ownerTag(owner) + ":" + sequence).getBytes(StandardCharsets.US_ASCII));
  }

  private long decodeCursor(OperationOwner owner, String cursor) {
    if (cursor == null || cursor.isBlank()) return 0;
    try {
      if (cursor.length() > 160) throw new IllegalArgumentException();
      String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
      String[] pieces = text.split(":", -1);
      if (pieces.length != 2 || !pieces[0].equals(ownerTag(owner)))
        throw new IllegalArgumentException();
      long sequence = Long.parseLong(pieces[1]);
      if (sequence < 0) throw new IllegalArgumentException();
      return sequence;
    } catch (IllegalArgumentException invalid) {
      throw new BridgeException(
          "INVALID_CURSOR", "Audit cursor does not match this identity and client");
    }
  }

  private String ownerTag(OperationOwner owner) {
    String key =
        owner.issuer().length()
            + ":"
            + owner.issuer()
            + owner.subject().length()
            + ":"
            + owner.subject()
            + owner.clientId().length()
            + ":"
            + owner.clientId();
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void requireOpen() {
    if (closed) throw new BridgeException("AUDIT_CLOSED", "Audit storage is closed");
  }

  private static BridgeException invalid() {
    return new BridgeException(
        "AUDIT_INVALID", "Audit storage is invalid; existing data was preserved");
  }

  @Override
  public synchronized void close() {
    closed = true;
    file.close();
  }
}
