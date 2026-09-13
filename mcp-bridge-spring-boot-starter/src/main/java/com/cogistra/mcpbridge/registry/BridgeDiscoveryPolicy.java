package com.cogistra.mcpbridge.registry;

import com.cogistra.mcpbridge.api.BridgePrincipal;

/** Host metadata visibility; never grants invocation or data access. */
@FunctionalInterface
public interface BridgeDiscoveryPolicy {
  enum Decision {
    ALLOW,
    DENY,
    ABSTAIN
  }

  Decision visible(String capabilityName, BridgePrincipal principal);
}
