# UnstableCore — Security & Exploit Audit, Second Pass

Follow-up adversarial review of the plugin after the first full audit (`SECURITY-AUDIT.md`,
36 issues found and fixed). The goal of this pass was explicitly to find what that audit
**missed**, and what its own fixes left incomplete.

**14 issues found · 7 fixed · 7 reported open · 4 files changed**

Build verified: `mvn -o clean package` → BUILD SUCCESS (JDK 21.0.12 / Maven 3.9.16).
Only the three pre-existing `HOTBAR_MOVE_AND_READD` deprecation warnings remain.

| Severity | Found | Fixed | Open |
|---|---|---|---|
| Critical | 1 | 1 | 0 |
| High | 1 | 1 | 0 |
| Medium | 5 | 4 | 1 |
| Low | 7 | 1 | 6 |

The one critical finding is a **new instance of the exact bug class the first audit was built
around** — duel gear being extractable from the plugin-managed inventory cycle. The first pass
closed the drop route and the empty-inventory route; it did not consider that the winner's
3-second victory delay holds a stale `Player` handle across a reconnect.

---

## Critical

### P2-C1 · Reconnecting during the victory delay mints a full duel kit, unlimited
`DuelManager.finishDuel` — `src/main/java/com/jovanstar/unstablecore/manager/DuelManager.java:1550`

**Category:** item duplication / unbounded item generation

Duels issue a plugin-chosen kit and take it back by overwriting it with the player's real
pre-duel inventory on every exit path. For the winner that overwrite is deferred 3 seconds so
the victory moment isn't interrupted:

```java
Player wp = Bukkit.getPlayer(winner);
...
Bukkit.getScheduler().runTaskLater(plugin, () -> {
    pendingPostDuelRestores.remove(winner);
    if (wp.isOnline()) {                      // <-- stale handle
        teleportToSpawn(wp);
        restorePlayerPostDuel(wp, winnerSnapshot);
    } else if (winnerSnapshot != null) {
        queuePersistentRestore(winner, winnerSnapshot);
    }
}, 60L);
```

**Root cause:** a Bukkit `Player` object is bound to one connection. After a disconnect *and
reconnect*, the captured `wp` refers to the dead session, so `wp.isOnline()` returns `false`
even though the player is online again. The task therefore takes the "player is gone" branch and
queues the inventory for restore-on-next-**join** — a join that has already happened.

**Exploitation:**
1. Win any duel (the matchmaking queue supplies unlimited zero-wager duels).
2. Disconnect within the 3-second victory window.
3. Reconnect immediately. `finishDuel` has already run: `playerDuel` is cleared, the duel is
   `FINISHED`, so `isInCombatDuel` is false and `DuelListener.onDropDuringDuel` no longer
   cancels drops. You are standing at spawn wearing the full duel kit.
4. Drop the entire kit on the ground. `clearArenaDrops` only sweeps the arena, and you are at
   spawn, so the sweep never sees it.
5. Relog. `PlayerListener.onJoin` → `applyPendingCrashRestore` restores your real pre-duel
   inventory over the (now empty) one.
6. Pick the dropped kit back up.

Net result: one complete kit minted per duel, freely tradeable, repeatable as fast as you can
requeue. Paid/rank-locked kits are reachable this way because queue duels select a random kit.

**Impact:** unbounded generation of top-tier gear; destroys kit scarcity and, through trading,
the coin economy that prices those kits.

**Fix applied:** re-resolve the player by UUID inside the delayed task instead of trusting the
captured handle, so a reconnected winner is restored immediately (the correct behaviour) and only
a genuinely-offline winner falls through to the persistent queue.

**Remaining risk:** none known for this path. **Testing required:** yes — win a duel, relog
inside 3s, confirm the pre-duel inventory is restored on the spot and no kit item survives.

---

## High

### P2-H1 · Shutdown silently discards pre-duel inventories owed to offline players
`DuelManager.shutdown` — `DuelManager.java:243`

**Category:** data loss / item loss

Two maps hold inventories the plugin still owes: `pendingPostDuelRestores` (winner, inside the
3s delay) and `pendingRespawnSnapshots` (loser, still on the death screen). Shutdown drained both
with `restoreIfOnline`, which is a **no-op for an offline player**:

```java
for (Map.Entry<UUID, DuelInventorySnapshot> entry : pendingPostDuelRestores.entrySet()) {
    restoreIfOnline(entry.getKey(), entry.getValue());
}
```

