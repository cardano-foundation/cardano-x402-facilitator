package org.cardanofoundation.x402.facilitator.controller.advice;

import lombok.extern.log4j.Log4j2;
import org.cardanofoundation.x402.facilitator.config.RequestSizeFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/**
 * Sanitized error surface: 500 bodies never leak internals — they carry a
 * correlation id only; the full detail goes to the log.
 */
@RestControllerAdvice
@Log4j2
public class ApiErrorAdvice {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> onUnreadable(HttpMessageNotReadableException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RequestSizeFilter.RequestTooLargeException) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                        .body(Map.of("error", "Request body too large"));
            }
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Malformed request body"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> onError(Exception e) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled error [{}]", correlationId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal_error", "correlationId", correlationId));
    }
}
