/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.junit.Test;

/** Exercises both production collection paths with Eclipse resource filtering. */
public class EdtDiagnosticsProblemMarkersTest {
    private static final String COLLECTOR = "com.codepilot1c.ui.diagnostics.EdtDiagnosticsCollector";
    private static final String DIAGNOSTIC = "com.codepilot1c.ui.diagnostics.EdtDiagnostic";
    private static final String RUN_MARKER = "example.tests.runMethod";
    private static final String PATH = "/example/src/CommonModules/Tests/Module.bsl";

    @Test
    public void fileScopeIgnoresRunMarkersWithWarningSeverity() throws Exception {
        List<?> result = collect(false, "INFO", markers(false));
        assertEquals(3, result.size());
        assertTrue(result.stream().noneMatch(d -> RUN_MARKER.equals(component(d, "markerType"))));
    }

    @Test
    public void projectScopeIgnoresRunMarkersWithWarningSeverity() throws Exception {
        List<?> result = collect(true, "INFO", markers(false));
        assertEquals(3, result.size());
        assertTrue(result.stream().noneMatch(d -> RUN_MARKER.equals(component(d, "markerType"))));
    }

    @Test
    public void genuineWarningWithTheSameTextIsRetainedInFileScope() throws Exception {
        List<?> result = collect(false, "WARNING", markers(true));
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(d -> "Run test method Example".equals(component(d, "message"))));
        assertTrue(result.stream().allMatch(d -> !RUN_MARKER.equals(component(d, "markerType"))));
    }

    @Test
    public void genuineWarningWithTheSameTextIsRetainedInProjectScope() throws Exception {
        List<?> result = collect(true, "WARNING", markers(true));
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(d -> "Run test method Example".equals(component(d, "message"))));
    }

    @Test
    public void fileScopeRetainsCompilerErrorsAtErrorThreshold() throws Exception {
        List<?> result = collect(false, "ERROR", markers(false));
        assertEquals(1, result.size());
        assertEquals("ERROR", component(result.get(0), "severity").toString());
        assertEquals("Compiler error", component(result.get(0), "message"));
    }

    @Test
    public void projectScopeRetainsCompilerErrorsAtErrorThreshold() throws Exception {
        List<?> result = collect(true, "ERROR", markers(false));
        assertEquals(1, result.size());
        assertEquals("ERROR", component(result.get(0), "severity").toString());
        assertEquals("Compiler error", component(result.get(0), "message"));
    }

    private static List<IMarker> markers(boolean sameText) {
        return List.of(
                marker(RUN_MARKER, IMarker.SEVERITY_WARNING, "Run test method Example", false, 2),
                marker("example.bookmark", IMarker.SEVERITY_INFO, "Bookmark", false, 3),
                marker("example.problem", IMarker.SEVERITY_ERROR, "Compiler error", true, 4),
                marker("example.problem", IMarker.SEVERITY_WARNING,
                        sameText ? "Run test method Example" : "Genuine warning", true, 5),
                marker("example.problem", IMarker.SEVERITY_INFO, "Genuine information", true, 6));
    }

    private static IMarker marker(String type, int severity, String message, boolean problem, int line) {
        return (IMarker) Proxy.newProxyInstance(IMarker.class.getClassLoader(), new Class<?>[] { IMarker.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("isSubtypeOf")) {
                        return IMarker.PROBLEM.equals(args[0]) && problem;
                    }
                    if (method.getName().equals("getType")) return type;
                    if (method.getName().equals("getResource")) return resource(IFile.class, List.of());
                    if (method.getName().equals("getAttribute")) {
                        String key = (String) args[0];
                        if (IMarker.SEVERITY.equals(key)) return severity;
                        if (IMarker.MESSAGE.equals(key)) return message;
                        if (IMarker.LINE_NUMBER.equals(key)) return line;
                        if (IMarker.CHAR_START.equals(key)) return 0;
                        if (IMarker.CHAR_END.equals(key)) return 1;
                        return args.length > 1 ? args[1] : null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object resource(Class<?> resourceType, List<IMarker> markers) {
        return Proxy.newProxyInstance(resourceType.getClassLoader(), new Class<?>[] { resourceType },
                (proxy, method, args) -> {
                    if (method.getName().equals("findMarkers")) {
                        String type = (String) args[0];
                        boolean subtypes = (Boolean) args[1];
                        List<IMarker> selected = new ArrayList<>();
                        for (IMarker marker : markers) {
                            if (type == null || type.equals(marker.getType()) || (subtypes && marker.isSubtypeOf(type))) {
                                selected.add(marker);
                            }
                        }
                        return selected.toArray(IMarker[]::new);
                    }
                    if (method.getName().equals("getFullPath")) return new Path(PATH);
                    if (method.getName().equals("getName")) return "example";
                    return defaultValue(method.getReturnType());
                });
    }

    private static List<?> collect(boolean project, String severity, List<IMarker> markers) throws Exception {
        Class<?> owner = loadCollector();
        Constructor<?> constructor = owner.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object collector = constructor.newInstance();
        ClassLoader loader = owner.getClassLoader();
        Class<?> queryType = loader.loadClass(COLLECTOR + "$DiagnosticsQuery");
        Class<?> severityType = loader.loadClass(DIAGNOSTIC + "$Severity");
        Object level = severityType.getMethod("valueOf", String.class).invoke(null, severity);
        Object query = queryType.getConstructor(severityType, int.class, boolean.class, long.class,
                boolean.class, String.class).newInstance(level, 0, false, 0L, false, null);
        List<Object> diagnostics = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (project) {
            Method method = owner.getDeclaredMethod("collectWorkspaceProjectMarkers", IProject.class,
                    queryType, List.class, Set.class);
            method.setAccessible(true);
            method.invoke(collector, resource(IProject.class, markers), query, diagnostics, seen);
        } else {
            Method method = owner.getDeclaredMethod("collectFromMarkers", IFile.class, String.class,
                    queryType, List.class, Set.class);
            method.setAccessible(true);
            method.invoke(collector, resource(IFile.class, markers), PATH, query, diagnostics, seen);
        }
        return diagnostics;
    }

    private static Class<?> loadCollector() throws Exception {
        try {
            return Class.forName(COLLECTOR);
        } catch (ClassNotFoundException exception) {
            // The collector is deliberately not an exported OSGi package.
            return Platform.getBundle("com.codepilot1c.ui").loadClass(COLLECTOR);
        }
    }

    private static Object component(Object record, String name) {
        try {
            return record.getClass().getMethod(name).invoke(record);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
