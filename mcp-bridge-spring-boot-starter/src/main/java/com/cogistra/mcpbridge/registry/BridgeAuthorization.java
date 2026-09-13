package com.cogistra.mcpbridge.registry;

/** Additional host object/data authorization. Method security is also always preserved. */
@FunctionalInterface
public interface BridgeAuthorization {
  void authorize(BridgeCall call);
}
