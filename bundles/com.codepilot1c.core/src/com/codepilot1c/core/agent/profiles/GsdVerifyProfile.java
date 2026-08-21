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
 * GSD Verify-фаза: только чтение проекта, сбор доказательств качества.
 *
 * <p>Запрещены мутации исходного проекта, EDT и Git. Разрешены strict GSD
 * control records: чтение состояния, запись evidence, переход по guard.</p>
 */
public final class GsdVerifyProfile extends GsdPhaseProfile {

    public static final String ID = "gsd-verify"; //$NON-NLS-1$

    private static final Set<String> ALLOWED_TOOLS = GsdProfileCapabilities.allowedTools(ID);

    private static final List<PermissionRule> DEFAULT_PERMISSIONS = extendPermissions(
            PermissionRule.allow("gsd_get_state").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_record_evidence").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_transition").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("inspect_role_rights").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("inspect_template").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("java_compile_probe").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("qa_validate_feature").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("validate_query").forAllResources() //$NON-NLS-1$
    );

    public GsdVerifyProfile() {
        super(
                ID,
                "GSD Верификация", //$NON-NLS-1$
                "Read-only фаза GSD: проверка реализации и сбор доказательств. " //$NON-NLS-1$
                        + "Не изменяет проект, EDT и Git.", //$NON-NLS-1$
                ALLOWED_TOOLS,
                DEFAULT_PERMISSIONS,
                25,
                6 * 60 * 1000L,
                true);
    }
}
