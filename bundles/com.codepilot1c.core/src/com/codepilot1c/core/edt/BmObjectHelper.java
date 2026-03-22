/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt;

import java.lang.reflect.Method;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;

import com._1c.g5.v8.bm.core.IBmObject;

/**
 * Safe helpers for BM object access from EDT services.
 */
public final class BmObjectHelper {

    private BmObjectHelper() {
    }

    public static String safeTopFqn(IBmObject object) {
        IBmObject top = safeTopObject(object);
        if (top == null) {
            return ""; //$NON-NLS-1$
        }
        try {
            String fqn = top.bmGetFqn();
            return fqn != null ? fqn : ""; //$NON-NLS-1$
        } catch (RuntimeException e) {
            return ""; //$NON-NLS-1$
        }
    }

    public static IBmObject safeTopObject(IBmObject object) {
        if (object == null) {
            return null;
        }
        try {
            if (object.bmIsTransient()) {
                return null;
            }
            IBmObject top = object;
            if (!top.bmIsTop()) {
                top = top.bmGetTopObject();
            }
            if (top == null || top.bmIsTransient() || !top.bmIsTop()) {
                return null;
            }
            return top;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Integer safeId(Object candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            Method method = candidate.getClass().getMethod("getId"); //$NON-NLS-1$
            Object value = method.invoke(candidate);
            if (value instanceof Number number) {
                return Integer.valueOf(number.intValue());
            }
            if (value != null) {
                return Integer.valueOf(String.valueOf(value));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    public static URI safeUri(EObject object) {
        if (object == null) {
            return null;
        }
        return safeUri(object.eResource());
    }

    public static URI safeUri(Resource resource) {
        if (resource == null) {
            return null;
        }
        try {
            return resource.getURI();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String safeUriString(EObject object) {
        return safeUriString(safeUri(object));
    }

    public static String safeUriString(Resource resource) {
        return safeUriString(safeUri(resource));
    }

    public static String safeUriString(URI uri) {
        return uri != null ? uri.toString() : ""; //$NON-NLS-1$
    }
}
