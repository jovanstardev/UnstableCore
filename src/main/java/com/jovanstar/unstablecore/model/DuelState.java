package com.jovanstar.unstablecore.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Explicit duel lifecycle. Transitions are validated centrally here so a duel can never be
 * pushed through an invalid jump (e.g. FINISHED -> ACTIVE) regardless of which code path
 * (accept click, timeout task, disconnect handler, admin forceend) attempts it.
 */
public enum DuelState {
    REQUESTED,
    ACCEPTED,
    STARTING,
    ACTIVE,
    ENDING,
    FINISHED,
    FORFEITED,
    DECLINED,
    EXPIRED,
    CANCELLED;

    private static final Map<DuelState, Set<DuelState>> TRANSITIONS = Map.of(
            REQUESTED, EnumSet.of(ACCEPTED, DECLINED, EXPIRED, CANCELLED),
            ACCEPTED, EnumSet.of(STARTING, CANCELLED),
            STARTING, EnumSet.of(ACTIVE, CANCELLED),
            ACTIVE, EnumSet.of(ENDING),
            ENDING, EnumSet.of(FINISHED, FORFEITED, CANCELLED),
            FINISHED, EnumSet.noneOf(DuelState.class),
            FORFEITED, EnumSet.noneOf(DuelState.class),
            DECLINED, EnumSet.noneOf(DuelState.class),
            EXPIRED, EnumSet.noneOf(DuelState.class),
            CANCELLED, EnumSet.noneOf(DuelState.class)
    );

    public boolean canTransitionTo(DuelState next) {
        return next != null && TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Set.of()).isEmpty();
    }

    /**
     * True from the moment both players are teleported into the arena through the end of combat
     * - used to gate escape-command/teleport-item restrictions. Includes STARTING (the pre-FIGHT
     * countdown) deliberately: without that, a player could ender-pearl out of the arena during
     * the countdown and simply never be reachable once ACTIVE begins, dodging the fight without
     * ever triggering the disconnect-forfeit path.
     */
    public boolean isActiveCombat() {
        return this == STARTING || this == ACTIVE || this == ENDING;
    }
}
