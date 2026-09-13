package com.cogistra.mcpbridge.api;

import java.util.Set;

/** Server-verified identity. Never bind this object from tool arguments. */
public record BridgeIdentity(
    String issuer, String subject, String clientId, Set<String> scopes, Set<String> authorities) {
  public BridgeIdentity {
    if (issuer == null
        || issuer.isBlank()
        || subject == null
        || subject.isBlank()
        || clientId == null
        || clientId.isBlank()) throw new IllegalArgumentException("Trusted identity is incomplete");
    scopes = Set.copyOf(scopes == null ? Set.of() : scopes);
    authorities = Set.copyOf(authorities == null ? Set.of() : authorities);
  }

  public String ownerKey() {
    return issuer.length() + ":" + issuer + subject.length() + ":" + subject;
  }
}
