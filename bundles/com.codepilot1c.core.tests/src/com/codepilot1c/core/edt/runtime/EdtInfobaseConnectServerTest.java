package com.codepilot1c.core.edt.runtime;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectRequest;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectResult;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectionKind;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.workspace.ConnectInfobaseTool;
import com.google.gson.JsonParser;

public class EdtInfobaseConnectServerTest {
    @Test
    public void publicToolAcceptsServerAndPreservesAccessParameters() {
        final List<ConnectRequest> calls = new ArrayList<>();
        EdtInfobaseConnectService service = new EdtInfobaseConnectService() {
            @Override public ConnectResult connect(ConnectRequest request) {
                calls.add(request);
                return new ConnectResult(request.kind(), "localhost:1541/demo", "demo", request.login(), null, true);
            }
        };
        ToolResult result = new ConnectInfobaseTool(service).execute(Map.of(
                "project_name", "Demo", "kind", "server", "server_address", "localhost:1541",
                "database_path", "demo", "login", "Reader", "password", "  secret  ")).join();
        assertTrue(result.getErrorMessage(), result.isSuccess());
        assertEquals(1, calls.size());
        assertEquals("localhost:1541", calls.get(0).serverAddress());
        assertEquals("demo", calls.get(0).databasePath());
        assertEquals("  secret  ", calls.get(0).password());
        assertFalse(result.getContent().contains("secret"));
        assertEquals("server", JsonParser.parseString(result.getContent()).getAsJsonObject()
                .getAsJsonObject("infobase").get("kind").getAsString());
    }

    @Test
    public void serverBindingUsesNativeReferenceAndExistingWriteSequence() {
        final List<String> steps = new ArrayList<>();
        EdtInfobaseConnectService service = new EdtInfobaseConnectService() {
            @Override protected String checkExistingPrimary(IProject project, ConnectRequest request) {
                steps.add("check"); return null;
            }
            @Override protected void persistReference(InfobaseReference reference) {
                steps.add("persist");
                String connection = reference.getConnectionString().asConnectionString();
                assertTrue(connection, connection.contains("localhost:1541"));
                assertTrue(connection, connection.contains("existing-demo"));
                assertFalse(connection, connection.contains("File="));
                reference.setUuid(UUID.fromString("d7f1a254-2b00-43b9-b4a8-03e834e584ad"));
            }
            @Override protected void storeAccessSettings(InfobaseReference reference, String login, String password) {
                steps.add("access"); assertNotNull(reference.getUuid());
                assertEquals("Reader", login); assertEquals("secret", password);
            }
            @Override protected boolean associate(IProject project, InfobaseReference reference, boolean primary) {
                steps.add("associate"); assertNotNull(reference.getUuid()); return primary;
            }
        };
        ConnectRequest request = new ConnectRequest("Demo", "existing-demo", ConnectionKind.SERVER,
                "Reader", "secret", true, null, null, false, "localhost:1541");
        ConnectResult result = service.connectServer(null, request);
        assertTrue(result.primary());
        assertEquals(List.of("check", "persist", "access", "associate"), steps);
    }

    @Test
    public void missingServerAddressFailsBeforeProjectOrDatabaseAccess() {
        ToolResult result = new ConnectInfobaseTool().execute(Map.of(
                "project_name", "DoesNotExist", "kind", "server", "database_path", "demo")).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage(), result.getErrorMessage().contains("server_address"));
    }

    @Test
    public void connectionStringCannotBeUsedAsServerName() {
        ToolResult result = new ConnectInfobaseTool().execute(Map.of(
                "project_name", "DoesNotExist", "kind", "server", "server_address", "localhost;Pwd=hidden",
                "database_path", "demo")).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("server_address"));
        assertFalse(result.getErrorMessage().contains("hidden"));
    }

    @Test
    public void connectionStringCannotBeUsedAsInfobaseName() {
        ToolResult result = new ConnectInfobaseTool().execute(Map.of(
                "project_name", "DoesNotExist", "kind", "server", "server_address", "localhost",
                "database_path", "demo;Pwd=hidden")).join();
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("database_path"));
        assertFalse(result.getErrorMessage().contains("hidden"));
    }

    @Test
    public void schemaPreservesLegacyKindsAndDeclaresServerAddress() {
        var properties = JsonParser.parseString(new ConnectInfobaseTool().getParameterSchema())
                .getAsJsonObject().getAsJsonObject("properties");
        String kinds = properties.getAsJsonObject("kind").get("enum").toString();
        assertTrue(kinds.contains("\"server\""));
        assertTrue(kinds.contains("\"file\""));
        assertTrue(kinds.contains("\"standalone\""));
        assertTrue(properties.has("server_address"));
    }
}
