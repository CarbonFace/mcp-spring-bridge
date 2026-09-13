package com.cogistra.mcpbridge.security;

import com.cogistra.mcpbridge.api.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * External authorization servers: JWT signature/issuer are verified by the host-supplied decoder.
 */
public final class JwtBridgeIdentityResolver implements BridgeIdentityResolver {
  private final String resource;

  public JwtBridgeIdentityResolver(String resource) {
    this.resource = Objects.requireNonNull(resource);
  }

  public BridgePrincipal resolve(Authentication auth) {
    BridgeInvocationContext.requireAuthenticated(auth);
    if (!(auth instanceof JwtAuthenticationToken token)) throw denied();
    var jwt = token.getToken();
    if (jwt.getExpiresAt() == null
        || !Instant.now().isBefore(jwt.getExpiresAt())
        || jwt.getIssuer() == null
        || !jwt.getAudience().contains(resource)) throw denied();
    String client = jwt.getClaimAsString("client_id");
    if (client == null || client.isBlank()) throw denied();
    Set<String> scopes = new HashSet<>();
    Object scope = jwt.getClaims().get("scope");
    if (scope instanceof String text) scopes.addAll(Arrays.asList(text.trim().split("\\s+")));
    else if (scope instanceof Collection<?> values)
      for (Object value : values) if (value instanceof String s) scopes.add(s);
    Set<String> authorities = new HashSet<>();
    auth.getAuthorities().forEach(a -> authorities.add(a.getAuthority()));
    return new BridgePrincipal(
        new BridgeIdentity(
            jwt.getIssuer().toString(), jwt.getSubject(), client, scopes, authorities),
        auth);
  }

  private BridgeException denied() {
    return new BridgeException("UNAUTHENTICATED", "A current token for this resource is required");
  }
}
