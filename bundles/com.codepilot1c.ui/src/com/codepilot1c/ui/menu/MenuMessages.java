/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.menu;

import org.eclipse.osgi.util.NLS;

/**
 * Localized messages for dynamic menu.
 */
public class MenuMessages extends NLS {

    private static final String BUNDLE_NAME = "com.codepilot1c.ui.menu.messages"; //$NON-NLS-1$

    public static String Menu_Settings;
    public static String Menu_SelectProvider;
    public static String Menu_ViewChat;

    static {
        NLS.initializeMessages(BUNDLE_NAME, MenuMessages.class);
    }

    private MenuMessages() {
    }
}
