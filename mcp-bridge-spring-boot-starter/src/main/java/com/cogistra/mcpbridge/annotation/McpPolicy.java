package com.cogistra.mcpbridge.annotation;

import java.lang.annotation.*;

/** Optional MCP-specific metadata; Spring method security remains the business authority. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpPolicy {
  Effect effect() default Effect.WRITE;

  String[] scopes() default {};

  boolean allowAuthenticated() default false;
}
