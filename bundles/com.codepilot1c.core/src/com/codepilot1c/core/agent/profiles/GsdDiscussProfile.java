/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.List;
import java.util.Set;

import com.codepilot1c.core.permissions.PermissionRule;

/**
 * GSD Discuss-фаза: только чтение проекта, фиксация договоренностей.
 *
 * <p>Запрещены мутации исходного проекта, EDT и Git. Разрешены strict GSD
 * control records: чтение состояния, запись решений, переход по guard.</p>
 */
public final class GsdDiscussProfile extends GsdPhaseProfile {

    public static final String ID = "gsd-discuss"; //$NON-NLS-1$

    private static final Set<String> ALLOWED_TOOLS = GsdProfileCapabilities.allowedTools(ID);

    private static final List<PermissionRule> DEFAULT_PERMISSIONS = extendPermissions(
            PermissionRule.allow("gsd_get_state").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_record_decision").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_transition").forAllResources() //$NON-NLS-1$
    );

    public GsdDiscussProfile() {
        super(
                ID,
                "GSD Обсуждение", //$NON-NLS-1$
                "Read-only фаза GSD: обсуждение задачи, фиксация решений и допущений. " //$NON-NLS-1$
                        + "Не изменяет проект, EDT и Git.", //$NON-NLS-1$
                ALLOWED_TOOLS,
                DEFAULT_PERMISSIONS,
                20,
                5 * 60 * 1000L,
                true);
    }
}
