package com.codepilot1c.core.agent.graph;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.meta.ToolDescriptor;
import com.codepilot1c.core.tools.meta.ToolDescriptorRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Router that limits tool availability based on a tool graph.
 */
public class ToolGraphRouter {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolGraphRouter.class);

    private static final Pattern GRAPH_OVERRIDE = Pattern.compile("graph\\s*=\\s*([a-zA-Z0-9_-]+)"); //$NON-NLS-1$
    private static final String TOOLGRAPH_OFF = "toolgraph=off"; //$NON-NLS-1$

    private final ToolGraphRegistry registry;
    private final ToolGraphSelectionStrategy selectionStrategy;
    private final ToolDescriptorRegistry descriptorRegistry;

    private ToolGraphSession session;
    private ToolGraphPolicy policy = ToolGraphPolicy.ADVISORY;
    private String userPrompt = ""; //$NON-NLS-1$
    private boolean disabled;

    public ToolGraphRouter(ToolGraphRegistry registry, ToolGraphSelectionStrategy selectionStrategy,
            ToolDescriptorRegistry descriptorRegistry) {
        this.registry = registry;
        this.selectionStrategy = selectionStrategy;
        this.descriptorRegistry = descriptorRegistry;
    }

    public static ToolGraphRouter createDefault() {
        return new ToolGraphRouter(
                ToolGraphRegistry.getInstance(),
                new KeywordToolGraphSelectionStrategy(),
                ToolDescriptorRegistry.getInstance());
    }

    public synchronized void initialize(String prompt, AgentConfig config) {
        this.userPrompt = prompt != null ? prompt : ""; //$NON-NLS-1$
        this.policy = config != null ? config.getToolGraphPolicy() : ToolGraphPolicy.ADVISORY;
        this.disabled = config != null && !config.isToolGraphEnabled();

        if (disabled) {
            session = null;
            return;
        }

        String lowered = userPrompt.toLowerCase();
        if (lowered.contains(TOOLGRAPH_OFF)) {
            disabled = true;
            session = null;
            return;
        }
        if (lowered.contains("toolgraph=strict")) { //$NON-NLS-1$
            policy = ToolGraphPolicy.STRICT;
        } else if (lowered.contains("toolgraph=advisory")) { //$NON-NLS-1$
            policy = ToolGraphPolicy.ADVISORY;
        }

        String graphId = resolveGraphOverride(userPrompt);
        if (graphId == null || registry.get(graphId) == null) {
            graphId = selectionStrategy.selectGraphId(userPrompt);
        }

        ToolGraph graph = registry.get(graphId);
        if (graph == null) {
            graph = registry.get(ToolGraphRegistry.GENERAL_GRAPH_ID);
        }

        session = new ToolGraphSession(graph);
        ToolGraphLogger.log(new ToolGraphEvent(
                ToolGraphEventType.GRAPH_SELECTED,
                graph.getId(),
                session.getCurrentNodeId(),
                null,
                "init")); //$NON-NLS-1$
    }

    public synchronized ToolGraphToolFilter buildToolFilter() {
        if (disabled || session == null) {
            return ToolGraphToolFilter.allowAll();
        }

        ToolNode node = session.getCurrentNode();
        if (node == null || !node.isRestrictive()) {
            return ToolGraphToolFilter.allowAll();
        }

        Set<String> allowed = new HashSet<>(node.getAllowedTools());
        if (!session.isValidationReady()) {
            allowed.removeIf(toolName -> descriptorRegistry.getOrDefault(toolName).requiresValidationToken());
        }

        if (allowed.isEmpty()) {
            if (policy == ToolGraphPolicy.ADVISORY) {
                ToolGraphLogger.log(new ToolGraphEvent(
                        ToolGraphEventType.POLICY_ESCALATED,
                        session.getGraph().getId(),
                        node.getId(),
                        null,
                        "empty-whitelist")); //$NON-NLS-1$
                return ToolGraphToolFilter.allowAll();
            }
            return ToolGraphToolFilter.restrictTo(Set.of(), LlmRequest.ToolChoice.NONE);
        }

        LlmRequest.ToolChoice choice = LlmRequest.ToolChoice.AUTO;
        if (node.getRequiredTools().size() == 1) {
            String requiredTool = node.getRequiredTools().iterator().next();
            if (allowed.contains(requiredTool)) {
                choice = LlmRequest.ToolChoice.REQUIRED;
            }
        }

        return ToolGraphToolFilter.restrictTo(allowed, choice);
    }

    public synchronized void onToolResult(String toolName, ToolResult result) {
        if (disabled || session == null || toolName == null) {
            return;
        }

        boolean success = result != null && result.isSuccess();
        String errorCode = extractErrorCode(result);

        if ("edt_validate_request".equals(toolName) && success) { //$NON-NLS-1$
            session.setValidationReady(true);
        }

        ToolDescriptor descriptor = descriptorRegistry.getOrDefault(toolName);
        if (descriptor.requiresValidationToken()) {
            session.setValidationReady(false);
        }

        ToolGraphContext context = new ToolGraphContext(userPrompt, toolName, result, success, errorCode);
        String nextNodeId = session.resolveNextNodeId(context);
        if (nextNodeId == null) {
            return;
        }

        String previousNodeId = session.getCurrentNodeId();
        CycleLimitAction action = session.enterNode(nextNodeId);

        ToolGraphLogger.log(new ToolGraphEvent(
                ToolGraphEventType.EDGE_TRAVERSED,
                session.getGraph().getId(),
                nextNodeId,
                toolName,
                previousNodeId + "->" + nextNodeId)); //$NON-NLS-1$

        ToolGraphLogger.log(new ToolGraphEvent(
                ToolGraphEventType.NODE_ENTERED,
                session.getGraph().getId(),
                nextNodeId,
                toolName,
                "entered")); //$NON-NLS-1$

        if (action != null && action == CycleLimitAction.ESCALATE_TO_FALLBACK) {
            switchToFallback("cycle-limit"); //$NON-NLS-1$
        }
    }

    private void switchToFallback(String reason) {
        ToolGraph fallback = registry.get(ToolGraphRegistry.GENERAL_GRAPH_ID);
        if (fallback == null) {
            return;
        }
        session = new ToolGraphSession(fallback);
        ToolGraphLogger.log(new ToolGraphEvent(
                ToolGraphEventType.POLICY_ESCALATED,
                fallback.getId(),
                session.getCurrentNodeId(),
                null,
                reason));
    }

    private String resolveGraphOverride(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        Matcher matcher = GRAPH_OVERRIDE.matcher(prompt);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractErrorCode(ToolResult result) {
        if (result == null || result.isSuccess()) {
            return null;
        }
        String raw = result.getErrorMessage();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) { //$NON-NLS-1$
            try {
                JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
                if (obj.has("error")) { //$NON-NLS-1$
                    return obj.get("error").getAsString(); //$NON-NLS-1$
                }
            } catch (Exception e) {
                LOG.debug("ToolGraphRouter: failed to parse error json: %s", e.getMessage()); //$NON-NLS-1$
            }
        }
        if (trimmed.startsWith("[")) { //$NON-NLS-1$
            int idx = trimmed.indexOf(']');
            if (idx > 1) {
                return trimmed.substring(1, idx);
            }
        }
        return null;
    }
}
