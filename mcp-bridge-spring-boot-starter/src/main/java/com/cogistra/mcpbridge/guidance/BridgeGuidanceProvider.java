package com.cogistra.mcpbridge.guidance;

import com.cogistra.mcpbridge.api.BridgePrincipal;
import java.util.List;

/** Hosts supply trusted build assets; current business permissions remain a host decision. */
public interface BridgeGuidanceProvider {
  List<BridgeGuidance> guidance();

  /** Evaluated for each catalog, body and bundle request under the current verified identity. */
  default boolean available(String skillId, BridgePrincipal principal) {
    return true;
  }
}
