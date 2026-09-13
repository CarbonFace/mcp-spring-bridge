package com.cogistra.mcpbridge.files;

import com.cogistra.mcpbridge.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Single-process persistent default. The directory must be owned by the service account. */
public final class LocalBridgeFileStore implements BridgeFileStore, AutoCloseable {
  final BridgeFilesProperties limits;
  final Path root;
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final Clock clock;
  private final FileChannel lockChannel;
  private final FileLock processLock;
  private final ScheduledExecutorService cleaner;

  record Metadata(
      String id,
      String owner,
      String client,
      String filename,
      String mediaType,
      long size,
      Instant expiresAt) {}

  public LocalBridgeFileStore(BridgeFilesProperties limits) {
    this(limits, Clock.systemUTC());
  }

  public LocalBridgeFileStore(BridgeFilesProperties limits, Clock clock) {
    limits.validate();
    this.limits = limits;
    this.clock = clock;
    root = limits.getDirectory().toAbsolutePath().normalize();
    FileChannel channel = null;
    FileLock lock = null;
    try {
      verifyAncestors(root);
      Files.createDirectories(root);
      verifyAncestors(root);
      try {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
      } catch (UnsupportedOperationException ignored) {
        /* Windows inherits service-account ACL. */
      }
      channel =
          FileChannel.open(
              safe(".store.lock"),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
      lock = channel.tryLock();
      if (lock == null) throw new IOException("File store already opened");
      lockChannel = channel;
      processLock = lock;
      cleanupExpired();
    } catch (Exception e) {
      try {
        if (lock != null) lock.release();
        if (channel != null) channel.close();
      } catch (IOException ignored) {
      }
      throw new IllegalStateException("Cannot safely open bridge file directory", e);
    }
    cleaner =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "mcp-file-cleaner");
              t.setDaemon(true);
              return t;
            });
    cleaner.scheduleWithFixedDelay(
        () -> {
          try {
            cleanupExpired();
          } catch (RuntimeException ignored) {
            /* Next request fails closed. */
          }
        },
        limits.getCleanupInterval().toMillis(),
        limits.getCleanupInterval().toMillis(),
        TimeUnit.MILLISECONDS);
  }

  static void verifyAncestors(Path path) throws IOException {
    for (Path p = path; p != null; p = p.getParent())
      if (Files.isSymbolicLink(p)) throw new IOException("Symbolic links are forbidden");
  }

  Path safe(String name) throws IOException {
    if (!name.matches("(?:[a-f0-9]{32}\\.(?:blob|json|part|upload|tmp)|\\.store\\.lock)"))
      throw new IOException("Invalid internal file name");
    verifyAncestors(root);
    Path p = root.resolve(name);
    if (Files.isSymbolicLink(p)) throw new IOException("Symbolic links are forbidden");
    return p;
  }

  static String id() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  static void checkId(String id) {
    if (id == null || !id.matches("[a-f0-9]{32}")) throw notFound();
  }

  static BridgeException notFound() {
    return new BridgeException("FILE_NOT_FOUND", "File is unavailable");
  }

  static BridgeException failure() {
    return new BridgeException("FILE_STORAGE_ERROR", "File storage is unavailable");
  }

  public static String normalizeFilename(String input) {
    String n = input == null ? "file" : input.replace('\\', '/');
    n = n.substring(n.lastIndexOf('/') + 1);
    n = n.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").strip().replaceAll("[. ]+$", "");
    if (n.isBlank() || n.equals(".") || n.equals("..")) n = "file";
    return n.length() > 180 ? n.substring(0, 180) : n;
  }

  static String mediaType(String value) {
    if (value == null
        || value.length() > 200
        || value.indexOf('\r') >= 0
        || value.indexOf('\n') >= 0) return "application/octet-stream";
    try {
      return org.springframework.http.MediaType.parseMediaType(value).toString();
    } catch (IllegalArgumentException e) {
      return "application/octet-stream";
    }
  }

  Metadata read(String id) throws IOException {
    return json.readValue(Files.readAllBytes(safe(id + ".json")), Metadata.class);
  }

  FileArtifact artifact(Metadata m) {
    return new FileArtifact(
        m.id,
        m.filename,
        m.mediaType,
        m.size,
        limits.getDownloadUrlPrefix().replaceAll("/+$", "") + "/" + m.id,
        m.expiresAt);
  }

  Metadata authorized(BridgeIdentity who, String id) throws IOException {
    Objects.requireNonNull(who);
    checkId(id);
    if (!Files.isRegularFile(safe(id + ".json"), LinkOption.NOFOLLOW_LINKS)) throw notFound();
    Metadata m = read(id);
    if (!m.id.equals(id)
        || !m.owner.equals(who.ownerKey())
        || !m.client.equals(who.clientId())
        || !m.expiresAt.isAfter(clock.instant())) throw notFound();
    return m;
  }

  void writeJson(String name, Object value) throws IOException {
    Path target = safe(name);
    String temp = id() + ".tmp";
    Path staging = safe(temp);
    try {
      Files.write(
          staging,
          json.writeValueAsBytes(value),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      Files.move(
          staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(staging);
    }
  }

  void checkQuota(BridgeIdentity owner, long addition, String excludedUpload) throws IOException {
    long total = 0, personal = 0;
    int count = 0, ownCount = 0;
    try (var paths = Files.list(root)) {
      for (Path p : paths.toList()) {
        String name = p.getFileName().toString();
        long size;
        String key;
        if (name.endsWith(".json")) {
          Metadata m = read(name.substring(0, 32));
          size = m.size;
          key = m.owner;
        } else if (name.endsWith(".upload") && !name.equals(excludedUpload + ".upload")) {
          FileUploadService.State s =
              json.readValue(Files.readAllBytes(safe(name)), FileUploadService.State.class);
          if (s.completedFileId() != null
              && Files.exists(safe(s.completedFileId() + ".json"), LinkOption.NOFOLLOW_LINKS))
            continue;
          size = s.completedFileId() == null ? s.totalBytes() : 0;
          key = s.owner();
        } else continue;
        total = Math.addExact(total, size);
        count++;
        if (key.equals(owner.ownerKey())) {
          personal = Math.addExact(personal, size);
          ownCount++;
        }
      }
    }
    if (addition > limits.getTotalQuotaBytes() - total
        || addition > limits.getOwnerQuotaBytes() - personal
        || count >= limits.getMaxFiles()
        || ownCount >= limits.getMaxOwnerFiles())
      throw new BridgeException("FILE_QUOTA_EXCEEDED", "File storage quota exceeded");
  }

  @Override
  public synchronized FileArtifact store(
      BridgeIdentity owner, String filename, String type, InputStream contents) {
    Objects.requireNonNull(owner);
    Objects.requireNonNull(contents);
    String id = id();
    Path blob = null;
    try {
      long size = 0;
      try (InputStream in = contents) {
        cleanupExpired();
        checkQuota(owner, 0, null);
        blob = safe(id + ".blob");
        try (OutputStream out =
            Files.newOutputStream(blob, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
          byte[] buffer = new byte[8192];
          int read;
          while ((read = in.read(buffer)) != -1) {
            if (read == 0) continue;
            size += read;
            if (size > limits.getMaxFileBytes())
              throw new BridgeException("FILE_TOO_LARGE", "File exceeds the configured limit");
            out.write(buffer, 0, read);
          }
        }
      }
      checkQuota(owner, size, null);
      Metadata m =
          new Metadata(
              id,
              owner.ownerKey(),
              owner.clientId(),
              normalizeFilename(filename),
              mediaType(type),
              size,
              clock.instant().plus(limits.getTtl()));
      writeJson(id + ".json", m);
      return artifact(m);
    } catch (BridgeException e) {
      discard(blob);
      throw e;
    } catch (IOException | ArithmeticException e) {
      discard(blob);
      throw failure();
    }
  }

  private void discard(Path p) {
    if (p != null)
      try {
        Files.deleteIfExists(p);
      } catch (IOException ignored) {
      }
  }

  @Override
  public synchronized ArtifactContent open(BridgeIdentity caller, String id) {
    try {
      Metadata m = authorized(caller, id);
      Path p = safe(id + ".blob");
      if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.size(p) != m.size)
        throw notFound();
      return new ArtifactContent(
          artifact(m), Files.newInputStream(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    } catch (IOException e) {
      throw notFound();
    }
  }

  @Override
  public synchronized void delete(BridgeIdentity caller, String id) {
    try {
      authorized(caller, id);
      Files.deleteIfExists(safe(id + ".blob"));
      Files.deleteIfExists(safe(id + ".json"));
    } catch (IOException e) {
      throw failure();
    }
  }

  public synchronized void cleanupExpired() {
    try (var paths = Files.list(root)) {
      for (Path p : paths.toList()) {
        String n = p.getFileName().toString();
        if (n.matches("[a-f0-9]{32}\\.json")) {
          Metadata m = read(n.substring(0, 32));
          if (!m.expiresAt.isAfter(clock.instant())) {
            Files.deleteIfExists(safe(m.id + ".blob"));
            Files.deleteIfExists(safe(n));
          }
        } else if (n.matches("[a-f0-9]{32}\\.upload")) {
          FileUploadService.State s =
              json.readValue(Files.readAllBytes(safe(n)), FileUploadService.State.class);
          if (!s.expiresAt().isAfter(clock.instant())) {
            Files.deleteIfExists(safe(s.uploadId() + ".part"));
            Files.deleteIfExists(safe(n));
          }
        } else if (n.matches("[a-f0-9]{32}\\.(blob|part|tmp)")) {
          String suffix = n.endsWith(".blob") ? ".json" : ".upload";
          boolean pendingCompletion =
              n.endsWith(".blob")
                  && Files.exists(safe(n.substring(0, 32) + ".upload"), LinkOption.NOFOLLOW_LINKS);
          if (n.endsWith(".tmp")
              || (!pendingCompletion
                  && !Files.exists(safe(n.substring(0, 32) + suffix), LinkOption.NOFOLLOW_LINKS)))
            Files.deleteIfExists(safe(n));
        }
      }
    } catch (IOException e) {
      throw failure();
    }
  }

  @Override
  public synchronized void close() throws IOException {
    cleaner.shutdownNow();
    processLock.release();
    lockChannel.close();
  }
}
