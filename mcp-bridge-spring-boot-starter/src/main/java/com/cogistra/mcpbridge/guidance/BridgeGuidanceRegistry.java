package com.cogistra.mcpbridge.guidance;

import com.cogistra.mcpbridge.api.*;
import java.util.*;
import java.util.function.BiPredicate;

/** Version-consistent reads from static assets and host-owned immutable published snapshots. */
public final class BridgeGuidanceRegistry {
  public static final String TOOL_NAME = "bridge_get_guidance";
  public static final String NAVIGATION =
      " Before a matching business task, call bridge_get_guidance with no arguments to discover available skills, then read the selected skill and its referenced sections. Carry the returned version on subsequent section and bundle reads; when omitting it, check for a changed version. Guidance is loaded into this conversation; downloading a bundle does not install it or confirm any business action.";
  private static final int MAX_ENTRIES = 64;
  private static final long MAX_SOURCE_BYTES = 8 * 1024 * 1024;
  private static final int MAX_CACHED_SNAPSHOTS = 64;
  private final Map<String, Registration> entries = new TreeMap<>();
  private final LinkedHashMap<CacheKey, BridgeGuidanceSnapshot> cache =
      new LinkedHashMap<>(16, 0.75f, true);
  private final BiPredicate<String, BridgePrincipal> toolVisible;
  private final BridgeFileStore files;
  private final long maxExportBytes;
  private long registeredSourceBytes;
  private long cachedSourceBytes;

  public BridgeGuidanceRegistry(
      List<BridgeGuidanceProvider> providers,
      BiPredicate<String, BridgePrincipal> toolVisible,
      BridgeFileStore files,
      long maxExportBytes) {
    this.toolVisible = toolVisible;
    this.files = files;
    this.maxExportBytes = maxExportBytes;
    for (var provider : providers) {
      for (var guidance : List.copyOf(provider.guidance())) {
        var snapshot = BridgeGuidanceSnapshot.of(guidance);
        register(
            new Registration(
                guidance.id(),
                guidance.requiredTools(),
                provider,
                null,
                snapshot,
                snapshot.sourceBytes()));
      }
      if (provider instanceof BridgeDynamicGuidanceProvider dynamic) {
        for (var descriptor : List.copyOf(dynamic.descriptors()))
          register(
              new Registration(
                  descriptor.id(),
                  descriptor.requiredTools(),
                  provider,
                  dynamic,
                  null,
                  descriptor.maxSourceBytes()));
      }
    }
  }

  private void register(Registration entry) {
    if (entries.size() >= MAX_ENTRIES
        || registeredSourceBytes + entry.maxSourceBytes > MAX_SOURCE_BYTES)
      throw new IllegalArgumentException("Registered guidance exceeds its bounded catalog limit");
    if (entries.putIfAbsent(entry.id, entry) != null)
      throw new IllegalArgumentException("Duplicate guidance id");
    registeredSourceBytes += entry.maxSourceBytes;
  }

  /** Dynamic descriptors count even before their host has published the first version. */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  public Object read(BridgePrincipal principal, Map<String, Object> arguments) {
    if (!Set.of("skillId", "section", "format", "version").containsAll(arguments.keySet()))
      throw invalid();
    String skillId = argument(arguments, "skillId");
    String section = argument(arguments, "section");
    String requestedVersion = argument(arguments, "version");
    String format = arguments.containsKey("format") ? argument(arguments, "format") : "text";
    if (requestedVersion != null && !requestedVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
      throw invalid();
    if (skillId == null) {
      if (section != null || requestedVersion != null || arguments.containsKey("format"))
        throw invalid();
      return catalog(principal);
    }
    if ((!format.equals("text") && !format.equals("bundle"))
        || (format.equals("bundle") && section != null)) throw invalid();
    Registration registration = entries.get(skillId);
    if (registration == null || !available(registration, principal))
      throw new BridgeException("GUIDANCE_UNAVAILABLE", "The requested guidance is unavailable");
    BridgeGuidanceSnapshot snapshot = snapshot(registration, requestedVersion);
    if (format.equals("bundle")) {
      if (files == null)
        throw new BridgeException("FILES_DISABLED", "File support is disabled in this application");
      if (snapshot.bundleSize() > maxExportBytes)
        throw new BridgeException("EXPORT_TOO_LARGE", "Guidance bundle exceeds the export limit");
      FileArtifact artifact =
          files.store(
              principal.identity(),
              skillId + "-" + snapshot.definition().version() + ".zip",
              "application/zip",
              snapshot.bundleStream());
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
          snapshot.definition().version(),
          "bundleSha256",
          snapshot.bundleSha256(),
          "artifact",
          metadata);
    }
    String path = section == null ? snapshot.definition().entrypoint() : section;
    String content = snapshot.text().get(path);
    if (content == null)
      throw new BridgeException(
          "GUIDANCE_SECTION_UNAVAILABLE", "Use an exact registered text section");
    return Map.of(
        "skillId",
        skillId,
        "version",
        snapshot.definition().version(),
        "bundleSha256",
        snapshot.bundleSha256(),
        "section",
        path,
        "content",
        content,
        "sections",
        snapshot.sections(),
        "bundleAvailable",
        bundleAvailable(snapshot));
  }