**Root cause:** these are precisely the two windows in which the duel's DB crash-recovery row has
**already been deleted** (`finishDuel` calls `deleteDuelRowAsync` before scheduling the restore),
so `start()`'s recovery pass has nothing to replay. If the owed player is offline at shutdown,
the snapshot exists only in that map and dies with the process.

**Exploitation:** not attacker-controlled, but trivially reachable — win a duel, disconnect, and
have the server restart in the next 3 seconds; or die in a duel, quit on the death screen during
a restart window. On a server that restarts on a schedule this happens by accident.

**Impact:** the player permanently loses their entire pre-duel inventory and is left holding the
plugin-issued duel kit as their saved inventory — item loss *and* unearned kit retention at once.

**Fix applied:** new `flushOwedRestores(Map)` helper replaces both loops. Online players are
restored as before; offline players get `queuePersistentRestore`, which (correctly detecting
`!plugin.isEnabled()` during `onDisable`) writes the `pending_restores` row inline so the next
join applies it.

**Remaining risk:** if the database is unreachable at shutdown the snapshot is still lost; that
is the pre-existing limit of the `pending_restores` mechanism. **Testing required:** yes.

---

## Medium

### P2-M1 · Duel-farming detection never ran for wagered duels
`DuelStatsManager.checkFarming`, `DuelManager.recordStats`

**Category:** leaderboard / stat manipulation

`checkFarming` was invoked only from `recordRankedResult`. But a duel is never both ranked and
wagered:

- `DuelManager.java:519` (the `/duel` request path — the only path carrying a wager) always
  constructs the duel with `ranked = false`.
- `DuelManager.java:907` (`createQueueMatch`, the only ranked path) always passes wager `0.0`.

So the entire economic half of duelling produced **no anti-farm signal at all**, and
`/dueladmin flags` stayed empty regardless of how blatant the pattern was.

**Exploitation:** two cooperating accounts alternate a large wager. With the shipped defaults
(`house-cut-percent: 0`, `wager.daily-limit: 0`) each round costs the pair nothing — the pot just
moves back and forth — while every win adds the full pot to `duel_coins_won` and increments
`duel_wins` / `duel_best_streak`. Three of the nine leaderboard categories are farmable for free
and undetected.

**Impact:** leaderboard integrity; no staff visibility into win-trading.

**Fix applied:** `checkFarming` is now public, self-guarding (null / self-pair), and driven from
`DuelManager.recordStats`, which is already gated by the `markStatsRecorded` idempotency flag —
so it fires exactly once per **decided duel of any kind**. Removed from `recordRankedResult` to
avoid double-counting ranked matches. Flag text and the log line were generalised from "ranked
duels"/"ELO farming" to "duels"/"duel farming".

**Remaining risk:** this flags, it does not block — matching the deliberate "flag, don't
auto-punish" design in `DUELS.md`. Win-trading is still *possible*, just now visible.
**Testing required:** yes — run 5 wagered duels between two accounts inside 30 minutes and
confirm the pair appears in `/dueladmin flags`.

### P2-M2 · `pairCooldownUntil` grew for the entire uptime of the server
`DuelManager.setDeclineCooldown` — `DuelManager.java:750`

**Category:** memory leak / unbounded collection

Entries were only ever inserted, never removed. One permanent entry per *distinct pair* of
players that ever declined, expired or cancelled a duel request — while every entry is dead
after `request.cooldown-seconds` (5s by default). Growth is O(distinct interacting pairs), which
on a busy server trends toward O(players²).

**Fix applied:** sweep expired entries on each write. Because the sweep runs on every write, the
map it walks stays at roughly "pairs that declined in the last 5 seconds" rather than being
allowed to accumulate first.

### P2-M3 · `lastRequestSentAt` grew without bound
`DuelManager.createRequest` — `DuelManager.java:525`

**Category:** memory leak / unbounded collection

Same shape: one permanent entry per player who ever sent a duel request, for a value that is
dead after `request.rate-limit-seconds` (3s).

**Fix applied:** same expiry sweep on write. The predicate (`elapsed >= rateLimitMs`) mirrors
`isRateLimited`'s own condition exactly, so no request is ever un-rate-limited early.

### P2-M4 · Quitting mid-teleport left a permanent "in arena" tag
`ArenaManager.teleportToArena` — `ArenaManager.java:856`

**Category:** state leak / denial of service against the affected player

The arena tag is written from a `runTask` scheduled off the `teleportAsync` completion, with no
liveness check:

```java
player.teleportAsync(destination).thenAccept(success -> {
    ...
    Bukkit.getScheduler().runTask(plugin, () -> {
        playerArena.put(player.getUniqueId(), arena.getId());
```

