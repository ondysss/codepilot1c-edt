package com.codepilot1c.core.edt.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class EdtLaunchConfigurationService {

    public static final String RUNTIME_CLIENT_TYPE = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; //$NON-NLS-1$

    private static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$
    private static final String ATTR_RUNTIME_INSTALLATION =
            "com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION"; //$NON-NLS-1$
    private static final String ATTR_RUNTIME_INSTALLATION_USE_AUTO =
            "com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION_USE_AUTO"; //$NON-NLS-1$
    private static final String ATTR_LAUNCH_USER_NAME =
            "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_NAME"; //$NON-NLS-1$
    private static final String ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS =
            "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS"; //$NON-NLS-1$
    private static final String ATTR_LAUNCH_OS_INFOBASE_ACCESS =
            "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_OS_INFOBASE_ACCESS"; //$NON-NLS-1$
    private static final String ATTR_CLIENT_AUTO_SELECT =
            "com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_AUTO_SELECT"; //$NON-NLS-1$
    private static final String ATTR_PRIVATE = "org.eclipse.debug.core.ATTR_PRIVATE"; //$NON-NLS-1$

    private static final Pattern VERSION_PATTERN = Pattern.compile("=(\\d+(?:\\.\\d+){0,3})(?:=|$)"); //$NON-NLS-1$

    public record LaunchConfigurationSettings(
            File file,
            String name,
            String typeId,
            String projectName,
            boolean isPrivate,
            String runtimeInstallation,
            String runtimeVersion,
            boolean runtimeInstallationUseAuto,
            String launchUserName,
            boolean launchUserUseInfobaseAccess,
            boolean launchOsInfobaseAccess,
            boolean clientAutoSelect) {
    }

    public LaunchConfigurationSettings resolveRuntimeClientConfiguration(String projectName, File workspaceRoot)
            throws IOException {
        if (projectName == null || projectName.isBlank()) {
            return null;
        }
        File launchesDir = resolveLaunchesDir(workspaceRoot);
        if (launchesDir == null || !launchesDir.isDirectory()) {
            return null;
        }
        List<LaunchConfigurationSettings> matches = new ArrayList<>();
        File[] files = launchesDir.listFiles(pathname -> pathname.isFile()
                && pathname.getName().toLowerCase(Locale.ROOT).endsWith(".launch")); //$NON-NLS-1$
        if (files == null) {
            return null;
        }
        for (File file : files) {
            LaunchConfigurationSettings settings = parse(file);
            if (settings == null) {
                continue;
            }
            if (!RUNTIME_CLIENT_TYPE.equals(settings.typeId())) {
                continue;
            }
            if (!projectName.equals(settings.projectName())) {
                continue;
            }
            matches.add(settings);
        }
        return matches.stream()
                .sorted(Comparator
                        .comparing((LaunchConfigurationSettings item) -> item.isPrivate())
                        .thenComparing(item -> !projectName.equals(item.name()))
                        .thenComparing(item -> item.file().getName()))
                .findFirst()
                .orElse(null);
    }

    static File resolveLaunchesDir(File workspaceRoot) {
        if (workspaceRoot == null) {
            return null;
        }
        return new File(workspaceRoot, ".metadata/.plugins/org.eclipse.debug.core/.launches"); //$NON-NLS-1$
    }

    static LaunchConfigurationSettings parse(File file) throws IOException {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (InputStream inputStream = Files.newInputStream(file.toPath())) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false); //$NON-NLS-1$
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); //$NON-NLS-1$
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); //$NON-NLS-1$
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); //$NON-NLS-1$
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(inputStream);
            Element root = document.getDocumentElement();
            if (root == null || !"launchConfiguration".equals(root.getTagName())) { //$NON-NLS-1$
                return null;
            }
            String typeId = root.getAttribute("type"); //$NON-NLS-1$
            String runtimeInstallation = readStringAttribute(root, ATTR_RUNTIME_INSTALLATION);
            boolean useAuto = readBooleanAttribute(root, ATTR_RUNTIME_INSTALLATION_USE_AUTO, false);
            return new LaunchConfigurationSettings(
                    file,
                    trimLaunchName(file.getName()),
                    typeId,
                    readStringAttribute(root, ATTR_PROJECT_NAME),
                    readBooleanAttribute(root, ATTR_PRIVATE, false),
                    runtimeInstallation,
                    extractRuntimeVersion(runtimeInstallation),
                    useAuto,
                    readStringAttribute(root, ATTR_LAUNCH_USER_NAME),
                    readBooleanAttribute(root, ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS, true),
                    readBooleanAttribute(root, ATTR_LAUNCH_OS_INFOBASE_ACCESS, false),
                    readBooleanAttribute(root, ATTR_CLIENT_AUTO_SELECT, true));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse launch configuration: " + file.getAbsolutePath(), e); //$NON-NLS-1$
        }
    }

    static String extractRuntimeVersion(String runtimeInstallation) {
        if (runtimeInstallation == null || runtimeInstallation.isBlank()) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(runtimeInstallation);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String trimLaunchName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".launch")) { //$NON-NLS-1$
            return fileName.substring(0, fileName.length() - ".launch".length()); //$NON-NLS-1$
        }
        return fileName;
    }

    private static String readStringAttribute(Element root, String key) {
        Element element = findAttribute(root, "stringAttribute", key); //$NON-NLS-1$
        return element == null ? null : element.getAttribute("value"); //$NON-NLS-1$
    }

    private static boolean readBooleanAttribute(Element root, String key, boolean defaultValue) {
        Element element = findAttribute(root, "booleanAttribute", key); //$NON-NLS-1$
        return element == null ? defaultValue : Boolean.parseBoolean(element.getAttribute("value")); //$NON-NLS-1$
    }

    private static Element findAttribute(Element root, String tagName, String key) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            if (!tagName.equals(element.getTagName())) {
                continue;
            }
            if (key.equals(element.getAttribute("key"))) { //$NON-NLS-1$
                return element;
            }
        }
        return null;
    }
}
