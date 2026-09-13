package com.cogistra.mcpbridge.files;

import com.cogistra.mcpbridge.api.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.HexFormat;

/** Restart-safe bounded chunks. All access is serialized with the backing default store. */
public final class FileUploadService {
  private final LocalBridgeFileStore store;

  public FileUploadService(LocalBridgeFileStore store) {
    this.store = store;
  }

  public record UploadSession(
      String uploadId, long totalBytes, int maxChunkBytes, Instant expiresAt) {}

  public record UploadProgress(String uploadId, long receivedBytes, long totalBytes) {}

  record State(
      String uploadId,
      String owner,
      String client,
      String filename,
      String mediaType,
      long totalBytes,
      String sha256,
      Instant expiresAt,
      String completedFileId) {}

  public UploadSession begin(
      BridgeIdentity who, String filename, String mediaType, long totalBytes, String sha256) {
    synchronized (store) {
      if (totalBytes < 0 || totalBytes > store.limits.getMaxFileBytes())
        throw new BridgeException("FILE_TOO_LARGE", "Invalid upload size");
      if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}"))
        throw new BridgeException("INVALID_DIGEST", "SHA-256 is required");
      String id = LocalBridgeFileStore.id();
      try {
        store.cleanupExpired();
        store.checkQuota(who, totalBytes, null);
        State s =
            new State(
                id,
                who.ownerKey(),
                who.clientId(),
                LocalBridgeFileStore.normalizeFilename(filename),
                LocalBridgeFileStore.mediaType(mediaType),
                totalBytes,
                sha256.toLowerCase(java.util.Locale.ROOT),
                store.clock.instant().plus(store.limits.getUploadTtl()),
                null);
        Files.createFile(store.safe(id + ".part"));
        try {
          store.writeJson(id + ".upload", s);
        } catch (IOException e) {
          Files.deleteIfExists(store.safe(id + ".part"));
          throw e;
        }
        return new UploadSession(id, totalBytes, store.limits.getMaxChunkBytes(), s.expiresAt);
      } catch (IOException e) {
        throw LocalBridgeFileStore.failure();
      }
    }
  }

  State authorized(BridgeIdentity who, String id) throws IOException {
    LocalBridgeFileStore.checkId(id);
    if (!Files.isRegularFile(store.safe(id + ".upload"), LinkOption.NOFOLLOW_LINKS))
      throw LocalBridgeFileStore.notFound();
    State s = store.json.readValue(Files.readAllBytes(store.safe(id + ".upload")), State.class);
    if (!s.uploadId.equals(id)
        || !s.owner.equals(who.ownerKey())
        || !s.client.equals(who.clientId())
        || !s.expiresAt.isAfter(store.clock.instant())) throw LocalBridgeFileStore.notFound();
    return s;
  }

  public UploadProgress append(BridgeIdentity who, String uploadId, long offset, byte[] chunk) {
    synchronized (store) {
      if (chunk == null || chunk.length == 0 || chunk.length > store.limits.getMaxChunkBytes())
        throw new BridgeException("INVALID_CHUNK", "Invalid upload chunk size");
      Path staging = null;
      try {
        State s = authorized(who, uploadId);
        if (s.completedFileId != null)
          throw new BridgeException("UPLOAD_COMPLETED", "Upload is already completed");
        if (offset < 0 || offset > s.totalBytes - chunk.length)
          throw new BridgeException("INVALID_CHUNK", "Chunk exceeds declared upload size");
        long length;
        try (FileChannel channel =
            FileChannel.open(
                store.safe(uploadId + ".part"),
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
          length = channel.size();
          if (offset > length || (offset < length && offset + chunk.length > length))
            throw new BridgeException("UPLOAD_OFFSET_CONFLICT", "Upload chunks must be sequential");
          channel.position(offset);
          if (offset < length) {
            ByteBuffer previous = ByteBuffer.allocate(chunk.length);
            while (previous.hasRemaining())
              if (channel.read(previous) < 0) throw LocalBridgeFileStore.failure();
            if (!MessageDigest.isEqual(previous.array(), chunk))
              throw new BridgeException(
                  "UPLOAD_RETRY_CONFLICT", "Retried chunk differs from stored content");
          } else {
            // Publish a complete replacement atomically: a crash cannot expose half a chunk.
            staging = store.safe(LocalBridgeFileStore.id() + ".tmp");
            try (FileChannel out =
                FileChannel.open(
                    staging,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
              long copied = 0;
              while (copied < length) {
                long n = channel.transferTo(copied, length - copied, out);
                if (n <= 0) throw new IOException("Cannot copy committed upload");
                copied += n;
              }
              ByteBuffer bytes = ByteBuffer.wrap(chunk);
              while (bytes.hasRemaining()) out.write(bytes);
              out.force(true);
            }
            length += chunk.length;
          }
        }
        if (staging != null)
          Files.move(
              staging,
              store.safe(uploadId + ".part"),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        return new UploadProgress(uploadId, length, s.totalBytes);
      } catch (IOException e) {
        throw LocalBridgeFileStore.failure();
      } finally {
        if (staging != null)
          try {
            Files.deleteIfExists(staging);
          } catch (IOException ignored) {
          }
      }
    }
  }

  public FileArtifact complete(BridgeIdentity who, String uploadId) {
    synchronized (store) {
      try {
        State s = authorized(who, uploadId);
        if (s.completedFileId != null) {
          try (ArtifactContent c = store.open(who, s.completedFileId)) {
            return c.artifact();
          }
        }
        // Reconcile a previous completion interrupted after artifact metadata was persisted.
        if (Files.exists(store.safe(uploadId + ".json"), LinkOption.NOFOLLOW_LINKS)) {
          FileArtifact a = store.artifact(store.authorized(who, uploadId));
          markCompleted(s);
          return a;
        }
        Path part = store.safe(uploadId + ".part");
        Path blob = store.safe(uploadId + ".blob");
        Path input = Files.exists(part, LinkOption.NOFOLLOW_LINKS) ? part : blob;
        if (Files.size(input) != s.totalBytes)
          throw new BridgeException("UPLOAD_INCOMPLETE", "Upload size does not match declaration");
        MessageDigest digest = digest();
        try (InputStream in =
            Files.newInputStream(input, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
          byte[] b = new byte[8192];
          int n;
          while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        if (!MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(s.sha256)))
          throw new BridgeException("UPLOAD_DIGEST_MISMATCH", "Upload SHA-256 does not match");
        store.checkQuota(who, s.totalBytes, uploadId);
        if (input.equals(part)) Files.move(part, blob, StandardCopyOption.ATOMIC_MOVE);
        var m =
            new LocalBridgeFileStore.Metadata(
                uploadId,
                s.owner,
                s.client,
                s.filename,
                s.mediaType,
                s.totalBytes,
                store.clock.instant().plus(store.limits.getTtl()));
        store.writeJson(uploadId + ".json", m);
        markCompleted(s);
        return store.artifact(m);
      } catch (IOException e) {
        throw LocalBridgeFileStore.failure();
      }
    }
  }

  private void markCompleted(State s) throws IOException {
    store.writeJson(
        s.uploadId + ".upload",
        new State(
            s.uploadId,
            s.owner,
            s.client,
            s.filename,
            s.mediaType,
            s.totalBytes,
            s.sha256,
            s.expiresAt,
            s.uploadId));
  }

  public void cancel(BridgeIdentity who, String uploadId) {
    synchronized (store) {
      try {
        State s = authorized(who, uploadId);
        if (s.completedFileId != null)
          throw new BridgeException("UPLOAD_COMPLETED", "Delete the completed file instead");
        Files.deleteIfExists(store.safe(uploadId + ".part"));
        Files.deleteIfExists(store.safe(uploadId + ".upload"));
      } catch (IOException e) {
        throw LocalBridgeFileStore.failure();
      }
    }
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
