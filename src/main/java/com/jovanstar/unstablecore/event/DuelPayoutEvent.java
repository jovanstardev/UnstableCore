package com.jovanstar.unstablecore.event;

import com.jovanstar.unstablecore.model.Duel;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Fired exactly once per duel, the moment its payout (or refund) completes. */
public final class DuelPayoutEvent extends DuelEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID recipient;
    private final double amount;

    public DuelPayoutEvent(@NotNull Duel duel, @Nullable UUID recipient, double amount) {
        super(duel);
        this.recipient = recipient;
        this.amount = amount;
    }

    @Nullable
    public UUID getRecipient() {
        return recipient;
    }

    public double getAmount() {
        return amount;
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
