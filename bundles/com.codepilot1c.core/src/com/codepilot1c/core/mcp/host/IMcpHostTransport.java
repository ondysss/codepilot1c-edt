package com.codepilot1c.core.mcp.host;

public interface IMcpHostTransport extends AutoCloseable {

    void start();

    void stop();

    boolean isRunning();
}
