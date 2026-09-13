package com.cogistra.mcpbridge.authorization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** Explicit JSON shape: no Java serialization, polymorphic type names or credential persistence. */
final class AuthorizationJson {
  private final ObjectMapper json = new ObjectMapper();
  private final RegisteredClientRepository clients;

  AuthorizationJson(RegisteredClientRepository clients) {
    this.clients = clients;
  }

  byte[] write(Collection<OAuth2Authorization> authorizations) throws Exception {
    var rows = new ArrayList<Map<String, Object>>();
    for (var a : authorizations) {
      var row = new LinkedHashMap<String, Object>();
      row.put("id", a.getId());
      row.put("client", a.getRegisteredClientId());
      row.put("subject", a.getPrincipalName());
      row.put("scopes", a.getAuthorizedScopes());
      var attrs = new LinkedHashMap<String, Object>(a.getAttributes());
      Authentication principal = (Authentication) attrs.remove(Principal.class.getName());
      if (principal != null) {
        row.put("principal", principal.getName());
        row.put("details", principal.getDetails());
      }
      OAuth2AuthorizationRequest request =
          (OAuth2AuthorizationRequest) attrs.remove(OAuth2AuthorizationRequest.class.getName());
      if (request != null) {
        var r = new LinkedHashMap<String, Object>();
        r.put("uri", request.getAuthorizationUri());
        r.put("client", request.getClientId());
        r.put("redirect", request.getRedirectUri());
        r.put("scopes", request.getScopes());
        r.put("state", request.getState());
        r.put("extra", request.getAdditionalParameters());
        row.put("request", r);
      }
      row.put("attributes", attrs);
      row.put("code", token(a.getToken(OAuth2AuthorizationCode.class)));
      row.put("access", token(a.getAccessToken()));
      row.put("refresh", token(a.getRefreshToken()));
      rows.add(row);
    }
    return json.writeValueAsBytes(Map.of("version", 1, "authorizations", rows));
  }

  List<OAuth2Authorization> read(byte[] bytes) throws Exception {
    Map<String, Object> root = json.readValue(bytes, new TypeReference<>() {});
    if (!Integer.valueOf(1).equals(root.get("version")))
      throw new IllegalArgumentException("Unsupported authorization storage version");
    var result = new ArrayList<OAuth2Authorization>();
    for (Object value : (List<?>) root.get("authorizations")) {
      Map<String, Object> row = map(value);
      var client = clients.findById(str(row, "client"));
      // A removed client cannot regain old grants when later re-added.
      if (client == null) continue;
      var builder =
          OAuth2Authorization.withRegisteredClient(client)
              .id(str(row, "id"))
              .principalName(str(row, "subject"))
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .authorizedScopes(strings(row.get("scopes")));
      builder.attributes(a -> a.putAll(map(row.get("attributes"))));
      if (row.get("principal") != null) {
        var principal =
            UsernamePasswordAuthenticationToken.authenticated(
                row.get("principal"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_BRIDGE_BROWSER")));
        principal.setDetails(row.get("details"));
        builder.attribute(Principal.class.getName(), principal);
      }
      if (row.get("request") != null) {
        var r = map(row.get("request"));
        var request =
            OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(str(r, "uri"))
                .clientId(str(r, "client"))
                .redirectUri(str(r, "redirect"))
                .scopes(strings(r.get("scopes")))
                .state((String) r.get("state"))
                .additionalParameters(map(r.get("extra")))
                .build();
        builder.attribute(OAuth2AuthorizationRequest.class.getName(), request);
      }
      for (String kind : List.of("code", "access", "refresh")) {
        if (row.get(kind) == null) continue;
        var t = map(row.get(kind));
        Instant issued = Instant.parse(str(t, "issued")),
            expires = Instant.parse(str(t, "expires"));
        OAuth2Token token =
            switch (kind) {
              case "code" -> new OAuth2AuthorizationCode(str(t, "value"), issued, expires);
              case "access" ->
                  new OAuth2AccessToken(
                      OAuth2AccessToken.TokenType.BEARER,
                      str(t, "value"),
                      issued,
                      expires,
                      strings(row.get("scopes")));
              default -> new OAuth2RefreshToken(str(t, "value"), issued, expires);
            };
        var metadata = map(t.get("metadata"));
        Object rawClaims = metadata.get(OAuth2Authorization.Token.CLAIMS_METADATA_NAME);
        if (rawClaims instanceof Map<?, ?>) {
          var claims = map(rawClaims);
          for (String timestamp : List.of("iat", "exp", "nbf"))
            if (claims.get(timestamp) instanceof Number n)
              claims.put(timestamp, Instant.ofEpochSecond(n.longValue()));
        }
        builder.token(token, target -> target.putAll(metadata));
      }
      result.add(builder.build());
    }
    return result;
  }

  private Map<String, Object> token(OAuth2Authorization.Token<?> token) {
    if (token == null) return null;
    var result = new LinkedHashMap<String, Object>();
    result.put("value", token.getToken().getTokenValue());
    result.put("issued", token.getToken().getIssuedAt().toString());
    result.put("expires", token.getToken().getExpiresAt().toString());
    // Claims contain Instants; convert to their standard NumericDate representation.
    result.put("metadata", plain(token.getMetadata()));
    return result;
  }

  private Object plain(Object value) {
    if (value instanceof Instant i) return i.getEpochSecond();
    if (value instanceof Map<?, ?> m) {
      var r = new LinkedHashMap<String, Object>();
      m.forEach((k, v) -> r.put(k.toString(), plain(v)));
      return r;
    }
    if (value instanceof Collection<?> c) return c.stream().map(this::plain).toList();
    return value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object o) {
    return (Map<String, Object>) o;
  }

  private static String str(Map<String, Object> m, String key) {
    return Objects.requireNonNull((String) m.get(key));
  }

  private static Set<String> strings(Object value) {
    var r = new LinkedHashSet<String>();
    for (Object s : (Collection<?>) value) r.add((String) s);
    return r;
  }
}
