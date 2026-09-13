package com.cogistra.mcpbridge.sample;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.SecurityFilterChain;

/** No generated fallback password and no anonymous sample access outside an explicit demo setup. */
@Configuration(proxyBeanMethods = false)
public class SampleSecurity {
  @Bean
  @Order(1000)
  SecurityFilterChain sampleDenyFallback(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(requests -> requests.anyRequest().denyAll()).build();
  }

  @Bean
  @Profile("!demo")
  UserDetailsService noImplicitSampleUsers() {
    return username -> {
      throw new UsernameNotFoundException(
          "Enable the demo profile and provide local credentials explicitly");
    };
  }
}
