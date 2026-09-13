package com.cogistra.mcpbridge.transport;

import com.cogistra.mcpbridge.api.BridgePrincipal;
import com.cogistra.mcpbridge.security.BridgeInvocationContext;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.List;
import reactor.core.publisher.Mono;

/** Uses the official protocol handler while filtering discovery for the current caller. */
public final class GuardedStatelessTransport implements McpStatelessServerTransport {
  public interface Visibility {
    boolean tool(String name, BridgePrincipal principal);

    boolean resource(String uri, BridgePrincipal principal);

    boolean template(String uri, BridgePrincipal principal);

    boolean prompt(String name, BridgePrincipal principal);
  }

  private final McpStatelessServerTransport delegate;
  private final BridgeInvocationContext identities;
  private final Visibility visibility;

  public GuardedStatelessTransport(
      McpStatelessServerTransport delegate,
      BridgeInvocationContext identities,
      Visibility visibility) {
    this.delegate = delegate;
    this.identities = identities;
    this.visibility = visibility;
  }

  public void setMcpHandler(McpStatelessServerHandler handler) {
    delegate.setMcpHandler(
        new McpStatelessServerHandler() {
          public Mono<JSONRPCResponse> handleRequest(
              McpTransportContext context, JSONRPCRequest request) {
            return Mono.defer(
                    () -> {
                      identities.resolve(context);
                      return handler.handleRequest(context, request);
                    })
                .map(
                    response -> {
                      if (response.error() != null) return safeError(request.id());
                      BridgePrincipal principal = identities.resolve(context);
                      Object result = response.result();
                      if (result instanceof ListToolsResult list)
                        result =
                            new ListToolsResult(
                                list.tools().stream()
                                    .filter(v -> visibility.tool(v.name(), principal))
                                    .toList(),
                                null);
                      else if (result instanceof ListResourcesResult list)
                        result =
                            new ListResourcesResult(
                                list.resources().stream()
                                    .filter(v -> visibility.resource(v.uri(), principal))
                                    .toList(),
                                null);
                      else if (result instanceof ListResourceTemplatesResult list)
                        result =
                            new ListResourceTemplatesResult(
                                list.resourceTemplates().stream()
                                    .filter(v -> visibility.template(v.uriTemplate(), principal))
                                    .toList(),
                                null);
                      else if (result instanceof ListPromptsResult list)
                        result =
                            new ListPromptsResult(
                                list.prompts().stream()
                                    .filter(v -> visibility.prompt(v.name(), principal))
                                    .toList(),
                                null);
                      return new JSONRPCResponse(
                          McpSchema.JSONRPC_VERSION, response.id(), result, null);
                    })
                .onErrorReturn(safeError(request.id()));
          }

          public Mono<Void> handleNotification(
              McpTransportContext context, JSONRPCNotification notification) {
            return Mono.defer(
                () -> {
                  identities.resolve(context);
                  return handler.handleNotification(context, notification);
                });
          }
        });
  }

  private JSONRPCResponse safeError(Object id) {
    return new JSONRPCResponse(
        McpSchema.JSONRPC_VERSION,
        id,
        null,
        new JSONRPCResponse.JSONRPCError(-32001, "Request denied or could not be completed", null));
  }

  public Mono<Void> closeGracefully() {
    return delegate.closeGracefully();
  }

  public List<String> protocolVersions() {
    return delegate.protocolVersions();
  }
}
