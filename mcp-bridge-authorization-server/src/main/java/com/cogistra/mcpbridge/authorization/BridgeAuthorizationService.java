package com.cogistra.mcpbridge.authorization;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.*;

/** Account and grant policy around the standard SAS protocol service. */
public final class BridgeAuthorizationService implements OAuth2AuthorizationService {
  static final String ABSOLUTE = "bridge.absolute-expires", VERSION = "bridge.account-version";
  static final String REVOKED = "bridge.revoked", RETIRED = "bridge.retired-refresh-hashes";
  static final String CLIENT_POLICY = "bridge.client-policy";
  static final String PENDING_EXPIRY = "bridge.pending-expires";
  private final BridgeAuthorizationStore store;
  private final HostAccountDirectory accounts;
  private final BridgeAuthorizationProperties properties;
  private final Clock clock;

  public BridgeAuthorizationService(
      BridgeAuthorizationStore store,
      HostAccountDirectory accounts,
      BridgeAuthorizationProperties properties,
      Clock clock) {
    this.store = store;
    this.accounts = accounts;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public void save(OAuth2Authorization authorization) {
    store.locked(
        () -> {
          var before = store.findById(authorization.getId());
          var b = OAuth2Authorization.from(authorization);
          if (before == null) {
            var account =
                accounts
                    .findBySubject(authorization.getPrincipalName())
                    .filter(HostAccount::enabled)
                    .orElseThrow(
                        () -> new OAuth2AuthenticationException(OAuth2ErrorCodes.ACCESS_DENIED));
            b.attribute(VERSION, account.version())
                .attribute(
                    ABSOLUTE, clock.instant().plus(properties.getAuthorizationTtl()).toString());
            b.attribute(CLIENT_POLICY, clientPolicy(authorization.getRegisteredClientId()));
            b.attribute(PENDING_EXPIRY, clock.instant().plus(Duration.ofMinutes(10)).toString());
          } else {
            b.attribute(VERSION, before.getAttribute(VERSION))
                .attribute(ABSOLUTE, before.getAttribute(ABSOLUTE));
            if (Boolean.TRUE.equals(before.getAttribute(REVOKED))) b.attribute(REVOKED, true);
            var oldRefresh = before.getRefreshToken();
            var refresh = authorization.getRefreshToken();
            List<String> oldHashes = before.getAttribute(RETIRED);
            var hashes = new ArrayList<String>(oldHashes == null ? List.of() : oldHashes);
            if (oldRefresh != null
                && refresh != null
                && !oldRefresh
                    .getToken()
                    .getTokenValue()
                    .equals(refresh.getToken().getTokenValue()))
              hashes.add(hash(oldRefresh.getToken().getTokenValue()));
            if (!hashes.isEmpty()) b.attribute(RETIRED, hashes);
            if (hashes.size() > 4096
                || newlyInvalidated(before.getAccessToken(), authorization.getAccessToken())
                || newlyInvalidated(before.getRefreshToken(), authorization.getRefreshToken()))
              b.attribute(REVOKED, true);
          }
          store.save(b.build());
          return null;
        });
  }

  private boolean newlyInvalidated(
      OAuth2Authorization.Token<?> before, OAuth2Authorization.Token<?> now) {
    return before != null
        && now != null
        && !before.isInvalidated()
        && now.isInvalidated()
        && before.getToken().getTokenValue().equals(now.getToken().getTokenValue());
  }

  @Override
  public void remove(OAuth2Authorization authorization) {
    store.remove(authorization);
  }

  @Override
  public OAuth2Authorization findById(String id) {
    return usable(store.findById(id));
  }

  @Override
  public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
    return usable(store.findByToken(token, type));
  }

  OAuth2Authorization usable(OAuth2Authorization a) {
    if (a == null || Boolean.TRUE.equals(a.getAttribute(REVOKED))) return null;
    if (!Objects.equals(a.getAttribute(CLIENT_POLICY), clientPolicy(a.getRegisteredClientId())))
      return null;
    if (a.getAttribute("state") != null
        && (a.getAttribute(PENDING_EXPIRY) == null
            || !clock.instant().isBefore(Instant.parse(a.getAttribute(PENDING_EXPIRY)))))
      return null;
    String expiry = a.getAttribute(ABSOLUTE);
    if (expiry == null || !clock.instant().isBefore(Instant.parse(expiry))) return null;
    return accounts
        .findBySubject(a.getPrincipalName())
        .filter(HostAccount::enabled)
        .filter(account -> account.version().equals(a.getAttribute(VERSION)))
        .map(account -> a)
        .orElse(null);
  }

  OAuth2Authorization refreshForClient(String clientId, String refresh) {
    return store.locked(
        () -> {
          var reused = store.findByRetiredRefreshHash(hash(refresh));
          if (reused != null && reused.getRegisteredClientId().equals(clientId)) {
            revoke(reused);
            return null;
          }
          var current = findByToken(refresh, OAuth2TokenType.REFRESH_TOKEN);
          return current != null && current.getRegisteredClientId().equals(clientId)
              ? current
              : null;
        });
  }

  void revoke(OAuth2Authorization a) {
    store.save(OAuth2Authorization.from(a).attribute(REVOKED, true).build());
  }

  public boolean revokeForSubject(String subject, String id) {
    return store.locked(
        () -> {
          var a = store.findById(id);
          if (a == null || !subject.equals(a.getPrincipalName())) return false;
          revoke(a);
          return true;
        });
  }

  public List<OAuth2Authorization> grants(String subject) {
    return store.findByPrincipalName(subject).stream()
        .filter(a -> a.getAccessToken() != null && usable(a) != null)
        .toList();
  }

  public OAuth2AuthorizationRequest pendingRequest(String subject, String clientId, String state) {
    var a = findByToken(state, new OAuth2TokenType("state"));
    if (a == null
        || !subject.equals(a.getPrincipalName())
        || !clientId.equals(a.getRegisteredClientId())) return null;
    return a.getAttribute(OAuth2AuthorizationRequest.class.getName());
  }

  HostAccount currentAccountForAccess(String token) {
    var a = findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
    if (a == null || !a.getAccessToken().isActive())
      throw new IllegalArgumentException("Inactive authorization");
    return accounts
        .findBySubject(a.getPrincipalName())
        .filter(HostAccount::enabled)
        .filter(account -> account.version().equals(a.getAttribute(VERSION)))
        .orElseThrow(() -> new IllegalArgumentException("Inactive account"));
  }

  Instant absoluteExpiry(OAuth2Authorization a) {
    return a == null
        ? clock.instant().plus(properties.getAuthorizationTtl())
        : Instant.parse(a.getAttribute(ABSOLUTE));
  }

  BridgeAuthorizationStore store() {
    return store;
  }

  private String clientPolicy(String clientId) {
    return properties.getClients().stream()
        .filter(c -> c.getId().equals(clientId))
        .findFirst()
        .map(
            c ->
                hash(
                    properties.getResource()
                        + "\n"
                        + c.getId()
                        + "\n"
                        + String.join("\n", new TreeSet<>(c.getRedirectUris()))
                        + "\n"
                        + String.join("\n", new TreeSet<>(c.getScopes()))))
        .orElse("");
  }

  static String hash(String token) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
