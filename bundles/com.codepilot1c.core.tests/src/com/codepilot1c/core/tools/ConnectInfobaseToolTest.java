package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectRequest;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectResult;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectionKind;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.tools.workspace.ConnectInfobaseTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ConnectInfobaseToolTest {

    private static final String SECRET_PASSWORD = "Str0ng-Secret-Value-XYZ"; //$NON-NLS-1$

    @Test
    public void fileKindReportsSuccessAndMarksPrimary() {
        RecordingConnectService service = new RecordingConnectService();
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("login", "Admin"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("password", SECRET_PASSWORD); //$NON-NLS-1$

        ToolResult result = tool.execute(params).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Demo", json.get("project").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("primary").getAsBoolean()); //$NON-NLS-1$
        JsonObject infobase = json.getAsJsonObject("infobase"); //$NON-NLS-1$
        assertEquals("file", infobase.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/tmp/demo-ib", infobase.get("path").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Admin", infobase.get("login").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(infobase.has("password")); //$NON-NLS-1$
        assertFalse(infobase.has("port")); //$NON-NLS-1$

        ConnectRequest observed = service.lastRequest;
        assertNotNull(observed);
        assertEquals(ConnectionKind.FILE, observed.kind());
        assertEquals("Demo", observed.projectName()); //$NON-NLS-1$
        assertEquals("Admin", observed.login()); //$NON-NLS-1$
        assertEquals(SECRET_PASSWORD, observed.password());
        assertTrue(observed.setPrimary());
    }

    @Test
    public void standaloneKindPropagatesPortAndRuntimeVersion() {
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> new ConnectResult(ConnectionKind.STANDALONE,
                req.databasePath(), "standalone-ib", req.login(), //$NON-NLS-1$
                req.serverPort() == null ? Integer.valueOf(1541) : req.serverPort(), req.setPrimary());
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/sa-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "standalone"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("server_port", Integer.valueOf(1545)); //$NON-NLS-1$
        params.put("runtime_version", "8.3.24.1656"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();

        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("success").getAsBoolean()); //$NON-NLS-1$
        JsonObject infobase = json.getAsJsonObject("infobase"); //$NON-NLS-1$
        assertEquals("standalone", infobase.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1545, infobase.get("port").getAsInt()); //$NON-NLS-1$

        ConnectRequest observed = service.lastRequest;
        assertNotNull(observed);
        assertEquals(ConnectionKind.STANDALONE, observed.kind());
        assertEquals(Integer.valueOf(1545), observed.serverPort());
        assertEquals("8.3.24.1656", observed.runtimeVersion()); //$NON-NLS-1$
    }

    @Test
    public void missingRequiredFieldFails() {
        ConnectInfobaseTool tool = new ConnectInfobaseTool(new RecordingConnectService());

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        // database_path intentionally omitted.

        ToolResult result = tool.execute(params).join();
        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.INVALID_ARGUMENT.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertTrue(json.get("message").getAsString().toLowerCase().contains("database_path")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void invalidKindValueFails() {
        ConnectInfobaseTool tool = new ConnectInfobaseTool(new RecordingConnectService());

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "cluster"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();
        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.INVALID_ARGUMENT.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertTrue(json.get("message").getAsString().contains("kind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void passwordIsNeverLeakedToResult() {
        RecordingConnectService service = new RecordingConnectService();
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("login", "Admin"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("password", SECRET_PASSWORD); //$NON-NLS-1$

        ToolResult result = tool.execute(params).join();
        assertTrue(result.isSuccess());
        String body = result.getContent();
        assertFalse("password must not appear in result content", body.contains(SECRET_PASSWORD)); //$NON-NLS-1$
        // Also verify that even on failure the password is not surfaced in the error payload.
        Map<String, Object> failingParams = new HashMap<>();
        failingParams.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        failingParams.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        failingParams.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        failingParams.put("password", SECRET_PASSWORD); //$NON-NLS-1$
        RecordingConnectService failing = new RecordingConnectService();
        failing.responseBuilder = req -> { throw new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                "service offline"); }; //$NON-NLS-1$
        ToolResult failResult = new ConnectInfobaseTool(failing).execute(failingParams).join();
        assertFalse(failResult.isSuccess());
        assertFalse("password must not appear in error content", //$NON-NLS-1$
                failResult.getErrorMessage().contains(SECRET_PASSWORD));
    }

    @Test
    public void setPrimaryFalseIsHonored() {
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> new ConnectResult(ConnectionKind.FILE, req.databasePath(),
                "ib", req.login(), null, req.setPrimary()); //$NON-NLS-1$
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("set_primary", Boolean.FALSE); //$NON-NLS-1$

        ToolResult result = tool.execute(params).join();
        assertTrue(result.isSuccess());

        ConnectRequest observed = service.lastRequest;
        assertNotNull(observed);
        assertFalse("tool must forward set_primary=false to service", observed.setPrimary()); //$NON-NLS-1$

        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertFalse(json.get("primary").getAsBoolean()); //$NON-NLS-1$
        JsonObject infobase = json.getAsJsonObject("infobase"); //$NON-NLS-1$
        // With no login provided the sanitized login should be omitted from the payload.
        assertNull(infobase.get("login")); //$NON-NLS-1$
    }

    @Test
    public void existingPrimaryWithoutForceReturnsPrimaryExists() {
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> { throw new EdtToolException(EdtToolErrorCode.PRIMARY_EXISTS,
                "primary_exists: current_primary=OldBase, pass force=true to replace"); }; //$NON-NLS-1$
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        // force intentionally omitted (default false).

        ToolResult result = tool.execute(params).join();

        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.PRIMARY_EXISTS.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertEquals("primary_exists", json.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("OldBase", json.get("current_primary").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("hint").getAsString().contains("force=true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void existingPrimaryWithForceReportsReplacedPrevious() {
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> new ConnectResult(ConnectionKind.FILE, req.databasePath(),
                "NewBase", req.login(), null, true, "OldBase"); //$NON-NLS-1$ //$NON-NLS-2$
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("force", Boolean.TRUE); //$NON-NLS-1$

        ToolResult result = tool.execute(params).join();
        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertTrue(json.get("primary").getAsBoolean()); //$NON-NLS-1$
        assertEquals("OldBase", json.get("replaced_previous").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        ConnectRequest observed = service.lastRequest;
        assertNotNull(observed);
        assertTrue("tool must forward force=true to service", observed.force()); //$NON-NLS-1$
    }

    @Test
    public void edtNotReadySurfacesFromIllegalStateException() {
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> { throw new IllegalStateException(
                "IInfobaseManager service unavailable \u2014 EDT may not be fully initialized"); }; //$NON-NLS-1$
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();
        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.EDT_NOT_READY.name(), json.get("error_code").getAsString()); //$NON-NLS-1$
        assertEquals("edt_not_ready", json.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("message").getAsString().contains("IInfobaseManager")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nullMessageExceptionSurfacesClassNameInstead() {
        // When the underlying service throws with a null message, the tool's JSON payload
        // must not surface an empty `message` field — it should fall back to the exception
        // class name so operators have something actionable to grep. See GH issue #31.
        RecordingConnectService service = new RecordingConnectService();
        service.responseBuilder = req -> { throw new RuntimeException((String) null); };
        ConnectInfobaseTool tool = new ConnectInfobaseTool(service);

        Map<String, Object> params = new HashMap<>();
        params.put("project_name", "Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("database_path", "/tmp/demo-ib"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "file"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();
        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE.name(),
                json.get("error_code").getAsString()); //$NON-NLS-1$
        String message = json.get("message").getAsString(); //$NON-NLS-1$
        assertFalse("message must not be empty when underlying exception has null getMessage()", //$NON-NLS-1$
                message.isBlank());
        assertTrue("message must include the exception class name, was: " + message, //$NON-NLS-1$
                message.contains("RuntimeException")); //$NON-NLS-1$
    }

    @Test
    public void pathOutsideWorkspaceOrHomeIsRejected() {
        // /etc/passwd is never inside the Eclipse workspace or the user home directory,
        // so the path validator must reject it with INVALID_PATH.
        try {
            EdtInfobaseConnectService.validateAndNormalizePath("/etc/passwd"); //$NON-NLS-1$
            fail("expected EdtToolException for path outside allowed roots"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.INVALID_PATH, e.getCode());
            assertTrue(e.getMessage().toLowerCase().contains("invalid_path")); //$NON-NLS-1$
        }
    }

    @Test
    public void pathWithParentSegmentIsRejected() {
        // Even if the normalized path might end up inside the home directory, any raw '..'
        // segment must be rejected as defense-in-depth.
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        String traversal = home + "/../tmp/pwned"; //$NON-NLS-1$
        try {
            EdtInfobaseConnectService.validateAndNormalizePath(traversal);
            fail("expected EdtToolException for path containing '..'"); //$NON-NLS-1$
        } catch (EdtToolException e) {
            assertEquals(EdtToolErrorCode.INVALID_PATH, e.getCode());
            assertTrue(e.getMessage().contains("'..'")); //$NON-NLS-1$
        }
    }

    /** Stub service that records the incoming request and returns a configurable result. */
    private static class RecordingConnectService extends EdtInfobaseConnectService {
        ConnectRequest lastRequest;
        ResponseBuilder responseBuilder = req -> new ConnectResult(req.kind(), req.databasePath(),
                "stub-ib", req.login() == null || req.login().isBlank() ? null : req.login(), //$NON-NLS-1$
                req.serverPort(), req.setPrimary());

        @Override
        public ConnectResult connect(ConnectRequest request) {
            // Reproduce the real service's validation so parameter-error tests still route
            // through the tool's EdtToolException handler.
            if (request == null) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "request is required"); //$NON-NLS-1$
            }
            if (request.projectName() == null || request.projectName().isBlank()) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "project_name is required"); //$NON-NLS-1$
            }
            if (request.databasePath() == null || request.databasePath().isBlank()) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT, "database_path is required"); //$NON-NLS-1$
            }
            if (request.kind() == null) {
                throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                        "kind is required and must be 'file' or 'standalone'"); //$NON-NLS-1$
            }
            this.lastRequest = request;
            return responseBuilder.build(request);
        }
    }

    @FunctionalInterface
    private interface ResponseBuilder {
        ConnectResult build(ConnectRequest request);
    }
}
