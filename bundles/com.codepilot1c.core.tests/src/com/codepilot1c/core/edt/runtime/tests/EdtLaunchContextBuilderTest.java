package com.codepilot1c.core.edt.runtime.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

import com.codepilot1c.core.edt.runtime.EdtLaunchConfigurationService;
import com.codepilot1c.core.edt.runtime.EdtLaunchContextBuilder;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;

public class EdtLaunchContextBuilderTest {

    @Test
    public void preservesFallbackWhenLaunchUsesInfobaseAccess() {
        EdtRuntimeService.AccessSettings fallback = EdtRuntimeService.AccessSettings.infobaseAuthentication(
                "admin", "secret", "/UseHWLicenses"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        EdtLaunchConfigurationService.LaunchConfigurationSettings launchConfig =
                new EdtLaunchConfigurationService.LaunchConfigurationSettings(
                        new File("/tmp/test.launch"), "test", //$NON-NLS-1$ //$NON-NLS-2$
                        EdtLaunchConfigurationService.RUNTIME_CLIENT_TYPE,
                        "Demo", false, null, null, true, null, true, false, true); //$NON-NLS-1$

        EdtRuntimeService.AccessSettings merged =
                EdtLaunchContextBuilder.mergeLaunchAccessSettings(fallback, launchConfig);

        assertEquals("admin", merged.getUserName()); //$NON-NLS-1$
        assertEquals("secret", merged.getPassword()); //$NON-NLS-1$
        assertEquals("/UseHWLicenses", merged.getAdditionalParameters()); //$NON-NLS-1$
    }

    @Test
    public void switchesToOsAuthenticationWhenLaunchRequiresIt() {
        EdtRuntimeService.AccessSettings fallback = EdtRuntimeService.AccessSettings.infobaseAuthentication(
                "admin", "secret", "/UseHWLicenses"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        EdtLaunchConfigurationService.LaunchConfigurationSettings launchConfig =
                new EdtLaunchConfigurationService.LaunchConfigurationSettings(
                        new File("/tmp/test.launch"), "test", //$NON-NLS-1$ //$NON-NLS-2$
                        EdtLaunchConfigurationService.RUNTIME_CLIENT_TYPE,
                        "Demo", false, null, null, false, null, false, true, true); //$NON-NLS-1$

        EdtRuntimeService.AccessSettings merged =
                EdtLaunchContextBuilder.mergeLaunchAccessSettings(fallback, launchConfig);

        assertTrue(merged.isOsAuthentication());
        assertEquals("/UseHWLicenses", merged.getAdditionalParameters()); //$NON-NLS-1$
    }

    @Test
    public void keepsPasswordWhenLaunchUserMatchesFallbackUser() {
        EdtRuntimeService.AccessSettings fallback = EdtRuntimeService.AccessSettings.infobaseAuthentication(
                "admin", "secret", "/UseHWLicenses"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        EdtLaunchConfigurationService.LaunchConfigurationSettings launchConfig =
                new EdtLaunchConfigurationService.LaunchConfigurationSettings(
                        new File("/tmp/test.launch"), "test", //$NON-NLS-1$ //$NON-NLS-2$
                        EdtLaunchConfigurationService.RUNTIME_CLIENT_TYPE,
                        "Demo", false, null, null, false, "admin", false, false, true); //$NON-NLS-1$ //$NON-NLS-2$

        EdtRuntimeService.AccessSettings merged =
                EdtLaunchContextBuilder.mergeLaunchAccessSettings(fallback, launchConfig);

        assertTrue(merged.isInfobaseAuthentication());
        assertEquals("admin", merged.getUserName()); //$NON-NLS-1$
        assertEquals("secret", merged.getPassword()); //$NON-NLS-1$
    }
}
