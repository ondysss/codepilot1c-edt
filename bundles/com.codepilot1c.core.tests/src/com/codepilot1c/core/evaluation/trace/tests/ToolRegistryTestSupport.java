package com.codepilot1c.core.evaluation.trace.tests;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.google.gson.Gson;

import sun.misc.Unsafe;

final class ToolRegistryTestSupport {

    private ToolRegistryTestSupport() {
    }

    static ToolRegistry createIsolatedRegistry() {
        try {
            ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
            setField(registry, "tools", new HashMap<String, ITool>());
            setField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>());
            setField(registry, "gson", new Gson());
            return registry;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create isolated ToolRegistry", e);
        }
    }

    static ToolRegistry installSingleton(ToolRegistry registry) {
        try {
            Field field = ToolRegistry.class.getDeclaredField("instance");
            field.setAccessible(true);
            ToolRegistry previous = (ToolRegistry) field.get(null);
            field.set(null, registry);
            return previous;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to install ToolRegistry singleton", e);
        }
    }

    private static void setField(ToolRegistry registry, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = ToolRegistry.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(registry, value);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
