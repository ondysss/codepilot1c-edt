package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Eval for {@link EdtModelFileGuard}: the text tools must recognize the command interface file in
 * any spelling of the extension and must not catch neighbours. Wiring into edit_file/write_file is
 * checked live on the installed build (the call is refused before the file is touched).
 */
public class EdtModelFileGuardTest {

    private static final String REAL =
            "demo/src/Subsystems/Служебная/Subsystems/Обработки/CommandInterface.cmi"; //$NON-NLS-1$

    @Test
    public void recognizesCommandInterfaceFiles() {
        assertTrue(EdtModelFileGuard.isCommandInterfacePath(REAL));
        assertTrue(EdtModelFileGuard.isCommandInterfacePath("/demo/src/Configuration/CommandInterface.cmi")); //$NON-NLS-1$
        assertTrue(EdtModelFileGuard.isCommandInterfacePath("demo/src/Subsystems/Приемка/COMMANDINTERFACE.CMI")); //$NON-NLS-1$
        assertTrue(EdtModelFileGuard.isCommandInterfacePath(REAL + "  ")); //$NON-NLS-1$
    }

    @Test
    public void doesNotCatchOtherFiles() {
        assertFalse(EdtModelFileGuard.isCommandInterfacePath(null));
        assertFalse(EdtModelFileGuard.isCommandInterfacePath("")); //$NON-NLS-1$
        assertFalse(EdtModelFileGuard.isCommandInterfacePath(
                "demo/src/Subsystems/Служебная/Subsystems/Обработки/Обработки.mdo")); //$NON-NLS-1$
        assertFalse(EdtModelFileGuard.isCommandInterfacePath("demo/src/CommonModules/Общий/Module.bsl")); //$NON-NLS-1$
        assertFalse(EdtModelFileGuard.isCommandInterfacePath(REAL + ".bak")); //$NON-NLS-1$
        assertFalse(EdtModelFileGuard.isCommandInterfacePath("docs/cmi-notes.md")); //$NON-NLS-1$
    }

    @Test
    public void refusalPointsToTheSemanticToolsAndMentionsOverrideOnlyWhenThereIsOne() {
        String withOverride = EdtModelFileGuard.commandInterfaceRefusal("allow_metadata_descriptor_edit"); //$NON-NLS-1$
        assertTrue(withOverride.contains("inspect_command_interface")); //$NON-NLS-1$
        assertTrue(withOverride.contains("mutate_command_interface")); //$NON-NLS-1$
        assertTrue(withOverride.contains("allow_metadata_descriptor_edit=true")); //$NON-NLS-1$

        String withoutOverride = EdtModelFileGuard.commandInterfaceRefusal(null);
        assertTrue(withoutOverride.contains("mutate_command_interface")); //$NON-NLS-1$
        assertFalse(withoutOverride.contains("=true")); //$NON-NLS-1$
    }
}
