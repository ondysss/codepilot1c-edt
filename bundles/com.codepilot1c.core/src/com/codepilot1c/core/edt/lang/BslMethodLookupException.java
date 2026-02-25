package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.codepilot1c.core.edt.ast.EdtAstErrorCode;
import com.codepilot1c.core.edt.ast.EdtAstException;

/**
 * Exception for ambiguous method lookup results.
 */
public class BslMethodLookupException extends EdtAstException {

    private static final long serialVersionUID = 1L;

    private final List<BslMethodCandidate> candidates;

    public BslMethodLookupException(
            EdtAstErrorCode code,
            String message,
            boolean recoverable,
            List<BslMethodCandidate> candidates) {
        super(code, message, recoverable);
        this.candidates = new ArrayList<>(candidates != null ? candidates : List.of());
    }

    public List<BslMethodCandidate> getCandidates() {
        return Collections.unmodifiableList(candidates);
    }
}
