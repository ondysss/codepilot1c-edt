/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.permissions;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Объединяет профильный и глобальный слои permission-правил.
 */
public final class ProfilePermissionGate {

    private static final Set<String> VALID_GIT_MUTATE_OPERATIONS = Set.of(
            "init", "create", "create_repo", "clone", "remote_add", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "remote_set_url", "fetch", "pull", "push", "checkout", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "create_branch", "add", "commit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    private static final PermissionRule INVALID_GIT_OPERATION_RULE =
            PermissionRule.deny("git_mutate") //$NON-NLS-1$
                    .withDescription("git_mutate requires a supported explicit operation") //$NON-NLS-1$
                    .forAllResources();

    /** Итоговое решение runtime-гейта. */
    public enum GateDecision {
        ALLOW,
        ASK,
        DENY,
        NO_RULE
    }

    /**
     * Результат проверки.
     *
     * @param decision итоговое решение
     * @param rule выигравшее правило, либо null для {@link GateDecision#NO_RULE}
     * @param layer слой выигравшего правила: profile, global или none
     * @param resource raw-ресурс strict gate без нормализации
     */
    public record GateResult(
            GateDecision decision, PermissionRule rule, String layer, String resource) {
        /**
         * @return true, если выполнение должно быть запрещено
         */
        public boolean isDenied() {
            return decision == GateDecision.DENY;
        }
    }

    private ProfilePermissionGate() {
    }

    /**
     * Проверяет вызов по принципу самого строгого решения:
     * DENY &gt; ASK &gt; ALLOW, а отсутствие правила не влияет на второй слой.
     * Для {@code git_mutate} дополнительно проверяется синтетический ресурс
     * {@code operation:&lt;name&gt;}; raw path/resource в результате не меняется.
     *
     * @param profileRules правила активного профиля
     * @param globalRules глобальные правила
     * @param toolName имя инструмента
     * @param arguments аргументы инструмента
     * @return итоговое решение с выигравшим правилом и слоем
     */
    public static GateResult evaluate(
            List<PermissionRule> profileRules,
            List<PermissionRule> globalRules,
            String toolName,
            Map<String, Object> arguments) {
        String rawResource = PermissionEvaluator.gateResourceOf(arguments);
        if ("git_mutate".equals(toolName) && !hasValidGitOperation(arguments)) { //$NON-NLS-1$
            return new GateResult(
                    GateDecision.DENY, INVALID_GIT_OPERATION_RULE, "boundary", rawResource); //$NON-NLS-1$
        }
        String resource = PermissionEvaluator.normalizedResourceOf(arguments);
        String operationResource = operationResource(toolName, arguments);
        PermissionRule profileRule = strictestMatch(
                profileRules, toolName, resource, operationResource);
        PermissionRule globalRule = strictestMatch(
                globalRules, toolName, resource, operationResource);

        if (profileRule == null && globalRule == null) {
            return new GateResult(GateDecision.NO_RULE, null, "none", rawResource);
        }
        if (hasDecision(profileRule, PermissionDecision.DENY)) {
            return new GateResult(GateDecision.DENY, profileRule, "profile", rawResource);
        }
        if (hasDecision(globalRule, PermissionDecision.DENY)) {
            return new GateResult(GateDecision.DENY, globalRule, "global", rawResource);
        }
        if (hasDecision(profileRule, PermissionDecision.ASK)) {
            return new GateResult(GateDecision.ASK, profileRule, "profile", rawResource);
        }
        if (hasDecision(globalRule, PermissionDecision.ASK)) {
            return new GateResult(GateDecision.ASK, globalRule, "global", rawResource);
        }
        return profileRule != null
                ? new GateResult(GateDecision.ALLOW, profileRule, "profile", rawResource)
                : new GateResult(GateDecision.ALLOW, globalRule, "global", rawResource);
    }

    private static boolean hasDecision(PermissionRule rule, PermissionDecision decision) {
        return rule != null && rule.getDecision() == decision;
    }

    private static PermissionRule strictestMatch(
            List<PermissionRule> rules,
            String toolName,
            String resource,
            String operationResource) {
        PermissionRule resourceRule = PermissionEvaluator
                .strictestMatch(rules, toolName, resource)
                .orElse(null);
        if (operationResource == null) {
            return resourceRule;
        }
        PermissionRule operationRule = PermissionEvaluator
                .strictestMatch(rules, toolName, operationResource)
                .orElse(null);
        if (resourceRule == null) {
            return operationRule;
        }
        if (operationRule == null) {
            return resourceRule;
        }
        return strictness(operationRule.getDecision()) > strictness(resourceRule.getDecision())
                ? operationRule
                : resourceRule;
    }

    private static String operationResource(String toolName, Map<String, Object> arguments) {
        if (!"git_mutate".equals(toolName) || arguments == null) { //$NON-NLS-1$
            return null;
        }
        Object operation = arguments.get("operation"); //$NON-NLS-1$
        if (operation == null || String.valueOf(operation).isBlank()) {
            return null;
        }
        return "operation:" + String.valueOf(operation).trim(); //$NON-NLS-1$
    }

    private static boolean hasValidGitOperation(Map<String, Object> arguments) {
        if (arguments == null || !(arguments.get("operation") instanceof String operation)) { //$NON-NLS-1$
            return false;
        }
        return VALID_GIT_MUTATE_OPERATIONS.contains(operation.trim());
    }

    private static int strictness(PermissionDecision decision) {
        return switch (decision) {
            case DENY -> 3;
            case ASK -> 2;
            case ALLOW -> 1;
        };
    }
}
