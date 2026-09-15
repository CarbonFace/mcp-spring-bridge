package com.cogistra.mcpbridge.guidance;

import static org.assertj.core.api.Assertions.*;

import com.cogistra.mcpbridge.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/** Reject oversized/corrupt publication and keep bounded histories reloadable without a restart. */
class DynamicGuidanceAssetsTest {
  private static BridgePrincipal principal() {
    return new BridgePrincipal(
        new BridgeIdentity("fixture", "alice", "client", Set.of(), Set.of()),
        UsernamePasswordAuthenticationToken.authenticated("alice", "unused", List.of()));
  }

  private static BridgeGuidanceRegistry registry(BridgeGuidanceProvider... providers) {
    return new BridgeGuidanceRegistry(
        List.of(providers), (tool, actor) -> true, null, 16 * 1024 * 1024);
  }

  private static BridgeGuidance definition(String version, Map<String, byte[]> assets) {
    return new BridgeGuidance(
        "fixture", "Fixture", "Published fixture", version, "SKILL.md", assets, Set.of());
  }

  private static Map<?, ?> read(BridgeGuidanceRegistry registry, String version) {
    return (Map<?, ?>) registry.read(principal(), Map.of("skillId", "fixture", "version", version));
  }

  @Test
  void dynamicRegistrationReservesCatalogCapacityEvenBeforeAnyVersionExists() {
    var dynamic = new RuntimeSource();
    dynamic.descriptors =
        IntStream.range(0, 65)
            .mapToObj(i -> new BridgeGuidanceDescriptor("guide-" + i, Set.of(), 1))
            .toList();
    assertThatThrownBy(() -> registry(dynamic)).isInstanceOf(IllegalArgumentException.class);
    dynamic.descriptors =
        IntStream.range(0, 4)
            .mapToObj(
                i ->
                    new BridgeGuidanceDescriptor(
                        "guide-" + i, Set.of(), BridgeGuidance.MAX_TOTAL_BYTES))
            .toList();
    BridgeGuidanceProvider fixed =
        () -> List.of(definition("1", Map.of("SKILL.md", new byte[] {65})));
    assertThatThrownBy(() -> registry(dynamic, fixed)).isInstanceOf(IllegalArgumentException.class);
    dynamic.descriptors = List.of(new BridgeGuidanceDescriptor("fixture", Set.of(), 4));
    dynamic.add("1", Map.of("SKILL.md", "More than reserved".getBytes(StandardCharsets.UTF_8)));
    assertThatThrownBy(() -> read(registry(dynamic), "1"))
        .isInstanceOf(BridgeException.class)
        .extracting("code")
        .isEqualTo("GUIDANCE_SNAPSHOT_INVALID");
    dynamic.descriptors =
        List.of(new BridgeGuidanceDescriptor("fixture", Set.of(), BridgeGuidance.MAX_TOTAL_BYTES));
    dynamic.corruptText = true;
    assertThatThrownBy(() -> read(registry(dynamic), "1"))
        .isInstanceOf(BridgeException.class)
        .extracting("code")
        .isEqualTo("GUIDANCE_SNAPSHOT_INVALID");
  }

  @Test
  void fixedVersionCannotFallForwardOrReadBytesThatDisagreeWithThePublishedDigest() {
    var dynamic = new RuntimeSource();
    dynamic.add("1", Map.of("SKILL.md", "Original".getBytes(StandardCharsets.UTF_8)));
    dynamic.add("2", Map.of("SKILL.md", "New instructions".getBytes(StandardCharsets.UTF_8)));
    var registry = registry(dynamic);
    dynamic.ignoreRequestedVersion = true;
    assertThatThrownBy(() -> read(registry, "1"))
        .isInstanceOf(BridgeException.class)
        .extracting("code")
        .isEqualTo("GUIDANCE_VERSION_UNAVAILABLE");
    dynamic.ignoreRequestedVersion = false;
    dynamic.wrongDigest = true;
    assertThatThrownBy(() -> read(registry, "1"))
        .isInstanceOf(BridgeException.class)
        .extracting("code")
        .isEqualTo("GUIDANCE_SNAPSHOT_INVALID");
    dynamic.wrongDigest = false;
    var original = read(registry, "1");
    assertThat(original.get("content")).isEqualTo("Original");
    dynamic.add(
        "1", Map.of("SKILL.md", "Mutated released version".getBytes(StandardCharsets.UTF_8)));
    assertThatThrownBy(() -> read(registry, "1"))
        .isInstanceOf(BridgeException.class)
        .extracting("code")
        .isEqualTo("GUIDANCE_SNAPSHOT_INVALID");
  }

  @Test
  void largeHistoryEvictsCompiledCopiesAndReloadsExactlyAfterEvictionOrRegistryRestart() {
    var dynamic = new RuntimeSource();
    for (int i = 1; i <= 6; i++) {
      var assets = new TreeMap<String, byte[]>();
      assets.put("SKILL.md", ("Release " + i).getBytes(StandardCharsets.UTF_8));
      for (int chunk = 0; chunk < 7; chunk++)
        assets.put("assets/" + chunk + ".bin", new byte[BridgeGuidance.MAX_FILE_BYTES]);
      dynamic.add(Integer.toString(i), assets);
    }
    var registry = registry(dynamic);
    var first = read(registry, "1");
    for (int i = 2; i <= 6; i++) read(registry, Integer.toString(i));
    var restored = read(registry, "1");
    assertThat(restored).isEqualTo(first);
    assertThat(dynamic.loads.get("1").get()).isEqualTo(2);
    assertThat(read(registry(dynamic), "1")).isEqualTo(first);
    var snapshot = dynamic.history.get("1");
    byte[] original = snapshot.bundleBytes();
    snapshot.bundleBytes()[0] = 0;
    assertThat(snapshot.bundleBytes()).isEqualTo(original);
  }

  static class RuntimeSource implements BridgeDynamicGuidanceProvider {
    List<BridgeGuidanceDescriptor> descriptors =
        List.of(new BridgeGuidanceDescriptor("fixture", Set.of(), BridgeGuidance.MAX_TOTAL_BYTES));
    final Map<String, BridgeGuidanceSnapshot> history = new HashMap<>();
    final Map<String, AtomicInteger> loads = new HashMap<>();
    String current;
    boolean ignoreRequestedVersion;
    boolean wrongDigest;
    boolean corruptText;

    void add(String version, Map<String, byte[]> assets) {
      history.put(version, BridgeGuidanceSnapshot.of(definition(version, assets)));
      loads.putIfAbsent(version, new AtomicInteger());
      current = version;
    }

    public List<BridgeGuidanceDescriptor> descriptors() {
      return descriptors;
    }

    public BridgeGuidanceVersion resolveVersion(String id, String requestedVersion) {
      var snapshot =
          history.get(
              requestedVersion == null || ignoreRequestedVersion ? current : requestedVersion);
      if (snapshot == null) throw new BridgeException("NOT_INITIALIZED", "Publish a version first");
      return wrongDigest
          ? new BridgeGuidanceVersion(snapshot.definition().version(), "0".repeat(64))
          : snapshot.version();
    }

    public BridgeGuidance load(String id, BridgeGuidanceVersion version) {
      loads.get(version.version()).incrementAndGet();
      if (corruptText)
        return definition(version.version(), Map.of("SKILL.md", new byte[] {(byte) 0xc3, 0x28}));
      return history.get(version.version()).definition();
    }
  }
}
