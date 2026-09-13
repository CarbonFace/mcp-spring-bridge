package com.cogistra.mcpbridge.files;

import com.cogistra.mcpbridge.api.*;
import com.cogistra.mcpbridge.audit.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/mcp-bridge/files")
public final class BridgeFileController {
  private final BridgeFileStore store;
  private final Supplier<BridgeIdentityResolver> identities;
  private final Supplier<AuditRepository> audit;

  public BridgeFileController(BridgeFileStore store, BridgeIdentityResolver identities) {
    this.store = store;
    this.identities = () -> identities;
    this.audit = () -> null;
  }

  public BridgeFileController(
      BridgeFileStore store, ObjectProvider<BridgeIdentityResolver> identities) {
    this.store = store;
    this.identities = identities::getIfAvailable;
    this.audit = () -> null;
  }

  @Autowired
  public BridgeFileController(
      BridgeFileStore store,
      ObjectProvider<BridgeIdentityResolver> identities,
      ObjectProvider<AuditRepository> audit) {
    this.store = store;
    this.identities = identities::getIfAvailable;
    this.audit = audit::getIfAvailable;
  }

  private BridgeIdentity identity(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    BridgeIdentityResolver resolver = identities.get();
    if (resolver == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    BridgePrincipal principal = resolver.resolve(authentication);
    if (principal == null
        || !principal.authentication().isAuthenticated()
        || principal.authentication() instanceof AnonymousAuthenticationToken)
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return principal.identity();
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public FileArtifact upload(Authentication authentication, @RequestPart("file") MultipartFile file)
      throws IOException {
    BridgeIdentity owner = identity(authentication);
    record(owner, AuditEvent.Phase.UPLOAD, AuditEvent.Outcome.ALLOWED);
    try {
      FileArtifact artifact =
          store.store(
              owner, file.getOriginalFilename(), file.getContentType(), file.getInputStream());
      recordOutcome(owner, AuditEvent.Phase.UPLOAD, AuditEvent.Outcome.SUCCEEDED);
      return artifact;
    } catch (RuntimeException | IOException failure) {
      recordOutcome(owner, AuditEvent.Phase.UPLOAD, AuditEvent.Outcome.FAILED);
      throw failure;
    }
  }

  @GetMapping("/{fileId}")
  public void download(
      Authentication authentication, @PathVariable String fileId, HttpServletResponse response)
      throws IOException {
    BridgeIdentity owner = identity(authentication);
    try (ArtifactContent content = store.open(owner, fileId)) {
      record(owner, AuditEvent.Phase.DOWNLOAD, AuditEvent.Outcome.ALLOWED);
      FileArtifact a = content.artifact();
      response.setContentType(a.mediaType());
      response.setContentLengthLong(a.size());
      response.setHeader(
          HttpHeaders.CONTENT_DISPOSITION,
          ContentDisposition.attachment()
              .filename(a.filename(), StandardCharsets.UTF_8)
              .build()
              .toString());
      response.setHeader("X-Content-Type-Options", "nosniff");
      response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
      response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
      content.stream().transferTo(response.getOutputStream());
      recordOutcome(owner, AuditEvent.Phase.DOWNLOAD, AuditEvent.Outcome.SUCCEEDED);
    } catch (RuntimeException | IOException failure) {
      recordOutcome(owner, AuditEvent.Phase.DOWNLOAD, AuditEvent.Outcome.FAILED);
      throw failure;
    }
  }

  @DeleteMapping("/{fileId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(Authentication authentication, @PathVariable String fileId) {
    BridgeIdentity owner = identity(authentication);
    record(owner, AuditEvent.Phase.CANCEL, AuditEvent.Outcome.ALLOWED);
    try {
      store.delete(owner, fileId);
      recordOutcome(owner, AuditEvent.Phase.CANCEL, AuditEvent.Outcome.SUCCEEDED);
    } catch (RuntimeException failure) {
      recordOutcome(owner, AuditEvent.Phase.CANCEL, AuditEvent.Outcome.FAILED);
      throw failure;
    }
  }

  private void record(BridgeIdentity owner, AuditEvent.Phase phase, AuditEvent.Outcome outcome) {
    AuditRepository repository = audit.get();
    if (repository != null)
      repository.append(
          AuditEvent.create(
              owner,
              "bridge.files." + phase.name().toLowerCase(java.util.Locale.ROOT),
              null,
              phase,
              outcome,
              null));
  }

  private void recordOutcome(
      BridgeIdentity owner, AuditEvent.Phase phase, AuditEvent.Outcome outcome) {
    try {
      record(owner, phase, outcome);
    } catch (RuntimeException auditFailure) {
      org.slf4j.LoggerFactory.getLogger(BridgeFileController.class)
          .error("File audit outcome could not be persisted; inspect the audit store");
    }
  }

  @ExceptionHandler(BridgeException.class)
  public ResponseEntity<Map<String, String>> error(BridgeException e) {
    HttpStatus status =
        switch (e.code()) {
          case "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
          case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
          case "FILE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
          case "FILE_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
          case "FILE_QUOTA_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
          case "FILE_STORAGE_ERROR", "AUDIT_FULL" -> HttpStatus.SERVICE_UNAVAILABLE;
          default -> HttpStatus.BAD_REQUEST;
        };
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .body(Map.of("code", e.code(), "message", e.getMessage()));
  }
}
