package com.bytesolutions.smartifier.controllers;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", exception.getReason() == null ? "Request failed." : exception.getReason()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> validation(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException validation
                && validation.getParameter().getContainingClass() == ContentStackController.class) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Enter a stack name (up to 100 characters) and 1–50 content items. Each item needs a type and text (up to 1,000 characters)."));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message",
                "Check the required fields. Passwords must use 12–64 characters with uppercase, lowercase, a number, and a special character."));
    }
}
