package com.cogistra.mcpbridge.boot;

import com.cogistra.mcpbridge.api.BridgeException;
import com.cogistra.mcpbridge.audit.*;
import com.cogistra.mcpbridge.security.BridgeInvocationContext;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mcp-bridge/audit")
public final class BridgeAuditController {
  private final AuditRepository repository;
  private final BridgeInvocationContext identities;

  public BridgeAuditController(AuditRepository repository, BridgeInvocationContext identities) {
    this.repository = repository;
    this.identities = identities;
  }

  @GetMapping
  public ResponseEntity<?> query(
      @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
    try {
      return ResponseEntity.ok()
          .header("Cache-Control", "no-store")
          .body(repository.query(identities.current().identity(), cursor, limit));
    } catch (BridgeException rejected) {
      return ResponseEntity.status(rejected.code().equals("UNAUTHENTICATED") ? 401 : 400)
          .header("Cache-Control", "no-store")
          .body(Map.of("errorCode", rejected.code()));
    }
  }
}
