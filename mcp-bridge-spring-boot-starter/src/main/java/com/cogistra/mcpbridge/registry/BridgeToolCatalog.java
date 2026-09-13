package com.cogistra.mcpbridge.registry;

import com.cogistra.mcpbridge.annotation.Effect;
import com.cogistra.mcpbridge.api.*;
import com.cogistra.mcpbridge.audit.*;
import com.cogistra.mcpbridge.binding.BridgeSchemas;
import com.cogistra.mcpbridge.boot.BridgeProperties;
import com.cogistra.mcpbridge.files.FileUploadService;
import com.cogistra.mcpbridge.guidance.BridgeGuidanceRegistry;
import com.cogistra.mcpbridge.operation.*;
import com.cogistra.mcpbridge.security.BridgeInvocationContext;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;

/**
 * The sole tool registration path for endpoint annotations, native annotations and explicit
 * callbacks.
 */
public final class BridgeToolCatalog {
  public record Capability(
      Tool tool,
      BridgePolicyGate.Policy policy,
      Preparation prepare,
      Invocation invoke,
      Invocation invokePrepared,
      boolean confirmationRuntime) {
    public Capability(
        Tool tool,
        BridgePolicyGate.Policy policy,
        Preparation prepare,
        Invocation invoke,
        Invocation invokePrepared) {
      this(tool, policy, prepare, invoke, invokePrepared, false);
    }
  }

  @FunctionalInterface
  public interface Preparation {
    Object validate(Map<String, Object> args, BridgePrincipal principal) throws Exception;
  }

  @FunctionalInterface
  public interface Invocation {
    Object call(McpTransportContext context, Map<String, Object> args, BridgePrincipal principal)
        throws Exception;
  }

  private final Map<String, Capability> capabilities = new LinkedHashMap<>();
  private final Map<String, BridgeConfirmedWriteRuntime> writeRuntimes = new LinkedHashMap<>();
  private final Map<String, BridgePolicyGate.Policy> published = new LinkedHashMap<>();
  private final List<SyncToolSpecification> specifications = new ArrayList<>();
  private final ObjectMapper json;
  private final BridgeSchemas schemas;
  private final BridgePolicyGate gate;
  private final BridgeInvocationContext identities;
  private final OperationCoordinator operations;
  private final AuditRepository audit;
  private final BridgeProperties properties;

  public BridgeToolCatalog(
      ObjectMapper json,
      BridgeSchemas schemas,
      BridgePolicyGate gate,
      BridgeInvocationContext identities,
      OperationCoordinator operations,
      AuditRepository audit,
      BridgeProperties properties) {
    this.json = json;
    this.schemas = schemas;
    this.gate = gate;
    this.identities = identities;
    this.operations = operations;
    this.audit = audit;
    this.properties = properties;
  }

  public void registerRuntime(BridgeConfirmedWriteRuntime runtime) {
    if (runtime.capabilityName() == null
        || runtime.capabilityName().isBlank()
        || writeRuntimes.putIfAbsent(runtime.capabilityName(), runtime) != null)
      throw new IllegalStateException("Duplicate or unnamed confirmed write runtime");
  }