Quitting between teleport completion and that task re-adds the tag **after**
`PlayerListener.onQuit` → `clearPlayer` has removed it. Everything that asks "is this player busy
in an arena" — duel requests, the duel queue, `/spec`, build protection — then answers yes for a
player who is not on the server.

Self-heals on rejoin *only* because `join.spawn-on-join` (default `true`) calls `clearPlayer`.
With that setting off, the player is permanently unable to duel, queue or spectate.

**Fix applied:** `if (!player.isOnline()) return;` at the top of the scheduled task.

### P2-M5 · In-memory state scales with total unique players, not concurrent players — **OPEN**
`KillstreakManager`, `StatsManager`, `SettingsManager`, `LoadoutManager`, `TagManager`,
`DuelStatsManager`, `LeaderboardManager`

**Category:** memory / scalability

Every one of these managers calls a `loadAll*()` at boot that reads its **entire table** into a
`ConcurrentHashMap`, and none of them evict on quit — `KillstreakManager.clear(UUID)` exists but
is never called from anywhere. Heap therefore scales with lifetime unique players (kills, deaths,
streaks, four stat maps, per-setting maps, two loadout maps, tags, nine duel-stat fields, plus
the leaderboard name cache and its reverse index), not with the ~100 players actually online.

Not fixed: making these evict on quit is a real architectural change (the bulk saves iterate the
same maps and use "absent from the map" as part of their delete logic — see `saveAllCombat0`), and
getting it wrong risks the data-loss class the first audit's C1 was about. Flagging rather than
attempting it blind. On a long-lived KitPvP server with 100k+ unique players this is worth
measuring before it becomes an incident.

---

## Low

| ID | Issue | Location | Status |
|---|---|---|---|
| P2-L1 | Non-finite event multiplier. `Math.max(1.0, NaN)` is `NaN`, and `Double.parseDouble` accepts `"NaN"`/`"Infinity"` from `/uc event start`. `rewardKill` then computes `floor(base * NaN) = NaN`; the `amount <= 0` bail-out is **false** for NaN, `deposit` rejects the non-finite value, and every kill for the whole event pays nothing while still telling the player they earned "NaN". | `EventManager.startCoinEvent` / `startStreakEvent` | **Fixed** — `Double.isFinite(multi) ? Math.max(1.0, multi) : 1.0` on both |
| P2-L2 | Per-player save skips writing when in-memory state is all-default, so a stale non-default DB row survives (e.g. `/resetkillstreak` then quit). Self-corrects within 5 minutes because the bulk autosave deletes default rows and quitting players are never evicted from the maps — but a restart inside that window resurrects the old streak. | `KillstreakManager.save(UUID)`, `StatsManager.save(UUID)` | Open |
| P2-L3 | Dead code that reads as a live safety control: `KillstreakManager.clear(UUID)` has no callers, and `SpectatorManager.enabled()` is never called (the real `spectate.enabled` check lives in `SpectateCommand`). Both invite a false sense of coverage in future edits. | `KillstreakManager`, `SpectatorManager` | Open |
| P2-L4 | `TagManager.tagLocks` is never evicted — one lock object per player who ever equipped or cleared a tag. `RewardsManager` evicts its equivalent `locks` map in `unload`; this one does not. | `TagManager` | Open |
| P2-L5 | `DuelHistoryGui` is the only paginated GUI that does not clamp its page (`this.page = page`, versus `Math.max(0, Math.min(page, pages - 1))` in the other three). `/duel history 2000000000` overflows `(page + 1) * PAGE_SIZE` to a negative int, so a "Next Page" arrow is drawn on an empty page. No crash — the SQL `OFFSET` is separately clamped by `Math.max(0, offset)`. Cosmetic. | `DuelHistoryGui:54` | Open |
| P2-L6 | `TagManager.equip(Player, String)` is public and applies an arbitrary suffix with **no permission check of its own**; the only `tags.*` check lives in `TagsGui`'s click handler. Correct today (that GUI is the sole caller) but one new call site away from a permission bypass. | `TagManager.equip` | Open |
| P2-L7 | `KitManager.applyKit` drops overflow via `dropItemNaturally` at the player's **current** location. During duel setup `applyKit` runs *before* the teleport, so a kit with more than 36 non-armor items would scatter real items at the lobby/spawn, outside `clearArenaDrops`' sweep radius. Requires an admin-authored oversized kit; not player-reachable. | `KitManager.applyKit:839` | Open |

---

## Fixes applied

