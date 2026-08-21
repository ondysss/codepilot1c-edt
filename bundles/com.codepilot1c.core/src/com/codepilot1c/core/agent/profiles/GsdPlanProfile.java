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
 * GSD Plan-фаза: только чтение проекта, создание/обновление плана задач.
 *
 * <p>Запрещены мутации исходного проекта, EDT и Git. Разрешены strict GSD
 * control records: чтение состояния, управление планом, переход по guard.</p>
 */
public final class GsdPlanProfile extends GsdPhaseProfile {

    public static final String ID = "gsd-plan"; //$NON-NLS-1$

    private static final Set<String> ALLOWED_TOOLS = GsdProfileCapabilities.allowedTools(ID);

    private static final List<PermissionRule> DEFAULT_PERMISSIONS = extendPermissions(
            PermissionRule.allow("gsd_get_state").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_create_plan").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_transition").forAllResources() //$NON-NLS-1$
    );

    public GsdPlanProfile() {
        super(
                ID,
                "GSD Планирование", //$NON-NLS-1$
                "Read-only фаза GSD: создание проверяемого плана задач на основе кода. " //$NON-NLS-1$
                        + "Не изменяет проект, EDT и Git.", //$NON-NLS-1$
                ALLOWED_TOOLS,
                DEFAULT_PERMISSIONS,
                25,
                6 * 60 * 1000L,
                true);
    }
}
