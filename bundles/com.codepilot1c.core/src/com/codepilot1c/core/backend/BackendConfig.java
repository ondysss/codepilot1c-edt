/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.backend;

/**
 * Configuration constants for the CodePilot backend APIs.
 */
public final class BackendConfig {

    public static final String BASE_URL = System.getProperty(
            "vibe.backend.url", "https://api.codepilot1c.ru"); //$NON-NLS-1$ //$NON-NLS-2$

    public static final String AUTH_BASE_URL = System.getProperty(
            "vibe.auth.url", "https://codepilot1c.ru"); //$NON-NLS-1$ //$NON-NLS-2$

    public static final String API_VERSION = "/api/v1"; //$NON-NLS-1$

    public static final String REGISTER_ENDPOINT = API_VERSION + "/register"; //$NON-NLS-1$
    public static final String USAGE_ENDPOINT = API_VERSION + "/usage"; //$NON-NLS-1$
    public static final String ROTATE_KEY_ENDPOINT = API_VERSION + "/rotate-key"; //$NON-NLS-1$

    public static final String PLUGIN_SIGNUP_START_ENDPOINT = "/api/plugin/auth/signup/start"; //$NON-NLS-1$
    public static final String PLUGIN_SIGNUP_CONFIRM_ENDPOINT = "/api/plugin/auth/signup/confirm"; //$NON-NLS-1$
    public static final String PLUGIN_LOGIN_ENDPOINT = "/api/plugin/auth/login"; //$NON-NLS-1$

    public static final String LITELLM_BASE_URL = BASE_URL + "/v1"; //$NON-NLS-1$

    private BackendConfig() {
    }
}