| File | Change |
|---|---|
| `manager/DuelManager.java` | Re-resolve the winner by UUID in the victory-delay task instead of using a stale `Player` handle (**P2-C1**) |
| `manager/DuelManager.java` | New `flushOwedRestores(Map)`; `shutdown()` now persists owed inventories for offline players instead of dropping them (**P2-H1**) |
| `manager/DuelManager.java` | Expiry sweep on write for `pairCooldownUntil` (**P2-M2**) and `lastRequestSentAt` (**P2-M3**) |
| `manager/DuelManager.java` | `recordStats` now drives `checkFarming` for every decided duel (**P2-M1**) |
| `manager/DuelStatsManager.java` | `checkFarming` made public + null/self-guarded; removed from `recordRankedResult`; flag and log wording generalised (**P2-M1**) |
| `manager/ArenaManager.java` | `isOnline()` guard before writing the arena tag from the post-teleport task (**P2-M4**) |
| `manager/EventManager.java` | Non-finite guard on both event multipliers (**P2-L1**) |

---

## Areas verified clean

Re-derived from the current source, not taken from the previous report:

- **Duel state machine.** Every transition is an `AtomicReference.compareAndSet` validated
  against an explicit transition table; every terminal action (escrow, payout, stats, inventory
  restore, rollback, death, grace release) is behind its own atomic CAS flag. Escrow/refund paths
  and `rollbackSetup`/`handleStartingDeath` are mutually exclusive by state, and a wager cannot be
  both refunded and paid.
- **Wager input.** `"NaN"`/`"Infinity"` survive `Double.parseDouble` in the wager chat prompt but
  are neutralised by `Math.floor(Math.max(0, raw))` + `!Double.isFinite → 0` in `createRequest`.
- **GUI click routing.** `GuiListener` cancels the event *before* any early-return branch, then
  defers the handler one tick with a holder-identity re-check. Drag, hotbar-swap, offhand-swap,
  double-click, collect-to-cursor and out-of-window (`raw < 0`) are all covered. The two
  uncancelled GUIs are the kit editors: `KitEditGui` blocks every extraction route and clears the
  cursor on close; `KitAdminEditGui` deliberately allows bottom-inventory clicks and is gated on
  `unstablecore.admin` both in `plugin.yml` and by an explicit `hasPermission` check in
  `KitCommand`.
- **Held-shulker duplication surface.** Session bound to hand *and* hotbar index, `PlayerItemHeldEvent`
  cancelled while open, `saveToItem` refuses on slot/type/amount mismatch, and shulkers are
  rejected as cursor/current/hotbar/drag items. `forceCloseSession` is called before both
  `applyKit`'s `inv.clear()` and the duel snapshot capture.
- **Permissions.** All 21 commands cross-checked against `plugin.yml`. Every privileged
  subcommand carries its own `hasPermission` check in addition to the manifest gate; no admin
  functionality is reachable without it. Tab-completers filter vanished players via `canSee`.
- **SQL.** Every query is a `PreparedStatement`; the only string-concatenated SQL is
  `createIndex` and `topProfiles`, both fed exclusively by compile-time constants. All
  `Connection`/`PreparedStatement`/`ResultSet` uses are try-with-resources.
- **Reward double-claim.** Per-UUID monitor + `claiming` set + an authoritative conditional DB
  update (`tryMarkDailyClaim` / `tryMarkMilestoneClaim`) as the single winner-selection point.
- **Number parsing.** Every `parseInt`/`parseDouble` on player input is inside a `try/catch`.
- **Chat handlers.** All three `AsyncChatEvent` listeners hop to the main thread via `runTask`
  before touching any Bukkit API. Echoed search text goes through `MessageUtil.escapeUserInput`.
- **Shop.** `canFit` pre-check, `takeExact` before delivery, `purchasing` re-entrancy set, and
  console-command placeholders sanitised to `[A-Za-z0-9_.-]`.

---

## Potential exploits a player would realistically try

1. **The kit mint (P2-C1)** — win, alt-F4, reconnect, drop, relog, collect. Now closed.
2. **Free win-trading (P2-M1)** — two accounts, one large wager, alternate wins. Costs nothing at
   the default 0% house cut and pushes three leaderboards. Now flagged; still not blocked.
3. **Alt-farming FFA kill rewards** — there is no repeat-kill cooldown, no same-IP heuristic and
   no diminishing return on `kill-reward`. An alt on a second client is a coin faucet limited only
   by respawn time. Unaddressed by design; see recommendations.
4. **Bounty laundering** — place a bounty on your own alt and claim it. Balance nets to zero, but
   `coins_spent` and `coins_earned` both inflate for free. Neither is a leaderboard category, so
   the impact is confined to `/stats` vanity.
