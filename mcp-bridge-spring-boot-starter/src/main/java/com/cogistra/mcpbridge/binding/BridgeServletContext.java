package com.cogistra.mcpbridge.binding;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;

/**
 * Detached request view for supported MVC/request-context behavior; never wraps the MCP response.
 */
public final class BridgeServletContext {
  private BridgeServletContext() {}

  public static HttpServletRequest request(
      String method,
      String path,
      Map<String, String[]> parameters,
      byte[] body,
      Principal principal) {
    Map<String, Object> attributes = new HashMap<>();
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            HttpServletRequest.class.getClassLoader(),
            new Class[] {HttpServletRequest.class},
            (proxy, called, args) ->
                switch (called.getName()) {
                  case "getMethod" -> method;
                  case "getRequestURI", "getServletPath" -> path;
                  case "getContextPath" -> "";
                  case "getParameter" -> {
                    String[] v = parameters.get((String) args[0]);
                    yield v == null || v.length == 0 ? null : v[0];
                  }
                  case "getParameterValues" -> {
                    String[] v = parameters.get((String) args[0]);
                    yield v == null ? null : v.clone();
                  }
                  case "getParameterMap" -> Collections.unmodifiableMap(parameters);
                  case "getParameterNames" -> Collections.enumeration(parameters.keySet());
                  case "getAttribute" -> attributes.get((String) args[0]);
                  case "setAttribute" -> {
                    attributes.put((String) args[0], args[1]);
                    yield null;
                  }
                  case "removeAttribute" -> {
                    attributes.remove((String) args[0]);
                    yield null;
                  }
                  case "getAttributeNames" -> Collections.enumeration(attributes.keySet());
                  case "getUserPrincipal" -> principal;
                  case "getRemoteUser" -> principal == null ? null : principal.getName();
                  case "getAuthType" -> "MCP";
                  case "getCharacterEncoding" -> "UTF-8";
                  case "setCharacterEncoding" -> null;
                  case "getContentType" -> "application/json";
                  case "getContentLength" -> body.length;
                  case "getContentLengthLong" -> (long) body.length;
                  case "getReader" ->
                      new BufferedReader(
                          new InputStreamReader(
                              new ByteArrayInputStream(body), StandardCharsets.UTF_8));
                  case "getInputStream" -> stream(body);
                  case "getHeader", "getQueryString", "getPathInfo", "getSession" -> null;
                  case "getHeaders", "getHeaderNames" -> Collections.emptyEnumeration();
                  case "getLocale" -> Locale.ROOT;
                  case "getLocales" -> Collections.enumeration(List.of(Locale.ROOT));
                  case "isAsyncStarted", "isAsyncSupported", "isRequestedSessionIdValid" -> false;
                  case "toString" -> "BridgeRequest[" + method + " " + path + "]";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default ->
                      throw new UnsupportedOperationException(
                          "MCP request adapter does not emulate Servlet method: "
                              + called.getName());
                });
  }

  private static ServletInputStream stream(byte[] bytes) {
    ByteArrayInputStream input = new ByteArrayInputStream(bytes);
    return new ServletInputStream() {
      public int read() {
        return input.read();
      }

      public boolean isFinished() {
        return input.available() == 0;
      }

      public boolean isReady() {
        return true;
      }

      public void setReadListener(ReadListener listener) {
        throw new UnsupportedOperationException("Async Servlet reads are not supported");
      }
    };
  }
}
