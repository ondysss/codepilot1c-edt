/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.profiling;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

/**
 * Reflection-based gateway to EDT debug profiling APIs.
 */
public class EdtProfilingService {

    private static final String WIRING_BUNDLE = "com._1c.g5.wiring"; //$NON-NLS-1$
    private static final String ECLIPSE_DEBUG_BUNDLE = "org.eclipse.debug.core"; //$NON-NLS-1$
    private static final String PROFILING_CORE_BUNDLE = "com._1c.g5.v8.dt.profiling.core"; //$NON-NLS-1$

    private static final String ATTR_APPLICATION_ID =
            "com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID"; //$NON-NLS-1$
    private static final String TYPE_REMOTE_RUNTIME =
            "com._1c.g5.v8.dt.debug.core.RemoteRuntime"; //$NON-NLS-1$
    private static final String TYPE_LOCAL_RUNTIME =
            "com._1c.g5.v8.dt.debug.core.LocalRuntime"; //$NON-NLS-1$
    private static final String ATTACH_APP_ID_PREFIX = "attach:"; //$NON-NLS-1$

    public StartProfilingResult startProfiling(StartProfilingRequest request, String opId) {
        ActiveDebugTarget activeTarget = resolveActiveTarget(request == null ? null : request.applicationId());
        try {
            Bundle profilingBundle = requireBundle(PROFILING_CORE_BUNDLE, "PROFILING_BUNDLE_MISSING"); //$NON-NLS-1$
            Class<?> profileTargetClass = profilingBundle.loadClass(
                    "com._1c.g5.v8.dt.profiling.core.IProfileTarget"); //$NON-NLS-1$
            Object profileTarget = adapt(activeTarget.target(), profileTargetClass);
            if (profileTarget == null) {
                throw new ProfilingException("TARGET_UNSUPPORTED", //$NON-NLS-1$
                        "Debug target does not support EDT profiling: " //$NON-NLS-1$
                                + activeTarget.target().getClass().getName(),
                        false);
            }

            Object profilingService = profilingService(profilingBundle);
            Class<?> profilingServiceClass = profilingBundle.loadClass(
                    "com._1c.g5.v8.dt.profiling.core.IProfilingService"); //$NON-NLS-1$
            Method toggleProfiling = profilingServiceClass.getMethod("toggleProfiling", profileTargetClass); //$NON-NLS-1$
            toggleProfiling.invoke(profilingService, profileTarget);
            return new StartProfilingResult(opId, "ok", true, activeTarget.applicationId(), //$NON-NLS-1$
                    activeTarget.launchName(), activeTarget.target().getClass().getName(),
                    "Profiling toggled. Calling start_profiling again toggles it off."); //$NON-NLS-1$
        } catch (ProfilingException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ProfilingException("PROFILING_TOGGLE_FAILED", //$NON-NLS-1$
                    unwrapMessage(e), true, e);
        }
    }

