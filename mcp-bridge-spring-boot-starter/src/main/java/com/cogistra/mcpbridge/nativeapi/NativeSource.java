package com.cogistra.mcpbridge.nativeapi;

import java.lang.reflect.Method;

/** Actual Spring declaration bean is retained. No target object is unwrapped for invocation. */
public record NativeSource(
    String beanName, Object bean, Class<?> targetClass, Method method, Origin origin) {
  public enum Origin {
    ANNOTATION,
    TOOL_CALLBACK,
    TOOL_CALLBACK_PROVIDER
  }
}
