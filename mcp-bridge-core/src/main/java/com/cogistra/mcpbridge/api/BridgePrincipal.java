package com.cogistra.mcpbridge.api;

import java.util.Objects;
import org.springframework.security.core.Authentication;

public record BridgePrincipal(BridgeIdentity identity, Authentication authentication) {
  public BridgePrincipal {
    Objects.requireNonNull(identity);
    Objects.requireNonNull(authentication);
  }
}
