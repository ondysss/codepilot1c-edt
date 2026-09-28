package com.codepilot1c.core.tools.file;

import java.util.Locale;

/**
 * Model files of EDT that the text tools must not rewrite, beyond the {@code .mdo}/{@code .form}
 * checks kept inside {@code EditFileTool} and {@code WriteTool}.
 *
 * <p>The command interface ({@code CommandInterface.cmi}) is an EDT model: a text edit bypasses BM,
 * and EDT keeps whatever the edit left behind. Stripping the {@code <command>} line of an entry while
 * keeping its {@code <visibilityFragments>} wrapper leaves an empty entry: EDT loads it into the model,
 * re-serializes it on the next save and reports "Команда для настройки видимости должна быть указана"
 * until the entry is removed. The semantic route is
 * {@code inspect_command_interface} / {@code mutate_command_interface}.</p>
 */
public final class EdtModelFileGuard {

    private EdtModelFileGuard() {
    }

    public static boolean isCommandInterfacePath(String path) {
        if (path == null) {
            return false;
        }
        return path.trim().toLowerCase(Locale.ROOT).endsWith(".cmi"); //$NON-NLS-1$
    }

    /**
     * @param overrideParameter name of the emergency override the calling tool accepts, or
     *                          {@code null} when the tool has none
     */
    public static String commandInterfaceRefusal(String overrideParameter) {
        StringBuilder message = new StringBuilder();
        message.append("❌ Прямая правка командного интерфейса (.cmi) заблокирована: это модель EDT, ") //$NON-NLS-1$
                .append("текстовая правка идёт мимо BM, и EDT сохраняет в модели всё, что она оставила.\n") //$NON-NLS-1$
                .append("Посмотреть — inspect_command_interface, изменить — mutate_command_interface ") //$NON-NLS-1$
                .append("(edt_validate_request operation=mutate_command_interface)."); //$NON-NLS-1$
        if (overrideParameter != null && !overrideParameter.isBlank()) {
            message.append("\nДля аварийного обхода передайте ").append(overrideParameter).append("=true."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return message.toString();
    }
}
