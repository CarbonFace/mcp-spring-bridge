package com.cogistra.mcpbridge.guidance;

import java.util.*;

/** Trusted, versioned host assets. Paths are explicit plugin-relative names, never server paths. */
public record BridgeGuidance(
    String id,
    String title,
    String description,
    String version,
    String entrypoint,
    Map<String, byte[]> files,
    Set<String> requiredTools) {
  public static final int MAX_FILES = 128;
  public static final int MAX_FILE_BYTES = 256 * 1024;
  public static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;

  public BridgeGuidance {
    if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,63}"))
      throw new IllegalArgumentException("Invalid guidance id");
    text(title, 160);
    text(description, 1024);
    if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
      throw new IllegalArgumentException("Invalid guidance version");
    path(entrypoint);
    if (!entrypoint.endsWith("/SKILL.md") && !entrypoint.equals("SKILL.md"))
      throw new IllegalArgumentException("Guidance entrypoint must be SKILL.md");
    if (files == null || files.isEmpty() || files.size() > MAX_FILES)
      throw new IllegalArgumentException("Guidance needs a bounded explicit asset manifest");
    var copied = new TreeMap<String, byte[]>();
    var portable = new HashSet<String>();
    long total = 0;
    for (var file : files.entrySet()) {
      path(file.getKey());
      if (!portable.add(file.getKey().toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException(
            "Guidance paths must be unique on all supported clients");
      byte[] bytes = Objects.requireNonNull(file.getValue(), "Guidance bytes");
      total += bytes.length;
      if (bytes.length > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES)
        throw new IllegalArgumentException("Guidance assets exceed their size limit");
      copied.put(file.getKey(), bytes.clone());
    }
    for (String asset : portable) {
      for (int slash = asset.indexOf('/'); slash >= 0; slash = asset.indexOf('/', slash + 1))
        if (portable.contains(asset.substring(0, slash)))
          throw new IllegalArgumentException(
              "Guidance assets cannot be both a file and a directory");
    }
    if (!copied.containsKey(entrypoint))
      throw new IllegalArgumentException("Guidance entrypoint is absent from its asset manifest");
    files = Collections.unmodifiableMap(copied);
    requiredTools = Set.copyOf(requiredTools);
    for (String tool : requiredTools)
      if (!tool.matches("[A-Za-z0-9_.-]{1,128}") || tool.equals("bridge_get_guidance"))
        throw new IllegalArgumentException("Invalid or recursive guidance tool dependency");
  }

  /** A provider or caller cannot mutate registered content after its digest has been calculated. */
  @Override
  public Map<String, byte[]> files() {
    var copied = new TreeMap<String, byte[]>();
    files.forEach((name, bytes) -> copied.put(name, bytes.clone()));
    return Collections.unmodifiableMap(copied);
  }

  private static void text(String value, int maximum) {
    if (value == null || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid guidance metadata");
  }

  private static void path(String path) {
    if (path == null || path.isEmpty() || path.length() > 240)
      throw new IllegalArgumentException("Invalid guidance asset path");
    for (String segment : path.split("/", -1)) {
      if (!segment.matches("[A-Za-z0-9._-]+")
          || segment.equals(".")
          || segment.equals("..")
          || segment.endsWith(".")
          || segment.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
        throw new IllegalArgumentException("Guidance assets must use safe relative portable paths");
    }
  }
}
