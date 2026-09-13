package com.cogistra.mcpbridge.binding;

import com.cogistra.mcpbridge.api.BridgeException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import jakarta.validation.constraints.*;
import java.lang.reflect.Type;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/** Uses the bridge's actual Jackson property model, not Java field spelling. */
public final class BridgeSchemas {
  private final ObjectMapper json;
  private final SchemaRegistry validator = SchemaRegistry.withDialect(Dialects.getDraft202012());

  public BridgeSchemas(ObjectMapper json) {
    this.json = json;
  }

  public ObjectNode object() {
    return json.createObjectNode().put("type", "object").set("properties", json.createObjectNode());
  }

  public ObjectNode schema(Type type) {
    return schema(json.constructType(type), new HashSet<>());
  }

  private ObjectNode schema(JavaType type, Set<JavaType> visiting) {
    ObjectNode result = json.createObjectNode();
    Class<?> raw = type.getRawClass();
    if (raw == Void.class || raw == void.class) return result.put("type", "null");
    if (raw == String.class
        || raw == Character.class
        || raw == char.class
        || raw == UUID.class
        || raw.isEnum()
        || Date.class.isAssignableFrom(raw)
        || TemporalAccessor.class.isAssignableFrom(raw)) {
      result.put("type", "string");
      if (raw.isEnum()) {
        ArrayNode enums = result.putArray("enum");
        for (Object value : raw.getEnumConstants()) enums.add(((Enum<?>) value).name());
      }
      return result;
    }
    if (raw == boolean.class || raw == Boolean.class) return result.put("type", "boolean");
    if (raw == byte.class
        || raw == short.class
        || raw == int.class
        || raw == long.class
        || raw == Byte.class
        || raw == Short.class
        || raw == Integer.class
        || raw == Long.class
        || raw == java.math.BigInteger.class) return result.put("type", "integer");
    if (raw == float.class || raw == double.class || Number.class.isAssignableFrom(raw))
      return result.put("type", "number");
    if (raw == Object.class || JsonNode.class.isAssignableFrom(raw)) return result;
    if (type.isArrayType() || type.isCollectionLikeType()) {
      result.put("type", "array");
      return result.set(
          "items", nullable(schema(type.getContentType(), visiting), type.getContentType()));
    }
    if (type.isMapLikeType()) {
      result.put("type", "object");
      return result.set(
          "additionalProperties",
          nullable(schema(type.getContentType(), visiting), type.getContentType()));
    }
    if (!visiting.add(type))
      throw new IllegalArgumentException(
          "Recursive DTO needs an explicit contract adapter: " + type);
    result = object();
    result.put("additionalProperties", false);
    ArrayNode required = result.putArray("required");
    for (var property : json.getDeserializationConfig().introspect(type).findProperties()) {
      var member = property.getPrimaryMember();
      if (member == null || !property.couldDeserialize()) continue;
      ObjectNode field = schema(property.getPrimaryType(), visiting);
      JsonProperty jp = member.getAnnotation(JsonProperty.class);
      boolean mandatory =
          property.getPrimaryType().isPrimitive()
              || member.hasAnnotation(NotNull.class)
              || member.hasAnnotation(NotBlank.class)
              || member.hasAnnotation(NotEmpty.class)
              || (jp != null && jp.required());
      if (mandatory) required.add(property.getName());
      else {
        ObjectNode nullable = json.createObjectNode();
        nullable.putArray("anyOf").add(field).add(json.createObjectNode().put("type", "null"));
        field = nullable;
      }
      ((ObjectNode) result.get("properties")).set(property.getName(), field);
    }
    visiting.remove(type);
    return result;
  }

  public void validate(JsonNode schema, JsonNode value) {
    if (!validator.getSchema(schema).validate(value).isEmpty())
      throw new BridgeException(
          "INVALID_ARGUMENT", "Input does not match the declared tool contract");
  }

  private ObjectNode nullable(ObjectNode schema, JavaType type) {
    if (type.isPrimitive()) return schema;
    ObjectNode nullable = json.createObjectNode();
    nullable.putArray("anyOf").add(schema).add(json.createObjectNode().put("type", "null"));
    return nullable;
  }
}
