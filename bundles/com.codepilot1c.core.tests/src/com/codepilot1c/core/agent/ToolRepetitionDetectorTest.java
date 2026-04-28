/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.util.Optional;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link ToolRepetitionDetector} (Plan 1.2).
 *
 * <p>Uses the package-visible {@code (windowSize, threshold)} constructor via
 * reflection-free direct access (same package) so each test pins its own
 * parameters regardless of Eclipse preferences.</p>
 */
public class ToolRepetitionDetectorTest {

    private ToolRepetitionDetector newDetector(int windowSize, int threshold) {
        try {
            Constructor<ToolRepetitionDetector> ctor =
                    ToolRepetitionDetector.class.getDeclaredConstructor(int.class, int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(Integer.valueOf(windowSize), Integer.valueOf(threshold));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to instantiate detector", e);
        }
    }

    @Test
    public void belowThresholdReturnsEmpty() {
        ToolRepetitionDetector d = newDetector(10, 5);
        String args = ToolRepetitionDetector.canonicalizeArgs(object("q", "\"foo\""));

        for (int i = 0; i < 4; i++) {
            Optional<ToolRepetitionDetector.Trip> trip = d.observe("grep", args);
            assertTrue("below threshold must not trip at call " + (i + 1), trip.isEmpty());
        }
    }

    @Test
    public void exactlyAtThresholdTrips() {
        ToolRepetitionDetector d = newDetector(10, 5);
        String args = ToolRepetitionDetector.canonicalizeArgs(object("q", "\"foo\""));

        Optional<ToolRepetitionDetector.Trip> lastResult = Optional.empty();
        for (int i = 0; i < 5; i++) {
            lastResult = d.observe("grep", args);
            if (i < 4) {
                assertTrue("must not trip before fifth call (call " + (i + 1) + ")",
                        lastResult.isEmpty());
            }
        }
        assertTrue("fifth identical call must trip", lastResult.isPresent());
        ToolRepetitionDetector.Trip trip = lastResult.get();
        assertEquals("grep", trip.toolName);
        assertEquals(5, trip.identicalCount);
        assertNotNull(trip.localizedMessage());
        assertTrue("message must mention tool name",
                trip.localizedMessage().contains("grep"));
    }

    @Test
    public void afterTripWindowIsReset() {
        ToolRepetitionDetector d = newDetector(10, 5);
        String args = ToolRepetitionDetector.canonicalizeArgs(object("q", "\"foo\""));

        // First 5 — trip.
        Optional<ToolRepetitionDetector.Trip> trip = Optional.empty();
        for (int i = 0; i < 5; i++) {
            trip = d.observe("grep", args);
        }
        assertTrue(trip.isPresent());

        // Next identical call must NOT immediately re-trip — threshold resets.
        Optional<ToolRepetitionDetector.Trip> again = d.observe("grep", args);
        assertTrue("first call after trip must not re-trip immediately", again.isEmpty());

        // Need another 4 identical calls (total 5 after reset) to trip again.
        for (int i = 0; i < 3; i++) {
            again = d.observe("grep", args);
            assertTrue("must not trip before completing a full new threshold",
                    again.isEmpty());
        }
        again = d.observe("grep", args);
        assertTrue("fifth identical call after reset must re-trip", again.isPresent());
    }

    @Test
    public void differentArgsOfSameToolDoNotTrip() {
        ToolRepetitionDetector d = newDetector(10, 5);

        for (int i = 0; i < 10; i++) {
            JsonObject args = new JsonObject();
            args.addProperty("q", "foo-" + i);
            Optional<ToolRepetitionDetector.Trip> trip =
                    d.observe("grep", ToolRepetitionDetector.canonicalizeArgs(args));
            assertTrue("different args must never trip (iteration " + i + ")",
                    trip.isEmpty());
        }
    }

    @Test
    public void differentToolsWithSameArgsDoNotTripTogether() {
        ToolRepetitionDetector d = newDetector(20, 5);
        String args = ToolRepetitionDetector.canonicalizeArgs(object("q", "\"foo\""));

        // Interleave grep and find 4 times each. Window holds all 8 entries,
        // but each tool-name key is counted separately — neither reaches the
        // threshold of 5. The detector keys on (toolName, argsHash), so two
        // different tools with identical args never aggregate into a trip.
        for (int i = 0; i < 4; i++) {
            assertTrue("grep call " + (i + 1) + " must not trip",
                    d.observe("grep", args).isEmpty());
            assertTrue("find call " + (i + 1) + " must not trip",
                    d.observe("find", args).isEmpty());
        }
    }

    @Test
    public void canonicalizeArgsIsOrderIndependent() {
        JsonObject a = (JsonObject) JsonParser.parseString("{\"a\":1,\"b\":2}");
        JsonObject b = (JsonObject) JsonParser.parseString("{\"b\":2,\"a\":1}");

        String canonA = ToolRepetitionDetector.canonicalizeArgs(a);
        String canonB = ToolRepetitionDetector.canonicalizeArgs(b);

        assertEquals("canonical forms must match regardless of key order", canonA, canonB);
    }

    @Test
    public void canonicalizeArgsHandlesNestedObjectsAndArrays() {
        JsonObject a = (JsonObject) JsonParser.parseString(
                "{\"z\":{\"y\":2,\"x\":1},\"arr\":[3,1,2]}");
        JsonObject b = (JsonObject) JsonParser.parseString(
                "{\"arr\":[3,1,2],\"z\":{\"x\":1,\"y\":2}}");

        assertEquals(
                ToolRepetitionDetector.canonicalizeArgs(a),
                ToolRepetitionDetector.canonicalizeArgs(b));
    }

    @Test
    public void canonicalizeArgsNullReturnsLiteralNull() {
        assertEquals("null", ToolRepetitionDetector.canonicalizeArgs(null));
    }

    @Test
    public void resetForNewTurnClearsState() {
        ToolRepetitionDetector d = newDetector(10, 5);
        String args = ToolRepetitionDetector.canonicalizeArgs(object("q", "\"foo\""));

        for (int i = 0; i < 4; i++) {
            assertTrue(d.observe("grep", args).isEmpty());
        }

        d.resetForNewTurn();

        // After reset, even a single identical call must not trip — we need
        // a fresh threshold worth of calls.
        for (int i = 0; i < 4; i++) {
            assertTrue("post-reset: must not trip until threshold reached",
                    d.observe("grep", args).isEmpty());
        }
        // Fifth identical call after reset trips.
        assertTrue("post-reset: fifth call trips as usual",
                d.observe("grep", args).isPresent());
    }

    @Test
    public void windowEvictsOldEntries() {
        // Small window to make the eviction test concrete: size 5, threshold 5.
        // We alternate grep/find for a while so the grep count drops below
        // threshold as older entries fall off.
        ToolRepetitionDetector d = newDetector(5, 5);
        String args = ToolRepetitionDetector.canonicalizeArgs(object("q", "\"foo\""));

        // 3 grep
        assertTrue(d.observe("grep", args).isEmpty());
        assertTrue(d.observe("grep", args).isEmpty());
        assertTrue(d.observe("grep", args).isEmpty());
        // 5 find — evicts all grep entries
        for (int i = 0; i < 5; i++) {
            Optional<ToolRepetitionDetector.Trip> trip = d.observe("find", args);
            if (i < 4) {
                assertTrue(trip.isEmpty());
            } else {
                assertTrue("find trips at 5 identical calls", trip.isPresent());
            }
        }
    }

    @Test
    public void nullOrEmptyToolNameDoesNotTrip() {
        ToolRepetitionDetector d = newDetector(10, 5);
        String args = ToolRepetitionDetector.canonicalizeArgs(new JsonObject());

        for (int i = 0; i < 10; i++) {
            assertFalse(d.observe(null, args).isPresent());
            assertFalse(d.observe("", args).isPresent());
        }
    }

    /** Helper: build a {k:v} JsonObject where v is a raw JSON literal. */
    private static JsonObject object(String key, String jsonLiteralValue) {
        JsonObject obj = (JsonObject) JsonParser.parseString(
                "{\"" + key + "\":" + jsonLiteralValue + "}");
        return obj;
    }
}