  public void register(Capability capability) {
    String name = capability.tool().name();
    if (capabilities.putIfAbsent(name, capability) != null)
      throw new IllegalStateException("Duplicate MCP capability: " + name);
    if (!capability.policy().enabled()) return;
    if (capability.confirmationRuntime()) {
      if (capability.policy().effect() != Effect.WRITE)
        throw new IllegalStateException(
            "A confirmed runtime requires a declared WRITE capability: " + name);
      BridgeConfirmedWriteRuntime runtime = writeRuntimes.get(name);
      if (runtime == null)
        throw new IllegalStateException("Missing confirmed write runtime for " + name);
      registerTransactional(capability, runtime);
      return;
    }
    if (writeRuntimes.containsKey(name))
      throw new IllegalStateException(
          "The capability must explicitly opt into its confirmed runtime: " + name);
    if (capability.policy().effect() == Effect.READ) {
      publish(
          capability.tool(),
          capability.policy(),
          AuditEvent.Phase.READ,
          (context, args, principal) -> {
            validate(capability, args, principal);
            return capability.invoke().call(context, args, principal);
          });
    } else {
      ObjectNode prep = object();
      ((ObjectNode) prep.get("properties"))
          .set("arguments", json.valueToTree(capability.tool().inputSchema()));
      ((ObjectNode) prep.get("properties"))
          .set(
              "requestId",
              json.createObjectNode()
                  .put("type", "string")
                  .put("minLength", 1)
                  .put("maxLength", 256));
      prep.putArray("required").add("arguments").add("requestId");
      publish(
          tool(
              name + "_prepare",
              capability.tool().description()
                  + " — Prepare only. Show the complete preview to the user and obtain explicit confirmation before submit.",
              prep,
              true),
          capability.policy(),
          AuditEvent.Phase.PREPARE,
          (context, args, principal) -> {
            Map<String, Object> input = map(args.get("arguments"));
            JsonNode snapshot = snapshot(input, validate(capability, input, principal));
            return operations.prepare(
                principal.identity(), name, snapshot, string(args, "requestId"));
          });
      ObjectNode submit =
          fields(Map.of("operationId", "string", "revision", "integer", "payloadHash", "string"));
      publish(
          tool(
              name + "_submit",
              "Execute "
                  + name
                  + " only after the user explicitly confirms the exact preview revision and hash. Never infer confirmation; any edit requires new confirmation.",
              submit,
              false),
          capability.policy(),
          AuditEvent.Phase.EXECUTE,
          (context, args, principal) -> submit(name, context, args, principal));
    }
  }

  private void registerTransactional(Capability capability, BridgeConfirmedWriteRuntime runtime) {
    String name = capability.tool().name();
    ObjectNode prepare = fields(Map.of("requestId", "string"));
    ((ObjectNode) prepare.get("properties"))
        .set("arguments", json.valueToTree(capability.tool().inputSchema()));
    ((ArrayNode) prepare.get("required")).add("arguments");
    publish(
        tool(
            name + "_prepare",
            capability.tool().description()
                + " Prepare only; show the complete server review and wait for a new explicit user confirmation.",
            prepare,
            true),
        capability.policy(),
        AuditEvent.Phase.PREPARE,
        (c, a, p) -> {
          Map<String, Object> input = map(a.get("arguments"));
          schemas.validate(
              json.valueToTree(capability.tool().inputSchema()), json.valueToTree(input));
          gate.authorize(capability.policy(), p, input);
          return runtime.prepare(p, input, string(a, "requestId"));
        });
    ObjectNode submit =
        fields(Map.of("operationId", "string", "revision", "integer", "payloadHash", "string"));
    ((ObjectNode) submit.get("properties"))
        .set("review", json.createObjectNode().put("type", "object"));
    ((ArrayNode) submit.get("required")).add("review");
    publish(
        tool(
            name + "_submit",
            "Execute only after the user confirms this exact full server review. "
                + "Send no replacement business input; after a timeout query "
                + name
                + "_status before any retry.",
            submit,
            false),
        capability.policy(),
        AuditEvent.Phase.EXECUTE,
        (c, a, p) ->
            runtime.submit(
                p,
                string(a, "operationId"),
                number(a, "revision"),
                string(a, "payloadHash"),
                map(a.get("review")),
                canonical -> {
                  // The host holds its authorization/intent/domain locks here. Replacing that
                  // context
                  // or opening another transaction would break its atomic business/receipt
                  // boundary.
                  gate.authorizeCanonical(capability.policy(), p, canonical);
                  return capability.invokePrepared().call(c, canonical, p);
                }));
    ObjectNode revise = fields(Map.of("operationId", "string", "revision", "integer"));
    // The host must invalidate the old approval before validating a replacement draft. Validating
    // the draft in this outer envelope would leave the old approval live on an invalid edit.
    ((ObjectNode) revise.get("properties"))
        .set(
            "arguments",
            json.createObjectNode()
                .put("type", "object")
                .put(
                    "description",
                    "Replacement business input uses the prepare schema; validation follows withdrawal of the old revision."));
    ((ArrayNode) revise.get("required")).add("arguments");
    publish(
        tool(
            name + "_revise",
            "Invalidate the old confirmation and prepare a revised review. "
                + "Always show the complete new review and wait for a new confirmation.",
            revise,
            true),
        capability.policy(),
        AuditEvent.Phase.REVISE,
        (c, a, p) ->
            runtime.revise(
                p, string(a, "operationId"), number(a, "revision"), map(a.get("arguments"))));
    ObjectNode status = fields(Map.of("operationId", "string", "requestId", "string"));
    status.putArray("required");
    publish(
        tool(
            name + "_status",
            "Read this capability's owned durable intent by operationId OR requestId. "
                + "Recover the original result after a timeout; do not create another request.",
            status,
            true),
        capability.policy(),
        AuditEvent.Phase.STATUS,
        (c, a, p) -> {
          if (a.containsKey("operationId") == a.containsKey("requestId"))
            throw new BridgeException(
                "INVALID_ARGUMENT", "Use exactly one operationId or requestId");
          return runtime.status(
              p,
              a.containsKey("operationId") ? string(a, "operationId") : null,
              a.containsKey("requestId") ? string(a, "requestId") : null);
        });
    publish(
        tool(
            name + "_cancel",
            "Cancel only an unexecuted intent; this does not delete or undo a created business record.",
            fields(Map.of("operationId", "string", "revision", "integer")),
            true),
        capability.policy(),
        AuditEvent.Phase.CANCEL,
        (c, a, p) -> runtime.cancel(p, string(a, "operationId"), number(a, "revision")));
  }

