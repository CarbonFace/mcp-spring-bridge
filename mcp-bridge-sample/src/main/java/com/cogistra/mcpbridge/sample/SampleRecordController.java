package com.cogistra.mcpbridge.sample;

import com.cogistra.mcpbridge.annotation.*;
import com.cogistra.mcpbridge.operation.OperationRejectedException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.*;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * These are ordinary working HTTP endpoints; MCP registration is an additional explicit
 * declaration.
 */
@RestController
@RequestMapping("/sample/records")
public class SampleRecordController {
  private final SampleRecordService records;
  private final ObjectMapper json;
  private final URI publicBase;

  public SampleRecordController(
      SampleRecordService records,
      ObjectMapper json,
      @Value("${sample.public-base-url:http://127.0.0.1:8091}") String publicBase) {
    this.records = records;
    this.json = json;
    this.publicBase = URI.create(publicBase);
    if (!Set.of("http", "https").contains(this.publicBase.getScheme())
        || this.publicBase.getHost() == null
        || this.publicBase.getRawUserInfo() != null
        || this.publicBase.getRawQuery() != null
        || this.publicBase.getRawFragment() != null)
      throw new IllegalArgumentException("Sample public base URL must be an HTTP(S) origin");
  }

  @GetMapping
  @PreAuthorize("hasAuthority('sample:read')")
  @McpEndpoint(
      name = "sample_records",
      description = "List only the current account's synthetic records",
      effect = Effect.READ,
      scopes = "sample:read")
  public List<SampleRecord> list() {
    return records.list();
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('sample:read')")
  @McpEndpoint(
      name = "sample_record",
      description = "Read one owned synthetic record",
      effect = Effect.READ,
      scopes = "sample:read")
  public SampleRecord get(@PathVariable("id") String id) {
    return records.get(id);
  }

  @PatchMapping("/{id}")
  @PreAuthorize("hasAuthority('sample:write')")
  @McpEndpoint(
      name = "sample_patch",
      description =
          "Prepare an owned record edit; missing keys preserve values, note null clears it, version must match",
      effect = Effect.WRITE,
      scopes = "sample:write")
  public SampleRecord patch(@PathVariable("id") String id, @Valid @RequestBody SamplePatch patch) {
    return records.patch(id, patch);
  }

  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('sample:write')")
  @McpEndpoint(
      name = "sample_import",
      description =
          "Import 1 to 20 owned synthetic records from a previously uploaded UTF-8 JSON file",
      effect = Effect.WRITE,
      scopes = "sample:write")
  public List<SampleRecord> importFile(@RequestParam("file") MultipartFile file) {
    if (file == null || file.getSize() > 128 * 1024)
      throw new OperationRejectedException("IMPORT_TOO_LARGE", "Import size limit is 128 KiB");
    try (InputStream input = file.getInputStream()) {
      byte[] bytes = input.readNBytes(128 * 1024 + 1);
      if (bytes.length > 128 * 1024)
        throw new OperationRejectedException("IMPORT_TOO_LARGE", "Import size limit is 128 KiB");
      return records.importRecords(
          json.readerFor(JsonNode.class)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readValue(bytes));
    } catch (IOException invalid) {
      throw new OperationRejectedException(
          "INVALID_IMPORT", "Import must be a valid UTF-8 JSON file");
    }
  }

  @GetMapping("/{id}/download")
  @PreAuthorize("hasAuthority('sample:read')")
  @McpEndpoint(
      name = "sample_export_file",
      description = "Export one owned synthetic record as a JSON download",
      effect = Effect.READ,
      scopes = "sample:read")
  public void download(@PathVariable("id") String id, HttpServletResponse response)
      throws IOException {
    SampleRecord record = records.get(id);
    response.setContentType("application/json");
    response.setHeader("Content-Disposition", "attachment; filename=sample-record.json");
    response.setHeader("Cache-Control", "no-store");
    json.writeValue(response.getOutputStream(), record);
  }

  @GetMapping("/{id}/export-url")
  @PreAuthorize("hasAuthority('sample:read')")
  @McpEndpoint(
      name = "sample_export_url",
      description = "Return the existing authenticated HTTP download URL without fetching it",
      effect = Effect.READ,
      scopes = "sample:read")
  public Map<String, String> exportUrl(@PathVariable("id") String id) {
    SampleRecord record = records.get(id);
    return Map.of(
        "url",
        publicBase.resolve("/sample/records/" + record.id() + "/download").toString(),
        "authentication",
        "required");
  }
}
