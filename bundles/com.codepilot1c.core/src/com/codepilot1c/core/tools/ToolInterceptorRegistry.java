/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Singleton registry for {@link IToolInterceptor} instances.
 *
 * <p>Thread-safe: uses {@link CopyOnWriteArrayList} for storage.
 * Interceptors are returned sorted by {@link IToolInterceptor#getPriority()}
 * (lower values first).</p>
 */
public final class ToolInterceptorRegistry {

    private static final ToolInterceptorRegistry INSTANCE = new ToolInterceptorRegistry();

    private final CopyOnWriteArrayList<IToolInterceptor> interceptors = new CopyOnWriteArrayList<>();

    private ToolInterceptorRegistry() {
        // singleton
    }

    /**
     * Returns the singleton instance.
     *
     * @return the registry instance
     */
    public static ToolInterceptorRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers an interceptor. If the interceptor is already registered,
     * this method has no effect.
     *
     * @param interceptor the interceptor to register
     */
    public void register(IToolInterceptor interceptor) {
        if (interceptor != null && !interceptors.contains(interceptor)) {
            interceptors.add(interceptor);
        }
    }

    /**
     * Unregisters an interceptor.
     *
     * @param interceptor the interceptor to remove
     */
    public void unregister(IToolInterceptor interceptor) {
        interceptors.remove(interceptor);
    }

    /**
     * Returns all registered interceptors sorted by priority (ascending).
     * Lower priority values execute first.
     *
     * @return an unmodifiable sorted list of interceptors
     */
    public List<IToolInterceptor> getInterceptors() {
        return interceptors.stream()
                .sorted(Comparator.comparingInt(IToolInterceptor::getPriority))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Removes all registered interceptors. Intended for testing only.
     */
    public void clear() {
        interceptors.clear();
    }
}
