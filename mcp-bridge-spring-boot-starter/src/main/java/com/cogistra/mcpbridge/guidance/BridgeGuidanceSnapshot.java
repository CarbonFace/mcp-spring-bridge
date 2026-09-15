package com.cogistra.mcpbridge.guidance;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;

/**
 * Validated immutable text and archive compiled identically by host publishers and online reads.
 */
public final class BridgeGuidanceSnapshot {
  private final BridgeGuidance definition;
  private final Map<String, String> text;
  private final List<Map<String, Object>> sections;
  private final byte[] bundle;
  private final String bundleSha256;
  private final long sourceBytes;

  private BridgeGuidanceSnapshot(BridgeGuidance definition) {
    this.definition = Objects.requireNonNull(definition, "Guidance definition is required");
    var texts = new TreeMap<String, String>();
    var metadata = new ArrayList<Map<String, Object>>();
    long total = 0;
    try {
      var output = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
        for (var asset : definition.files().entrySet()) {
          String path = asset.getKey();
          byte[] bytes = asset.getValue();
          total += bytes.length;
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
            texts.put(path, decoded);
            metadata.add(Map.of("path", path, "sha256", sha256(bytes), "bytes", bytes.length));
          }
          var item = new ZipEntry(path);
          item.setMethod(ZipEntry.STORED);
          // Avoid the JDK's 1980 sentinel, which adds timezone-dependent extended timestamps.
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
      sourceBytes = total;
      text = Collections.unmodifiableMap(texts);
      sections = List.copyOf(metadata);
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Guidance assets must contain valid UTF-8 text", invalid);
    }
  }

  public static BridgeGuidanceSnapshot of(BridgeGuidance definition) {
    return new BridgeGuidanceSnapshot(definition);
  }

  public BridgeGuidance definition() {
    return definition;
  }

  public byte[] bundleBytes() {
    return bundle.clone();
  }

  public String bundleSha256() {
    return bundleSha256;
  }

  public long sourceBytes() {
    return sourceBytes;
  }

  public BridgeGuidanceVersion version() {
    return new BridgeGuidanceVersion(definition.version(), bundleSha256);
  }

  Map<String, String> text() {
    return text;
  }

  List<Map<String, Object>> sections() {
    return sections;
  }

  int bundleSize() {
    return bundle.length;
  }

  InputStream bundleStream() {
    return new ByteArrayInputStream(bundle);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
