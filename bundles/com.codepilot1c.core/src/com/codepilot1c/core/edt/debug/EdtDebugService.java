/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.debug;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.codepilot1c.core.edt.ast.BreakpointInfo;
import com.codepilot1c.core.edt.ast.DebugStatusRequest;
import com.codepilot1c.core.edt.ast.DebugStatusResult;
import com.codepilot1c.core.edt.ast.DebugVariableInfo;
import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtServiceGateway;
import com.codepilot1c.core.edt.ast.EvaluateExpressionRequest;
import com.codepilot1c.core.edt.ast.EvaluateExpressionResult;
import com.codepilot1c.core.edt.ast.GetVariablesRequest;
import com.codepilot1c.core.edt.ast.GetVariablesResult;
import com.codepilot1c.core.edt.ast.ListBreakpointsRequest;
import com.codepilot1c.core.edt.ast.ListBreakpointsResult;
import com.codepilot1c.core.edt.ast.RemoveBreakpointRequest;
import com.codepilot1c.core.edt.ast.RemoveBreakpointResult;
import com.codepilot1c.core.edt.ast.ResumeRequest;
import com.codepilot1c.core.edt.ast.ResumeResult;
import com.codepilot1c.core.edt.ast.SetBreakpointRequest;
import com.codepilot1c.core.edt.ast.SetBreakpointResult;
import com.codepilot1c.core.edt.ast.StepRequest;
import com.codepilot1c.core.edt.ast.StepResult;
import com.codepilot1c.core.edt.ast.WaitForBreakRequest;
import com.codepilot1c.core.edt.ast.WaitForBreakResult;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Debug facade for EDT/1C debug operations.
 *
 * <p>The service intentionally talks to Eclipse Debug Framework and EDT debug
 * services through reflection. This keeps the OSS core resilient across EDT
 * versions while the concrete {@code com._1c.g5.v8.dt.debug.*} service binding
 * is filled in incrementally.</p>
 */
