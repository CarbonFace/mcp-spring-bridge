package com.cogistra.mcpbridge.authorization;

import java.util.Optional;

/**
 * Resolve the current account, not a cached token's permissions. No account database is created.
 */
@FunctionalInterface
public interface HostAccountDirectory {
  Optional<HostAccount> findBySubject(String subject);

  /**
   * Override when the host treats case or aliases as the same login identifier. Shared by
   * verification and limiting.
   */
  default String normalizeUsername(String username) {
    return username == null ? "" : username.trim();
  }
}
