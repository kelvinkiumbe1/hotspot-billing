package com.spalimited.hotspotbilling.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps validation-style exceptions to 400s with a JSON message the
 * frontend can show directly.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(RuntimeException e) {
        return Map.of("message", e.getMessage() == null ? "That request could not be completed" : e.getMessage());
    }

    /**
     * Bean-validation failures. Without this they fall through to Spring's
     * default body, which carries no `message` field — so every form in the
     * admin showed "Request failed (400)" instead of the reason, and the
     * constraint messages written on each field were never seen.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalid(MethodArgumentNotValidException e) {
        Map<String, String> byField = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            byField.putIfAbsent(error.getField(),
                    error.getDefaultMessage() == null ? "is not valid" : error.getDefaultMessage());
        }
        e.getBindingResult().getGlobalErrors().forEach(error ->
                byField.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        if (byField.isEmpty()) {
            return Map.of("message", "Some of those details are not valid");
        }

        // One readable sentence for the toast, plus the per-field detail so a
        // form can highlight the offending input if it wants to.
        String summary = byField.entrySet().stream()
                .map(entry -> describe(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining("; "));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", summary);
        out.put("fields", byField);
        return out;
    }

    /** Malformed JSON, or a value that will not fit the field it was sent for. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> unreadable(HttpMessageNotReadableException e) {
        String detail = e.getMostSpecificCause().getMessage();
        // Jackson names the offending enum and its valid values, which is
        // genuinely useful; the rest of its output is noise.
        if (detail != null && detail.contains("not one of the values accepted")) {
            int from = detail.indexOf("accepted for Enum class");
            return Map.of("message", "That is not a valid choice — " + detail.substring(from));
        }
        return Map.of("message", "That request could not be read. Check the values and try again.");
    }

    /**
     * Joins a field name to its message without the clumsy repetition of
     * naively prefixing everything ("Phone number phone must be…").
     *
     * <p>A message that already names the field stands alone. A message
     * written as a full sentence gets the field as a label. Only the
     * fragments Jakarta generates by default ("must not be blank") are
     * prefixed directly.
     */
    private static String describe(String field, String message) {
        String humanField = humanise(field);
        String lower = message.toLowerCase();
        for (String word : humanField.toLowerCase().split(" ")) {
            if (word.length() > 2 && lower.contains(word)) {
                return message;
            }
        }
        boolean isSentence = Character.isUpperCase(message.charAt(0));
        return isSentence ? humanField + ": " + message : humanField + " " + message;
    }

    /** "phoneNumber" reads as "Phone number" rather than as a field name. */
    private static String humanise(String field) {
        String spaced = field.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
