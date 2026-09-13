package com.cogistra.mcpbridge.transport;

import com.cogistra.mcpbridge.boot.BridgeProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects cross-origin requests and oversized RPC bodies before protocol parsing. */
public final class BridgeRequestGuard extends OncePerRequestFilter {
  private final BridgeProperties properties;

  public BridgeRequestGuard(BridgeProperties properties) {
    this.properties = properties;
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    if (origin != null && !properties.getAllowedOrigins().contains(origin)) {
      response.sendError(403);
      return;
    }
    String query = request.getQueryString();
    if (query != null
        && java.util.Arrays.stream(query.split("&"))
            .map(part -> part.split("=", 2)[0])
            .map(part -> java.net.URLDecoder.decode(part, StandardCharsets.UTF_8))
            .anyMatch("access_token"::equals)) {
      response.sendError(400);
      return;
    }
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (path.equals(properties.getPath()) && request.getMethod().equals("POST")) {
      long limit = (long) properties.getMaxArgumentBytes() + 65536;
      if (request.getContentLengthLong() > limit) {
        response.sendError(413);
        return;
      }
      byte[] bytes = request.getInputStream().readNBytes((int) limit + 1);
      if (bytes.length > limit) {
        response.sendError(413);
        return;
      }
      HttpServletRequestWrapper wrapper =
          new HttpServletRequestWrapper(request) {
            public ServletInputStream getInputStream() {
              ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
              return new ServletInputStream() {
                public int read() {
                  return stream.read();
                }

                public int read(byte[] b, int off, int len) {
                  return stream.read(b, off, len);
                }

                public boolean isFinished() {
                  return stream.available() == 0;
                }

                public boolean isReady() {
                  return true;
                }

                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException("Synchronous transport");
                }
              };
            }

            public BufferedReader getReader() {
              return new BufferedReader(
                  new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }

            public int getContentLength() {
              return bytes.length;
            }

            public long getContentLengthLong() {
              return bytes.length;
            }
          };
      chain.doFilter(wrapper, response);
      return;
    }
    chain.doFilter(request, response);
  }
}
