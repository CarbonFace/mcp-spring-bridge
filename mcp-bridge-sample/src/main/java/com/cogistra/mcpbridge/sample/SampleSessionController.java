package com.cogistra.mcpbridge.sample;

import com.cogistra.mcpbridge.annotation.Effect;
import com.cogistra.mcpbridge.annotation.McpEndpoint;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** Existing HTTP clients obtain CSRF through their own session; this is not an MCP capability. */
@RestController
@Profile("demo")
public class SampleSessionController {
  @GetMapping("/sample/whoami")
  @PreAuthorize("hasAuthority('sample:read')")
  @McpEndpoint(
      name = "sample_whoami",
      description = "Read the authenticated local demo account",
      effect = Effect.READ,
      scopes = "sample:read")
  public Map<String, String> whoami(Authentication authentication) {
    return Map.of("subject", authentication.getName());
  }

  @GetMapping("/sample/session")
  public Map<String, String> session(Authentication authentication, CsrfToken csrf) {
    return Map.of(
        "subject",
        authentication.getName(),
        "csrfHeader",
        csrf.getHeaderName(),
        "csrfToken",
        csrf.getToken());
  }
}