5. **Restart-window item loss (P2-H1)** — not an exploit as such, but a player who noticed it
   could time a disconnect around the scheduled restart to keep a duel kit. Now closed.

---

## Untested / not fully covered

Stated plainly so it is not mistaken for coverage:

- **No runtime testing.** There is no test suite and no server was available. Everything here is
  static analysis plus a clean `mvn -o clean package`. Every fix above is marked "testing
  required" for a reason — the duel inventory cycle in particular deserves manual play-testing.
- **Read in full:** `DuelManager`, `DuelListener`, `DuelState`, `DuelQueueManager`,
  `SpectatorManager`, `CombatListener`, `GuiListener`, `HeldShulkerListener`, `PlayerListener`,
  `LoadoutManager`, `ShopManager`, `BountyManager`, `AfkZoneManager`, `ItemCleanupManager`,
  `KillstreakManager`, `AntiGlitchListener`, `KitEditGui`, `KitAdminEditGui`, `DisposalGui`,
  `EconomyManager`, `UnstableCore`, `TagsGui`, `TagsCommand`, `SpectateCommand`, `plugin.yml`,
  `pom.xml`.
- **Read in the areas that matter, not line-by-line:** `DatabaseManager` (2120 lines — connection
  setup, DDL/index/migration, and a full survey of every SQL construction site and resource scope;
  the individual CRUD bodies were not each read end-to-end), `ArenaManager` (1325 — join/teleport/
  auto-kit/cleanup paths), `ArenaListener` (684 — full handler inventory, protection logic spot-read),
  `KitManager` (930 — `applyKit`/`getEffectiveContents`/persistence), `RewardsManager` (claim and
  locking paths), `LeaderboardManager` (threading and cache paths), `MapVoteManager` (vote paths),
  `UnstableCoreCommand` (dispatch and all parse sites), `config.yml` (first ~300 of 547 lines).
- **Not audited:** `MessageUtil`, `ItemBuilder`, `SmallCaps`, `UnstablePlaceholders`,
  `WorldGuardHook`, `DuelArenaManager`, `DuelScoreboardManager`, `SettingsManager`,
  `PlaytimeManager`, `KitRankManager`, `LeaderboardListener`, `BountyListener`, and the display
  GUIs (`BountyBoardGui`, `PlaceBountyGui`, `StatsGui`, `DuelMapGui`, `LeaderboardCategoryGui`,
  `KitsGui`, `KitPreviewGui`, `VoteGui`, `ArenaGui`, `SwordGui`, `ShopGui`, `RewardsGui` beyond
  its click dispatch). The 26 `kits/*.yml` files and `dailyrewards.yml` / `leaderboard.yml` /
  `tags.yml` / `shop.yml` were not read in full.
- **Third-party surface not reviewed:** Vault's economy provider, LuckPerms and WorldGuard are
  trusted as-is. A duplication bug inside the economy provider is invisible from here.
- **Single-server assumption still holds.** Bounty ID allocation and arena reservation assume one
  process owns the database. Reward claims are the exception — those are safe cross-process
  because the claim is decided by a conditional UPDATE.

---

## Recommended future improvements

**Security / abuse**
1. Add an FFA anti-farm control to match the duel one: a short per-(killer, victim) repeat-kill
   cooldown, or a decaying reward for repeated kills on the same victim. This is the single
   largest remaining economic hole and it is entirely unmonitored.
2. Give the anti-farm detector teeth *optionally* — a config switch to withhold `duel_coins_won`
   /ELO credit from a flagged pair, rather than only logging.
3. Set a non-zero `house-cut-percent` default. A 0% rake makes wager cycling free by construction.
4. Move the `tags.*` permission check from `TagsGui` into `TagManager.equip` so the check cannot
   be bypassed by a future caller (P2-L6).

**Reliability**
5. Introduce a payout ledger. Failed Vault deposits currently log `SEVERE` and are owed manually;
   a persisted retry queue would make that self-healing.
6. Give `Player`-handle-across-a-scheduled-task its own helper (`withOnlinePlayer(uuid, action)`)
   so P2-C1 cannot recur. This exact pattern appears in `ArenaManager` too, where it happens to
   fail safe.
7. Regression tests. The duel inventory cycle now has enough invariants (restore exactly once,
   never into a live inventory, never lost) that they should be asserted, not re-derived by audit.

**Performance / scale**
8. Convert the `loadAll*()` managers to lazy per-player loading with eviction on quit (P2-M5).
9. Reward claims hold a synchronous JDBC round trip on the main thread by design. That is the
   right call for atomicity, but on MySQL with any latency it is a visible stall — consider
   moving the conditional UPDATE off-thread and applying the result back on the main thread.
