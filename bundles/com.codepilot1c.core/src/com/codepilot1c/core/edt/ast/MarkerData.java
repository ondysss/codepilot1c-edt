/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

/**
 * Marker data representation.
 */
public class MarkerData {

    private final String resource;
    private final int line;
    private final String message;
    private final String type;
    private final String priority;
    private final String markerType;

    public MarkerData(String resource, int line, String message, String type,
                     String priority, String markerType) {
        this.resource = resource;
        this.line = line;
        this.message = message;
        this.type = type;
        this.priority = priority;
        this.markerType = markerType;
    }

    public String getResource() {
        return resource;
    }

    public int getLine() {
        return line;
    }

    public String getMessage() {
        return message;
    }

    public String getType() {
        return type;
    }

    public String getPriority() {
        return priority;
    }

    public String getMarkerType() {
        return markerType;
    }
}
