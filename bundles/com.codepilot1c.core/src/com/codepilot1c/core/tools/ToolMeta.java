/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative metadata for tool implementations.
 *
 * <p>Replaces the triple duplication of tool metadata across
 * ToolRegistry, ToolDescriptorRegistry, and BuiltinToolTaxonomy.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * {@literal @}ToolMeta(
 *     name = "read_file",
 *     category = "file",
 *     mutating = false,
 *     tags = {"read-only", "workspace"}
 * )
 * public class ReadFileTool extends AbstractTool { ... }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolMeta {

    /**
     * Tool name used in API calls.
     */
    String name();

    /**
     * Category for surface grouping.
     */
    String category() default "general";

    /**
     * Whether this tool mutates project state.
     */
    boolean mutating() default false;

    /**
     * Whether a validation token is required for EDT mutations.
     */
    boolean requiresValidationToken() default false;

    /**
     * Tags for classification and filtering.
     */
    String[] tags() default {};

    /**
     * Surface category for UI grouping.
     */
    String surfaceCategory() default "";
}
