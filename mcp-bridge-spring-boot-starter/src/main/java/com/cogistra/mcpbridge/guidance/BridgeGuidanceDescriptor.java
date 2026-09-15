package com.cogistra.mcpbridge.guidance;

import java.util.Set;

/** Stable registration and reserved source budget for a runtime-maintained skill. */
public record BridgeGuidanceDescriptor(String id, Set<String> requiredTools, int maxSourceBytes) {
  public BridgeGuidanceDescriptor {
    if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,63}"))
      throw new IllegalArgumentException("Invalid guidance id");
    requiredTools = Set.copyOf(requiredTools);
    for (String tool : requiredTools)
      if (!tool.matches("[A-Za-z0-9_.-]{1,128}") || tool.equals("bridge_get_guidance"))
        throw new IllegalArgumentException("Invalid or recursive guidance tool dependency");
    if (maxSourceBytes < 1 || maxSourceBytes > BridgeGuidance.MAX_TOTAL_BYTES)
      throw new IllegalArgumentException("Invalid dynamic guidance source budget");
  }
}
