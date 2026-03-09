package com.codepilot1c.core.edt.runtime.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.codepilot1c.core.edt.runtime.EdtLaunchConfigurationService;

public class EdtLaunchConfigurationServiceTest {

    @Test
    public void resolvesRuntimeClientConfigurationForProject() throws Exception {
        Path workspace = Files.createTempDirectory("edt-launch-workspace"); //$NON-NLS-1$
        Path launchesDir = workspace.resolve(".metadata/.plugins/org.eclipse.debug.core/.launches"); //$NON-NLS-1$
        Files.createDirectories(launchesDir);

        Files.writeString(launchesDir.resolve("test.launch"), """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <launchConfiguration type="com._1c.g5.v8.dt.launching.core.RuntimeClient">
                    <stringAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME" value="test"/>
                    <stringAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION" value="com._1c.g5.v8.dt.platform.services.core.resolvableInstallations.fixed:com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform=8.5.1.1150=Undefined"/>
                    <booleanAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION_USE_AUTO" value="false"/>
                    <stringAttribute key="com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_NAME" value="admin"/>
                    <booleanAttribute key="com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS" value="false"/>
                    <booleanAttribute key="com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_OS_INFOBASE_ACCESS" value="false"/>
                    <booleanAttribute key="com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_AUTO_SELECT" value="true"/>
                </launchConfiguration>
                """, StandardCharsets.UTF_8);

        EdtLaunchConfigurationService service = new EdtLaunchConfigurationService();
        var config = service.resolveRuntimeClientConfiguration("test", workspace.toFile()); //$NON-NLS-1$

        assertNotNull(config);
        assertEquals("test", config.name()); //$NON-NLS-1$
        assertEquals("test", config.projectName()); //$NON-NLS-1$
        assertEquals("8.5.1.1150", config.runtimeVersion()); //$NON-NLS-1$
        assertEquals("admin", config.launchUserName()); //$NON-NLS-1$
        assertFalse(config.launchUserUseInfobaseAccess());
    }

    @Test
    public void extractsRuntimeVersionMaskFromEnvironmentLaunchConfiguration() throws Exception {
        Path workspace = Files.createTempDirectory("edt-launch-workspace-mask"); //$NON-NLS-1$
        Path launchesDir = workspace.resolve(".metadata/.plugins/org.eclipse.debug.core/.launches"); //$NON-NLS-1$
        Files.createDirectories(launchesDir);

        Files.writeString(launchesDir.resolve("test.launch"), """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <launchConfiguration type="com._1c.g5.v8.dt.launching.core.RuntimeClient">
                    <stringAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME" value="test"/>
                    <stringAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION" value="com._1c.g5.v8.dt.platform.services.core.resolvableInstallations.environments:com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform=8.5"/>
                    <booleanAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION_USE_AUTO" value="false"/>
                </launchConfiguration>
                """, StandardCharsets.UTF_8);

        EdtLaunchConfigurationService service = new EdtLaunchConfigurationService();
        var config = service.resolveRuntimeClientConfiguration("test", workspace.toFile()); //$NON-NLS-1$

        assertNotNull(config);
        assertEquals("8.5", config.runtimeVersion()); //$NON-NLS-1$
    }

    @Test
    public void prefersNonPrivateLaunchConfiguration() throws Exception {
        Path workspace = Files.createTempDirectory("edt-launch-workspace-private"); //$NON-NLS-1$
        Path launchesDir = workspace.resolve(".metadata/.plugins/org.eclipse.debug.core/.launches"); //$NON-NLS-1$
        Files.createDirectories(launchesDir);

        Files.writeString(launchesDir.resolve("test-private.launch"), """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <launchConfiguration type="com._1c.g5.v8.dt.launching.core.RuntimeClient">
                    <stringAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME" value="test"/>
                    <booleanAttribute key="org.eclipse.debug.core.ATTR_PRIVATE" value="true"/>
                </launchConfiguration>
                """, StandardCharsets.UTF_8);
        Files.writeString(launchesDir.resolve("test.launch"), """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <launchConfiguration type="com._1c.g5.v8.dt.launching.core.RuntimeClient">
                    <stringAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME" value="test"/>
                    <booleanAttribute key="org.eclipse.debug.core.ATTR_PRIVATE" value="false"/>
                </launchConfiguration>
                """, StandardCharsets.UTF_8);

        var config = new EdtLaunchConfigurationService().resolveRuntimeClientConfiguration("test", workspace.toFile()); //$NON-NLS-1$

        assertNotNull(config);
        assertEquals("test", config.name()); //$NON-NLS-1$
        assertFalse(config.isPrivate());
    }

    @Test
    public void returnsNullRuntimeVersionWhenAttributeMissing() throws Exception {
        Path workspace = Files.createTempDirectory("edt-launch-workspace-no-runtime"); //$NON-NLS-1$
        Path launchesDir = workspace.resolve(".metadata/.plugins/org.eclipse.debug.core/.launches"); //$NON-NLS-1$
        Files.createDirectories(launchesDir);

        Files.writeString(launchesDir.resolve("test.launch"), """
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <launchConfiguration type="com._1c.g5.v8.dt.launching.core.RuntimeClient">
                    <stringAttribute key="com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME" value="test"/>
                </launchConfiguration>
                """, StandardCharsets.UTF_8);

        var config = new EdtLaunchConfigurationService().resolveRuntimeClientConfiguration("test", workspace.toFile()); //$NON-NLS-1$

        assertNotNull(config);
        assertNull(config.runtimeVersion());
        assertTrue(config.clientAutoSelect());
    }
}