  private Object submit(
      String capabilityName,
      McpTransportContext context,
      Map<String, Object> args,
      BridgePrincipal principal) {
    String id = string(args, "operationId");
    OperationView current = operations.status(principal.identity(), id);
    if (!current.capabilityName().equals(capabilityName))
      throw new BridgeException(
          "OPERATION_CONFLICT", "Operation belongs to a different capability");
    Capability capability = capability(capabilityName);
    Map<String, Object> stored = map(current.preview().get("execution"));
    // Re-authorize also when returning an existing receipt after retry.
    gate.authorizeCanonical(capability.policy(), principal, stored);
    return operations.execute(
        principal.identity(),
        id,
        number(args, "revision"),
        string(args, "payloadHash"),
        execution ->
            identities.call(
                context,
                latest -> {
                  Map<String, Object> original = map(execution.arguments().get("arguments"));
                  Map<String, Object> bound = map(execution.arguments().get("execution"));
                  try {
                    Object fresh = validate(capability, original, latest);
                    if (!json.<JsonNode>valueToTree(fresh)
                        .equals(
                            (left, right) ->
                                left.isNumber() && right.isNumber()
                                    ? left.decimalValue().compareTo(right.decimalValue())
                                    : left.equals(right) ? 0 : 1,
                            execution.arguments().get("execution")))
                      throw new OperationRejectedException(
                          "BINDING_CHANGED",
                          "Resolved input changed; prepare and confirm a new operation");
                    gate.authorizeCanonical(capability.policy(), latest, bound);
                  } catch (OperationRejectedException rejected) {
                    throw rejected;
                  } catch (BridgeException | AccessDeniedException rejected) {
                    throw new OperationRejectedException(
                        "FORBIDDEN_OR_INVALID",
                        "The current identity or input no longer allows this action");
                  }
                  Object result = capability.invokePrepared().call(context, bound, latest);
                  if (result instanceof CallToolResult nativeResult
                      && Boolean.TRUE.equals(nativeResult.isError()))
                    throw new BridgeException(
                        "NATIVE_EXECUTION_FAILED",
                        "The native callback reported failure; its side effects require reconciliation");
                  return json.valueToTree(result);
                }));
  }

