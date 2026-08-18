package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.BountyBoardGui;
import com.jovanstar.unstablecore.gui.PlaceBountyGui;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BountyManager {

    public enum PromptType {
        BOARD_SEARCH,
        PLACE_SEARCH,
        AMOUNT
    }

    public record Prompt(PromptType type, UUID target, String targetName, long expiresAt) {
        public Prompt(PromptType type, UUID target, String targetName) {
            this(type, target, targetName, System.currentTimeMillis() + DEFAULT_PROMPT_MS);
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    /** Fallback prompt lifetime; the live value comes from config via {@link #promptTimeoutMs()}. */
    private static final long DEFAULT_PROMPT_MS = 60_000L;

    public record Bounty(UUID target, String targetName, double amount, int bountyId, long updatedAt) {
        public DatabaseManager.BountyRow toRow() {
            return new DatabaseManager.BountyRow(target, targetName, amount, bountyId, updatedAt);
        }

        public static Bounty from(DatabaseManager.BountyRow row) {
            return new Bounty(row.target(), row.targetName(), row.amount(), row.bountyId(), row.updatedAt());
        }
    }

    private final UnstableCore plugin;
    private final Map<UUID, Bounty> bounties = new ConcurrentHashMap<>();
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Object claimLock = new Object();
    private volatile List<Bounty> sortedCache = List.of();

    public BountyManager(UnstableCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        bounties.clear();
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isConnected()) {
            for (DatabaseManager.BountyRow row : db.loadAllBounties().values()) {
                bounties.put(row.target(), Bounty.from(row));
            }
        }
        rebuildSortedCache();
    }

    public FileConfiguration cfg() {
        return plugin.getConfigManager().getBounty();
    }

    public boolean enabled() {
        return cfg().getBoolean("enabled", true);
    }

    public double minAmount() {
        return Math.max(1D, cfg().getDouble("min-amount", 10D));
    }

    public double maxAmount() {
        return Math.max(minAmount(), cfg().getDouble("max-amount", 1_000_000D));
    }

    public int count() {
        return bounties.size();
    }

    public Bounty get(UUID target) {
        return target == null ? null : bounties.get(target);
    }

    public double amountOf(UUID target) {
        Bounty b = get(target);
        return b == null ? 0D : b.amount();
    }

    public List<Bounty> sorted() {
        return sortedCache;
    }

    public List<Bounty> sortedFiltered(String query) {
        if (query == null || query.isBlank()) {
            return sorted();
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<Bounty> out = new ArrayList<>();
        for (Bounty b : sorted()) {
            if (b.targetName() != null && b.targetName().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(b);
            }
        }
        return out;
    }

    private void rebuildSortedCache() {
        List<Bounty> list = new ArrayList<>(bounties.values());
        list.sort(Comparator
                .comparingDouble(Bounty::amount).reversed()
                .thenComparingInt(Bounty::bountyId));
        sortedCache = List.copyOf(list);
    }

    /**
     * How long a pending chat prompt stays armed before it stops capturing chat. The AMOUNT
     * prompt deliberately re-arms itself when the input won't parse, so without a bound a player
     * who missed the "type cancel" hint could never send a normal chat message again.
     */
    private long promptTimeoutMs() {
        return Math.max(5_000L, plugin.getConfig().getLong("chat-prompt-timeout-seconds", 60) * 1000L);
    }

    public void beginPrompt(Player player, Prompt prompt) {
        if (prompt == null) {
            return;
        }
        // Re-stamp the deadline from config so a re-armed prompt gets a fresh window rather than
        // inheriting the original one (and so the record's compile-time default is never binding).
        prompts.put(player.getUniqueId(), new Prompt(prompt.type(), prompt.target(),
                prompt.targetName(), System.currentTimeMillis() + promptTimeoutMs()));
    }

    public Prompt takePrompt(UUID uuid) {
        Prompt prompt = prompts.remove(uuid);
        return (prompt == null || prompt.isExpired()) ? null : prompt;
    }

    /** Reads the pending prompt, discarding it first if it has expired. */
    public Prompt peekPrompt(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Prompt prompt = prompts.get(uuid);
        if (prompt == null) {
            return null;
        }
        if (prompt.isExpired()) {
            prompts.remove(uuid, prompt);
            return null;
        }
        return prompt;
    }

    public void clearPrompt(UUID uuid) {
        prompts.remove(uuid);
    }

    public boolean placeOrStack(Player placer, OfflinePlayer target, double amount) {
        if (!enabled()) {
            msg(placer, "disabled", Map.of());
            return false;
        }
        if (!plugin.getEconomyManager().isReady()) {
            msg(placer, "economy", Map.of());
            return false;
        }
        if (target == null || target.getUniqueId() == null) {
            msg(placer, "offline", Map.of());
            return false;
        }
        if (!cfg().getBoolean("allow-self", false)
                && placer.getUniqueId().equals(target.getUniqueId())) {
            msg(placer, "self", Map.of());
            return false;
        }

        double rounded = Math.floor(amount);
        if (!Double.isFinite(rounded) || rounded < minAmount() || rounded > maxAmount()) {
            msg(placer, "invalid-amount", Map.of(
                    "min", EconomyManager.format(minAmount()),
                    "max", EconomyManager.format(maxAmount())
            ));
            return false;
        }

        // Cap check before the withdrawal, not after. Taking the coins first and refunding on
        // rejection is a visible, spammable balance round-trip, and every refund used to be
        // counted as fresh income by the stats layer - so a rejected stack silently inflated the
        // placer's lifetime coins-earned figure at no cost. Reading the current total here is
        // safe: the authoritative re-check still happens under claimLock below.
        Bounty preview = bounties.get(target.getUniqueId());
        if (preview != null && preview.amount() + rounded > maxAmount()) {
            msg(placer, "invalid-amount", Map.of(
                    "min", EconomyManager.format(minAmount()),
                    "max", EconomyManager.format(maxAmount())
            ));
            return false;
        }

        if (!plugin.getEconomyManager().takeExact(placer, rounded)) {
            msg(placer, "not-enough", Map.of("amount", EconomyManager.format(rounded)));
            return false;
        }

        String name = target.getName() == null ? "Unknown" : target.getName();
        Bounty result;
        boolean stacked;
        synchronized (claimLock) {
            Bounty existing = bounties.get(target.getUniqueId());
            long now = System.currentTimeMillis();
            if (existing == null) {
                int id = plugin.getDatabaseManager().nextBountyId();
                result = new Bounty(target.getUniqueId(), name, rounded, id, now);
                stacked = false;
            } else {
                double newTotal = existing.amount() + rounded;
                if (newTotal > maxAmount()) {
                    // Individual contributions are validated above, but stacking onto an
                    // existing bounty was never re-checked against the cap - repeated small
                    // contributions could push the total arbitrarily above max-amount. Still
                    // needed as the authoritative check: two placers can land between the
                    // pre-check above and this block.
                    plugin.getEconomyManager().refund(placer, rounded);
                    msg(placer, "invalid-amount", Map.of(
                            "min", EconomyManager.format(minAmount()),
                            "max", EconomyManager.format(maxAmount())
                    ));
                    return false;
                }
                result = new Bounty(
                        existing.target(),
                        name,
                        newTotal,
                        existing.bountyId(),
                        now
                );
                stacked = true;
            }
            bounties.put(result.target(), result);
            rebuildSortedCache();
        }

        // Persisted synchronously (like nextBountyId() above) so the DB is never behind the
        // in-memory map. reload() reads straight from the DB and clears the in-memory map, so a
        // fire-and-forget async write here could lose this bounty (or resurrect a claimed one)
        // if a reload landed in the gap before the write completed.
        plugin.getDatabaseManager().upsertBounty(result.toRow());

        if (stacked) {
            msg(placer, "stacked", Map.of(
                    "amount", EconomyManager.format(rounded),
                    "total", EconomyManager.format(result.amount()),
                    "target", name,
                    "id", String.valueOf(result.bountyId())
            ));
            if (cfg().getBoolean("broadcast-stack", true)) {
                MessageUtil.broadcast(cfg().getString("messages.stacked-broadcast", ""), Map.of(
                        "placer", placer.getName(),
                        "target", name,
                        "amount", EconomyManager.format(rounded),
                        "total", EconomyManager.format(result.amount()),
                        "id", String.valueOf(result.bountyId())
                ));
            }
        } else {
            msg(placer, "placed", Map.of(
                    "amount", EconomyManager.format(rounded),
                    "target", name,
                    "id", String.valueOf(result.bountyId())
            ));
            if (cfg().getBoolean("broadcast-place", true)) {
                MessageUtil.broadcast(cfg().getString("messages.placed-broadcast", ""), Map.of(
                        "placer", placer.getName(),
                        "target", name,
                        "amount", EconomyManager.format(rounded),
                        "id", String.valueOf(result.bountyId())
                ));
            }
        }
        return true;
    }

    public void claimOnKill(Player killer, Player victim) {
        if (!enabled() || killer == null || victim == null || killer.equals(victim)) {
            return;
        }
        Bounty claimed;
        synchronized (claimLock) {
            claimed = bounties.remove(victim.getUniqueId());
            if (claimed != null) {
                rebuildSortedCache();
            }
        }
        if (claimed == null) {
            return;
        }

        UUID target = claimed.target();
        double reward = claimed.amount();
        boolean paid = reward <= 0 || (plugin.getEconomyManager().isReady()
                && plugin.getEconomyManager().deposit(killer, reward));
        if (!paid) {
            synchronized (claimLock) {
                bounties.putIfAbsent(claimed.target(), claimed);
                rebuildSortedCache();
            }
            plugin.getLogger().warning("Bounty claim deposit failed for " + killer.getName()
                    + " on " + victim.getName() + " - bounty restored.");
            return;
        }

        // Synchronous for the same reason as the upsert in placeOrStack: an async delete that
        // hasn't landed yet by the time reload() runs would leave the paid-out bounty row in the
        // DB, letting reload() resurrect it and pay it out a second time to the next killer.
        plugin.getDatabaseManager().deleteBounty(target, claimed.bountyId(), claimed.updatedAt());

        msg(killer, "claimed", Map.of(
                "amount", EconomyManager.format(reward),
                "target", claimed.targetName() == null ? victim.getName() : claimed.targetName(),
                "id", String.valueOf(claimed.bountyId())
        ));
        if (cfg().getBoolean("broadcast-claim", true)) {
            MessageUtil.broadcast(cfg().getString("messages.claimed-broadcast", ""), Map.of(
                    "killer", killer.getName(),
                    "victim", victim.getName(),
                    "amount", EconomyManager.format(reward),
                    "id", String.valueOf(claimed.bountyId())
            ));
        }
    }

    public void msg(Player player, String key, Map<String, String> placeholders) {
        String path = "messages." + key;
        String raw = cfg().getString(path, "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        MessageUtil.send(player, MessageUtil.apply(raw, placeholders));
    }

    public void openBoard(Player player) {
        openBoard(player, 0, null);
    }

    public void openBoard(Player player, int page, String filter) {
        if (!enabled()) {
            msg(player, "disabled", Map.of());
            return;
        }
        BountyBoardGui.open(plugin, player, page, filter);
    }

    public void openPlace(Player player) {
        openPlace(player, 0, null);
    }

    public void openPlace(Player player, int page, String filter) {
        if (!enabled()) {
            msg(player, "disabled", Map.of());
            return;
        }
        PlaceBountyGui.open(plugin, player, page, filter);
    }

    public void promptAmount(Player player, OfflinePlayer target) {
        if (target == null || target.getUniqueId() == null) {
            msg(player, "offline", Map.of());
            return;
        }
        String name = target.getName() == null ? "Unknown" : target.getName();
        beginPrompt(player, new Prompt(PromptType.AMOUNT, target.getUniqueId(), name));
        msg(player, "amount-prompt", Map.of(
                "target", name,
                "min", EconomyManager.format(minAmount()),
                "max", EconomyManager.format(maxAmount())
        ));
    }

    public boolean handleChat(Player player, String raw) {
        Prompt prompt = peekPrompt(player.getUniqueId());
        if (prompt == null) {
            return false;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("c")) {
            clearPrompt(player.getUniqueId());
            msg(player, "cancelled", Map.of());
            return true;
        }

        takePrompt(player.getUniqueId());
        switch (prompt.type()) {
            case BOARD_SEARCH -> {
                if (text.isEmpty()) {
                    openBoard(player);
                    return true;
                }
                List<Bounty> matches = sortedFiltered(text);
                if (matches.isEmpty()) {
                    // Raw chat text goes through MiniMessage in the message pipeline - escape it
                    // so a crafted query can't come back as live markup. See MessageUtil.
                    msg(player, "board-search-empty", Map.of("query", MessageUtil.escapeUserInput(text)));
                    openBoard(player);
                } else {
                    openBoard(player, 0, text);
                }
            }
            case PLACE_SEARCH -> {
                if (text.isEmpty()) {
                    openPlace(player);
                    return true;
                }
                Player online = Bukkit.getPlayerExact(text);
                if (online == null) {
                    online = Bukkit.getOnlinePlayers().stream()
                            .filter(p -> p.getName().toLowerCase(Locale.ROOT)
                                    .startsWith(text.toLowerCase(Locale.ROOT)))
                            .findFirst()
                            .orElse(null);
                }
                if (online == null) {
                    msg(player, "not-found-search", Map.of("query", MessageUtil.escapeUserInput(text)));
                    openPlace(player);
                } else {
                    player.closeInventory();
                    promptAmount(player, online);
                }
            }
            case AMOUNT -> {
                double value;
                try {
                    value = Double.parseDouble(text.replace(",", ""));
                } catch (NumberFormatException e) {
                    msg(player, "invalid-amount", Map.of(
                            "min", EconomyManager.format(minAmount()),
                            "max", EconomyManager.format(maxAmount())
                    ));
                    beginPrompt(player, prompt);
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(prompt.target());
                placeOrStack(player, target, value);
            }
        }
        return true;
    }
}