  private Object catalog(BridgePrincipal principal) {
    var skills = new ArrayList<Map<String, Object>>();
    var unavailable = new ArrayList<Map<String, String>>();
    for (Registration entry : entries.values()) {
      if (!available(entry, principal)) continue;
      try {
        skills.add(metadata(snapshot(entry, null)));
      } catch (BridgeException failure) {
        // One uninitialized/incompatible source must not hide another source's maintenance guide.
        unavailable.add(
            Map.of("id", entry.id, "code", failure.code(), "message", failure.getMessage()));
      }
    }
    if (unavailable.isEmpty()) return Map.of("skills", skills);
    return Map.of("skills", skills, "unavailableSkills", unavailable);
  }

  private BridgeGuidanceSnapshot snapshot(Registration entry, String requestedVersion) {
    if (entry.fixed != null) {
      if (requestedVersion != null && !requestedVersion.equals(entry.fixed.definition().version()))
        throw unavailableVersion();
      return entry.fixed;
    }
    // Never cache the current pointer or the host's current compatibility/availability decision.
    BridgeGuidanceVersion version = entry.dynamic.resolveVersion(entry.id, requestedVersion);
    if (version == null) throw invalidSnapshot();
    if (requestedVersion != null && !requestedVersion.equals(version.version()))
      throw unavailableVersion();
    CacheKey key = new CacheKey(entry.id, version.version(), version.bundleSha256());
    synchronized (cache) {
      verifyVersionIdentity(key);
      BridgeGuidanceSnapshot present = cache.get(key);
      if (present != null) return present;
    }
    BridgeGuidanceSnapshot loaded;
    try {
      BridgeGuidance definition = entry.dynamic.load(entry.id, version);
      if (definition == null
          || !entry.id.equals(definition.id())
          || !entry.requiredTools.equals(definition.requiredTools())
          || !version.version().equals(definition.version())) throw invalidSnapshot();
      loaded = BridgeGuidanceSnapshot.of(definition);
      if (loaded.sourceBytes() > entry.maxSourceBytes
          || !version.bundleSha256().equals(loaded.bundleSha256())) throw invalidSnapshot();
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw invalidSnapshot();
    }
    synchronized (cache) {
      verifyVersionIdentity(key);
      BridgeGuidanceSnapshot present = cache.get(key);
      if (present != null) return present;
      while (cache.size() >= MAX_CACHED_SNAPSHOTS
          || cachedSourceBytes + loaded.sourceBytes() > MAX_SOURCE_BYTES) {
        var iterator = cache.entrySet().iterator();
        var eldest = iterator.next();
        cachedSourceBytes -= eldest.getValue().sourceBytes();
        iterator.remove();
      }
      cache.put(key, loaded);
      cachedSourceBytes += loaded.sourceBytes();
    }
    return loaded;
  }

  private void verifyVersionIdentity(CacheKey requested) {
    for (CacheKey cached : cache.keySet())
      if (cached.id.equals(requested.id)
          && cached.version.equals(requested.version)
          && !cached.bundleSha256.equals(requested.bundleSha256)) throw invalidSnapshot();
  }

  private Map<String, Object> metadata(BridgeGuidanceSnapshot snapshot) {
    var value = snapshot.definition();
    return Map.of(
        "id",
        value.id(),
        "title",
        value.title(),
        "description",
        value.description(),
        "version",
        value.version(),
        "entrypoint",
        value.entrypoint(),
        "bundleSha256",
        snapshot.bundleSha256(),
        "bundleAvailable",
        bundleAvailable(snapshot));
  }

  private boolean bundleAvailable(BridgeGuidanceSnapshot snapshot) {
    return files != null && snapshot.bundleSize() <= maxExportBytes;
  }

  private boolean available(Registration entry, BridgePrincipal principal) {
    try {
      return entry.requiredTools.stream().allMatch(tool -> toolVisible.test(tool, principal))
          && entry.provider.available(entry.id, principal);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private static String argument(Map<String, Object> arguments, String name) {
    if (!arguments.containsKey(name)) return null;
    Object value = arguments.get(name);
    if (!(value instanceof String text) || text.isBlank()) throw invalid();
    return text;
  }

  private static BridgeException invalidSnapshot() {
    return new BridgeException(
        "GUIDANCE_SNAPSHOT_INVALID", "Published guidance failed integrity or resource validation");
  }

  private static BridgeException unavailableVersion() {
    return new BridgeException(
        "GUIDANCE_VERSION_UNAVAILABLE",
        "The requested guidance version is unavailable; refresh the catalog");
  }

  private static BridgeException invalid() {
    return new BridgeException(
        "INVALID_ARGUMENT",
        "Use catalog, skill text, section text, or bundle arguments separately; version requires skillId");
  }

  private record Registration(
      String id,
      Set<String> requiredTools,
      BridgeGuidanceProvider provider,
      BridgeDynamicGuidanceProvider dynamic,
      BridgeGuidanceSnapshot fixed,
      long maxSourceBytes) {}

  private record CacheKey(String id, String version, String bundleSha256) {}
}