  public void registerSystem(FileUploadService uploads) {
    system(
        "bridge_operation_status",
        "Read the durable outcome of a prepared operation; UNKNOWN must be reconciled, never blindly retried.",
        fields(Map.of("operationId", "string")),
        AuditEvent.Phase.STATUS,
        (c, a, p) -> ownedOperation(p, a));
    system(
        "bridge_operation_cancel",
        "Cancel an unexecuted preparation. This cannot undo a completed business action.",
        fields(Map.of("operationId", "string", "revision", "integer")),
        AuditEvent.Phase.CANCEL,
        (c, a, p) -> {
          ownedOperation(p, a);
          return operations.cancel(p.identity(), string(a, "operationId"), number(a, "revision"));
        });
    ObjectNode revise = fields(Map.of("operationId", "string", "revision", "integer"));
    ((ObjectNode) revise.get("properties"))
        .set("arguments", object().put("additionalProperties", true));
    ((ArrayNode) revise.get("required")).add("arguments");
    system(
        "bridge_operation_revise",
        "Change an unexecuted preview; old confirmation becomes invalid. Show the new preview and obtain confirmation again.",
        revise,
        AuditEvent.Phase.REVISE,
        (c, a, p) -> {
          OperationView previous = ownedOperation(p, a);
          Capability capability = capability(previous.capabilityName());
          Map<String, Object> input = map(a.get("arguments"));
          JsonNode snapshot = snapshot(input, validate(capability, input, p));
          return operations.revise(
              p.identity(), previous.operationId(), number(a, "revision"), snapshot);
        });
    ObjectNode auditSchema = object();
    ((ObjectNode) auditSchema.get("properties"))
        .set("cursor", json.createObjectNode().put("type", "string"));
    ((ObjectNode) auditSchema.get("properties"))
        .set(
            "limit",
            json.createObjectNode().put("type", "integer").put("minimum", 1).put("maximum", 200));
    system(
        "bridge_audit_query",
        "Query minimal audit metadata for the current identity and client only.",
        auditSchema,
        AuditEvent.Phase.READ,
        (c, a, p) ->
            audit.query(
                p.identity(),
                (String) a.get("cursor"),
                a.containsKey("limit") ? Math.toIntExact(number(a, "limit")) : 50));
    if (uploads != null) {
      system(
          "bridge_file_begin",
          "Begin an authenticated file upload. Provide the exact byte length and SHA-256 hex digest.",
          fields(
              Map.of(
                  "filename",
                  "string",
                  "mediaType",
                  "string",
                  "totalBytes",
                  "integer",
                  "sha256",
                  "string")),
          AuditEvent.Phase.UPLOAD,
          (c, a, p) ->
              uploads.begin(
                  p.identity(),
                  string(a, "filename"),
                  string(a, "mediaType"),
                  number(a, "totalBytes"),
                  string(a, "sha256")));
      system(
          "bridge_file_append",
          "Append one base64-encoded file chunk at the exact byte offset. A repeated identical chunk is safe.",
          fields(Map.of("uploadId", "string", "offset", "integer", "base64", "string")),
          AuditEvent.Phase.UPLOAD,
          (c, a, p) -> {
            byte[] bytes;
            try {
              bytes = Base64.getDecoder().decode(string(a, "base64"));
            } catch (IllegalArgumentException invalid) {
              throw new BridgeException("INVALID_BASE64", "File chunk must be valid base64");
            }
            return uploads.append(p.identity(), string(a, "uploadId"), number(a, "offset"), bytes);
          });
      system(
          "bridge_file_complete",
          "Verify the complete file length and digest and receive the fileId for an upload endpoint.",
          fields(Map.of("uploadId", "string")),
          AuditEvent.Phase.UPLOAD,
          (c, a, p) -> uploads.complete(p.identity(), string(a, "uploadId")));
      system(
          "bridge_file_cancel",
          "Discard an unfinished file upload.",
          fields(Map.of("uploadId", "string")),
          AuditEvent.Phase.UPLOAD,
          (c, a, p) -> {
            uploads.cancel(p.identity(), string(a, "uploadId"));
            return Map.of("cancelled", true);
          });
    }
  }

  public boolean registerGuidance(BridgeGuidanceRegistry registry) {
    if (registry.isEmpty()) return false;
    ObjectNode schema = object();
    var fields = (ObjectNode) schema.get("properties");
    fields.set(
        "skillId",
        json.createObjectNode().put("type", "string").put("minLength", 1).put("maxLength", 64));
    fields.set(
        "section",
        json.createObjectNode()
            .put("type", "string")
            .put("minLength", 1)
            .put("maxLength", 240)
            .put(
                "description",
                "An exact path returned in this skill's sections; never a local or remote path"));
    var format = json.createObjectNode().put("type", "string");
    format.putArray("enum").add("text").add("bundle");
    fields.set("format", format);
    system(
        BridgeGuidanceRegistry.TOOL_NAME,
        "Read current server-maintained business skills. Use {} for the available catalog; skillId for the complete SKILL; skillId plus section for a listed reference; skillId plus format=bundle for an optional private ZIP."
            + BridgeGuidanceRegistry.NAVIGATION,
        schema,
        AuditEvent.Phase.READ,
        (context, args, principal) -> {
          if (!visible(BridgeGuidanceRegistry.TOOL_NAME, principal))
            throw new BridgeException(
                "GUIDANCE_UNAVAILABLE", "Guidance is not available for this identity");
          Object value = registry.read(principal, args);
          return CallToolResult.builder()
              .addTextContent(json.writeValueAsString(value))
              .structuredContent(value)
              .isError(false)
              .build();
        });
    return published.get(BridgeGuidanceRegistry.TOOL_NAME).enabled();
  }

