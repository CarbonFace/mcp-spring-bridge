package com.cogistra.mcpbridge.api;

import org.springframework.security.core.Authentication;

/** Host adapter may revalidate account status and provide its own business Authentication. */
@FunctionalInterface
public interface BridgeIdentityResolver {
  BridgePrincipal resolve(Authentication verifiedAuthentication);
}
