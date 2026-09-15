package com.cogistra.mcpbridge.guidance;

import java.util.List;

/**
 * Host-owned published storage. Descriptors are fixed at registration; each request resolves a
 * currently readable immutable version before consulting the bounded compiled-asset cache.
 */
public interface BridgeDynamicGuidanceProvider extends BridgeGuidanceProvider {
  @Override
  default List<BridgeGuidance> guidance() {
    return List.of();
  }

  List<BridgeGuidanceDescriptor> descriptors();

  /**
   * Null requests the current published pointer. A non-null version must resolve exactly, or throw
   * a safe BridgeException. Check compatibility here even for previously cached historical
   * versions.
   */
  BridgeGuidanceVersion resolveVersion(String skillId, String requestedVersion);

  /**
   * Load the exact immutable resource set identified by the previously resolved version and digest.
   */
  BridgeGuidance load(String skillId, BridgeGuidanceVersion version);
}
