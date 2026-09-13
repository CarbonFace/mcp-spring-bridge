package com.cogistra.mcpbridge.sample;

import com.cogistra.mcpbridge.api.BridgeException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackageClasses = SampleRecordController.class)
public class SampleExceptionAdvice {
  @ExceptionHandler(BridgeException.class)
  public ResponseEntity<Map<String, String>> rejected(BridgeException exception) {
    int status =
        switch (exception.code()) {
          case "RECORD_NOT_FOUND" -> 404;
          case "VERSION_CONFLICT" -> 409;
          case "AUTHENTICATION_REQUIRED" -> 401;
          default -> 400;
        };
    return ResponseEntity.status(status)
        .body(Map.of("code", exception.code(), "message", exception.getMessage()));
  }
}
