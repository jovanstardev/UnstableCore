package com.jovanstar.unstablecore.event;

import com.jovanstar.unstablecore.model.Duel;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired once the target has accepted and the atomic setup sequence is about to begin. */
public final class DuelAcceptEvent extends DuelEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public DuelAcceptEvent(@NotNull Duel duel) {
        super(duel);
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
