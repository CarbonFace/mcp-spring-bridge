package com.cogistra.mcpbridge.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;

final class BridgeMetadataFilter extends OncePerRequestFilter {
  private final BridgeAuthorizationProperties p;
  private final ObjectMapper json = new ObjectMapper();

  BridgeMetadataFilter(BridgeAuthorizationProperties p) {
    this.p = p;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!"GET".equals(request.getMethod())) {
      response.setStatus(405);
      response.setHeader("Allow", "GET");
      return;
    }
    var data = new LinkedHashMap<String, Object>();
    if (BridgeBrowserSupport.path(request).equals(p.metadataPath())) {
      data.put("issuer", p.getIssuer());
      data.put("authorization_endpoint", p.endpoint("/authorize"));
      data.put("token_endpoint", p.endpoint("/token"));
      data.put("revocation_endpoint", p.endpoint("/revoke"));
      data.put("jwks_uri", p.endpoint("/jwks"));
      data.put("response_types_supported", List.of("code"));
      data.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
      data.put("token_endpoint_auth_methods_supported", List.of("none"));
      data.put("revocation_endpoint_auth_methods_supported", List.of("none"));
      data.put("code_challenge_methods_supported", List.of("S256"));
      data.put("scopes_supported", p.scopes());
    } else {
      data.put("resource", p.getResource());
      data.put("authorization_servers", List.of(p.getIssuer()));
      data.put("scopes_supported", p.scopes());
      data.put("bearer_methods_supported", List.of("header"));
    }
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json");
    json.writeValue(response.getOutputStream(), data);
  }
}
