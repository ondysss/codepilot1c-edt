package com.codepilot1c.core.edt.lang;

/**
 * BSL method parameter description.
 */
public class BslMethodParamInfo {

    private final String name;
    private final boolean byValue;
    private final String defaultValue;

    public BslMethodParamInfo(String name, boolean byValue, String defaultValue) {
        this.name = name;
        this.byValue = byValue;
        this.defaultValue = defaultValue;
    }

    public String getName() {
        return name;
    }

    public boolean isByValue() {
        return byValue;
    }

    public String getDefaultValue() {
        return defaultValue;
    }
}
