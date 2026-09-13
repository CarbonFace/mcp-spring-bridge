package com.cogistra.mcpbridge.annotation;

import java.lang.annotation.*;

/** Explicitly exposes one registered Spring MVC handler as a governed MCP capability. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface McpEndpoint {
  String name();

  String description();

  Class<?> input() default Void.class;

  Class<?> output() default Void.class;

  Effect effect() default Effect.WRITE;

  /** Requires a host transactional confirmation runtime instead of the default file workflow. */
  boolean confirmationRuntime() default false;

  String[] scopes() default {};

  boolean allowAuthenticated() default false;
}
