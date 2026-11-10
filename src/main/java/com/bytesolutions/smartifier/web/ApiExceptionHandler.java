package com.bytesolutions.smartifier.web;

import com.bytesolutions.smartifier.content.controllers.ContentStackController;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Map<String, String>> conflict(OptimisticLockingFailureException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "This stack has changed. Reload before saving."));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> unavailable(DataAccessException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "The database is temporarily unavailable. Please try again."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", exception.getReason() == null ? "Request failed." : exception.getReason()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> validation(Exception exception, HttpServletRequest request) {
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/admin/reviews")) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Choose approve or deny, include the current revision, and keep the review note within 500 characters."));
        }
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/admin/")) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Check the required fields and length limits. Content needs a type and body. Reload if the stack revision is missing."));
        }
        if (exception instanceof MethodArgumentNotValidException validation
                && validation.getParameter().getContainingClass() == ContentStackController.class) {
            var metadataErrors = validation.getBindingResult().getFieldErrors().stream()
                    .filter(error -> error.getField().equals("name") || error.getField().equals("description"))
                    .map(error -> error.getField().equals("name")
                            ? "Stack name is required and must be 100 characters or fewer."
                            : "Description is required and must be 500 characters or fewer.")
                    .distinct().toList();
            if (!metadataErrors.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Could not save your stack. " + String.join(" ", metadataErrors)));
            }
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Enter a stack name (up to 100 characters) and 1–50 content items. Each item needs a type and text (up to 1,000 characters)."));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message",
                "Check the required fields. Passwords must use 12–64 characters with uppercase, lowercase, a number, and a special character."));
    }
}
