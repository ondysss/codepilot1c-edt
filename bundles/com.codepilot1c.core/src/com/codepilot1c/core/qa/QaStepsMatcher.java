package com.codepilot1c.core.qa;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QaStepsMatcher {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%\\d+"); //$NON-NLS-1$

    private QaStepsMatcher() {
    }

    public static Pattern compilePattern(String stepTemplate) {
        String normalized = stripPlaceholderLabels(stepTemplate);
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(normalized);
        StringBuilder regex = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            regex.append(Pattern.quote(normalized.substring(last, matcher.start())));
            regex.append("(.+)"); //$NON-NLS-1$
            last = matcher.end();
        }
        regex.append(Pattern.quote(normalized.substring(last)));
        return Pattern.compile("^" + regex + "$", Pattern.DOTALL); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static String toRegex(String stepTemplate) {
        String normalized = stripPlaceholderLabels(stepTemplate);
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(normalized);
        StringBuilder regex = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            regex.append(Pattern.quote(normalized.substring(last, matcher.start())));
            regex.append("(.+)"); //$NON-NLS-1$
            last = matcher.end();
        }
        regex.append(Pattern.quote(normalized.substring(last)));
        return "^" + regex + "$"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static int countPlaceholders(String stepTemplate) {
        if (stepTemplate == null) {
            return 0;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(stepTemplate);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String stripPlaceholderLabels(String stepTemplate) {
        if (stepTemplate == null || stepTemplate.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder(stepTemplate.length());
        boolean inQuotes = false;
        int i = 0;
        while (i < stepTemplate.length()) {
            char ch = stepTemplate.charAt(i);
            if (ch == '"') { //$NON-NLS-1$
                inQuotes = !inQuotes;
                sb.append(ch);
                i++;
                continue;
            }
            if (inQuotes && ch == '%' && i + 1 < stepTemplate.length()
                    && Character.isDigit(stepTemplate.charAt(i + 1))) {
                int j = i + 1;
                while (j < stepTemplate.length() && Character.isDigit(stepTemplate.charAt(j))) {
                    j++;
                }
                sb.append(stepTemplate, i, j);
                while (j < stepTemplate.length() && stepTemplate.charAt(j) != '"') { //$NON-NLS-1$
                    j++;
                }
                i = j;
                continue;
            }
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }
}
