package com.cogistra.mcpbridge.files;

import com.cogistra.mcpbridge.api.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.Supplier;

/** Isolated bounded response, never delegates to the actual MCP transport response. */
public final class CapturingFileResponse implements HttpServletResponse {
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final long maxBytes;
  private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  private int status = 200;
  private String encoding = "UTF-8";
  private String type;
  private Locale locale = Locale.ROOT;
  private boolean committed;
  private boolean streamUsed;
  private PrintWriter writer;
  private Supplier<Map<String, String>> trailers = Map::of;
  private final ServletOutputStream stream =
      new ServletOutputStream() {
        @Override
        public void write(int b) {
          checkLimit(1);
          bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
          Objects.checkFromIndexSize(off, len, b.length);
          checkLimit(len);
          bytes.write(b, off, len);
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
          throw new UnsupportedOperationException("Asynchronous servlet output is not supported");
        }
      };

  public CapturingFileResponse(long maxBytes) {
    if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Invalid export limit");
    this.maxBytes = maxBytes;
  }

  private void checkLimit(int length) {
    if (length > maxBytes - bytes.size())
      throw new BridgeException("FILE_TOO_LARGE", "Export exceeds the configured limit");
  }

  public FileArtifact finish(
      BridgeFileStore store, BridgeIdentity identity, String defaultFilename) {
    if (writer != null) writer.flush();
    if (status < 200 || status >= 300)
      throw new BridgeException("EXPORT_FAILED", "The export endpoint rejected the request");
    return store.store(
        identity,
        FileAdapters.filename(getHeader("Content-Disposition"), defaultFilename),
        type,
        new ByteArrayInputStream(bytes.toByteArray()));
  }

  public void addCookie(Cookie cookie) {
    addHeader("Set-Cookie", cookie.getName() + "=" + cookie.getValue());
  }

  public boolean containsHeader(String name) {
    return headers.containsKey(name);
  }

  public String encodeURL(String url) {
    return url;
  }

  public String encodeRedirectURL(String url) {
    return url;
  }

  public void sendError(int status, String message) throws IOException {
    ensureMutable();
    this.status = status;
    resetBuffer();
    committed = true;
  }

  public void sendError(int status) throws IOException {
    sendError(status, "");
  }

  public void sendRedirect(String location) throws IOException {
    ensureMutable();
    status = 302;
    setHeader("Location", location);
    committed = true;
  }

  public void setDateHeader(String name, long date) {
    setHeader(
        name,
        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(date).atZone(java.time.ZoneOffset.UTC)));
  }

  public void addDateHeader(String name, long date) {
    addHeader(
        name,
        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(date).atZone(java.time.ZoneOffset.UTC)));
  }

  public void setHeader(String name, String value) {
    if (!committed) {
      validateHeader(name, value);
      headers.put(name, new ArrayList<>(List.of(value)));
      if (name.equalsIgnoreCase("Content-Type")) type = value;
    }
  }

  public void addHeader(String name, String value) {
    if (!committed) {
      validateHeader(name, value);
      headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
      if (name.equalsIgnoreCase("Content-Type")) type = value;
    }
  }

  private void validateHeader(String name, String value) {
    if (name == null
        || value == null
        || name.matches(".*[\\r\\n].*")
        || value.matches(".*[\\r\\n].*"))
      throw new IllegalArgumentException("Invalid response header");
  }

  public void setIntHeader(String name, int value) {
    setHeader(name, Integer.toString(value));
  }

  public void addIntHeader(String name, int value) {
    addHeader(name, Integer.toString(value));
  }

  public void setStatus(int status) {
    if (!committed) this.status = status;
  }

  public int getStatus() {
    return status;
  }

  public String getHeader(String name) {
    List<String> v = headers.get(name);
    return v == null || v.isEmpty() ? null : v.get(0);
  }

  public Collection<String> getHeaders(String name) {
    return List.copyOf(headers.getOrDefault(name, List.of()));
  }

  public Collection<String> getHeaderNames() {
    return List.copyOf(headers.keySet());
  }

  public String getCharacterEncoding() {
    return encoding;
  }

  public String getContentType() {
    return type;
  }

  public ServletOutputStream getOutputStream() {
    if (writer != null) throw new IllegalStateException("Writer already obtained");
    streamUsed = true;
    return stream;
  }

  public PrintWriter getWriter() {
    if (streamUsed) throw new IllegalStateException("Output stream already obtained");
    if (writer == null)
      writer = new PrintWriter(new OutputStreamWriter(stream, Charset.forName(encoding)));
    return writer;
  }

  public void setCharacterEncoding(String charset) {
    if (!committed && writer == null) encoding = Charset.forName(charset).name();
  }

  public void setContentLength(int length) {
    setContentLengthLong(length);
  }

  public void setContentLengthLong(long length) {
    if (length < 0 || length > maxBytes)
      throw new BridgeException("FILE_TOO_LARGE", "Export exceeds the configured limit");
    setHeader("Content-Length", Long.toString(length));
  }

  public void setContentType(String value) {
    setHeader("Content-Type", value);
    if (writer == null) {
      try {
        Charset c = org.springframework.http.MediaType.parseMediaType(value).getCharset();
        if (c != null) setCharacterEncoding(c.name());
      } catch (IllegalArgumentException ignored) {
      }
    }
  }

  public void setBufferSize(int size) {
    ensureMutable();
    if (bytes.size() > 0) throw new IllegalStateException("Response already written");
  }

  public int getBufferSize() {
    return (int) maxBytes;
  }

  public void flushBuffer() {
    if (writer != null) writer.flush();
    committed = true;
  }

  public void resetBuffer() {
    ensureMutable();
    bytes.reset();
  }

  public boolean isCommitted() {
    return committed;
  }

  public void reset() {
    ensureMutable();
    bytes.reset();
    headers.clear();
    status = 200;
    type = null;
    encoding = "UTF-8";
    writer = null;
    streamUsed = false;
  }

  private void ensureMutable() {
    if (committed) throw new IllegalStateException("Response is committed");
  }

  public void setLocale(Locale locale) {
    if (!committed) this.locale = Objects.requireNonNull(locale);
  }

  public Locale getLocale() {
    return locale;
  }

  public void setTrailerFields(Supplier<Map<String, String>> supplier) {
    trailers = Objects.requireNonNull(supplier);
  }

  public Supplier<Map<String, String>> getTrailerFields() {
    return trailers;
  }
}
