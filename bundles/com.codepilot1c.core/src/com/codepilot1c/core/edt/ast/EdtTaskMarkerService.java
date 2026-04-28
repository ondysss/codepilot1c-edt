/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

/**
 * Service for retrieving bookmarks and task markers from EDT projects.
 * Uses Eclipse Platform IMarker API.
 */
public class EdtTaskMarkerService {

    private static final Pattern TASK_TOKEN_PATTERN =
            Pattern.compile("\\b(TODO|FIXME|XXX|HACK|NOTE)\\b", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private final EdtServiceGateway gateway;
    private final ProjectReadinessChecker readinessChecker;

    public EdtTaskMarkerService(EdtServiceGateway gateway, ProjectReadinessChecker readinessChecker) {
        this.gateway = gateway;
        this.readinessChecker = readinessChecker;
    }

    /**
     * Retrieves bookmarks from the specified project.
     *
     * @param request The bookmarks request
     * @return bookmarks result
     */
    public GetBookmarksResult getBookmarks(GetBookmarksRequest request) {
        try {
            request.validate();
            IProject project = resolveReadyProject(request.getProjectName());
            MarkerSearchResult found = findMarkers(project, IMarker.BOOKMARK, "bookmark", request.getLimit()); //$NON-NLS-1$

            return new GetBookmarksResult(request.getProjectName(), found.total(),
                    found.hasMore(), found.markers());
        } catch (EdtAstException e) {
            throw e;
        } catch (Exception e) {
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "Failed to get bookmarks: " + e.getMessage(), false, e); //$NON-NLS-1$
        }
    }

    /**
     * Retrieves task markers (TODO, FIXME, etc.) from the specified project.
     *
     * @param request The tasks request
     * @return task markers result
     */
    public GetTasksResult getTasks(GetTasksRequest request) {
        try {
            request.validate();
            IProject project = resolveReadyProject(request.getProjectName());
            MarkerSearchResult found = findMarkers(project, IMarker.TASK, "task", request.getLimit()); //$NON-NLS-1$

            return new GetTasksResult(request.getProjectName(), found.total(),
                    found.hasMore(), found.markers());
        } catch (EdtAstException e) {
            throw e;
        } catch (Exception e) {
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "Failed to get tasks: " + e.getMessage(), false, e); //$NON-NLS-1$
        }
    }

    private IProject resolveReadyProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        readinessChecker.ensureReady(project);
        return project;
    }

    /**
     * Finds markers of the specified type in a project.
     *
     * @param project The EDT project
     * @param markerType The marker type (IMarker.BOOKMARK or IMarker.TASK)
     * @param limit Maximum number of markers to return
     * @return List of marker data
     */
    private MarkerSearchResult findMarkers(IProject project, String markerType, String type, int limit) {
        List<MarkerData> markers = new ArrayList<>();
        try {
            IMarker[] found = project.findMarkers(markerType, true,
                    IResource.DEPTH_INFINITE);
            List<MarkerData> all = new ArrayList<>(found.length);

            for (IMarker marker : found) {
                String resourcePath = getMarkerResourcePath(marker);
                int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                String message = marker.getAttribute(IMarker.MESSAGE, ""); //$NON-NLS-1$
                String priority = getPriorityLabel(marker);
                String markerTypeLabel = getMarkerTypeLabel(marker, type, message);

                all.add(new MarkerData(resourcePath, line, message, type,
                        priority, markerTypeLabel));
            }
            all.sort(Comparator
                    .comparing(MarkerData::getResource, String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(MarkerData::getLine)
                    .thenComparing(MarkerData::getMessage, String.CASE_INSENSITIVE_ORDER));
            int total = all.size();
            int returned = Math.min(limit, total);
            markers.addAll(all.subList(0, returned));
            return new MarkerSearchResult(total, total > returned, markers);
        } catch (CoreException e) {
            throw new EdtAstException(EdtAstErrorCode.INTERNAL_ERROR,
                    "Failed to find markers: " + e.getMessage(), false, e); //$NON-NLS-1$
        }
    }

    /**
     * Extracts the resource path relative to project from a marker.
     */
    private String getMarkerResourcePath(IMarker marker) {
        IResource resource = marker.getResource();
        if (resource == null) {
            return ""; //$NON-NLS-1$
        }
        return resource.getProjectRelativePath().toString();
    }

    /**
     * Returns priority label for a marker.
     */
    private String getPriorityLabel(IMarker marker) {
        int priority = marker.getAttribute(IMarker.PRIORITY, IMarker.PRIORITY_NORMAL);
        if (priority == IMarker.PRIORITY_HIGH) {
            return "P_HIGH"; //$NON-NLS-1$
        } else if (priority == IMarker.PRIORITY_LOW) {
            return "P_LOW"; //$NON-NLS-1$
        } else {
            return "P_NORMAL"; //$NON-NLS-1$
        }
    }

    /**
     * Returns marker type label (TODO, FIXME, etc.) for task markers.
     */
    private String getMarkerTypeLabel(IMarker marker, String type, String message) throws CoreException {
        if ("bookmark".equals(type)) { //$NON-NLS-1$
            return "bookmark"; //$NON-NLS-1$
        }
        String taskToken = extractTaskToken(message);
        if (taskToken != null) {
            return taskToken;
        }
        String eclipseMarkerType = marker.getType();
        return eclipseMarkerType == null || eclipseMarkerType.isBlank() ? "task" : eclipseMarkerType; //$NON-NLS-1$
    }

    private String extractTaskToken(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = TASK_TOKEN_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private record MarkerSearchResult(int total, boolean hasMore, List<MarkerData> markers) {
    }
}
