package com.cogistra.mcpbridge.boot;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Separate holder avoids replacing the host application's ObjectMapper bean. */
public record BridgeJson(ObjectMapper mapper, ObjectMapper protocolMapper) {
  public BridgeJson(ObjectMapper mapper) {
    this(
        mapper,
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
  }
}
