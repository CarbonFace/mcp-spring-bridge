package com.cogistra.mcpbridge.operation;

import static java.nio.file.StandardOpenOption.*;

import com.cogistra.mcpbridge.api.BridgeException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Internal bounded single-writer persistence shared by the optional local repositories. */
public final class AtomicPrivateJsonFile<T> implements AutoCloseable {
  private final ObjectMapper mapper =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
  private final Path directory;
  private final Path data;
  private final String temporaryPrefix;
  private final long maxBytes;
  private final Class<T> type;
  private FileChannel lockChannel;
  private FileLock processLock;
  private boolean closed;

  public AtomicPrivateJsonFile(Path directory, String fileName, long maxBytes, Class<T> type) {
    if (maxBytes < 1024 || maxBytes > 256L * 1024 * 1024)
      throw new IllegalArgumentException("Storage byte limit must be 1 KiB to 256 MiB");
    if (!fileName.matches("[a-z][a-z0-9-]*\\.json"))
      throw new IllegalArgumentException("Invalid storage file name");
    this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    this.data = this.directory.resolve(fileName);
    this.temporaryPrefix = "." + fileName + ".pending-";
    this.maxBytes = maxBytes;
    this.type = type;
    try {
      rejectLinkedParents(this.directory);
      Files.createDirectories(this.directory);
      if (!Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Invalid directory");
      makePrivate(this.directory, true);
      Path lockPath = this.directory.resolve(fileName + ".lock");
      rejectLink(lockPath);
      lockChannel = FileChannel.open(lockPath, CREATE, WRITE, LinkOption.NOFOLLOW_LINKS);
      makePrivate(lockPath, false);
      processLock = lockChannel.tryLock();
      if (processLock == null) throw new IOException("Storage is already open");
      // Only this file's abandoned drafts; another repository may own a different file in the
      // directory.
      try (DirectoryStream<Path> drafts =
          Files.newDirectoryStream(this.directory, temporaryPrefix + "*.json")) {
        for (Path draft : drafts) {
          rejectLink(draft);
          if (!Files.isRegularFile(draft, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid storage draft");
          Files.delete(draft);
        }
      }
      rejectLink(data);
      if (Files.exists(data, LinkOption.NOFOLLOW_LINKS)) makePrivate(data, false);
    } catch (IOException | OverlappingFileLockException | SecurityException failure) {
      closeAfterFailure();
      throw storageError(
          "STORAGE_UNAVAILABLE", "Private local storage is unavailable or already in use");
    }
  }

  public synchronized T readOr(T empty) {
    requireOpen();
    try {
      rejectLink(data);
      if (!Files.exists(data, LinkOption.NOFOLLOW_LINKS)) return empty;
      if (!Files.isRegularFile(data, LinkOption.NOFOLLOW_LINKS) || Files.size(data) > maxBytes) {
        throw storageError(
            "STORAGE_INVALID", "Stored data is invalid or exceeds its configured limit");
      }
      try (var input = Files.newInputStream(data, READ, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = input.readNBytes((int) maxBytes + 1);
        if (bytes.length > maxBytes)
          throw storageError("STORAGE_INVALID", "Stored data exceeds its configured limit");
        return mapper.readValue(bytes, type);
      }
    } catch (IOException failure) {
      throw storageError(
          "STORAGE_INVALID", "Private local data could not be read; it was not replaced");
    }
  }

  public synchronized void write(T value) {
    requireOpen();
    Path temporary = null;
    try {
      byte[] bytes = mapper.writeValueAsBytes(value);
      if (bytes.length > maxBytes)
        throw storageError("STORAGE_FULL", "Local storage reached its configured byte limit");
      rejectLink(data);
      temporary = Files.createTempFile(directory, temporaryPrefix, ".json");
      makePrivate(temporary, false);
      try (var channel =
          FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      // No non-atomic fallback: a platform without atomic replacement must use another store.
      Files.move(
          temporary, data, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      temporary = null;
      // File force protects contents; directory durability is platform dependent, documented
      // separately.
      try (var channel = FileChannel.open(directory, READ)) {
        channel.force(true);
      } catch (IOException | UnsupportedOperationException ignored) {
      }
    } catch (IOException failure) {
      throw storageError(
          "STORAGE_WRITE_FAILED", "Private local data could not be durably recorded");
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
    }
  }

  private static void rejectLinkedParents(Path path) throws IOException {
    for (Path current = path; current != null; current = current.getParent()) rejectLink(current);
  }

  private static void rejectLink(Path path) throws IOException {
    if (Files.isSymbolicLink(path)
        || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            && Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .isOther())) {
      throw new IOException("Linked paths are not permitted");
    }
  }

  private static void makePrivate(Path path, boolean directory) throws IOException {
    PosixFileAttributeView posix =
        Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) {
      posix.setPermissions(
          directory
              ? PosixFilePermissions.fromString("rwx------")
              : PosixFilePermissions.fromString("rw-------"));
      return;
    }
    AclFileAttributeView acl =
        Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl == null) throw new IOException("Private file permissions unavailable");
    var builder =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(acl.getOwner())
            .setPermissions(EnumSet.allOf(AclEntryPermission.class));
    if (directory) builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
    acl.setAcl(List.of(builder.build()));
  }

  private void requireOpen() {
    if (closed) throw storageError("STORAGE_CLOSED", "Local storage is closed");
  }

  private static BridgeException storageError(String code, String message) {
    return new BridgeException(code, message);
  }

  private void closeAfterFailure() {
    try {
      if (processLock != null) processLock.release();
    } catch (IOException ignored) {
    }
    try {
      if (lockChannel != null) lockChannel.close();
    } catch (IOException ignored) {
    }
  }

  @Override
  public synchronized void close() {
    if (!closed) {
      closed = true;
      closeAfterFailure();
    }
  }
}