    public ProfilingResultsResult getProfilingResults(GetProfilingResultsRequest request, String opId) {
        GetProfilingResultsRequest effective = request == null
                ? new GetProfilingResultsRequest(null, 1, 200)
                : request;
        try {
            Bundle profilingBundle = requireBundle(PROFILING_CORE_BUNDLE, "PROFILING_BUNDLE_MISSING"); //$NON-NLS-1$
            Object profilingService = profilingService(profilingBundle);
            Class<?> profilingServiceClass = profilingBundle.loadClass(
                    "com._1c.g5.v8.dt.profiling.core.IProfilingService"); //$NON-NLS-1$
            Method getResults = profilingServiceClass.getMethod("getResults"); //$NON-NLS-1$
            List<?> rawResults = asList(getResults.invoke(profilingService));
            if (rawResults.isEmpty()) {
                return new ProfilingResultsResult(opId, "ok", 0, effective.moduleFilter(), //$NON-NLS-1$
                        effective.minFrequency(), effective.maxLinesPerModule(), List.of(),
                        "No profiling results available. Toggle profiling before running code."); //$NON-NLS-1$
            }

            Class<?> profilingResultClass = profilingBundle.loadClass(
                    "com._1c.g5.v8.dt.profiling.core.IProfilingResult"); //$NON-NLS-1$
            Class<?> lineResultClass = profilingBundle.loadClass(
                    "com._1c.g5.v8.dt.profiling.core.ILineProfilingResult"); //$NON-NLS-1$
            Class<?> timeHolderClass = profilingBundle.loadClass(
                    "com._1c.g5.v8.dt.profiling.core.IProfilingTimeHolder"); //$NON-NLS-1$

            Method getProfilingResults = profilingResultClass.getMethod("getProfilingResults"); //$NON-NLS-1$
            Method getTotalDurability = profilingResultClass.getMethod("getTotalDurability"); //$NON-NLS-1$
            Method getResultName = profilingResultClass.getMethod("getName"); //$NON-NLS-1$
            Method getLineNo = lineResultClass.getMethod("getLineNo"); //$NON-NLS-1$
            Method getFrequency = lineResultClass.getMethod("getFrequency"); //$NON-NLS-1$
            Method getModuleName = lineResultClass.getMethod("getModuleName"); //$NON-NLS-1$
            Method getLine = lineResultClass.getMethod("getLine"); //$NON-NLS-1$
            Method getPercentage = lineResultClass.getMethod("getPercentage"); //$NON-NLS-1$
            Method getMethodSignature = lineResultClass.getMethod("getMethodSignature"); //$NON-NLS-1$
            Method getDurability = timeHolderClass.getMethod("getDurability"); //$NON-NLS-1$
            Method getPureDurability = timeHolderClass.getMethod("getPureDurability"); //$NON-NLS-1$

            List<ProfilingRunResult> runs = new ArrayList<>();
            for (Object result : rawResults) {
                List<?> rawLines = asList(getProfilingResults.invoke(result));
                Map<String, MutableModuleResult> modules = new LinkedHashMap<>();
                int returnedLineCount = 0;
                for (Object lineResult : rawLines) {
                    long frequency = asLong(getFrequency.invoke(lineResult));
                    if (frequency < effective.minFrequency()) {
                        continue;
                    }
                    String moduleName = asString(getModuleName.invoke(lineResult), "?"); //$NON-NLS-1$
                    if (!effective.matchesModule(moduleName)) {
                        continue;
                    }
                    MutableModuleResult module = modules.computeIfAbsent(moduleName, MutableModuleResult::new);
                    if (module.lines.size() >= effective.maxLinesPerModule()) {
                        module.omittedLines++;
                        continue;
                    }
                    module.lines.add(new ProfilingLineResult(
                            asInt(getLineNo.invoke(lineResult)),
                            frequency,
                            rounded(asDouble(getPercentage.invoke(lineResult)), 2),
                            rounded(asDouble(getDurability.invoke(lineResult)), 3),
                            rounded(asDouble(getPureDurability.invoke(lineResult)), 3),
                            trimCode(asString(getLine.invoke(lineResult), null)),
                            asString(getMethodSignature.invoke(lineResult), null)));
                    returnedLineCount++;
                }
                List<ProfilingModuleResult> moduleResults = modules.values().stream()
                        .map(MutableModuleResult::toResult)
                        .toList();
                runs.add(new ProfilingRunResult(
                        asString(getResultName.invoke(result), null),
                        rounded(asDouble(getTotalDurability.invoke(result)), 3),
                        rawLines.size(),
                        returnedLineCount,
                        moduleResults.size(),
                        moduleResults));
            }
            return new ProfilingResultsResult(opId, "ok", runs.size(), effective.moduleFilter(), //$NON-NLS-1$
                    effective.minFrequency(), effective.maxLinesPerModule(), runs, null);
        } catch (ProfilingException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ProfilingException("PROFILING_RESULTS_FAILED", unwrapMessage(e), true, e); //$NON-NLS-1$
        }
    }

