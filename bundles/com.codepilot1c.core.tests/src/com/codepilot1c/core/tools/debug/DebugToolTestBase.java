package com.codepilot1c.core.tools.debug;

import java.util.Map;

import org.junit.Before;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Патч для тестовый базовый класс debug-инструментов.
 * Base test class for debug tools testing.
 */
public abstract class DebugToolTestBase {

    protected ToolRegistry registry;

    @Before
    public void setUp() {
        registry = ToolRegistry.getInstance();
    }

    protected ToolParameters params(Map<String, Object> raw) {
        return new ToolParameters(raw);
    }
}
