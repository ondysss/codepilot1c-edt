package com.codepilot1c.core.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class QaFeatureCompiler {

    public QaCompiledFeature compile(QaScenarioPlan plan, QaStepRegistry registry, QaStepsCatalog catalog) {
        List<String> steps = new ArrayList<>();
        List<QaCompileIssue> issues = new ArrayList<>();
        if (plan == null) {
            issues.add(new QaCompileIssue("missing_plan", "Scenario plan is required", null, null, List.of())); //$NON-NLS-1$ //$NON-NLS-2$
            return new QaCompiledFeature(null, null, steps, issues);
        }
        for (int i = 0; i < plan.steps().size(); i++) {
            QaScenarioStep scenarioStep = plan.steps().get(i);
            QaStepSpec spec = registry.findStepByIntent(scenarioStep.intent()).orElse(null);
            if (spec == null) {
                issues.add(new QaCompileIssue("unsupported_intent",
                        "No step spec found for intent " + scenarioStep.intent(), i, scenarioStep.intent(), List.of())); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            List<String> missing = spec.missingRequiredArguments(scenarioStep.args());
            if (!missing.isEmpty()) {
                issues.add(new QaCompileIssue("missing_parameter",
                        "Missing required parameters for intent " + scenarioStep.intent(), i,
                        scenarioStep.intent(), missing)); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            String rendered = spec.render(scenarioStep.args());
            if (rendered.contains("${")) { //$NON-NLS-1$
                issues.add(new QaCompileIssue("unresolved_binding",
                        "Scenario plan still contains unresolved template bindings", i,
                        scenarioStep.intent(), List.of(rendered))); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            if (catalog != null && !matchesCatalog(rendered, catalog)) {
                issues.add(new QaCompileIssue("catalog_mismatch",
                        "Rendered step is not accepted by bundled Vanessa steps catalog", i,
                        scenarioStep.intent(), List.of(rendered))); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            steps.add(rendered);
        }
        return new QaCompiledFeature(plan.scenarioTitle(), plan.recipeId(), steps, issues);
    }

    private static boolean matchesCatalog(String step, QaStepsCatalog catalog) {
        if (catalog.contains(step)) {
            return true;
        }
        for (String template : catalog.getSteps()) {
            if (template.contains("%") && QaStepsMatcher.compilePattern(template).matcher(step).matches()) { //$NON-NLS-1$
                return true;
            }
        }
        return false;
    }

    public static QaFeatureValidationResult validateFeatureLines(List<String> lines, QaStepRegistry registry,
                                                                 QaStepsCatalog catalog) {
        List<QaValidationIssue> issues = new ArrayList<>();
        if (lines == null) {
            return new QaFeatureValidationResult(false,
                    List.of(new QaValidationIssue("missing_feature", "Feature file content is required", null, 0))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String normalized = normalizeStepLine(line);
            if (normalized == null) {
                continue;
            }
            boolean registryMatch = registry.getSteps().stream().anyMatch(step -> step.matchesStepText(normalized));
            if (!registryMatch) {
                issues.add(new QaValidationIssue("registry_unknown_step",
                        "Step is not covered by structured QA registry", normalized, i + 1)); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            if (catalog != null && !matchesCatalog(normalized, catalog)) {
                issues.add(new QaValidationIssue("catalog_unknown_step",
                        "Step is not accepted by Vanessa static catalog", normalized, i + 1)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return new QaFeatureValidationResult(issues.isEmpty(), issues);
    }

    private static String normalizeStepLine(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("@") || trimmed.startsWith("|")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("функциональность:") || lower.startsWith("сценарий:") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.startsWith("структура сценария:") || lower.startsWith("примеры:") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.startsWith("контекст:") || lower.startsWith("background:") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.startsWith("#language:")) { //$NON-NLS-1$
            return null;
        }
        if (trimmed.startsWith("*")) { //$NON-NLS-1$
            return "И " + trimmed.substring(1).trim(); //$NON-NLS-1$
        }
        return trimmed;
    }
}
