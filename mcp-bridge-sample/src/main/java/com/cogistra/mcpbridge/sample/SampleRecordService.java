package com.cogistra.mcpbridge.sample;

import com.cogistra.mcpbridge.operation.OperationRejectedException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Bounded in-memory host domain, independent of the MCP protocol and any real business system. */
@Service
public class SampleRecordService {
  private final Map<String, SampleRecord> records = new LinkedHashMap<>();

  public SampleRecordService() {
    records.put(
        "alice-1",
        new SampleRecord("alice-1", "alice", 1, "Alice sample record", "Synthetic note A"));
    records.put(
        "bob-1", new SampleRecord("bob-1", "bob", 1, "Bob sample record", "Synthetic note B"));
  }

  public synchronized List<SampleRecord> list() {
    String subject = subject();
    return records.values().stream().filter(record -> record.owner().equals(subject)).toList();
  }

  public synchronized SampleRecord get(String id) {
    return owned(id);
  }

  public synchronized SampleRecord patch(String id, SamplePatch patch) {
    SampleRecord current = owned(id);
    if (patch == null || patch.changes() == null || patch.changes().isEmpty())
      throw rejected("INVALID_PATCH", "Supply at least one changed field");
    if (current.version() != patch.version())
      throw rejected("VERSION_CONFLICT", "The record changed; reload it before editing");
    if (!Set.of("title", "note").containsAll(patch.changes().keySet()))
      throw rejected("INVALID_PATCH", "Only title and note may be changed");
    String title =
        patch.changes().containsKey("title")
            ? title(patch.changes().get("title"))
            : current.title();
    String note =
        patch.changes().containsKey("note") ? note(patch.changes().get("note")) : current.note();
    SampleRecord changed =
        new SampleRecord(id, current.owner(), Math.addExact(current.version(), 1), title, note);
    records.put(id, changed);
    return changed;
  }

  /** Parse and validate every row before adding any records, to avoid partial imports. */
  public synchronized List<SampleRecord> importRecords(JsonNode rows) {
    String owner = subject();
    if (rows == null || !rows.isArray() || rows.isEmpty() || rows.size() > 20)
      throw rejected("INVALID_IMPORT", "Import a JSON array containing 1 to 20 rows");
    long existing =
        records.values().stream().filter(record -> record.owner().equals(owner)).count();
    if (existing + rows.size() > 100)
      throw rejected("SAMPLE_CAPACITY", "The sample supports at most 100 records per account");
    if (records.size() + rows.size() > 200)
      throw rejected("SAMPLE_CAPACITY", "The sample supports at most 200 records in total");
    List<SampleRecord> created = new ArrayList<>();
    for (JsonNode row : rows) {
      if (!row.isObject()) throw rejected("INVALID_IMPORT", "Every import row must be an object");
      Set<String> names = new HashSet<>();
      row.fieldNames().forEachRemaining(names::add);
      if (!Set.of("title", "note").containsAll(names))
        throw rejected("INVALID_IMPORT", "Import fields are title and note only");
      created.add(
          new SampleRecord(
              UUID.randomUUID().toString(),
              owner,
              1,
              title(row.get("title")),
              note(row.get("note"))));
    }
    created.forEach(record -> records.put(record.id(), record));
    return List.copyOf(created);
  }

  private SampleRecord owned(String id) {
    String owner = subject();
    SampleRecord record = records.get(id);
    if (record == null || !record.owner().equals(owner))
      throw rejected("RECORD_NOT_FOUND", "Record was not found for the current account");
    return record;
  }

  private String subject() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication
            instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
      throw rejected("AUTHENTICATION_REQUIRED", "An authenticated account is required");
    return authentication.getName();
  }

  private String title(JsonNode value) {
    if (value == null
        || !value.isTextual()
        || value.textValue().isBlank()
        || value.textValue().length() > 120)
      throw rejected("INVALID_TITLE", "Title must contain 1 to 120 characters");
    return value.textValue();
  }

  private String note(JsonNode value) {
    if (value == null || value.isNull()) return null;
    if (!value.isTextual() || value.textValue().length() > 2000)
      throw rejected("INVALID_NOTE", "Note must be text of at most 2000 characters or null");
    return value.textValue();
  }

  private OperationRejectedException rejected(String code, String message) {
    return new OperationRejectedException(code, message);
  }
}
