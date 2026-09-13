package com.cogistra.mcpbridge.guidance;

import com.cogistra.mcpbridge.api.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.zip.*;

/** One immutable source for progressive instruction reads and optional private bundle exports. */
public final class BridgeGuidanceRegistry {
  public static final String TOOL_NAME = "bridge_get_guidance";
  public static final String NAVIGATION =
      " Before a matching business task, call bridge_get_guidance with no arguments to discover available skills, then read the selected skill and its referenced sections. Guidance is loaded into this conversation; downloading a bundle does not install it or confirm any business action.";
  private final Map<String, Entry> entries = new TreeMap<>();
  private final BiPredicate<String, BridgePrincipal> toolVisible;
  private final BridgeFileStore files;
  private final long maxExportBytes;

  public BridgeGuidanceRegistry(
      List<BridgeGuidanceProvider> providers,
      BiPredicate<String, BridgePrincipal> toolVisible,
      BridgeFileStore files,
      long maxExportBytes) {
    this.toolVisible = toolVisible;
    this.files = files;
    this.maxExportBytes = maxExportBytes;
    long total = 0;
    for (var provider : providers) {
      for (var guidance : List.copyOf(provider.guidance())) {
        var assets = guidance.files();
        for (byte[] bytes : assets.values()) total += bytes.length;
        if (entries.size() >= 64 || total > 8 * 1024 * 1024)
          throw new IllegalArgumentException(
              "Registered guidance exceeds its bounded catalog limit");
        Entry entry = new Entry(guidance, provider, assets);
        if (entries.putIfAbsent(guidance.id(), entry) != null)
          throw new IllegalArgumentException("Duplicate guidance id");
      }
    }
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  public Object read(BridgePrincipal principal, Map<String, Object> arguments) {
    String skillId = (String) arguments.get("skillId");
    String section = (String) arguments.get("section");
    String format = (String) arguments.getOrDefault("format", "text");
    if (skillId == null) {
      if (section != null || arguments.containsKey("format")) throw invalid();
      return Map.of(
          "skills",
          entries.values().stream()
              .filter(entry -> available(entry, principal))
              .map(this::metadata)
              .toList());
    }
    if ((!format.equals("text") && !format.equals("bundle"))
        || (format.equals("bundle") && section != null)) throw invalid();
    Entry entry = entries.get(skillId);
    if (entry == null || !available(entry, principal))
      throw new BridgeException("GUIDANCE_UNAVAILABLE", "The requested guidance is unavailable");
    if (format.equals("bundle")) {
      if (files == null)
        throw new BridgeException("FILES_DISABLED", "File support is disabled in this application");
      if (entry.bundle.length > maxExportBytes)
        throw new BridgeException("EXPORT_TOO_LARGE", "Guidance bundle exceeds the export limit");
      FileArtifact artifact =
          files.store(
              principal.identity(),
              skillId + "-" + entry.definition.version() + ".zip",
              "application/zip",
              new ByteArrayInputStream(entry.bundle));
      var metadata = new LinkedHashMap<String, Object>();
      metadata.put("fileId", artifact.fileId());
      metadata.put("filename", artifact.filename());
      metadata.put("mediaType", artifact.mediaType());
      metadata.put("size", artifact.size());
      metadata.put("url", artifact.url());
      metadata.put(
          "expiresAt", artifact.expiresAt() == null ? null : artifact.expiresAt().toString());
      return Map.of(
          "skillId",
          skillId,
          "version",
          entry.definition.version(),
          "bundleSha256",
          entry.bundleSha256,
          "artifact",
          metadata);
    }
    String path = section == null ? entry.definition.entrypoint() : section;
    String content = entry.text.get(path);
    if (content == null)
      throw new BridgeException(
          "GUIDANCE_SECTION_UNAVAILABLE", "Use an exact registered text section");
    return Map.of(
        "skillId",
        skillId,
        "version",
        entry.definition.version(),
        "bundleSha256",
        entry.bundleSha256,
        "section",
        path,
        "content",
        content,
        "sections",
        entry.sections,
        "bundleAvailable",
        bundleAvailable(entry));
  }

  private Map<String, Object> metadata(Entry entry) {
    var value = entry.definition;
    return Map.of(
        "id", value.id(),
        "title", value.title(),
        "description", value.description(),
        "version", value.version(),
        "entrypoint", value.entrypoint(),
        "bundleSha256", entry.bundleSha256,
        "bundleAvailable", bundleAvailable(entry));
  }

  private boolean bundleAvailable(Entry entry) {
    return files != null && entry.bundle.length <= maxExportBytes;
  }

  private boolean available(Entry entry, BridgePrincipal principal) {
    try {
      return entry.definition.requiredTools().stream()
              .allMatch(tool -> toolVisible.test(tool, principal))
          && entry.provider.available(entry.definition.id(), principal);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private static BridgeException invalid() {
    return new BridgeException(
        "INVALID_ARGUMENT",
        "Use catalog, skill text, section text, or bundle arguments separately");
  }

  private static final class Entry {
    final BridgeGuidance definition;
    final BridgeGuidanceProvider provider;
    final Map<String, String> text = new TreeMap<>();
    final List<Map<String, Object>> sections = new ArrayList<>();
    final byte[] bundle;
    final String bundleSha256;

    Entry(BridgeGuidance definition, BridgeGuidanceProvider provider, Map<String, byte[]> assets) {
      this.definition = definition;
      this.provider = provider;
      try {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
          for (var asset : assets.entrySet()) {
            String path = asset.getKey();
            byte[] bytes = asset.getValue();
            if (path.matches("(?i).*\\.(md|txt|json|yaml|yml)")) {
              String decoded =
                  StandardCharsets.UTF_8
                      .newDecoder()
                      .onMalformedInput(CodingErrorAction.REPORT)
                      .onUnmappableCharacter(CodingErrorAction.REPORT)
                      .decode(ByteBuffer.wrap(bytes))
                      .toString();
              if (decoded.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Guidance text contains NUL");
              text.put(path, decoded);
              sections.add(Map.of("path", path, "sha256", sha256(bytes), "bytes", bytes.length));
            }
            var item = new ZipEntry(path);
            item.setMethod(ZipEntry.STORED);
            // 1980-01-01 is the JDK sentinel that adds timezone-dependent extended timestamps.
            item.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0));
            item.setSize(bytes.length);
            var crc = new CRC32();
            crc.update(bytes);
            item.setCrc(crc.getValue());
            zip.putNextEntry(item);
            zip.write(bytes);
            zip.closeEntry();
          }
        }
        bundle = output.toByteArray();
        bundleSha256 = sha256(bundle);
      } catch (IOException invalid) {
        throw new IllegalArgumentException(
            "Guidance assets must contain valid UTF-8 text", invalid);
      }
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
