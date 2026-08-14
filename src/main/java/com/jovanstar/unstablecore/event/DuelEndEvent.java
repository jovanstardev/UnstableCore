package com.jovanstar.unstablecore.event;

import com.jovanstar.unstablecore.model.Duel;
import org.jetbrains.annotations.Nullable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired once a duel has definitively ended (normal win or timeout resolution - not a forfeit,
 * see {@link DuelForfeitEvent}). Payout/stats/inventory-restore have already happened by the
 * time this fires. Duel announcements hook off this event.
 */
public final class DuelEndEvent extends DuelEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID winner;
    private final UUID loser;

    public DuelEndEvent(@NotNull Duel duel, @Nullable UUID winner, @Nullable UUID loser) {
        super(duel);
        this.winner = winner;
        this.loser = loser;
    }

    @Nullable
    public UUID getWinner() {
        return winner;
    }

    @Nullable
    public UUID getLoser() {
        return loser;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
