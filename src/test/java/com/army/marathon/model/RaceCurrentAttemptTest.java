package com.army.marathon.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Truth table for Race.hasCurrentAttempt(): the single "is there a live attempt to show" test that
 * Live Tracking and Results use, so a null session/boundary renders EMPTY instead of "everything".
 */
class RaceCurrentAttemptTest {

    private static Race race(Race.RaceStatus status, Long sessionId, LocalDateTime boundary) {
        Race r = new Race();
        r.setStatus(status);
        r.setCurrentSessionId(sessionId);
        r.setCurrentAttemptStartedAt(boundary);
        return r;
    }

    @Test
    void hasCurrentAttempt_truthTable() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 9, 0);
        Object[][] cases = {
                // status,                        sessionId, boundary, expected
                {Race.RaceStatus.NOT_STARTED,     null, null, false},   // fresh launch / after Reset
                {Race.RaceStatus.NOT_STARTED,     5L,   t,    false},   // stale pointers on a NOT_STARTED race
                {Race.RaceStatus.ARMED,           5L,   t,    true},
                {Race.RaceStatus.ACTIVE,          5L,   t,    true},
                {Race.RaceStatus.FINISHED,        5L,   t,    true},    // stopped, results still shown
                {Race.RaceStatus.ACTIVE,          null, null, false},   // legacy row with no session
                {Race.RaceStatus.FINISHED,        null, t,    false},
                {Race.RaceStatus.ACTIVE,          5L,   null, false},
                {Race.RaceStatus.PENDING,         null, null, false},
                {Race.RaceStatus.CANCELLED,       5L,   t,    true},
        };
        for (Object[] c : cases) {
            Race r = race((Race.RaceStatus) c[0], (Long) c[1], (LocalDateTime) c[2]);
            assertEquals(c[3], r.hasCurrentAttempt(),
                    "status=" + c[0] + " session=" + c[1] + " boundary=" + c[2]);
        }
    }
}
