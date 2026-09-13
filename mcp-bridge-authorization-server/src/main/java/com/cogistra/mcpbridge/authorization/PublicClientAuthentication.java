package com.cogistra.mcpbridge.authorization;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;

/** SAS intentionally does not enable refresh tokens for public clients by default. */
final class PublicClientAuthentication implements AuthenticationConverter, AuthenticationProvider {
  private final RegisteredClientRepository clients;
  private final BridgeAuthorizationService service;
  private final BridgeAuthorizationProperties properties;

  PublicClientAuthentication(
      RegisteredClientRepository clients,
      BridgeAuthorizationService service,
      BridgeAuthorizationProperties properties) {
    this.clients = clients;
    this.service = service;
    this.properties = properties;
  }

  @Override
  public Authentication convert(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean revoke = path.equals(BridgeAuthorizationProperties.PREFIX + "/revoke");
    boolean refresh =
        path.equals(BridgeAuthorizationProperties.PREFIX + "/token")
            && "refresh_token".equals(request.getParameter("grant_type"));
    if (!revoke && !refresh) return null;
    if (!"POST".equals(request.getMethod())
        || request.getQueryString() != null
        || request.getHeader("Authorization") != null
        || request.getParameter("client_secret") != null
        || request.getParameter("client_assertion") != null) fail(OAuth2ErrorCodes.INVALID_REQUEST);
    String client = single(request, "client_id", true),
        token = single(request, revoke ? "token" : "refresh_token", true);
    String resource = single(request, "resource", false);
    single(request, "grant_type", false);
    single(request, "token_type_hint", false);
    return new Presented(client, token, resource, revoke);
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    Presented presented = (Presented) authentication;
    var client = clients.findByClientId(presented.getPrincipal().toString());
    if (client == null
        || !client.getClientAuthenticationMethods().equals(Set.of(ClientAuthenticationMethod.NONE))
        || !client.getClientSettings().isRequireProofKey()) fail(OAuth2ErrorCodes.INVALID_CLIENT);
    if (presented.resource != null && !properties.getResource().equals(presented.resource))
      fail("invalid_target");
    if (!presented.revoke) {
      var a = service.refreshForClient(client.getId(), presented.getCredentials().toString());
      if (a == null || a.getRefreshToken() == null || !a.getRefreshToken().isActive())
        fail(OAuth2ErrorCodes.INVALID_GRANT);
    } else {
      var a = service.store().findByToken(presented.getCredentials().toString(), null);
      if (a == null)
        a =
            service
                .store()
                .findByRetiredRefreshHash(
                    BridgeAuthorizationService.hash(presented.getCredentials().toString()));
      if (a != null && client.getId().equals(a.getRegisteredClientId())) service.revoke(a);
      // RFC 7009: an unknown token, including one owned by another client, is a successful no-op.
    }
    return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
  }

  @Override
  public boolean supports(Class<?> type) {
    return Presented.class.equals(type);
  }

  static String single(HttpServletRequest r, String name, boolean required) {
    String[] values = r.getParameterValues(name);
    if (values == null) {
      if (required) fail(OAuth2ErrorCodes.INVALID_REQUEST);
      return null;
    }
    if (values.length != 1 || values[0].isBlank() || values[0].length() > 8192)
      fail(OAuth2ErrorCodes.INVALID_REQUEST);
    return values[0];
  }

  static void fail(String code) {
    throw new OAuth2AuthenticationException(new OAuth2Error(code));
  }

  private static final class Presented extends OAuth2ClientAuthenticationToken {
    final String resource;
    final boolean revoke;

    Presented(String client, String token, String resource, boolean revoke) {
      super(client, ClientAuthenticationMethod.NONE, token, null);
      this.resource = resource;
      this.revoke = revoke;
    }
  }
}