    private Object profilingService(Bundle profilingBundle) throws ReflectiveOperationException {
        Bundle wiringBundle = requireBundle(WIRING_BUNDLE, "WIRING_BUNDLE_MISSING"); //$NON-NLS-1$
        Class<?> serviceAccessClass = wiringBundle.loadClass("com._1c.g5.wiring.ServiceAccess"); //$NON-NLS-1$
        Class<?> profilingServiceClass = profilingBundle.loadClass(
                "com._1c.g5.v8.dt.profiling.core.IProfilingService"); //$NON-NLS-1$
        Method getService = serviceAccessClass.getMethod("get", Class.class); //$NON-NLS-1$
        Object service = getService.invoke(null, profilingServiceClass);
        if (service == null) {
            throw new ProfilingException("PROFILING_SERVICE_UNAVAILABLE", //$NON-NLS-1$
                    "IProfilingService is not available.", true); //$NON-NLS-1$
        }
        return service;
    }

    private ActiveDebugTarget resolveActiveTarget(String applicationId) {
        List<ActiveDebugTarget> activeTargets = listActiveTargets();
        if (activeTargets.isEmpty()) {
            throw new ProfilingException("NO_ACTIVE_DEBUG_TARGET", //$NON-NLS-1$
                    "No active debug targets found. Start an EDT debug session first.", true); //$NON-NLS-1$
        }
        if (applicationId != null && !applicationId.isBlank()) {
            return activeTargets.stream()
                    .filter(target -> applicationId.equals(target.applicationId())
                            || applicationId.equals(target.launchName()))
                    .findFirst()
                    .orElseThrow(() -> new ProfilingException("DEBUG_TARGET_NOT_FOUND", //$NON-NLS-1$
                            "No active debug target for applicationId: " + applicationId, //$NON-NLS-1$
                            true, describeTargets(activeTargets)));
        }
        if (activeTargets.size() == 1) {
            return activeTargets.get(0);
        }
        throw new ProfilingException("AMBIGUOUS_DEBUG_TARGET", //$NON-NLS-1$
                "Multiple active debug targets found. Pass applicationId.", true, //$NON-NLS-1$
                describeTargets(activeTargets));
    }

    private List<ActiveDebugTarget> listActiveTargets() {
        try {
            Bundle debugBundle = requireBundle(ECLIPSE_DEBUG_BUNDLE, "DEBUG_BUNDLE_MISSING"); //$NON-NLS-1$
            Class<?> debugPluginClass = debugBundle.loadClass("org.eclipse.debug.core.DebugPlugin"); //$NON-NLS-1$
            Object debugPlugin = debugPluginClass.getMethod("getDefault").invoke(null); //$NON-NLS-1$
            if (debugPlugin == null) {
                return List.of();
            }
            Object launchManager = invoke(debugPlugin, "getLaunchManager"); //$NON-NLS-1$
            Object launches = invoke(launchManager, "getLaunches"); //$NON-NLS-1$
            List<ActiveDebugTarget> activeTargets = new ArrayList<>();
            for (Object launch : asArrayList(launches)) {
                if (asBoolean(invoke(launch, "isTerminated"))) { //$NON-NLS-1$
                    continue;
                }
                Object configuration = invoke(launch, "getLaunchConfiguration"); //$NON-NLS-1$
                String launchName = launchName(configuration);
                String applicationId = applicationId(configuration, launchName);
                Object debugTargets = invoke(launch, "getDebugTargets"); //$NON-NLS-1$
                for (Object target : asArrayList(debugTargets)) {
                    if (target != null && !asBoolean(invoke(target, "isTerminated"))) { //$NON-NLS-1$
                        activeTargets.add(new ActiveDebugTarget(
                                applicationId == null ? launchName : applicationId,
                                launchName,
                                target));
                    }
                }
            }
            return activeTargets;
        } catch (ProfilingException e) {
            throw e;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ProfilingException("DEBUG_TARGET_DISCOVERY_FAILED", unwrapMessage(e), true, e); //$NON-NLS-1$
        }
    }

