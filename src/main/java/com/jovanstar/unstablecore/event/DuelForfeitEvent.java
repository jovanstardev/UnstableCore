package com.jovanstar.unstablecore.event;

import com.jovanstar.unstablecore.model.Duel;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Fired when a duel ends because one participant disconnected while ACTIVE. */
public final class DuelForfeitEvent extends DuelEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID winner;
    private final UUID forfeiter;

    public DuelForfeitEvent(@NotNull Duel duel, @NotNull UUID winner, @NotNull UUID forfeiter) {
        super(duel);
        this.winner = winner;
        this.forfeiter = forfeiter;
    }

    @NotNull
    public UUID getWinner() {
        return winner;
    }

    @NotNull
    public UUID getForfeiter() {
        return forfeiter;
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
