package com.cogistra.mcpbridge.binding;

import com.cogistra.mcpbridge.annotation.McpEndpoint;
import com.cogistra.mcpbridge.api.*;
import com.cogistra.mcpbridge.files.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.*;
import jakarta.validation.Validator;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;

public final class EndpointPlan {
  private final Object bean;
  private final Method method, invocable;
  private final McpEndpoint declaration;
  private final ObjectMapper json;
  private final BridgeSchemas schemas;
  private final Validator validator;
  private final BridgeFileStore files;
  private final List<BridgeContractAdapter> adapters;
  private final String verb, path;
  private final long maxExportBytes;
  private final boolean flatBody;
  private final ObjectNode inputSchema;
  private final ConversionService conversions = DefaultConversionService.getSharedInstance();

  public EndpointPlan(
      HandlerMethod handler,
      McpEndpoint declaration,
      String verb,
      String path,
      ObjectMapper json,
      BridgeSchemas schemas,
      Validator validator,
      BridgeFileStore files,
      List<BridgeContractAdapter> adapters,
      long maxExportBytes) {
    this.bean = handler.getBean();
    this.method = handler.getMethod();
    this.invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
    this.declaration = declaration;
    this.verb = verb;
    this.path = path;
    this.json = json;
    this.schemas = schemas;
    this.validator = validator;
    this.files = files;
    this.adapters = adapters;
    this.maxExportBytes = maxExportBytes;
    long business =
        Arrays.stream(method.getParameters()).filter(p -> !injected(p.getType())).count();
    flatBody =
        business == 1
            && Arrays.stream(method.getParameters())
                .anyMatch(p -> p.isAnnotationPresent(RequestBody.class));
    validateSupportedSignature();
    inputSchema = buildSchema();
  }

  public ObjectNode inputSchema() {
    return inputSchema.deepCopy();
  }

  public Object bean() {
    return bean;
  }

  public Method method() {
    return method;
  }

  public Object[] permissionArguments(Map<String, Object> arguments, BridgePrincipal principal) {
    JsonNode input = json.valueToTree(arguments);
    for (BridgeContractAdapter adapter : adapters)
      if (adapter.supports(method)) input = adapter.mapInput(method, input, principal.identity());
    return bind(input, null, null, principal.authentication(), principal.identity());
  }

  public Object[] canonicalPermissionArguments(
      Map<String, Object> arguments, BridgePrincipal principal) {
    return bind(
        json.valueToTree(arguments), null, null, principal.authentication(), principal.identity());
  }

