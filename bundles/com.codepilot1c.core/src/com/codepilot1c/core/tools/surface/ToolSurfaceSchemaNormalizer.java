/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Normalizes model-facing JSON schema without changing runtime parser behavior.
 */
final class ToolSurfaceSchemaNormalizer {

    private static final Gson GSON = new Gson();

    private ToolSurfaceSchemaNormalizer() {
    }

    static String normalizeBuiltIn(String toolName, String rawSchema) {
        String override = switch (toolName) {
            case "read_file" -> """
                    {
                      "type": "object",
                      "properties": {
                        "path": {
                          "type": "string",
                          "description": "Existing workspace file path. Use a workspace-relative path whenever possible."
                        },
                        "start_line": {
                          "type": "integer",
                          "minimum": 1,
                          "description": "1-based inclusive start line for partial reads."
                        },
                        "end_line": {
                          "type": "integer",
                          "minimum": 1,
                          "description": "1-based inclusive end line for partial reads."
                        }
                      },
                      "required": ["path"],
                      "additionalProperties": false
                    }
                    """; //$NON-NLS-1$
            case "list_files" -> """
                    {
                      "type": "object",
                      "properties": {
                        "path": {
                          "type": "string",
                          "description": "Workspace-relative directory path. Omit to list top-level projects."
                        },
                        "pattern": {
                          "type": "string",
                          "description": "Optional file-name glob such as *.bsl."
                        },
                        "recursive": {
                          "type": "boolean",
                          "description": "List descendants recursively."
                        }
                      },
                      "additionalProperties": false
                    }
                    """; //$NON-NLS-1$
            case "glob" -> """
                    {
                      "type": "object",
                      "properties": {
                        "pattern": {
                          "type": "string",
                          "description": "Glob pattern such as **/*.bsl or src/**/*.java."
                        },
                        "path": {
                          "type": "string",
                          "description": "Optional workspace-relative search root. Defaults to workspace root."
                        },
                        "max_results": {
                          "type": "integer",
                          "minimum": 1,
                          "maximum": 500,
                          "description": "Maximum number of matches to return."
                        },
                        "include_hidden": {
                          "type": "boolean",
                          "description": "Include hidden files and directories."
                        }
                      },
                      "required": ["pattern"],
                      "additionalProperties": false
                    }
                    """; //$NON-NLS-1$
            case "grep" -> """
                    {
                      "type": "object",
                      "properties": {
                        "pattern": {
                          "type": "string",
                          "description": "Plain-text search string or regex pattern."
                        },
                        "path": {
                          "type": "string",
                          "description": "Optional workspace-relative directory to search."
                        },
                        "file_pattern": {
                          "type": "string",
                          "description": "Optional file-name glob such as *.bsl."
                        },
                        "regex": {
                          "type": "boolean",
                          "description": "Interpret pattern as a regular expression."
                        },
                        "case_sensitive": {
                          "type": "boolean",
                          "description": "Use case-sensitive matching."
                        },
                        "context_lines": {
                          "type": "integer",
                          "minimum": 0,
                          "description": "Number of surrounding lines to include around each match."
                        }
                      },
                      "required": ["pattern"],
                      "additionalProperties": false
                    }
                    """; //$NON-NLS-1$
            case "edit_file" -> """
                    {
                      "type": "object",
                      "properties": {
                        "path": {
                          "type": "string",
                          "description": "Existing workspace-relative file path."
                        },
                        "content": {
                          "type": "string",
                          "description": "Full replacement content for the file."
                        },
                        "old_text": {
                          "type": "string",
                          "description": "Existing text to replace for a targeted edit."
                        },
                        "new_text": {
                          "type": "string",
                          "description": "Replacement text used with old_text."
                        },
                        "edits": {
                          "type": "string",
                          "description": "SEARCH/REPLACE block payload for one or more exact edits."
                        },
                        "create": {
                          "type": "boolean",
                          "description": "Deprecated and ignored; this tool edits existing files only."
                        },
                        "allow_metadata_descriptor_edit": {
                          "type": "boolean",
                          "description": "Emergency override for direct .mdo edits when BM APIs cannot be used."
                        }
                      },
                      "required": ["path"],
                      "additionalProperties": false
                    }
                    """; //$NON-NLS-1$
            case "write_file" -> """
                    {
                      "type": "object",
                      "properties": {
                        "path": {
                          "type": "string",
                          "description": "Existing workspace-relative file path."
                        },
                        "content": {
                          "type": "string",
                          "description": "Full file content to write."
                        },
                        "overwrite": {
                          "type": "boolean",
                          "description": "Must be true because write_file only overwrites existing files."
                        }
                      },
                      "required": ["path", "content"],
                      "additionalProperties": false
                    }
                    """; //$NON-NLS-1$
            case "edt_validate_request" -> """
                    {
                      "type": "object",
                      "properties": {
                        "project": {
                          "type": "string",
                          "description": "EDT project name."
                        },
                        "operation": {
                          "type": "string",
                          "enum": ["create_metadata", "create_form", "apply_form_recipe", "external_create_report", "external_create_processing", "extension_create_project", "extension_adopt_object", "extension_set_property_state", "dcs_create_main_schema", "dcs_upsert_query_dataset", "dcs_upsert_parameter", "dcs_upsert_calculated_field", "add_metadata_child", "ensure_module_artifact", "update_metadata", "delete_metadata", "mutate_form_model"],
                          "description": "Target mutating tool that will consume the issued validation_token."
                        },
                        "payload": {
                          "type": "object",
                          "description": "Exact arguments for the target operation, excluding validation_token.",
                          "additionalProperties": true
                        }
                      },
                      "required": ["project", "operation", "payload"],
                      "additionalProperties": false
                    }
                    """; //$NON-NLS-1$
            case "ensure_module_artifact" -> """
                    {
                      "type": "object",
                      "properties": {
                        "project": {
                          "type": "string",
                          "description": "EDT project name."
                        },
                        "object_fqn": {
                          "type": "string",
                          "description": "Metadata object FQN whose module artifact must exist."
                        },
                        "module_kind": {
                          "type": "string",
                          "enum": ["auto", "object", "objectmodule", "object_module", "manager", "managermodule", "manager_module", "module", "form", "formmodule", "form_module"],
                          "description": "Requested module artifact kind; aliases are accepted case-insensitively."
                        },
                        "create_if_missing": {
                          "type": "boolean",
                          "description": "Create the module file if it does not exist."
                        },
                        "initial_content": {
                          "type": "string",
                          "description": "Optional initial module content when the artifact is created."
                        },
                        "validation_token": {
                          "type": "string",
                          "description": "Unmodified token returned by edt_validate_request."
                        }
                      },
                      "required": ["project", "object_fqn", "validation_token"],
                      "additionalProperties": false
                    }
                    """; //$NON-NLS-1$
            default -> null;
        };
        return override != null ? override : harden(rawSchema);
    }

