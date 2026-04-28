package com.codepilot1c.core.edt.ast;

import com.codepilot1c.core.edt.debug.EdtDebugService;

/**
 * Compatibility facade for debug DTOs living in the AST package.
 */
public final class EdtDebugServices {

    private static final EdtDebugServices INSTANCE = new EdtDebugServices();

    private final EdtDebugService debugService;

    private EdtDebugServices() {
        this.debugService = EdtDebugService.getInstance();
    }

    public static EdtDebugServices getInstance() {
        return INSTANCE;
    }

    public SetBreakpointResult setBreakpoint(SetBreakpointRequest request) {
        return debugService.setBreakpoint(request);
    }

    public RemoveBreakpointResult removeBreakpoint(RemoveBreakpointRequest request) {
        return debugService.removeBreakpoint(request);
    }

    public ListBreakpointsResult listBreakpoints(ListBreakpointsRequest request) {
        return debugService.listBreakpoints(request);
    }

    public WaitForBreakResult waitForBreak(WaitForBreakRequest request) {
        return debugService.waitForBreak(request);
    }

    public GetVariablesResult getVariables(GetVariablesRequest request) {
        return debugService.getVariables(request);
    }

    public StepResult step(StepRequest request) {
        return debugService.step(request);
    }

    public ResumeResult resume(ResumeRequest request) {
        return debugService.resume(request);
    }

    public EvaluateExpressionResult evaluateExpression(EvaluateExpressionRequest request) {
        return debugService.evaluateExpression(request);
    }

    public DebugStatusResult debugStatus(DebugStatusRequest request) {
        return debugService.debugStatus(request);
    }
}
