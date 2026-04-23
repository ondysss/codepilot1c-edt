package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Test;

import com.codepilot1c.core.tools.memory.RememberFactTool;

public class RememberFactToolHeadlessTest {

    @After
    public void tearDown() throws ReflectiveOperationException {
        setToolRegistryInstance(null);
    }

    @Test
    public void rememberFactToolCanBeInstantiatedWithoutOsgiRuntime() {
        RememberFactTool tool = new RememberFactTool();

        assertEquals("remember_fact", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void toolRegistryRegistersRememberFactToolInHeadlessMode() throws ReflectiveOperationException {
        setToolRegistryInstance(null);

        ToolRegistry registry = ToolRegistry.getInstance();

        assertNotNull(registry.getTool("remember_fact")); //$NON-NLS-1$
    }

    private static void setToolRegistryInstance(ToolRegistry registry) throws ReflectiveOperationException {
        Field field = ToolRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        field.setAccessible(true);
        field.set(null, registry);
    }
}
