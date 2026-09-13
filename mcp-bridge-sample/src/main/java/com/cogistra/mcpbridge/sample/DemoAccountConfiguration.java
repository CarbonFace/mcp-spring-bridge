package com.cogistra.mcpbridge.sample;

import com.cogistra.mcpbridge.authorization.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Explicit local-only profile; there is no checked-in or automatically generated demo password. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoAccountConfiguration {
  private final PasswordEncoder passwords = new BCryptPasswordEncoder();
  private final Map<String, String> hashes;

  public DemoAccountConfiguration(
      @Value("${sample.demo.alice-password}") String alice,
      @Value("${sample.demo.bob-password}") String bob) {
    if (alice == null
        || bob == null
        || alice.length() < 12
        || bob.length() < 12
        || alice.equals(bob))
      throw new IllegalArgumentException(
          "Set different local demo passwords with at least 12 characters");
    hashes = Map.of("alice", passwords.encode(alice), "bob", passwords.encode(bob));
  }

  @Bean
  UserDetailsService demoUsers() {
    var users =
        hashes.entrySet().stream()
            .map(
                entry ->
                    User.withUsername(entry.getKey())
                        .password(entry.getValue())
                        .authorities("sample:read", "sample:write")
                        .build())
            .toList();
    return new InMemoryUserDetailsManager(users);
  }

  @Bean
  PasswordEncoder demoPasswordEncoder() {
    return passwords;
  }

  @Bean
  HostAccountDirectory demoHostAccountDirectory() {
    return subject -> Optional.ofNullable(account(subject));
  }

  @Bean
  HostPasswordVerifier demoHostPasswordVerifier() {
    return (username, supplied) -> {
      String hash = hashes.get(username);
      if (hash == null || supplied == null || !passwords.matches(new String(supplied), hash))
        return Optional.empty();
      return Optional.of(account(username));
    };
  }

  private HostAccount account(String subject) {
    return hashes.containsKey(subject)
        ? new HostAccount(
            subject,
            subject,
            "Sample " + subject,
            true,
            "demo-v1",
            Set.of("sample:read", "sample:write"))
        : null;
  }

  @Bean
  @Order(50)
  SecurityFilterChain sampleDemoHttp(HttpSecurity http) throws Exception {
    return http.securityMatcher("/sample/**")
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
        .httpBasic(Customizer.withDefaults())
        .build();
  }
}
