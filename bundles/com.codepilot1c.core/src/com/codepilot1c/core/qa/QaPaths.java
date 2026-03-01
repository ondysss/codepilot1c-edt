package com.codepilot1c.core.qa;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class QaPaths {

    private QaPaths() {
        // Utility class.
    }

    public static File resolve(String path, File workspaceRoot) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        if (isWindowsAbsolute(trimmed)) {
            return new File(trimmed);
        }
        Path raw = Paths.get(trimmed);
        if (raw.isAbsolute()) {
            return raw.toFile();
        }
        if (workspaceRoot == null) {
            return raw.toFile();
        }
        return new File(workspaceRoot, trimmed);
    }

    public static File resolveConfigFile(String path, File workspaceRoot, String defaultRelative) {
        if (path == null || path.isBlank()) {
            return resolve(defaultRelative, workspaceRoot);
        }
        return resolve(path, workspaceRoot);
    }

    public static boolean isWithinWorkspace(File workspaceRoot, File target) {
        if (workspaceRoot == null || target == null) {
            return false;
        }
        try {
            String rootPath = workspaceRoot.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            return targetPath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            String rootPath = workspaceRoot.getAbsolutePath();
            String targetPath = target.getAbsolutePath();
            return targetPath.startsWith(rootPath + File.separator);
        }
    }

    private static boolean isWindowsAbsolute(String path) {
        if (path == null || path.length() < 3) {
            return false;
        }
        char drive = path.charAt(0);
        char colon = path.charAt(1);
        char slash = path.charAt(2);
        return ((drive >= 'A' && drive <= 'Z') || (drive >= 'a' && drive <= 'z'))
                && colon == ':'
                && (slash == '\\' || slash == '/');
    }
}
