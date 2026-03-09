package com.codepilot1c.core.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public record QaStepSpec(
        String id,
        String intent,
        String canonical_text,
        String expression,
        String ui_scope,
        List<QaStepParameterSpec> parameters,
        List<String> domain_tags,
        List<String> synonyms,
        List<String> preconditions,
        List<String> postconditions) {

    public List<QaStepParameterSpec> safeParameters() {
        return parameters == null ? List.of() : parameters;
    }

    public String render(Map<String, String> arguments) {
        String template = canonical_text == null ? "" : canonical_text; //$NON-NLS-1$
        StringBuilder rendered = new StringBuilder(template.length());
        boolean inQuotes = false;
        int i = 0;
        while (i < template.length()) {
            char ch = template.charAt(i);
            if (ch == '"') { //$NON-NLS-1$
                inQuotes = !inQuotes;
                rendered.append(ch);
                i++;
                continue;
            }
            if (ch == '%' && i + 1 < template.length() && Character.isDigit(template.charAt(i + 1))) {
                int j = i + 1;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                int parameterIndex = Integer.parseInt(template.substring(i + 1, j)) - 1;
                rendered.append(resolveParameterValue(parameterIndex, arguments));
                i = j;
                if (inQuotes) {
                    while (i < template.length() && template.charAt(i) != '"') { //$NON-NLS-1$
                        i++;
                    }
                }
                continue;
            }
            rendered.append(ch);
            i++;
        }
        return rendered.toString().replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public List<String> missingRequiredArguments(Map<String, String> arguments) {
        List<String> missing = new ArrayList<>();
        for (QaStepParameterSpec parameter : safeParameters()) {
            String value = arguments == null ? null : arguments.get(parameter.name());
            if ((value == null || value.isBlank()) && parameter.required()
                    && (parameter.defaultValue() == null || parameter.defaultValue().isBlank())) {
                missing.add(parameter.name());
            }
        }
        return missing;
    }

    private String resolveParameterValue(int parameterIndex, Map<String, String> arguments) {
        if (parameterIndex < 0 || parameterIndex >= safeParameters().size()) {
            return ""; //$NON-NLS-1$
        }
        QaStepParameterSpec parameter = safeParameters().get(parameterIndex);
        String value = arguments == null ? null : arguments.get(parameter.name());
        if ((value == null || value.isBlank()) && parameter.defaultValue() != null) {
            value = parameter.defaultValue();
        }
        return value == null ? "" : value; //$NON-NLS-1$
    }

    public boolean matchesStepText(String stepText) {
        if (stepText == null || stepText.isBlank()) {
            return false;
        }
        String normalized = stepText.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        if (canonical_text != null && !canonical_text.isBlank()) {
            String canonical = canonical_text.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
            if (canonical.equals(normalized)) {
                return true;
            }
            if (canonical.contains("%")) { //$NON-NLS-1$
                Pattern pattern = QaStepsMatcher.compilePattern(canonical);
                if (pattern.matcher(normalized).matches()) {
                    return true;
                }
            }
        }
        if (expression != null && !expression.isBlank()) {
            return Pattern.compile(expression).matcher(normalized).matches();
        }
        return false;
    }
}