    private String applicationId(Object configuration, String launchName) {
        if (configuration == null) {
            return launchName;
        }
        String realId = readStringAttribute(configuration, ATTR_APPLICATION_ID, null);
        if (realId != null && !realId.isBlank()) {
            return realId;
        }
        String typeId = launchTypeId(configuration);
        if (TYPE_REMOTE_RUNTIME.equals(typeId) || TYPE_LOCAL_RUNTIME.equals(typeId)) {
            return ATTACH_APP_ID_PREFIX + launchName;
        }
        return launchName;
    }

    private String launchName(Object configuration) {
        if (configuration == null) {
            return null;
        }
        try {
            Object name = invoke(configuration, "getName"); //$NON-NLS-1$
            return name == null ? null : String.valueOf(name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private String launchTypeId(Object configuration) {
        try {
            Object type = invoke(configuration, "getType"); //$NON-NLS-1$
            Object id = type == null ? null : invoke(type, "getIdentifier"); //$NON-NLS-1$
            return id == null ? null : String.valueOf(id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private String readStringAttribute(Object configuration, String attribute, String defaultValue) {
        try {
            Method method = configuration.getClass().getMethod("getAttribute", String.class, String.class); //$NON-NLS-1$
            Object value = method.invoke(configuration, attribute, defaultValue);
            return value == null ? defaultValue : String.valueOf(value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return defaultValue;
        }
    }

    private Object adapt(Object source, Class<?> targetClass) throws ReflectiveOperationException {
        if (targetClass.isInstance(source)) {
            return source;
        }
        try {
            Method getAdapter = source.getClass().getMethod("getAdapter", Class.class); //$NON-NLS-1$
            return getAdapter.invoke(source, targetClass);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private Bundle requireBundle(String bundleName, String errorCode) {
        Bundle bundle = Platform.getBundle(bundleName);
        if (bundle == null) {
            throw new ProfilingException(errorCode, "Required bundle not found: " + bundleName, false); //$NON-NLS-1$
        }
        return bundle;
    }

    private static Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static List<?> asArrayList(Object array) {
        if (array == null) {
            return List.of();
        }
        if (array instanceof Collection<?> collection) {
            return List.copyOf(collection);
        }
        if (!array.getClass().isArray()) {
            return List.of(array);
        }
        int length = Array.getLength(array);
        List<Object> list = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            list.add(Array.get(array, i));
        }
        return list;
    }

    private static List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of();
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean bool && bool.booleanValue();
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0d;
    }

    private static String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static double rounded(double value, int scale) {
        double multiplier = Math.pow(10.0d, scale);
        return Math.round(value * multiplier) / multiplier;
    }

    private static String trimCode(String code) {
        if (code == null || code.length() <= 160) {
            return code;
        }
        return code.substring(0, 160) + "..."; //$NON-NLS-1$
    }

    private static List<String> describeTargets(List<ActiveDebugTarget> targets) {
        return targets.stream()
                .map(target -> target.applicationId() + " (" + target.target().getClass().getName() + ")") //$NON-NLS-1$ //$NON-NLS-2$
                .toList();
    }

    private static String unwrapMessage(Throwable throwable) {
        Throwable effective = throwable;
        if (throwable instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            effective = invocation.getCause();
        }
        String message = effective.getMessage();
        return message == null || message.isBlank() ? effective.getClass().getName() : message;
    }

    private record ActiveDebugTarget(String applicationId, String launchName, Object target) {
    }

    private static final class MutableModuleResult {

        private final String moduleName;
        private final List<ProfilingLineResult> lines = new ArrayList<>();
        private int omittedLines;

        private MutableModuleResult(String moduleName) {
            this.moduleName = moduleName;
        }

        private ProfilingModuleResult toResult() {
            return new ProfilingModuleResult(moduleName, lines.size(), omittedLines, lines);
        }
    }
}
