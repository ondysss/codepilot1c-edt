package com.codepilot1c.core.mcp.host.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

public class RemoteWebControllerTest {

    @Test
    public void sessionCookieIsSerializedWithoutQuotesAndParsedDefensively() {
        String cookie = RemoteWebController.buildSessionCookie("token-123", true); //$NON-NLS-1$

        assertEquals(
                "CP_REMOTE_SESSION=token-123; Path=/remote/api; HttpOnly; SameSite=Lax; Max-Age=28800", //$NON-NLS-1$
                cookie);

        Map<String, String> parsed = RemoteWebController.parseCookies("CP_REMOTE_SESSION=\"token-123\"; theme=dark"); //$NON-NLS-1$
        assertEquals("token-123", parsed.get("CP_REMOTE_SESSION")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("dark", parsed.get("theme")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void logoutCookieExpiresImmediately() {
        String cookie = RemoteWebController.buildSessionCookie("", false); //$NON-NLS-1$

        assertTrue(cookie.contains("CP_REMOTE_SESSION=")); //$NON-NLS-1$
        assertTrue(cookie.contains("Max-Age=0")); //$NON-NLS-1$
        assertTrue(cookie.contains("Path=/remote/api")); //$NON-NLS-1$
    }
}