    static String normalizeDynamic(String rawSchema) {
        return harden(rawSchema);
    }

    private static String harden(String rawSchema) {
        if (rawSchema == null || rawSchema.isBlank()) {
            return rawSchema;
        }
        try {
            JsonElement root = JsonParser.parseString(rawSchema);
            hardenElement(root);
            return GSON.toJson(root);
        } catch (RuntimeException e) {
            return rawSchema;
        }
    }

    private static void hardenElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (definesClosedObjectShape(object)) {
                object.addProperty("additionalProperties", false); //$NON-NLS-1$
            }
            if (object.has("properties") && object.get("properties").isJsonObject()) { //$NON-NLS-1$
                for (JsonElement value : object.getAsJsonObject("properties").asMap().values()) { //$NON-NLS-1$
                    hardenElement(value);
                }
            }
            hardenChild(object, "items"); //$NON-NLS-1$
            hardenChild(object, "anyOf"); //$NON-NLS-1$
            hardenChild(object, "oneOf"); //$NON-NLS-1$
            hardenChild(object, "allOf"); //$NON-NLS-1$
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                hardenElement(child);
            }
        }
    }

    private static void hardenChild(JsonObject object, String key) {
        if (object.has(key)) {
            hardenElement(object.get(key));
        }
    }

    private static boolean definesClosedObjectShape(JsonObject object) {
        return "object".equals(typeOf(object)) //$NON-NLS-1$
                && object.has("properties") //$NON-NLS-1$
                && object.get("properties").isJsonObject() //$NON-NLS-1$
                && !object.has("additionalProperties"); //$NON-NLS-1$
    }

    private static String typeOf(JsonObject object) {
        JsonElement type = object.get("type"); //$NON-NLS-1$
        return type != null && type.isJsonPrimitive() ? type.getAsString() : null;
    }
}