  private void validateSupportedSignature() {
    if (org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
        method, com.fasterxml.jackson.annotation.JsonView.class))
      throw new IllegalArgumentException(
          "HTTP JsonView requires an explicit MCP projection facade: " + method);
    Class<?> result = method.getReturnType();
    if (java.util.concurrent.Future.class.isAssignableFrom(result)
        || java.util.concurrent.CompletionStage.class.isAssignableFrom(result)
        || java.util.concurrent.Callable.class.isAssignableFrom(result)
        || org.reactivestreams.Publisher.class.isAssignableFrom(result)
        || org.springframework.web.context.request.async.DeferredResult.class.isAssignableFrom(
            result)
        || org.springframework.web.context.request.async.WebAsyncTask.class.isAssignableFrom(result)
        || org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody.class
            .isAssignableFrom(result)
        || org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class
            .isAssignableFrom(result))
      throw new IllegalArgumentException(
          "Asynchronous HTTP endpoints require an explicit synchronous MCP facade: " + method);
    if (!Modifier.isPublic(method.getModifiers())
        || Modifier.isStatic(method.getModifiers())
        || (org.springframework.aop.support.AopUtils.isAopProxy(bean)
            && Modifier.isFinal(method.getModifiers())))
      throw new IllegalArgumentException(
          "An MCP endpoint must be an interceptable public instance method: " + method);
    for (Parameter parameter : method.getParameters()) {
      if (org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
              parameter, org.springframework.security.core.annotation.AuthenticationPrincipal.class)
          || org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
              parameter, org.springframework.security.core.annotation.CurrentSecurityContext.class)
          || org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
              parameter, com.fasterxml.jackson.annotation.JsonView.class))
        throw new IllegalArgumentException(
            "Trusted/view parameter annotations cannot become model input: " + method);
      for (var annotation : parameter.getAnnotations()) {
        String type = annotation.annotationType().getName();
        if (type.startsWith("org.springframework.security.")
            || type.equals(RequestHeader.class.getName())
            || type.equals(CookieValue.class.getName())
            || type.equals(RequestAttribute.class.getName())
            || type.equals(SessionAttribute.class.getName())
            || type.equals(MatrixVariable.class.getName()))
          throw new IllegalArgumentException(
              "Trusted or unsupported MVC parameter injection cannot become model input: "
                  + method
                  + " parameter "
                  + parameter.getName());
        boolean supported =
            Set.of(
                        RequestBody.class,
                        RequestParam.class,
                        PathVariable.class,
                        RequestPart.class,
                        ModelAttribute.class,
                        jakarta.validation.Valid.class,
                        org.springframework.validation.annotation.Validated.class)
                    .contains(annotation.annotationType())
                || annotation
                    .annotationType()
                    .isAnnotationPresent(jakarta.validation.Constraint.class)
                || type.startsWith("jakarta.validation.")
                || type.startsWith("com.fasterxml.jackson.annotation.")
                || type.startsWith("io.swagger.v3.oas.annotations.")
                || type.startsWith("org.jspecify.annotations.")
                || type.startsWith("org.jetbrains.annotations.")
                || type.equals("org.springframework.lang.Nullable")
                || type.equals("javax.annotation.Nullable")
                || type.equals("javax.annotation.Nonnull");
        if (!supported)
          throw new IllegalArgumentException(
              "Unknown parameter annotation needs an explicit compatible MCP facade: "
                  + method
                  + " parameter "
                  + parameter.getName());
      }
      Class<?> type = parameter.getType();
      if (!injected(type)
          && (type.getName().startsWith("jakarta.servlet.")
              || org.springframework.validation.Errors.class.isAssignableFrom(type)
              || org.springframework.ui.Model.class.isAssignableFrom(type)
              || org.springframework.http.HttpEntity.class.isAssignableFrom(type)
              || type == java.util.Locale.class))
        throw new IllegalArgumentException(
            "Unsupported MVC argument requires an explicit contract facade: "
                + method
                + " parameter "
                + parameter.getName());
    }
  }

  private boolean injected(Class<?> type) {
    return type == HttpServletRequest.class
        || type == HttpServletResponse.class
        || Authentication.class.isAssignableFrom(type)
        || type == java.security.Principal.class;
  }

  private String name(Parameter p) {
    RequestParam query = p.getAnnotation(RequestParam.class);
    if (query != null) {
      if (!query.name().isBlank()) return query.name();
      if (!query.value().isBlank()) return query.value();
    }
    PathVariable route = p.getAnnotation(PathVariable.class);
    if (route != null) {
      if (!route.name().isBlank()) return route.name();
      if (!route.value().isBlank()) return route.value();
    }
    RequestPart part = p.getAnnotation(RequestPart.class);
    if (part != null) {
      if (!part.name().isBlank()) return part.name();
      if (!part.value().isBlank()) return part.value();
    }
    if (!p.isNamePresent())
      throw new IllegalArgumentException(
          "Compile endpoint parameters with -parameters or provide explicit parameter names: "
              + method);
    return p.getName();
  }

  private ObjectNode buildSchema() {
    ObjectNode override = null;
    for (BridgeContractAdapter adapter : adapters) {
      if (!adapter.supports(method)) continue;
      JsonNode candidate = adapter.inputSchema(method);
      if (candidate == null) continue;
      if (override != null || !(candidate instanceof ObjectNode))
        throw new IllegalArgumentException(
            "One object input schema adapter is required: " + method);
      override = requireObject(((ObjectNode) candidate).deepCopy());
    }
    if (override != null) return override;
    if (declaration.input() != Void.class)
      return requireObject(schemas.schema(declaration.input()));
    if (flatBody)
      return requireObject(
          schemas.schema(
              Arrays.stream(method.getParameters())
                  .filter(p -> p.isAnnotationPresent(RequestBody.class))
                  .findFirst()
                  .orElseThrow()
                  .getParameterizedType()));
    ObjectNode result = schemas.object();
    result.put("additionalProperties", false);
    ArrayNode required = result.putArray("required");
    for (Parameter p : method.getParameters()) {
      if (injected(p.getType())) continue;
      String key = name(p);
      ObjectNode part;
      if (fileParameter(p)) {
        part = schemas.object();
        ((ObjectNode) part.get("properties"))
            .set("fileId", json.createObjectNode().put("type", "string"));
        part.putArray("required").add("fileId");
        part.put("additionalProperties", false);
        if (p.getType() != MultipartFile.class)
          part = json.createObjectNode().put("type", "array").set("items", part);
      } else part = schemas.schema(p.getParameterizedType());
      ((ObjectNode) result.get("properties")).set(key, part);
      RequestParam query = p.getAnnotation(RequestParam.class);
      RequestBody body = p.getAnnotation(RequestBody.class);
      RequestPart requestPart = p.getAnnotation(RequestPart.class);
      if (p.isAnnotationPresent(PathVariable.class)
          || p.getType().isPrimitive()
          || (body != null && body.required())
          || (query != null
              && query.required()
              && ValueConstants.DEFAULT_NONE.equals(query.defaultValue()))
          || (requestPart != null && requestPart.required())
          || (fileParameter(p) && query == null && requestPart == null)) required.add(key);
    }
    return result;
  }

  private ObjectNode requireObject(ObjectNode schema) {
    if (!"object".equals(schema.path("type").asText()))
      throw new IllegalArgumentException("An MCP input contract must be an object: " + method);
    return schema;
  }

  public JsonNode validate(Map<String, Object> arguments, BridgeIdentity identity) {
    JsonNode input = json.valueToTree(arguments);
    schemas.validate(inputSchema, input);
    if (declaration.input() != Void.class)
      validateBean(json.convertValue(input, declaration.input()));
    for (BridgeContractAdapter adapter : adapters)
      if (adapter.supports(method)) input = adapter.mapInput(method, input, identity);
    if (input == null || !input.isObject())
      throw new BridgeException("INVALID_ARGUMENT", "Mapped input must be an object");
    // Bind/validate without invoking the business action during preparation.
    bind(input, null, null, null, identity);
    return input;
  }

  public Object invoke(Map<String, Object> arguments, BridgePrincipal principal) throws Exception {
    JsonNode input = validate(arguments, principal.identity());
    return invokeCanonical(input, principal);
  }

  /**
   * Executes the already confirmed binding snapshot without re-resolving names or dynamic defaults.
   */
  public Object invokeCanonical(Map<String, Object> arguments, BridgePrincipal principal)
      throws Exception {
    JsonNode input = json.valueToTree(arguments);
    return invokeCanonical(input, principal);
  }

  private Object invokeCanonical(JsonNode input, BridgePrincipal principal) throws Exception {
    Map<String, String[]> params = new LinkedHashMap<>();
    input
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getValue().isValueNode())
                params.put(entry.getKey(), new String[] {entry.getValue().asText()});
            });
    String expanded = path;
    for (Parameter p : method.getParameters())
      if (p.isAnnotationPresent(PathVariable.class) && input.has(name(p)))
        expanded = expanded.replace("{" + name(p) + "}", input.get(name(p)).asText());
    CapturingFileResponse response = new CapturingFileResponse(maxExportBytes);
    HttpServletRequest request =
        BridgeServletContext.request(
            verb, expanded, params, json.writeValueAsBytes(input), principal.authentication());
    RequestAttributes previous = RequestContextHolder.getRequestAttributes();
    ServletRequestAttributes current = new ServletRequestAttributes(request, response);
    RequestContextHolder.setRequestAttributes(current);
    try {
      Object[] values =
          bind(input, request, response, principal.authentication(), principal.identity());
      Set<?> violations =
          validator.forExecutables().validateParameters(bean, method, values, validationGroups());
      if (!violations.isEmpty())
        throw new BridgeException("INVALID_ARGUMENT", "Endpoint parameter validation failed");
      Object value;
      try {
        value = invocable.invoke(bean, values);
      } catch (InvocationTargetException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof Exception exception) throw exception;
        throw failure;
      }
      if (response.getStatus() >= 400)
        throw new BridgeException("BUSINESS_ERROR", "The endpoint rejected this request");
      if (value instanceof ResponseEntity<?> entity) {
        if (entity.getStatusCode().isError())
          throw new BridgeException("BUSINESS_ERROR", "The endpoint rejected this request");
        if (entity.getBody() instanceof byte[])
          value =
              FileAdapters.fromResponseEntity(
                  files, principal.identity(), (ResponseEntity<byte[]>) entity);
        else value = entity.getBody();
      }
      if (value instanceof byte[] bytes)
        value =
            files.store(
                principal.identity(),
                "export.bin",
                "application/octet-stream",
                new ByteArrayInputStream(bytes));
      if (value instanceof Resource resource) {
        try (InputStream in = resource.getInputStream()) {
          value =
              files.store(
                  principal.identity(),
                  Optional.ofNullable(resource.getFilename()).orElse("export.bin"),
                  "application/octet-stream",
                  in);
        }
      }
      if (method.getReturnType() == void.class
          && Arrays.stream(method.getParameterTypes())
              .anyMatch(HttpServletResponse.class::isAssignableFrom))
        value = response.finish(files, principal.identity(), "export.bin");
      for (BridgeContractAdapter adapter : adapters)
        if (adapter.supports(method))
          value = adapter.mapOutput(method, value, principal.identity());
      if (declaration.output() != Void.class) {
        ObjectMapper projection =
            json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        value = projection.convertValue(value, declaration.output());
        schemas.validate(schemas.schema(declaration.output()), json.valueToTree(value));
      }
      if (value instanceof FileArtifact && declaration.output() == Void.class) return value;
      return json.valueToTree(value);
    } finally {
      try {
        current.requestCompleted();
      } finally {
        if (previous == null) RequestContextHolder.resetRequestAttributes();
        else RequestContextHolder.setRequestAttributes(previous);
      }
    }
  }

  private Object[] bind(
      JsonNode input,
      HttpServletRequest request,
      HttpServletResponse response,
      Authentication auth,
      BridgeIdentity identity) {
    Object[] values = new Object[method.getParameterCount()];
    Parameter[] parameters = method.getParameters();
    for (int i = 0; i < parameters.length; i++) {
      Parameter p = parameters[i];
      Class<?> type = p.getType();
      if (type == HttpServletRequest.class) {
        values[i] = request;
        continue;
      }
      if (type == HttpServletResponse.class) {
        values[i] = response;
        continue;
      }
      if (Authentication.class.isAssignableFrom(type) || type == java.security.Principal.class) {
        values[i] = auth;
        continue;
      }
      JsonNode node =
          flatBody && p.isAnnotationPresent(RequestBody.class) ? input : input.get(name(p));
      if (fileParameter(p)) {
        boolean optional =
            (p.isAnnotationPresent(RequestParam.class)
                    && !p.getAnnotation(RequestParam.class).required())
                || (p.isAnnotationPresent(RequestPart.class)
                    && !p.getAnnotation(RequestPart.class).required());
        if ((node == null || node.isNull()) && optional) {
          values[i] = null;
          continue;
        }
        if (type == MultipartFile.class) values[i] = multipart(node, identity);
        else {
          if (node == null || !node.isArray())
            throw new BridgeException(
                "INVALID_ARGUMENT", "A list of uploaded file references is required");
          List<MultipartFile> uploaded = new ArrayList<>();
          for (JsonNode element : node) uploaded.add(multipart(element, identity));
          values[i] = type.isArray() ? uploaded.toArray(MultipartFile[]::new) : uploaded;
        }
        continue;
      }
      RequestParam query = p.getAnnotation(RequestParam.class);
      if (node == null
          && query != null
          && !ValueConstants.DEFAULT_NONE.equals(query.defaultValue()))
        values[i] = conversions.convert(query.defaultValue(), type);
      else {
        try {
          values[i] =
              node == null
                  ? null
                  : json.convertValue(node, json.constructType(p.getParameterizedType()));
        } catch (IllegalArgumentException invalid) {
          throw new BridgeException(
              "INVALID_ARGUMENT", "Cannot bind endpoint parameter " + name(p));
        }
      }
      if (values[i] == null
          && (type.isPrimitive()
              || p.isAnnotationPresent(PathVariable.class)
              || (query != null
                  && query.required()
                  && ValueConstants.DEFAULT_NONE.equals(query.defaultValue()))
              || (p.isAnnotationPresent(RequestBody.class)
                  && p.getAnnotation(RequestBody.class).required())
              || (p.isAnnotationPresent(RequestPart.class)
                  && p.getAnnotation(RequestPart.class).required())))
        throw new BridgeException(
            "INVALID_ARGUMENT", "Missing required endpoint parameter " + name(p));
      if (values[i] != null) {
        var group = p.getAnnotation(org.springframework.validation.annotation.Validated.class);
        validateBean(values[i], group == null ? validationGroups() : group.value());
      }
    }
    return values;
  }

  private Class<?>[] validationGroups() {
    var group =
        org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
            method, org.springframework.validation.annotation.Validated.class);
    if (group == null)
      group =
          org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
              method.getDeclaringClass(),
              org.springframework.validation.annotation.Validated.class);
    return group == null ? new Class<?>[0] : group.value();
  }

  private void validateBean(Object bean, Class<?>... groups) {
    if (bean != null && !validator.validate(bean, groups).isEmpty())
      throw new BridgeException("INVALID_ARGUMENT", "Endpoint input validation failed");
  }

  private boolean fileParameter(Parameter parameter) {
    Class<?> type = parameter.getType();
    return type == MultipartFile.class
        || (type.isArray() && type.componentType() == MultipartFile.class)
        || (List.class.isAssignableFrom(type)
            && json.constructType(parameter.getParameterizedType()).getContentType() != null
            && json.constructType(parameter.getParameterizedType()).getContentType().getRawClass()
                == MultipartFile.class);
  }

  private MultipartFile multipart(JsonNode node, BridgeIdentity identity) {
    if (node == null || !node.hasNonNull("fileId"))
      throw new BridgeException("INVALID_ARGUMENT", "A previously uploaded fileId is required");
    return FileAdapters.multipart(files, identity, node.get("fileId").asText());
  }
}
