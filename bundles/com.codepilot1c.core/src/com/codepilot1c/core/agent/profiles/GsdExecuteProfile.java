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
 * GSD Execute-фаза: реализация плана минимальными обратимыми изменениями.
 *
 * <p>Включает project/EDT мутации: редактирование файлов, подготовку модулей
 * и CRUD метаданных/форм. Перед любой EDT-мутацией обязателен
 * edt_validate_request; validation_token не обходится. write_file напрямую
 * для .mdo/Configuration.mdo запрещён — такие изменения идут только через
 * семантические EDT mutation tools.</p>
 */
public final class GsdExecuteProfile extends GsdPhaseProfile {

    public static final String ID = "gsd-execute"; //$NON-NLS-1$

    private static final Set<String> ALLOWED_TOOLS = GsdProfileCapabilities.allowedTools(ID);

    private static final List<PermissionRule> DEFAULT_PERMISSIONS = extendPermissions(
            PermissionRule.allow("gsd_get_state").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_update_task").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_record_evidence").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_transition").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("edt_validate_request").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("remember_fact").forAllResources(), //$NON-NLS-1$
            PermissionRule.deny("write_file") //$NON-NLS-1$
                    .withDescription("Прямая запись .mdo и Configuration.mdo запрещена; используй EDT mutation tools") //$NON-NLS-1$
                    .forResourcePattern("**/*.mdo") //$NON-NLS-1$
                    .build(),
            PermissionRule.deny("edit_file") //$NON-NLS-1$
                    .withDescription("Прямое редактирование .mdo и Configuration.mdo запрещено; используй EDT mutation tools") //$NON-NLS-1$
                    .forResourcePattern("**/*.mdo") //$NON-NLS-1$
                    .build(),
            PermissionRule.ask("edit_file") //$NON-NLS-1$
                    .withDescription("Редактирование файлов проекта") //$NON-NLS-1$
                    .forAllResources(),
            PermissionRule.ask("write_file") //$NON-NLS-1$
                    .withDescription("Создание файлов проекта") //$NON-NLS-1$
                    .forAllResources(),
            PermissionRule.ask("ensure_module_artifact") //$NON-NLS-1$
                    .withDescription("Создание/подготовка файлов модулей EDT") //$NON-NLS-1$
                    .forAllResources(),
            PermissionRule.ask("create_metadata") //$NON-NLS-1$
                    .withDescription("Создание объектов метаданных EDT") //$NON-NLS-1$
                    .forAllResources(),
            PermissionRule.ask("create_form") //$NON-NLS-1$
                    .withDescription("Создание управляемых форм EDT") //$NON-NLS-1$
                    .forAllResources(),
            PermissionRule.ask("add_metadata_child") //$NON-NLS-1$
                    .withDescription("Создание вложенных объектов метаданных EDT") //$NON-NLS-1$
                    .forAllResources(),
            PermissionRule.ask("update_metadata") //$NON-NLS-1$
                    .withDescription("Обновление свойств объектов метаданных EDT") //$NON-NLS-1$
                    .forAllResources(),
            PermissionRule.ask("mutate_form_model") //$NON-NLS-1$
                    .withDescription("Обновление модели управляемых форм EDT") //$NON-NLS-1$
                    .forAllResources(),
            PermissionRule.ask("delete_metadata") //$NON-NLS-1$
                    .withDescription("Удаление объектов метаданных EDT") //$NON-NLS-1$
                    .forAllResources()
    );

    public GsdExecuteProfile() {
        super(
                ID,
                "GSD Исполнение", //$NON-NLS-1$
                "Фаза GSD: реализация задач плана. Допускает минимальные " //$NON-NLS-1$
                        + "project/EDT мутации только через edt_validate_request + validation_token. " //$NON-NLS-1$
                        + "Прямая запись .mdo/Configuration.mdo запрещена.", //$NON-NLS-1$
                ALLOWED_TOOLS,
                DEFAULT_PERMISSIONS,
                40,
                10 * 60 * 1000L,
                false);
    }
}
