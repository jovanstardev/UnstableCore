package com.jovanstar.unstablecore.event;

import com.jovanstar.unstablecore.model.Duel;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired when a duel request is created (REQUESTED) - before either side has confirmed anything. */
public final class DuelCreateEvent extends DuelEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public DuelCreateEvent(@NotNull Duel duel) {
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
