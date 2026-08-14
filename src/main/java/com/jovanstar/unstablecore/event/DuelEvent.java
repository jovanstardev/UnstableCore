package com.jovanstar.unstablecore.event;

import com.jovanstar.unstablecore.model.Duel;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;

/** Common base for every duel lifecycle event - all fire from the main thread. */
public abstract class DuelEvent extends Event {

    private final Duel duel;

    protected DuelEvent(@NotNull Duel duel) {
        this.duel = duel;
    }

    @NotNull
    public Duel getDuel() {
        return duel;
    }
}