  private OperationView ownedOperation(BridgePrincipal p, Map<String, Object> args) {
    OperationView view = operations.status(p.identity(), string(args, "operationId"));
    gate.authorizeCanonical(
        capability(view.capabilityName()).policy(), p, map(view.preview().get("execution")));
    return view;
  }

  private Capability capability(String name) {
    Capability c = capabilities.get(name);
    if (c == null)
      throw new BridgeException(
          "CAPABILITY_UNAVAILABLE", "The original capability is no longer available");
    return c;
  }

  private Object validate(
      Capability capability, Map<String, Object> args, BridgePrincipal principal) throws Exception {
    schemas.validate(json.valueToTree(capability.tool().inputSchema()), json.valueToTree(args));
    gate.authorize(capability.policy(), principal, args);
    return capability.prepare().validate(args, principal);
  }

  private JsonNode snapshot(Map<String, Object> original, Object execution) throws Exception {
    JsonNode snapshot = json.valueToTree(Map.of("arguments", original, "execution", execution));
    if (json.writeValueAsBytes(snapshot).length
        > Math.min(properties.getMaxArgumentBytes(), properties.getMaxResultBytes() / 4))
      throw new BridgeException(
          "PREVIEW_TOO_LARGE",
          "Use bounded arguments or file references so the full confirmation preview can be returned");
    return snapshot;
  }

  private void system(
      String name,
      String description,
      ObjectNode schema,
      AuditEvent.Phase phase,
      Invocation callback) {
    publish(tool(name, description, schema, true), gate.systemPolicy(name), phase, callback);
  }

  private void publish(
      Tool tool, BridgePolicyGate.Policy policy, AuditEvent.Phase phase, Invocation action) {
    if (published.putIfAbsent(tool.name(), policy) != null)
      throw new IllegalStateException("Duplicate public MCP tool: " + tool.name());
    if (!policy.enabled()) return;
    specifications.add(
        new SyncToolSpecification(
            tool,
            (context, request) -> {
              try {
                return identities.call(
                    context,
                    principal -> {
                      Map<String, Object> args =
                          request.arguments() == null ? Map.of() : request.arguments();
                      try {
                        if (json.writeValueAsBytes(args).length > properties.getMaxArgumentBytes())
                          throw new BridgeException(
                              "ARGUMENTS_TOO_LARGE", "Arguments exceed the configured limit");
                        schemas.validate(
                            json.valueToTree(tool.inputSchema()), json.valueToTree(args));
                        if (policy.effect() == Effect.WRITE)
                          gate.requireAvailable(policy, principal);
                        else gate.authorize(policy, principal, args);
                        audit.append(
                            AuditEvent.create(
                                principal.identity(),
                                policy.name(),
                                null,
                                phase,
                                AuditEvent.Outcome.ALLOWED,
                                null));
                        Object value = action.call(context, args, principal);
                        CallToolResult result = result(value);
                        if (json.writeValueAsBytes(result).length > properties.getMaxResultBytes())
                          throw new BridgeException(
                              "RESULT_TOO_LARGE",
                              "Result exceeds the configured limit; use a file export");
                        String operation =
                            value instanceof OperationView view ? view.operationId() : null;
                        AuditEvent.Outcome outcome =
                            value instanceof OperationView view
                                ? outcome(view.state())
                                : Boolean.TRUE.equals(result.isError())
                                    ? AuditEvent.Outcome.FAILED
                                    : AuditEvent.Outcome.SUCCEEDED;
                        // Audit failure after execution does not remove or invalidate the durable
                        // operation receipt.
                        try {
                          audit.append(
                              AuditEvent.create(
                                  principal.identity(),
                                  policy.name(),
                                  operation,
                                  phase,
                                  outcome,
                                  null));
                        } catch (RuntimeException auditFailure) {
                          return CallToolResult.builder()
                              .addTextContent(
                                  "AUDIT_INCOMPLETE: inspect the durable operation status before any retry")
                              .structuredContent(
                                  Map.of(
                                      "outcome", json.valueToTree(value), "auditIncomplete", true))
                              .isError(false)
                              .build();
                        }
                        return result;
                      } catch (Exception failure) {
                        String code = code(failure);
                        try {
                          audit.append(
                              AuditEvent.create(
                                  principal.identity(),
                                  policy.name(),
                                  null,
                                  phase,
                                  code.equals("FORBIDDEN")
                                      ? AuditEvent.Outcome.DENIED
                                      : AuditEvent.Outcome.FAILED,
                                  code));
                        } catch (RuntimeException ignored) {
                        }
                        return error(code);
                      }
                    });
              } catch (Exception denied) {
                return error(code(denied));
              }
            }));
  }

