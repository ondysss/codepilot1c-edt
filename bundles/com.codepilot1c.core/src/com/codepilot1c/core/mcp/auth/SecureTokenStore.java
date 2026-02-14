/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.auth;

import java.time.Instant;
import java.util.Optional;

import com.codepilot1c.core.settings.SecureStorageUtil;

/**
 * Secure storage for OAuth tokens.
 */
public class SecureTokenStore {

    private static final String PREFIX = "mcp.oauth."; //$NON-NLS-1$
    private static final String ACCESS_TOKEN = ".accessToken"; //$NON-NLS-1$
    private static final String REFRESH_TOKEN = ".refreshToken"; //$NON-NLS-1$
    private static final String EXPIRES_AT = ".expiresAt"; //$NON-NLS-1$
    private static final String TOKEN_TYPE = ".tokenType"; //$NON-NLS-1$

    public Optional<OAuthToken> read(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return Optional.empty();
        }
        String access = SecureStorageUtil.retrieveSecurely(PREFIX + profileId + ACCESS_TOKEN, ""); //$NON-NLS-1$
        if (access.isBlank()) {
            return Optional.empty();
        }
        String refresh = SecureStorageUtil.retrieveSecurely(PREFIX + profileId + REFRESH_TOKEN, ""); //$NON-NLS-1$
        String expiresAtRaw = SecureStorageUtil.retrieveSecurely(PREFIX + profileId + EXPIRES_AT, "0"); //$NON-NLS-1$
        String tokenType = SecureStorageUtil.retrieveSecurely(PREFIX + profileId + TOKEN_TYPE, "Bearer"); //$NON-NLS-1$ //$NON-NLS-2$
        long expiresAt = 0L;
        try {
            expiresAt = Long.parseLong(expiresAtRaw);
        } catch (NumberFormatException e) {
            expiresAt = 0L;
        }
        return Optional.of(new OAuthToken(access, refresh, tokenType, expiresAt));
    }

    public void save(String profileId, OAuthToken token) {
        if (profileId == null || profileId.isBlank() || token == null) {
            return;
        }
        SecureStorageUtil.storeSecurely(PREFIX + profileId + ACCESS_TOKEN, token.accessToken());
        SecureStorageUtil.storeSecurely(PREFIX + profileId + REFRESH_TOKEN, token.refreshToken() != null ? token.refreshToken() : ""); //$NON-NLS-1$
        SecureStorageUtil.storeSecurely(PREFIX + profileId + TOKEN_TYPE, token.tokenType() != null ? token.tokenType() : "Bearer"); //$NON-NLS-1$
        SecureStorageUtil.storeSecurely(PREFIX + profileId + EXPIRES_AT, Long.toString(token.expiresAtEpochSeconds()));
    }

    public void clear(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        SecureStorageUtil.removeSecurely(PREFIX + profileId + ACCESS_TOKEN);
        SecureStorageUtil.removeSecurely(PREFIX + profileId + REFRESH_TOKEN);
        SecureStorageUtil.removeSecurely(PREFIX + profileId + TOKEN_TYPE);
        SecureStorageUtil.removeSecurely(PREFIX + profileId + EXPIRES_AT);
    }

    /**
     * Stored OAuth token model.
     */
    public record OAuthToken(String accessToken, String refreshToken, String tokenType, long expiresAtEpochSeconds) {
        public boolean isExpired() {
            return expiresAtEpochSeconds > 0 && Instant.now().getEpochSecond() >= expiresAtEpochSeconds;
        }

        public boolean willExpireSoon(long seconds) {
            if (expiresAtEpochSeconds <= 0) {
                return false;
            }
            return Instant.now().getEpochSecond() + seconds >= expiresAtEpochSeconds;
        }
    }
}
