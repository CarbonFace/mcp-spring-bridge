package com.cogistra.mcpbridge.files;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cogistra.mcpbridge.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FileLifecycleTest {
  @TempDir Path directory;
  BridgeIdentity alice = new BridgeIdentity("issuer", "alice", "client", Set.of(), Set.of());
  BridgeIdentity bob = new BridgeIdentity("issuer", "bob", "client", Set.of(), Set.of());
  BridgeIdentity otherClient =
      new BridgeIdentity("issuer", "alice", "other-client", Set.of(), Set.of());

  BridgeFilesProperties limits() {
    BridgeFilesProperties p = new BridgeFilesProperties();
    p.setDirectory(directory);
    return p;
  }

  static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  static String digest(byte[] b) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
  }

  @Test
  void persistsAcrossRestartAndRejectsOtherOwnersAndClients() throws Exception {
    BridgeFilesProperties p = limits();
    FileArtifact file;
    boolean[] closed = {false};
    try (LocalBridgeFileStore store = new LocalBridgeFileStore(p)) {
      file =
          store.store(
              alice,
              "../../bad\r\n:name.html",
              "text/html",
              new ByteArrayInputStream(bytes("private")) {
                public void close() throws IOException {
                  closed[0] = true;
                  super.close();
                }
              });
      assertThat(closed[0]).isTrue();
      assertThat(file.filename()).isEqualTo("bad___name.html");
      assertThatThrownBy(() -> store.open(bob, file.fileId()))
          .isInstanceOf(BridgeException.class)
          .hasMessage("File is unavailable");
      assertThatThrownBy(() -> store.delete(otherClient, file.fileId()))
          .isInstanceOf(BridgeException.class);
      assertThatThrownBy(() -> store.open(alice, "../../escape"))
          .isInstanceOf(BridgeException.class);
    }
    try (LocalBridgeFileStore reopened = new LocalBridgeFileStore(p);
        ArtifactContent c = reopened.open(alice, file.fileId())) {
      assertThat(c.stream().readAllBytes()).isEqualTo(bytes("private"));
    }
  }

  @Test
  void quotasAndExpiryCoverArtifactsAndUploadReservations() throws Exception {
    BridgeFilesProperties p = limits();
    p.setMaxFileBytes(8);
    p.setOwnerQuotaBytes(8);
    p.setTotalQuotaBytes(16);
    p.setMaxChunkBytes(4);
    p.setTtl(Duration.ofSeconds(10));
    p.setUploadTtl(Duration.ofSeconds(10));
    MutableClock clock = new MutableClock();
    try (LocalBridgeFileStore store = new LocalBridgeFileStore(p, clock)) {
      FileUploadService uploads = new FileUploadService(store);
      var session = uploads.begin(alice, "a", "text/plain", 8, digest(bytes("12345678")));
      assertThatThrownBy(
              () -> store.store(alice, "b", "text/plain", new ByteArrayInputStream(bytes("1"))))
          .isInstanceOf(BridgeException.class)
          .hasMessageContaining("quota");
      uploads.append(alice, session.uploadId(), 0, bytes("1234"));
      clock.now = clock.now.plusSeconds(11);
      store.cleanupExpired();
      assertThatThrownBy(() -> uploads.append(alice, session.uploadId(), 4, bytes("5678")))
          .isInstanceOf(BridgeException.class);
      FileArtifact a =
          store.store(alice, "a", "text/plain", new ByteArrayInputStream(bytes("12345678")));
      assertThatThrownBy(
              () ->
                  store.store(
                      bob, "too-large", "text/plain", new ByteArrayInputStream(bytes("123456789"))))
          .isInstanceOf(BridgeException.class)
          .hasMessageContaining("limit");
      clock.now = clock.now.plusSeconds(11);
      store.cleanupExpired();
      assertThatThrownBy(() -> store.open(alice, a.fileId())).isInstanceOf(BridgeException.class);
      try (var paths = Files.list(directory)) {
        assertThat(paths.map(x -> x.getFileName().toString()).toList())
            .containsExactly(".store.lock");
      }
    }
  }

  @Test
  void chunkRetriesAreConsistentAndCompletePersistsAcrossRestarts() throws Exception {
    BridgeFilesProperties p = limits();
    p.setMaxChunkBytes(4);
    byte[] data = bytes("abcdefgh");
    String session;
    try (LocalBridgeFileStore store = new LocalBridgeFileStore(p)) {
      FileUploadService uploads = new FileUploadService(store);
      session = uploads.begin(alice, "data.txt", "text/plain", 8, digest(data)).uploadId();
      uploads.append(alice, session, 0, bytes("abcd"));
      assertThat(uploads.append(alice, session, 0, bytes("abcd")).receivedBytes()).isEqualTo(4);
      assertThatThrownBy(() -> uploads.append(alice, session, 0, bytes("abce")))
          .isInstanceOf(BridgeException.class)
          .hasMessageContaining("differs");
      assertThatThrownBy(() -> uploads.append(alice, session, 6, bytes("gh")))
          .isInstanceOf(BridgeException.class)
          .hasMessageContaining("sequential");
      assertThatThrownBy(() -> uploads.append(otherClient, session, 4, bytes("efgh")))
          .isInstanceOf(BridgeException.class);
    }
    String fileId;
    try (LocalBridgeFileStore store = new LocalBridgeFileStore(p)) {
      FileUploadService uploads = new FileUploadService(store);
      uploads.append(alice, session, 4, bytes("efgh"));
      fileId = uploads.complete(alice, session).fileId();
      assertThat(uploads.complete(alice, session).fileId()).isEqualTo(fileId);
      assertThat(FileAdapters.multipart(store, alice, fileId).getBytes()).isEqualTo(data);
    }
    try (LocalBridgeFileStore store = new LocalBridgeFileStore(p)) {
      assertThat(new FileUploadService(store).complete(alice, session).fileId()).isEqualTo(fileId);
      Path copy = directory.resolve("test-copy");
      FileAdapters.multipart(store, alice, fileId).transferTo(copy);
      assertThat(Files.readAllBytes(copy)).isEqualTo(data);
      Files.delete(copy);
    }
  }

  @Test
  void failedDigestCannotPublishAndCancelledUploadReleasesReservation() throws Exception {
    try (LocalBridgeFileStore store = new LocalBridgeFileStore(limits())) {
      FileUploadService uploads = new FileUploadService(store);
      String id = uploads.begin(alice, "a", "text/plain", 3, digest(bytes("abc"))).uploadId();
      assertThatThrownBy(() -> uploads.complete(alice, id))
          .isInstanceOf(BridgeException.class)
          .hasMessageContaining("size");
      uploads.append(alice, id, 0, bytes("abd"));
      assertThatThrownBy(() -> uploads.complete(alice, id))
          .isInstanceOf(BridgeException.class)
          .hasMessageContaining("SHA-256");
      assertThatThrownBy(() -> store.open(alice, id)).isInstanceOf(BridgeException.class);
      uploads.cancel(alice, id);
      try (var paths = Files.list(directory)) {
        assertThat(paths.map(x -> x.getFileName().toString()).toList())
            .containsExactly(".store.lock");
      }
    }
  }

  @Test
  void servletAndResponseEntityExportsAreBoundedAndRetainFileMetadata() throws Exception {
    try (LocalBridgeFileStore store = new LocalBridgeFileStore(limits())) {
      CapturingFileResponse response = new CapturingFileResponse(32);
      response.setContentType("text/csv;charset=UTF-8");
      response.setHeader("Content-Disposition", "attachment; filename=report.csv");
      response.getWriter().write("amount\n12");
      FileArtifact file = response.finish(store, alice, "export");
      assertThat(file.filename()).isEqualTo("report.csv");
      assertThat(FileAdapters.multipart(store, alice, file.fileId()).getBytes())
          .isEqualTo(bytes("amount\n12"));
      var entity =
          ResponseEntity.ok()
              .header("Content-Disposition", "attachment; filename=data.bin")
              .body(new byte[] {0, 1, 2});
      assertThat(FileAdapters.fromResponseEntity(store, alice, entity).size()).isEqualTo(3);
      CapturingFileResponse tooLarge = new CapturingFileResponse(2);
      assertThatThrownBy(() -> tooLarge.getOutputStream().write(new byte[3]))
          .isInstanceOf(BridgeException.class);
      CapturingFileResponse denied = new CapturingFileResponse(8);
      denied.sendError(403);
      assertThatThrownBy(() -> denied.finish(store, alice, "x"))
          .isInstanceOf(BridgeException.class)
          .hasMessageContaining("rejected");
    }
  }

  @Test
  void httpMultipartDownloadDeletePreserveIsolationAndAttachmentHeaders() throws Exception {
    try (LocalBridgeFileStore store = new LocalBridgeFileStore(limits())) {
      var auth = UsernamePasswordAuthenticationToken.authenticated("alice", "", List.of());
      var other = UsernamePasswordAuthenticationToken.authenticated("bob", "", List.of());
      BridgeFileController controller =
          new BridgeFileController(
              store, a -> new BridgePrincipal(a.getName().equals("alice") ? alice : bob, a));
      MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
      MockMultipartFile part =
          new MockMultipartFile("file", "page.html", "text/html", bytes("<p>private</p>"));
      mvc.perform(multipart("/mcp-bridge/files").file(part)).andExpect(status().isUnauthorized());
      MockMvc missingResolver =
          MockMvcBuilders.standaloneSetup(
                  new BridgeFileController(store, (BridgeIdentityResolver) null))
              .build();
      missingResolver
          .perform(multipart("/mcp-bridge/files").file(part).principal(auth))
          .andExpect(status().isUnauthorized());
      String result =
          mvc.perform(multipart("/mcp-bridge/files").file(part).principal(auth))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      String id = store.json.readTree(result).get("fileId").asText();
      mvc.perform(get("/mcp-bridge/files/" + id)).andExpect(status().isUnauthorized());
      mvc.perform(get("/mcp-bridge/files/" + id).principal(other)).andExpect(status().isNotFound());
      mvc.perform(get("/mcp-bridge/files/" + id).principal(auth))
          .andExpect(status().isOk())
          .andExpect(content().bytes(part.getBytes()))
          .andExpect(header().string("X-Content-Type-Options", "nosniff"))
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(
              header()
                  .string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")));
      mvc.perform(delete("/mcp-bridge/files/" + id).principal(other))
          .andExpect(status().isNotFound());
      mvc.perform(delete("/mcp-bridge/files/" + id).principal(auth))
          .andExpect(status().isNoContent());
      mvc.perform(get("/mcp-bridge/files/" + id).principal(auth)).andExpect(status().isNotFound());
    }
  }

  @Test
  void existingSymbolicLinkCannotRedirectStoredContent() throws Exception {
    Path outside = Files.createTempFile("bridge-outside-", ".txt");
    try (LocalBridgeFileStore store = new LocalBridgeFileStore(limits())) {
      FileArtifact file =
          store.store(alice, "a", "text/plain", new ByteArrayInputStream(bytes("safe")));
      Files.delete(directory.resolve(file.fileId() + ".blob"));
      try {
        Files.createSymbolicLink(directory.resolve(file.fileId() + ".blob"), outside);
      } catch (IOException | UnsupportedOperationException e) {
        Assumptions.abort("Host does not permit symbolic links");
      }
      assertThatThrownBy(() -> store.open(alice, file.fileId()))
          .isInstanceOf(BridgeException.class);
      assertThat(Files.size(outside)).isZero();
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-09-12T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
