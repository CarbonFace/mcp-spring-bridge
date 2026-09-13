package com.cogistra.mcpbridge.authorization;

import java.util.Optional;

/** Verify using the host's password policy. The supplied password is cleared after this call. */
@FunctionalInterface
public interface HostPasswordVerifier {
  Optional<HostAccount> verify(String username, char[] password);
}
