package com.cogistra.mcpbridge.authorization;

import java.util.Set;

/**
 * Host-owned current account. Change version whenever credentials, permissions or enabled state
 * change.
 */
public record HostAccount(
    String subject,
    String username,
    String displayName,
    boolean enabled,
    String version,
    Set<String> authorities)
    implements java.security.Principal {
  public HostAccount {
    if (subject == null
        || subject.isBlank()
        || username == null
        || username.isBlank()
        || version == null
        || version.isBlank()) throw new IllegalArgumentException("Account identity is incomplete");
    authorities = Set.copyOf(authorities == null ? Set.of() : authorities);
    displayName = displayName == null || displayName.isBlank() ? username : displayName;
  }

  @Override
  public String getName() {
    return subject;
  }
}
