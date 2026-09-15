package com.codepilot1c.core.mcp.host;

import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.agent.profiles.ProfileToolAccess;
import com.codepilot1c.core.tools.ToolRegistry.ToolResolution;

/**
 * Видно ли инструмент клиенту MCP.
 *
 * <p>Решение одно на всех: его принимает tools/list, по нему же отказывает tools/call, и по
 * нему discover_tools перечисляет категорию. Порознь они расходились - discover_tools читал
 * реестр целиком и обещал "You can call them directly" про снятые инструменты.
 */
public final class McpToolVisibility {

    private final McpToolExposurePolicy exposurePolicy;
    private final AgentProfile sessionProfile;
    private final boolean profileGateEnabled;

    public McpToolVisibility(McpToolExposurePolicy exposurePolicy, AgentProfile sessionProfile,
            boolean profileGateEnabled) {
        this.exposurePolicy = exposurePolicy;
        this.sessionProfile = sessionProfile;
        this.profileGateEnabled = profileGateEnabled;
    }

    /**
     * Видимость по текущим настройкам хоста, или {@code null}, когда хост выключен либо
     * настройки недоступны: показать лишнее лучше, чем скрыть работающее.
     */
    public static McpToolVisibility fromHostConfig() {
        try {
            McpHostConfig config = McpHostConfigStore.getInstance().load();
            if (!config.isEnabled()) {
                return null;
            }
            String profileId = config.getSessionProfileId();
            boolean gate = profileId != null && !profileId.trim().isEmpty();
            AgentProfile profile = gate
                    ? AgentProfileRegistry.getInstance()
                            .getAvailableProfile(profileId.trim())
                            .orElse(null)
                    : null;
            return new McpToolVisibility(new DefaultMcpToolExposurePolicy(config), profile, gate);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    public boolean isVisible(ToolResolution resolution) {
        if (resolution == null || resolution.tool() == null) {
            return false;
        }
        if (exposurePolicy != null && !exposurePolicy.isExposed(resolution.tool().getName())) {
            return false;
        }
        if (!profileGateEnabled) {
            return true;
        }
        return sessionProfile != null && ProfileToolAccess.allows(sessionProfile, resolution);
    }
}
