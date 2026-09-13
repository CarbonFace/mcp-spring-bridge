package com.cogistra.mcpbridge.authorization;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;

/** Durable storage. locked must serialize a complete protocol operation across all writers. */
public interface BridgeAuthorizationStore extends OAuth2AuthorizationService {
  <T> T locked(Supplier<T> operation);

  List<OAuth2Authorization> findByPrincipalName(String subject);

  OAuth2Authorization findByRetiredRefreshHash(String hash);
}
