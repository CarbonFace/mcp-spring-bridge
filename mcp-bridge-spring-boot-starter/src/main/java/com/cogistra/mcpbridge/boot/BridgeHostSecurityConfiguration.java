package com.cogistra.mcpbridge.boot;

import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Preserve protection when the host previously relied entirely on Boot's default chain. */
@AutoConfiguration(
    after = BridgeAutoConfiguration.class,
    afterName = {
      "com.cogistra.mcpbridge.authorization.BridgeAuthorizationAutoConfiguration",
      "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration"
    })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(BridgeRuntime.class)
public class BridgeHostSecurityConfiguration {
  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  @Conditional(NoHostChain.class)
  SecurityFilterChain bridgeHostFallbackSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .httpBasic(org.springframework.security.config.Customizer.withDefaults())
        .formLogin(org.springframework.security.config.Customizer.withDefaults())
        .build();
  }

  static final class NoHostChain implements Condition {
    private static final Set<String> OWN =
        Set.of(
            "bridgeSecurityFilterChain",
            "bridgeAuthorizationProtocolChain",
            "bridgeAuthorizationBrowserChain",
            "bridgeDiscoveryChain",
            "bridgeHostFallbackSecurityFilterChain");

    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      if (context.getBeanFactory() == null) return true;
      for (String name :
          context.getBeanFactory().getBeanNamesForType(SecurityFilterChain.class, false, false))
        if (!OWN.contains(name)) return false;
      return true;
    }
  }
}
