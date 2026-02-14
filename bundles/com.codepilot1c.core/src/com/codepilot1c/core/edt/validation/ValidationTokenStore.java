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
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * In-memory short-lived tokens for validated metadata mutations.
 */
public class ValidationTokenStore {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ValidationTokenStore.class);

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
        String opId = LogSanitizer.newId("issue-token"); //$NON-NLS-1$
        cleanupExpired();
        String payloadHash = payloadHash(normalizedPayload);
        @SuppressWarnings("unchecked")
        Map<String, Object> storedPayload = (Map<String, Object>) canonicalize(normalizedPayload);
        long expiresAt = Instant.now().toEpochMilli() + TTL_MS;
        String token = UUID.randomUUID().toString();
        tokens.put(token, new TokenEntry(operation, normalizeProject(projectName), payloadHash, storedPayload, expiresAt));
        LOG.debug("[%s] Issued token=%s operation=%s project=%s payloadHash=%s expiresAt=%d activeTokens=%d", // $NON-NLS-1$
                opId,
                LogSanitizer.truncate(token, 80),
                operation,
                projectName,
                LogSanitizer.truncate(payloadHash, 24),
                expiresAt,
                tokens.size());
        return new TokenIssue(token, expiresAt);
    }

    public Map<String, Object> consumeToken(
            String token,
            ValidationOperation operation,
            String projectName
    ) {
        String opId = LogSanitizer.newId("consume-token"); //$NON-NLS-1$
        cleanupExpired();
        if (token == null || token.isBlank()) {
            LOG.warn("[%s] Missing validation token for operation=%s project=%s", opId, operation, projectName); //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "validation_token is required. Call edt_validate_request before mutation.", false); //$NON-NLS-1$
        }

        TokenEntry entry = tokens.get(token);
        if (entry == null) {
            LOG.warn("[%s] Unknown token=%s operation=%s project=%s activeTokens=%d", // $NON-NLS-1$
                    opId, LogSanitizer.truncate(token, 80), operation, projectName, tokens.size());
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_VALIDATION_TOKEN,
                    "Unknown validation_token. Request a new token via edt_validate_request.", true); //$NON-NLS-1$
        }

        long now = Instant.now().toEpochMilli();
        if (entry.expiresAtEpochMs < now) {
            tokens.remove(token);
            LOG.warn("[%s] Expired token=%s operation=%s project=%s", // $NON-NLS-1$
                    opId, LogSanitizer.truncate(token, 80), operation, projectName);
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_VALIDATION_TOKEN,
                    "validation_token expired. Request a new token via edt_validate_request.", true); //$NON-NLS-1$
        }

        String normalizedProject = normalizeProject(projectName);
        if (entry.operation != operation
                || !entry.projectName.equals(normalizedProject)) {
            LOG.warn("[%s] Token mismatch token=%s expected(operation=%s,project=%s,hash=%s) actual(operation=%s,project=%s)", // $NON-NLS-1$
                    opId,
                    LogSanitizer.truncate(token, 80),
                    entry.operation,
                    entry.projectName,
                    LogSanitizer.truncate(entry.payloadHash, 24),
                    operation,
                    normalizedProject);
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_VALIDATION_TOKEN,
                    "validation_token does not match current operation/project.", false); //$NON-NLS-1$
        }

        // One-time token: prevent replay on destructive mutations.
        tokens.remove(token);
        LOG.debug("[%s] Token consumed token=%s operation=%s project=%s activeTokens=%d", // $NON-NLS-1$
                opId, LogSanitizer.truncate(token, 80), operation, projectName, tokens.size());
        return entry.normalizedPayload;
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
            Map<String, Object> normalizedPayload,
            long expiresAtEpochMs
    ) {
    }
}
