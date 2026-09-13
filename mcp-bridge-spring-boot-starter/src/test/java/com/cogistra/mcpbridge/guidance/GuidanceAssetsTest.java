package com.cogistra.mcpbridge.guidance;

import static org.assertj.core.api.Assertions.*;

import com.cogistra.mcpbridge.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/** Prevents unsafe/incomplete archives and instructions changing after startup hashing. */
class GuidanceAssetsTest {
  private static BridgeGuidance guide(Map<String, byte[]> assets) {
    return new BridgeGuidance(
        "fixture", "Fixture", "Read fixture instructions", "1.0.0", "SKILL.md", assets, Set.of());
  }

  private static BridgeGuidanceRegistry registry(BridgeGuidance definition) {
    return new BridgeGuidanceRegistry(
        List.of(() -> List.of(definition)), (name, principal) -> true, null, 1024 * 1024);
  }

  private static BridgePrincipal principal() {
    return new BridgePrincipal(
        new BridgeIdentity("fixture", "alice", "client", Set.of(), Set.of()),
        UsernamePasswordAuthenticationToken.authenticated("alice", "unused", List.of()));
  }

  @Test
  void unsafeArchivePathsInvalidTextAndOversizedAssetsFailBeforePublication() {
    for (String path :
        List.of(
            "../secret",
            "/secret",
            "C:/secret",
            "refs\\secret",
            "refs/CON.txt",
            "ref./test",
            "refs//x",
            "refs/../x"))
      assertThatThrownBy(() -> guide(Map.of("SKILL.md", new byte[0], path, new byte[0])))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> guide(Map.of("SKILL.md", new byte[BridgeGuidance.MAX_FILE_BYTES + 1])))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry(guide(Map.of("SKILL.md", new byte[] {(byte) 0xc3, 0x28}))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> guide(Map.of("SKILL.md", new byte[0], "skill.md", new byte[0])))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void registeredSnapshotCannotDriftAndOnlineReadingWorksWithFilesDisabled() {
    byte[] mutable = "Read then confirm".getBytes(StandardCharsets.UTF_8);
    BridgeGuidance definition = guide(Map.of("SKILL.md", mutable));
    mutable[0] = 'X';
    definition.files().get("SKILL.md")[0] = 'Y';
    var registry = registry(definition);
    Map<?, ?> body = (Map<?, ?>) registry.read(principal(), Map.of("skillId", "fixture"));
    assertThat(body.get("content")).isEqualTo("Read then confirm");
    assertThat(body.get("bundleAvailable")).isEqualTo(false);
    assertThatThrownBy(
            () -> registry.read(principal(), Map.of("skillId", "fixture", "format", "bundle")))
        .isInstanceOf(BridgeException.class)
        .extracting("code")
        .isEqualTo("FILES_DISABLED");
    var originalZone = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
      Map<?, ?> another =
          (Map<?, ?>) registry(definition).read(principal(), Map.of("skillId", "fixture"));
      assertThat(another.get("bundleSha256")).isEqualTo(body.get("bundleSha256"));
    } finally {
      TimeZone.setDefault(originalZone);
    }
  }
}
