package com.codepilot1c.core.qa.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com.codepilot1c.core.qa.QaConfig;

public class QaConfigTest {

    @Test
    public void defaultConfigCreatesRunnableTestClient() {
        QaConfig config = QaConfig.defaultConfig("test"); //$NON-NLS-1$

        assertNotNull(config.test_clients);
        assertEquals(1, config.test_clients.size());
        assertNotNull(config.test_clients.get(0).port);
        assertEquals(Integer.valueOf(48111), config.test_clients.get(0).port);
    }
}