public class EdtDebugService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtDebugService.class);
    private static final String DEBUG_CORE_BUNDLE = "org.eclipse.debug.core"; //$NON-NLS-1$
    private static final String DEBUG_PLUGIN_CLASS = "org.eclipse.debug.core.DebugPlugin"; //$NON-NLS-1$
    private static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$

    private static volatile EdtDebugService instance;

    private final EdtServiceGateway gateway;

    public EdtDebugService(EdtServiceGateway gateway) {
        this.gateway = gateway;
    }

    public static EdtDebugService getInstance() {
        EdtDebugService local = instance;
        if (local == null) {
            synchronized (EdtDebugService.class) {
                local = instance;
                if (local == null) {
                    local = new EdtDebugService(new EdtServiceGateway());
                    instance = local;
                }
            }
        }
        return local;
    }

    public SetBreakpointResult setBreakpoint(SetBreakpointRequest request) {
        request.validate();
        requireProject(request.projectName());
        throw unsupported("set_breakpoint requires EDT BSL breakpoint factory binding"); //$NON-NLS-1$
    }

    public RemoveBreakpointResult removeBreakpoint(RemoveBreakpointRequest request) {
        request.validate();
        requireProject(request.projectName());
        Object breakpoint = findBreakpoint(request.projectName(), request.breakpointId(), request.filePath(), request.line());
        if (breakpoint == null) {
            return new RemoveBreakpointResult(request.projectName(), request.breakpointId(), false,
                    "not_found", "Breakpoint was not found"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try {
            invoke(breakpoint, "delete"); //$NON-NLS-1$
            return new RemoveBreakpointResult(request.projectName(), request.breakpointId(), true,
                    "removed", "Breakpoint removed"); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (RuntimeException e) {
            throw internal("Failed to remove breakpoint", e); //$NON-NLS-1$
        }
    }

    public ListBreakpointsResult listBreakpoints(ListBreakpointsRequest request) {
        request.validate();
        if (request.projectName() != null) {
            requireProject(request.projectName());
        }
        return new ListBreakpointsResult(request.projectName(), listBreakpointInfos(request.projectName()));
    }

    public WaitForBreakResult waitForBreak(WaitForBreakRequest request) {
        request.validate();
        requireProject(request.projectName());
        long started = System.currentTimeMillis();
        long deadline = started + request.timeoutMs();
        while (System.currentTimeMillis() <= deadline) {
            DebugThreadSelection selection = findThread(request.projectName(), null, true);
            if (selection != null) {
                return new WaitForBreakResult(request.projectName(), true, selection.threadId(),
                        "suspended", System.currentTimeMillis() - started, false); //$NON-NLS-1$
            }
            sleepQuietly(200L);
        }
        return new WaitForBreakResult(request.projectName(), false, null,
                "timeout", System.currentTimeMillis() - started, true); //$NON-NLS-1$
    }

    public GetVariablesResult getVariables(GetVariablesRequest request) {
        request.validate();
        requireProject(request.projectName());
        DebugFrameSelection frame = findFrame(request.projectName(), request.threadId(), request.frameId());
        if (frame == null) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "No suspended stack frame found for project " + request.projectName(), true); //$NON-NLS-1$
        }
        List<DebugVariableInfo> variables = new ArrayList<>();
        for (Object variable : toArray(invoke(frame.frame(), "getVariables"))) { //$NON-NLS-1$
            variables.add(toVariableInfo(variable));
        }
        return new GetVariablesResult(request.projectName(), frame.threadId(), frame.frameId(), variables);
    }

    public StepResult step(StepRequest request) {
        request.validate();
        requireProject(request.projectName());
        DebugThreadSelection selection = findThread(request.projectName(), request.threadId(), true);
        if (selection == null) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "No suspended debug thread found for project " + request.projectName(), true); //$NON-NLS-1$
        }
        String kind = request.normalizedKind();
        String capability = switch (kind) {
            case "into" -> "canStepInto"; //$NON-NLS-1$ //$NON-NLS-2$
            case "over" -> "canStepOver"; //$NON-NLS-1$ //$NON-NLS-2$
            case "out" -> "canStepReturn"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> "canStepOver"; //$NON-NLS-1$
        };
        String operation = switch (kind) {
            case "into" -> "stepInto"; //$NON-NLS-1$ //$NON-NLS-2$
            case "over" -> "stepOver"; //$NON-NLS-1$ //$NON-NLS-2$
            case "out" -> "stepReturn"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> "stepOver"; //$NON-NLS-1$
        };
        if (!booleanValue(invokeOptional(selection.thread(), capability), true)) {
            return new StepResult(request.projectName(), selection.threadId(), kind,
                    "not_available", "Selected thread cannot perform " + kind); //$NON-NLS-1$ //$NON-NLS-2$
        }
        invoke(selection.thread(), operation);
        return new StepResult(request.projectName(), selection.threadId(), kind,
                "started", "Step command sent"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public ResumeResult resume(ResumeRequest request) {
        request.validate();
        requireProject(request.projectName());
        DebugThreadSelection selection = findThread(request.projectName(), request.threadId(), false);
        if (selection == null) {
            throw new EdtAstException(EdtAstErrorCode.INVALID_ARGUMENT,
                    "No debug thread found for project " + request.projectName(), true); //$NON-NLS-1$
        }
        Object resumable = selection.thread();
        if (!booleanValue(invokeOptional(resumable, "canResume"), true)) { //$NON-NLS-1$
            resumable = selection.target();
        }
        if (!booleanValue(invokeOptional(resumable, "canResume"), true)) { //$NON-NLS-1$
            return new ResumeResult(request.projectName(), selection.threadId(),
                    "not_available", "Debug target cannot resume"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        invoke(resumable, "resume"); //$NON-NLS-1$
        return new ResumeResult(request.projectName(), selection.threadId(),
                "resumed", "Resume command sent"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public EvaluateExpressionResult evaluateExpression(EvaluateExpressionRequest request) {
        request.validate();
        requireProject(request.projectName());
        findFrame(request.projectName(), request.threadId(), request.frameId());
        throw unsupported("evaluate_expression requires EDT expression evaluation service binding"); //$NON-NLS-1$
    }

    public DebugStatusResult debugStatus(DebugStatusRequest request) {
        request.validate();
        if (request.projectName() != null) {
            requireProject(request.projectName());
        }
        Object[] launches = getLaunches();
        int launchCount = 0;
        int targetCount = 0;
        boolean suspended = false;
        String activeThreadId = null;
        for (Object launch : launches) {
            if (!matchesProject(launch, request.projectName())) {
                continue;
            }
            launchCount++;
            for (Object target : toArray(invokeOptional(launch, "getDebugTargets"))) { //$NON-NLS-1$
                targetCount++;
                for (Object thread : toArray(invokeOptional(target, "getThreads"))) { //$NON-NLS-1$
                    if (booleanValue(invokeOptional(thread, "isSuspended"), false)) { //$NON-NLS-1$
                        suspended = true;
                        activeThreadId = debugObjectId(thread);
                        break;
                    }
                }
            }
        }
        int breakpointCount = listBreakpointInfos(request.projectName()).size();
        String state = suspended ? "suspended" : (targetCount > 0 ? "running" : "inactive"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return new DebugStatusResult(request.projectName(), state, suspended, launchCount, targetCount,
                breakpointCount, activeThreadId, "Debug status collected"); //$NON-NLS-1$
    }

    private List<BreakpointInfo> listBreakpointInfos(String projectName) {
        List<BreakpointInfo> result = new ArrayList<>();
        for (Object breakpoint : getBreakpoints()) {
            BreakpointInfo info = toBreakpointInfo(breakpoint);
            if (info != null && (projectName == null || projectName.equals(info.projectName()))) {
                result.add(info);
            }
        }
        return result;
    }

    private Object findBreakpoint(String projectName, String breakpointId, String filePath, Integer line) {
        for (Object breakpoint : getBreakpoints()) {
            BreakpointInfo info = toBreakpointInfo(breakpoint);
            if (info == null || !projectName.equals(info.projectName())) {
                continue;
            }
            if (breakpointId != null && breakpointId.equals(info.id())) {
                return breakpoint;
            }
            if (filePath != null && line != null && filePath.equals(info.filePath()) && line.intValue() == info.line()) {
                return breakpoint;
            }
        }
        return null;
    }

    private BreakpointInfo toBreakpointInfo(Object breakpoint) {
        try {
            Object marker = invokeOptional(breakpoint, "getMarker"); //$NON-NLS-1$
            if (marker == null) {
                return null;
            }
            Object resource = invokeOptional(marker, "getResource"); //$NON-NLS-1$
            Object project = resource == null ? null : invokeOptional(resource, "getProject"); //$NON-NLS-1$
            String projectName = project == null ? null : stringValue(invokeOptional(project, "getName")); //$NON-NLS-1$
            String filePath = resource == null ? null : stringValue(invokeOptional(resource, "getProjectRelativePath")); //$NON-NLS-1$
            int line = intValue(invokeOptional(marker, "getAttribute", "lineNumber", Integer.valueOf(-1)), -1); //$NON-NLS-1$ //$NON-NLS-2$
            String id = stringValue(invokeOptional(marker, "getId")); //$NON-NLS-1$
            boolean enabled = booleanValue(invokeOptional(breakpoint, "isEnabled"), true); //$NON-NLS-1$
            String condition = stringValue(invokeOptional(breakpoint, "getCondition")); //$NON-NLS-1$
            String model = stringValue(invokeOptional(breakpoint, "getModelIdentifier")); //$NON-NLS-1$
            return new BreakpointInfo(id, projectName, filePath, line, enabled, condition, model);
        } catch (RuntimeException e) {
            LOG.debug("Skipping unreadable breakpoint: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private DebugThreadSelection findThread(String projectName, String threadId, boolean requireSuspended) {
        for (Object launch : getLaunches()) {
            if (!matchesProject(launch, projectName)) {
                continue;
            }
            for (Object target : toArray(invokeOptional(launch, "getDebugTargets"))) { //$NON-NLS-1$
                for (Object thread : toArray(invokeOptional(target, "getThreads"))) { //$NON-NLS-1$
                    String candidateId = debugObjectId(thread);
                    String candidateName = stringValue(invokeOptional(thread, "getName")); //$NON-NLS-1$
                    boolean idMatches = threadId == null || threadId.equals(candidateId) || threadId.equals(candidateName);
                    boolean stateMatches = !requireSuspended
                            || booleanValue(invokeOptional(thread, "isSuspended"), false); //$NON-NLS-1$
                    if (idMatches && stateMatches) {
                        return new DebugThreadSelection(launch, target, thread, candidateId);
                    }
                }
            }
        }
        return null;
    }

    private DebugFrameSelection findFrame(String projectName, String threadId, String frameId) {
        DebugThreadSelection thread = findThread(projectName, threadId, true);
        if (thread == null) {
            return null;
        }
        for (Object frame : toArray(invoke(thread.thread(), "getStackFrames"))) { //$NON-NLS-1$
            String candidateId = debugObjectId(frame);
            String candidateName = stringValue(invokeOptional(frame, "getName")); //$NON-NLS-1$
            if (frameId == null || frameId.equals(candidateId) || frameId.equals(candidateName)) {
                return new DebugFrameSelection(thread.threadId(), candidateId, frame);
            }
        }
        return null;
    }

    private DebugVariableInfo toVariableInfo(Object variable) {
        String name = stringValue(invokeOptional(variable, "getName")); //$NON-NLS-1$
        String type = stringValue(invokeOptional(variable, "getReferenceTypeName")); //$NON-NLS-1$
        Object value = invokeOptional(variable, "getValue"); //$NON-NLS-1$
        String valueText = value == null ? null : stringValue(invokeOptional(value, "getValueString")); //$NON-NLS-1$
        if (type == null && value != null) {
            type = stringValue(invokeOptional(value, "getReferenceTypeName")); //$NON-NLS-1$
        }
        boolean hasChildren = value != null && booleanValue(invokeOptional(value, "hasVariables"), false); //$NON-NLS-1$
        return new DebugVariableInfo(name, type, valueText, hasChildren);
    }

    private Object[] getBreakpoints() {
        Object plugin = getDebugPlugin();
        Object manager = invoke(plugin, "getBreakpointManager"); //$NON-NLS-1$
        return toArray(invoke(manager, "getBreakpoints")); //$NON-NLS-1$
    }

    private Object[] getLaunches() {
        Object plugin = getDebugPlugin();
        Object manager = invoke(plugin, "getLaunchManager"); //$NON-NLS-1$
        return toArray(invoke(manager, "getLaunches")); //$NON-NLS-1$
    }

    private Object getDebugPlugin() {
        try {
            Bundle bundle = Platform.getBundle(DEBUG_CORE_BUNDLE);
            if (bundle == null) {
                throw serviceUnavailable("Eclipse debug core bundle is unavailable"); //$NON-NLS-1$
            }
            Class<?> debugPluginClass = bundle.loadClass(DEBUG_PLUGIN_CLASS);
            Object plugin = invokeStatic(debugPluginClass, "getDefault"); //$NON-NLS-1$
            if (plugin == null) {
                throw serviceUnavailable("Eclipse debug plugin is not initialized"); //$NON-NLS-1$
            }
            return plugin;
        } catch (ClassNotFoundException e) {
            throw serviceUnavailable("Eclipse debug plugin class is unavailable", e); //$NON-NLS-1$
        }
    }

    private boolean matchesProject(Object launch, String projectName) {
        if (projectName == null) {
            return true;
        }
        Object configuration = invokeOptional(launch, "getLaunchConfiguration"); //$NON-NLS-1$
        if (configuration != null) {
            String configuredProject = stringValue(invokeOptional(configuration, "getAttribute", ATTR_PROJECT_NAME, "")); //$NON-NLS-1$ //$NON-NLS-2$
            if (projectName.equals(configuredProject)) {
                return true;
            }
        }
        String launchName = stringValue(invokeOptional(launch, "getLaunchMode")); //$NON-NLS-1$
        return launchName != null && launchName.contains(projectName);
    }

    private IProject requireProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new EdtAstException(EdtAstErrorCode.PROJECT_NOT_FOUND,
                    "EDT project not found: " + projectName, true); //$NON-NLS-1$
        }
        return project;
    }

    private static Object invokeStatic(Class<?> type, String methodName, Object... args) {
        return invokeMethod(null, type, methodName, args, false);
    }

    private static Object invoke(Object target, String methodName, Object... args) {
        if (target == null) {
            throw new IllegalArgumentException("Cannot call " + methodName + " on null"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return invokeMethod(target, target.getClass(), methodName, args, false);
    }

    private static Object invokeOptional(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        return invokeMethod(target, target.getClass(), methodName, args, true);
    }

    private static Object invokeMethod(Object target, Class<?> type, String methodName, Object[] args, boolean optional) {
        Method method = findMethod(type, methodName, args);
        if (method == null) {
            if (optional) {
                return null;
            }
            throw new IllegalArgumentException("Method not found: " + type.getName() + "." + methodName); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            if (optional) {
                return null;
            }
            throw new IllegalStateException("Cannot access method: " + methodName, e); //$NON-NLS-1$
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (optional) {
                return null;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Method failed: " + methodName, cause); //$NON-NLS-1$
        }
    }

    private static Method findMethod(Class<?> type, String methodName, Object[] args) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            if (parametersMatch(method.getParameterTypes(), args)) {
                return method;
            }
        }
        return null;
    }

    private static boolean parametersMatch(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            if (args[i] == null) {
                continue;
            }
            Class<?> expected = wrap(parameterTypes[i]);
            if (!expected.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static Object[] toArray(Object value) {
        if (value == null) {
            return new Object[0];
        }
        if (value instanceof Object[] array) {
            return array;
        }
        if (!value.getClass().isArray()) {
            return new Object[0];
        }
        int length = Array.getLength(value);
        Object[] array = new Object[length];
        for (int i = 0; i < length; i++) {
            array[i] = Array.get(value, i);
        }
        return array;
    }

    private static String debugObjectId(Object value) {
        if (value == null) {
            return null;
        }
        return value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value)); //$NON-NLS-1$
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        return value instanceof Boolean bool ? bool.booleanValue() : defaultValue;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "Interrupted while waiting for debug break", true, e); //$NON-NLS-1$
        }
    }

    private static EdtAstException unsupported(String message) {
        return new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE, message, true);
    }

    private static EdtAstException serviceUnavailable(String message) {
        return new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE, message, true);
    }

    private static EdtAstException serviceUnavailable(String message, Throwable cause) {
        return new EdtAstException(EdtAstErrorCode.EDT_SERVICE_UNAVAILABLE, message, true, cause);
    }

    private static EdtAstException internal(String message, Throwable cause) {
        return new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR, message, false, cause);
    }

    private record DebugThreadSelection(Object launch, Object target, Object thread, String threadId) {
    }

    private record DebugFrameSelection(String threadId, String frameId, Object frame) {
    }
}