  public List<SyncToolSpecification> specifications() {
    return List.copyOf(specifications);
  }

  public boolean visible(String name, BridgePrincipal principal) {
    BridgePolicyGate.Policy policy = published.get(name);
    return policy != null && gate.visible(policy, principal);
  }

  public Tool tool(String name, String description, ObjectNode input, boolean readOnly) {
    return Tool.builder()
        .name(name)
        .description(description)
        .inputSchema(json.convertValue(input, JsonSchema.class))
        .annotations(new ToolAnnotations(null, readOnly, !readOnly, readOnly, false, false))
        .build();
  }

  public Tool withOutput(Tool tool, ObjectNode schema) {
    ObjectNode output = schema;
    if (!"object".equals(schema.path("type").asText())) {
      output = object();
      ((ObjectNode) output.get("properties")).set("result", schema);
      output.putArray("required").add("result");
    }
    return Tool.builder()
        .name(tool.name())
        .title(tool.title())
        .description(tool.description())
        .inputSchema(tool.inputSchema())
        .annotations(tool.annotations())
        .outputSchema(map(output))
        .build();
  }

  private CallToolResult result(Object value) throws Exception {
    if (value instanceof CallToolResult result) return result;
    JsonNode node = json.valueToTree(value);
    Object structured = node.isObject() ? node : Map.of("result", node);
    String summary =
        value instanceof OperationView view
            ? "Operation "
                + view.operationId()
                + " is "
                + view.state()
                + ". Review the full structured preview before confirming."
            : "Result is available in structuredContent.";
    return CallToolResult.builder()
        .structuredContent(structured)
        .addTextContent(summary)
        .isError(false)
        .build();
  }

  private CallToolResult error(String code) {
    return CallToolResult.builder()
        .addTextContent(
            code + ": request could not be completed; inspect the operation status for writes")
        .structuredContent(Map.of("errorCode", code))
        .isError(true)
        .build();
  }

  private String code(Exception failure) {
    if (failure instanceof AccessDeniedException) return "FORBIDDEN";
    if (failure instanceof BridgeException bridge) return bridge.code();
    return "EXECUTION_FAILED";
  }

  private AuditEvent.Outcome outcome(OperationState state) {
    return switch (state) {
      case PREPARED -> AuditEvent.Outcome.PREPARED;
      case SUCCEEDED -> AuditEvent.Outcome.SUCCEEDED;
      case CANCELLED -> AuditEvent.Outcome.CANCELLED;
      case EXPIRED -> AuditEvent.Outcome.EXPIRED;
      case REJECTED -> AuditEvent.Outcome.REJECTED;
      default -> AuditEvent.Outcome.UNKNOWN;
    };
  }

  private ObjectNode object() {
    return schemas.object().put("additionalProperties", false);
  }

  private ObjectNode fields(Map<String, String> fields) {
    ObjectNode schema = object();
    ArrayNode required = schema.putArray("required");
    fields.forEach(
        (name, type) -> {
          ((ObjectNode) schema.get("properties"))
              .set(name, json.createObjectNode().put("type", type));
          required.add(name);
        });
    return schema;
  }

  private Map<String, Object> map(Object value) {
    if (value == null) return Map.of();
    JsonNode node = json.valueToTree(value);
    if (!node.isObject())
      throw new BridgeException("INVALID_ARGUMENT", "Arguments must be an object");
    return json.convertValue(
        node,
        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
  }

  private static String string(Map<String, Object> args, String name) {
    if (!(args.get(name) instanceof String value) || value.isBlank())
      throw new BridgeException("INVALID_ARGUMENT", name + " is required");
    return value;
  }

  private static long number(Map<String, Object> args, String name) {
    Object value = args.get(name);
    try {
      return new java.math.BigDecimal(String.valueOf(value)).longValueExact();
    } catch (RuntimeException invalid) {
      throw new BridgeException("INVALID_ARGUMENT", name + " must be an integer");
    }
  }
}
