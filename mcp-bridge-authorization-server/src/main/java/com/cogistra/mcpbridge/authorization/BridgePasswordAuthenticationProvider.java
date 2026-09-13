package com.cogistra.mcpbridge.authorization;

import java.time.Clock;
import java.util.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Dedicated browser login provider; host may replace the named bean. */
public final class BridgePasswordAuthenticationProvider implements AuthenticationProvider {
  static final String VERSION = "bridge.account-version", LOGIN = "bridge.login-at";
  private final HostPasswordVerifier verifier;
  private final HostAccountDirectory accounts;
  private final Clock clock;

  public BridgePasswordAuthenticationProvider(
      HostPasswordVerifier verifier, HostAccountDirectory accounts, Clock clock) {
    this.verifier = verifier;
    this.accounts = accounts;
    this.clock = clock;
  }

  @Override
  public Authentication authenticate(Authentication input) {
    String name = accounts.normalizeUsername(input.getName());
    char[] password =
        input.getCredentials() == null
            ? new char[0]
            : input.getCredentials().toString().toCharArray();
    try {
      if (name.isEmpty() || name.length() > 200 || password.length == 0 || password.length > 1024)
        throw bad();
      HostAccount verified =
          verifier.verify(name, password).orElseThrow(BridgePasswordAuthenticationProvider::bad);
      HostAccount current =
          accounts
              .findBySubject(verified.subject())
              .filter(a -> a.enabled() && a.version().equals(verified.version()))
              .orElseThrow(BridgePasswordAuthenticationProvider::bad);
      var token =
          UsernamePasswordAuthenticationToken.authenticated(
              current, null, List.of(new SimpleGrantedAuthority("ROLE_BRIDGE_BROWSER")));
      token.setDetails(
          new LinkedHashMap<>(
              Map.of(VERSION, current.version(), LOGIN, clock.instant().toString())));
      return token;
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  private static BadCredentialsException bad() {
    return new BadCredentialsException("Account or credentials unavailable");
  }

  @Override
  public boolean supports(Class<?> type) {
    return UsernamePasswordAuthenticationToken.class.equals(type);
  }
}
