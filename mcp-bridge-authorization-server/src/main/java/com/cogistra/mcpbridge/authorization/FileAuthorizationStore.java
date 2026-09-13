package com.cogistra.mcpbridge.authorization;

import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Encrypted, atomic, single-process durable store. Use a transactional replacement for clusters.
 */
public final class FileAuthorizationStore implements BridgeAuthorizationStore, AutoCloseable {
  private static final int MAX_BYTES = 64 * 1024 * 1024;
  private final ReentrantLock mutex = new ReentrantLock(true);
  private final Path file;
  private final SecretKeySpec key;
  private final AuthorizationJson json;
  private final FileChannel owner;
  private final FileLock ownerLock;
  private final LinkedHashMap<String, OAuth2Authorization> rows = new LinkedHashMap<>();
  private boolean failed, closed;

  public FileAuthorizationStore(
      Path directory, byte[] encryptionKey, RegisteredClientRepository clients) {
    if (directory == null || !directory.isAbsolute() || encryptionKey.length != 32)
      throw new IllegalArgumentException(
          "An absolute storage directory and a 256-bit storage key are required");
    key = new SecretKeySpec(encryptionKey.clone(), "AES");
    json = new AuthorizationJson(clients);
    FileChannel opened = null;
    FileLock held = null;
    try {
      Path normalized = directory.normalize();
      for (Path p = normalized; p != null; p = p.getParent())
        if (Files.isSymbolicLink(p))
          throw new IllegalArgumentException("Storage directory must not contain symbolic links");
      Files.createDirectories(normalized);
      privatePermissions(normalized, true);
      file = normalized.resolve("authorizations.enc");
      Path lock = normalized.resolve("authorizations.lock");
      if (Files.isSymbolicLink(lock) || Files.isSymbolicLink(file))
        throw new IllegalArgumentException("Storage files must not be symbolic links");
      opened = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      privatePermissions(lock, false);
      held = opened.tryLock();
      if (held == null)
        throw new IllegalStateException(
            "Authorization storage is already in use by another process");
      if (Files.exists(file)) {
        if (Files.size(file) > MAX_BYTES)
          throw new IllegalStateException("Authorization storage exceeds its size limit");
        byte[] data = Files.readAllBytes(file);
        if (data.length < 29) throw new IllegalStateException("Invalid authorization storage");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(data, 12)));
        cipher.updateAAD(
            "mcp-bridge-authorization-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (var a : json.read(cipher.doFinal(data, 12, data.length - 12))) rows.put(a.getId(), a);
      }
      owner = opened;
      ownerLock = held;
      persist(rows); // Verify writability and remove grants belonging to removed clients before
      // serving.
    } catch (Exception e) {
      try {
        if (held != null) held.release();
        if (opened != null) opened.close();
      } catch (Exception ignored) {
      }
      throw new IllegalStateException("Cannot open durable authorization storage", e);
    }
  }

  @Override
  public <T> T locked(Supplier<T> operation) {
    mutex.lock();
    try {
      if (failed || closed) throw new IllegalStateException("Authorization storage is unavailable");
      return operation.get();
    } finally {
      mutex.unlock();
    }
  }

  @Override
  public void save(OAuth2Authorization a) {
    locked(
        () -> {
          var next = new LinkedHashMap<>(rows);
          next.put(a.getId(), a);
          commit(next);
          return null;
        });
  }

  @Override
  public void remove(OAuth2Authorization a) {
    locked(
        () -> {
          var next = new LinkedHashMap<>(rows);
          next.remove(a.getId());
          commit(next);
          return null;
        });
  }

  @Override
  public OAuth2Authorization findById(String id) {
    return locked(() -> rows.get(id));
  }

  @Override
  public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
    if (token == null) return null;
    return locked(
        () ->
            rows.values().stream()
                .filter(
                    a -> {
                      if ((type == null || "state".equals(type.getValue()))
                          && token.equals(a.getAttribute("state"))) return true;
                      OAuth2Authorization.Token<?> found = a.getToken(token);
                      return found != null
                          && (type == null
                              || (OAuth2TokenType.ACCESS_TOKEN.equals(type)
                                  && found == a.getAccessToken())
                              || (OAuth2TokenType.REFRESH_TOKEN.equals(type)
                                  && found == a.getRefreshToken())
                              || ("code".equals(type.getValue())
                                  && found == a.getToken(OAuth2AuthorizationCode.class)));
                    })
                .findFirst()
                .orElse(null));
  }

  @Override
  public List<OAuth2Authorization> findByPrincipalName(String subject) {
    return locked(
        () -> rows.values().stream().filter(a -> subject.equals(a.getPrincipalName())).toList());
  }

  @Override
  public OAuth2Authorization findByRetiredRefreshHash(String hash) {
    return locked(
        () ->
            rows.values().stream()
                .filter(
                    a -> {
                      Collection<?> retired = a.getAttribute(BridgeAuthorizationService.RETIRED);
                      return retired != null && retired.contains(hash);
                    })
                .findFirst()
                .orElse(null));
  }

  private void commit(LinkedHashMap<String, OAuth2Authorization> next) {
    java.time.Instant now = java.time.Instant.now();
    next.values()
        .removeIf(
            a -> {
              String absolute = a.getAttribute(BridgeAuthorizationService.ABSOLUTE),
                  pending = a.getAttribute(BridgeAuthorizationService.PENDING_EXPIRY);
              return absolute != null && !now.isBefore(java.time.Instant.parse(absolute))
                  || a.getAttribute("state") != null
                      && pending != null
                      && !now.isBefore(java.time.Instant.parse(pending));
            });
    if (next.size() > 10000)
      throw new IllegalStateException(
          "Authorization storage capacity reached; install a scalable store");
    try {
      persist(next);
      rows.clear();
      rows.putAll(next);
    } catch (Exception e) {
      failed = true;
      throw new IllegalStateException(
          "Authorization storage write failed; reopen after recovery", e);
    }
  }

  private void persist(Map<String, OAuth2Authorization> data) throws Exception {
    byte[] plain = json.write(data.values());
    if (plain.length > MAX_BYTES - 32)
      throw new IllegalStateException("Authorization storage capacity reached");
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
    cipher.updateAAD(
        "mcp-bridge-authorization-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] encrypted = cipher.doFinal(plain);
    Arrays.fill(plain, (byte) 0);
    Path temporary = Files.createTempFile(file.getParent(), "authorization-", ".tmp");
    try {
      privatePermissions(temporary, false);
      try (var out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer =
            ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted);
        buffer.flip();
        while (buffer.hasRemaining()) out.write(buffer);
        out.force(true);
      }
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      // Directory fsync is supported on Unix; Windows guarantees replacement through its
      // filesystem.
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        try (var directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
          directory.force(true);
        }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void privatePermissions(Path path, boolean directory) throws Exception {
    if (Files.getFileStore(path).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(
          path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
  }

  @Override
  public void close() throws Exception {
    mutex.lock();
    try {
      if (!closed) {
        closed = true;
        try {
          ownerLock.release();
        } finally {
          owner.close();
        }
      }
    } finally {
      mutex.unlock();
    }
  }
}
