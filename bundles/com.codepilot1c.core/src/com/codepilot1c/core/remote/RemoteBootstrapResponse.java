package com.codepilot1c.core.remote;

import java.util.List;
import java.util.Map;

/**
 * Initial payload returned to authenticated remote clients.
 */
public class RemoteBootstrapResponse {

    private final String clientId;
    private final String sessionId;
    private final boolean controller;
    private final String controllerClientId;
    private final Map<String, Object> agent;
    private final Map<String, Object> pendingConfirmation;
    private final IdeSnapshot ideSnapshot;
    private final List<Map<String, Object>> profiles;

    public RemoteBootstrapResponse(
            String clientId,
            String sessionId,
            boolean controller,
            String controllerClientId,
            Map<String, Object> agent,
            Map<String, Object> pendingConfirmation,
            IdeSnapshot ideSnapshot,
            List<Map<String, Object>> profiles) {
        this.clientId = clientId;
        this.sessionId = sessionId;
        this.controller = controller;
        this.controllerClientId = controllerClientId;
        this.agent = agent != null ? Map.copyOf(agent) : Map.of();
        this.pendingConfirmation = pendingConfirmation != null ? Map.copyOf(pendingConfirmation) : Map.of();
        this.ideSnapshot = ideSnapshot;
        this.profiles = profiles != null ? List.copyOf(profiles) : List.of();
    }

    public String getClientId() {
        return clientId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isController() {
        return controller;
    }

    public String getControllerClientId() {
        return controllerClientId;
    }

    public Map<String, Object> getAgent() {
        return agent;
    }

    public Map<String, Object> getPendingConfirmation() {
        return pendingConfirmation;
    }

    public IdeSnapshot getIdeSnapshot() {
        return ideSnapshot;
    }

    public List<Map<String, Object>> getProfiles() {
        return profiles;
    }
}
