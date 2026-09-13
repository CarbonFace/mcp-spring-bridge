package com.cogistra.mcpbridge.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.Map;
import java.util.Objects;

/** Validates SDK structured results without returning domain values in validation errors. */
public final class BridgeSdkSchemaValidator implements JsonSchemaValidator {
  private static final String INVALID =
      "Structured content does not match the declared tool output schema";
  private final ObjectMapper json;
  private final SchemaRegistry schemas = SchemaRegistry.withDialect(Dialects.getDraft202012());

  public BridgeSdkSchemaValidator(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public ValidationResponse validate(Map<String, Object> schema, Object structuredContent) {
    if (schema == null || structuredContent == null) {
      return ValidationResponse.asInvalid(INVALID);
    }
    try {
      // Match the SDK contract: a String is JSON text, while other values are objects.
      JsonNode content =
          structuredContent instanceof String text
              ? json.readTree(text)
              : json.valueToTree(structuredContent);
      if (content == null
          || !schemas.getSchema(json.valueToTree(schema)).validate(content).isEmpty()) {
        return ValidationResponse.asInvalid(INVALID);
      }
      return ValidationResponse.asValid(json.writeValueAsString(content));
    } catch (Exception invalid) {
      // The SDK places this message in a client-visible CallToolResult. Do not copy
      // validator messages, exception details or rejected business values into it.
      return ValidationResponse.asInvalid(INVALID);
    }
  }
}
