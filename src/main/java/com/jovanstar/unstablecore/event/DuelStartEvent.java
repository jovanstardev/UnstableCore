package com.jovanstar.unstablecore.event;

import com.jovanstar.unstablecore.model.Duel;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired the instant the countdown finishes and the duel becomes ACTIVE (FIGHT!). */
public final class DuelStartEvent extends DuelEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public DuelStartEvent(@NotNull Duel duel) {
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
