package com.codepilot1c.core.evaluation.trace;

/**
 * Event types recorded by the agent evaluation trace infrastructure.
 */
public enum TraceEventType {
    AGENT_STARTED,
    AGENT_STEP,
    AGENT_STREAM_CHUNK,
    AGENT_CONFIRMATION_REQUIRED,
    AGENT_COMPLETED,
    LLM_REQUEST,
    LLM_RESPONSE,
    LLM_STREAM_CHUNK,
    TOOL_CALL,
    TOOL_RESULT,
    MCP_SESSION_CREATED,
    MCP_REQUEST,
    MCP_RESPONSE,
    MCP_SESSION_CLOSED,
    REMOTE_AUTH,
    REMOTE_LEASE,
    REMOTE_COMMAND,
    REMOTE_CONFIRMATION,
    REMOTE_EDITOR_APPLY
}
