package com.codepilot1c.core.edt.validation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * In-memory short-lived tokens for validated metadata mutations.
 */
public class ValidationTokenStore {

    private static final long TTL_MS = 5L * 60L * 1000L;

    private static final ValidationTokenStore INSTANCE = new ValidationTokenStore();

    private final Gson gson = new Gson();
    private final ConcurrentHashMap<String, TokenEntry> tokens = new ConcurrentHashMap<>();

    public static ValidationTokenStore getInstance() {
        return INSTANCE;
    }

    public TokenIssue issueToken(
            ValidationOperation operation,
            String projectName,
            Map<String, Object> normalizedPayload
    ) {
        cleanupExpired();
        String payloadHash = payloadHash(normalizedPayload);
        long expiresAt = Instant.now().toEpochMilli() + TTL_MS;
        String token = UUID.randomUUID().toString();
        tokens.put(token, new TokenEntry(operation, normalizeProject(projectName), payloadHash, expiresAt));
        return new TokenIssue(token, expiresAt);
    }

    public void consumeToken(
            String token,
            ValidationOperation operation,
            String projectName,
            Map<String, Object> normalizedPayload
    ) {
        cleanupExpired();
        if (token == null || token.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "validation_token is required. Call edt_validate_request before mutation.", false); //$NON-NLS-1$
        }

        TokenEntry entry = tokens.get(token);
        if (entry == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_VALIDATION_TOKEN,
                    "Unknown validation_token. Request a new token via edt_validate_request.", true); //$NON-NLS-1$
        }

        long now = Instant.now().toEpochMilli();
        if (entry.expiresAtEpochMs < now) {
            tokens.remove(token);
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_VALIDATION_TOKEN,
                    "validation_token expired. Request a new token via edt_validate_request.", true); //$NON-NLS-1$
        }

        String expectedHash = payloadHash(normalizedPayload);
        String normalizedProject = normalizeProject(projectName);
        if (entry.operation != operation
                || !entry.projectName.equals(normalizedProject)
                || !entry.payloadHash.equals(expectedHash)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_VALIDATION_TOKEN,
                    "validation_token does not match current mutation payload.", false); //$NON-NLS-1$
        }

        // One-time token: prevent replay on destructive mutations.
        tokens.remove(token);
    }

    private String payloadHash(Map<String, Object> normalizedPayload) {
        String canonicalJson = gson.toJson(canonicalize(normalizedPayload));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] bytes = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value)); //$NON-NLS-1$
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e); //$NON-NLS-1$
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(e -> String.valueOf(e.getKey()), String.CASE_INSENSITIVE_ORDER));
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : entries) {
                String key = String.valueOf(entry.getKey());
                result.put(key, canonicalize(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(canonicalize(item));
            }
            return result;
        }
        return value;
    }

    private void cleanupExpired() {
        long now = Instant.now().toEpochMilli();
        for (Map.Entry<String, TokenEntry> entry : tokens.entrySet()) {
            if (entry.getValue().expiresAtEpochMs < now) {
                tokens.remove(entry.getKey());
            }
        }
    }

    private String normalizeProject(String projectName) {
        return projectName == null ? "" : projectName.toLowerCase(Locale.ROOT).trim(); //$NON-NLS-1$
    }

    public record TokenIssue(String token, long expiresAtEpochMs) {
    }

    private record TokenEntry(
            ValidationOperation operation,
            String projectName,
            String payloadHash,
            long expiresAtEpochMs
    ) {
    }
}
