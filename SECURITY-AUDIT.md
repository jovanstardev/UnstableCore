# UnstableCore — Security, Exploit & Stability Audit

Adversarial full-source review of the plugin (101 Java files, 14 configs, ~32k lines),
assuming cooperating, malicious and packet-modifying clients.

**36 issues found · 36 fixed · 0 open · 27 files changed**

Build verified: `mvn -o clean package` → BUILD SUCCESS with JDK 21.0.12 / Maven 3.9.16.
Only pre-existing `HOTBAR_MOVE_AND_READD` deprecation warnings remain.

| Severity | Found | Fixed |
|---|---|---|
| Critical | 7 | 7 |
| High | 6 | 6 |
| Medium | 11 | 11 |
| Low | 9 | 9 |
| Info | 3 | 3 |

Three themes accounted for nearly all of it: **item generation** (five independent
unlimited routes), **consequence suppression** (a pending duel request switched off
death handling, killstreak loss, kill rewards, bounty payouts and combat tagging), and
**persistence fragility** (one failed read at boot would have wiped all player data).

---

## Critical

### C1 · Autosave wiped all player data if the startup read failed
`DatabaseManager.saveAllStats / saveAllCombat / saveAllSettings / saveAllTags / saveAllLoadouts / saveAllLoadoutNoCooldown`

Each bulk save ran `DELETE FROM <table>` then re-inserted from memory. The loaders swallow
`SQLException` and return an **empty** map, so the managers cannot tell "no data" from "the
read failed". Any transient boot failure — a locked SQLite file, a MySQL blip, a slow
container — meant the 5-minute autosave deleted the real rows and wrote nothing back.
Irreversible loss of kills, best streaks, coins earned/spent, deaths, killstreaks, tags,
all settings and every loadout cooldown.

**Fix:** removed every DELETE-then-reinsert. Rows are upserted; rows that genuinely return
to the default/empty state are deleted individually by key. Loadout tables expire by
explicit age predicate rather than by omission, so an empty map can never clear everyone's
cooldown.

### C2 · Duplication — shulker contents staged in a virtual GUI at snapshot time
`DuelManager.runSetupSequence` + `HeldShulkerListener`

The held-shulker feature opens a detached inventory holding a *copy* of the box's contents
and only writes them back into the item's NBT on close. `DuelInventorySnapshot.capture()`
clones the live item — still carrying pre-edit contents — while the items already dragged
out sit loose in the same storage array. Send a request, open a shulker, drag its contents
out, leave the GUI open; when the opponent accepts, the snapshot records both copies and
restores them verbatim at duel end.

**Fix:** `settleOpenInventories()` flushes any held-shulker session and closes the container
before both snapshots are captured, so the snapshot describes exactly one inventory state.

### C3 · Duplication — shulker session wrote into a different box after a held-slot swap
`HeldShulkerListener.saveToItem`

The session recorded only the hand, not the slot, and validated only "is a shulker of the
same type, amount 1". A vanilla client can't scroll the hotbar with a container open, but a
modified one can still send the packet — so holding two same-coloured shulkers, opening one
and switching slots wrote the session's contents into the second while the first kept its own.

**Fix:** the session stores its hotbar index; `PlayerItemHeldEvent` is cancelled while a
main-hand session is open; `saveToItem` refuses to write on a slot mismatch.

### C4 · Unlimited kits — arena auto-kit read the cooldown but never started it
`ArenaManager.giveRandomKitIfEmpty`

The method checked `remainingMillis() > 0` and bailed, but on success never recorded a use,
so the cooldown stayed at zero forever. Drop everything, click the arena join button, get a
complete random unlocked kit, repeat as fast as you can click. Unbounded generation of
paid-kit gear, freely transferable by dropping it.

**Fix:** added `LoadoutManager.markUsed(Player)` (persisted; a no-op for legitimate bypass
permissions and active no-cooldown grants) and invoke it when the kit is actually granted.

### C5 · Every respawn on the server cleared the inventory and issued a free kit
`DuelListener.onRespawn`

The duel-specific respawn hook called `restorePlayerPostDuel` unconditionally. For a
non-duel respawn the snapshot is `null`, and that branch clears the inventory then calls
`KitManager.applyLoadout` directly, bypassing `LoadoutManager`. Dying became a faster, free
replacement for `/loadout` — and any inventory a player respawned holding was destroyed.

**Fix:** the duel hook only acts on a genuinely queued duel respawn. The re-gear behaviour
was **not deleted** — it moved to `PlayerListener.onRespawn` as an explicit feature that
routes through `LoadoutManager.tryGive` (respects and consumes the cooldown), fires only on
an empty inventory so it can never delete items, and is switchable via
`loadout.give-on-respawn`.

### C6 · Duel gear extracted from the arena by dropping it mid-fight
`DuelManager.finishDuel` / `DuelListener`

Duels issue a plugin-chosen kit and restore the real pre-duel inventory over it on every
exit path. Nothing stopped a duelist dropping that kit, and nothing cleaned the arena.
The arena is an ordinary world location — walk back once released, or have an accomplice
stand in it during the fight (the mutual-hide wall is visual only; it blocks neither
collision nor item pickup). One full kit minted per duel, with unlimited duels via the queue.

**Fix:** two layers. `PlayerDropItemEvent` is cancelled during a committed duel, and
`clearArenaDrops()` sweeps loose items inside the arena on every release path (finish, grace
expiry, setup rollback, countdown death) using a chunk-bounded query.

### C7 · Free kit at the end of every duel entered empty-handed
`DuelManager.restorePlayerPostDuel`

With an empty pre-duel snapshot the fallback branch called `KitManager.applyLoadout`
directly. Empty your inventory, queue, finish, receive a full kit, requeue — entirely inside
intended game flow.

**Fix:** the fallback goes through `LoadoutManager.tryGive`. The duel kit is still taken back
and the default kit still selected; only the re-gear is now cooldown-gated.

---

## High

### H1 · A pending duel request switched off all death consequences
`CombatListener.onDeath`, `CombatListener.handleQuitCombatTag`, `DuelListener.onDeath`

All three gated on `isInDuel()`, which is `true` from the moment a duel is *requested*.
Holding a standing request (they last 30s and re-send freely) meant: keep your killstreak
through death, record no deaths, drops silently voided, death message suppressed — and your
killer received no coins, no streak credit and no bounty payout. Combat-logging was exempted
too. Corrupts the leaderboards and the kill economy simultaneously.

**Fix:** added `DuelManager.isInCombatDuel()` — true only from accept onwards — and switched
every combat/inventory consequence to it. `isInDuel` stays where it belongs: preventing a
second concurrent duel.

### H2 · A pending request also made the holder impossible to combat-tag
`CombatListener.onCombatMonitor`

Same confusion applied to tagging. Every gate using the combat tag as an anti-escape check
was bypassed at once — arena join, `/spec`, queue join, duel request validation — giving a
free, instant escape from any losing fight.

**Fix:** switched to `isInCombatDuel`.

### H3 · The assigned duel kit could be swapped for your own mid-fight
`LoadoutManager.tryGive`, `DisposalCommand`, `duels.yml`

`restricted-commands` lists only teleport commands, and nothing enforced this server-side, so
aliases and internal callers bypassed it entirely. `/loadout` mid-duel re-applied your own
kit over the duel kit; `/trash` then `/kits` did the same via the auto-equip path.

**Fix:** server-side refusal in `LoadoutManager.tryGive` (covers `/loadout`, aliases, the
`/kits` auto-equip and any future caller) plus a disposal-GUI block during a committed duel.

### H4 · `/stats <name>` scanned every player data file on the main thread
`StatsManager.resolvePlayer`

On a cache miss it fell through to `Bukkit.getOfflinePlayers()`, which stats and parses the
entire `playerdata` directory synchronously. Any player, no permission required, could stall
the main thread on demand by looking up names that don't exist — the miss is the expensive
path. Also reachable via `/uc economy` and `/dueladmin`.

**Fix:** removed the scan; resolution is online → profile-name cache → Paper's non-blocking
`getOfflinePlayerIfCached`. The name cache also gained an O(1) reverse index.

### H5 · Background full-table saves blocked main-thread reward claims
`DatabaseManager` — all ten `synchronized` methods

Bulk saves and small atomic operations shared one instance monitor, and the bulk saves hold
it for the whole transaction on the async autosave task. A claim or bounty placement on the
main thread blocked until the full save finished.

**Fix:** split into `bulkSaveLock` and `atomicOpLock` via thin delegating wrappers.

### H6 · Challenger's eligibility was never re-checked at accept time
`DuelManager.runSetupSequence`

`isBusy(challenger)` ran when the request was *sent*, but the target may sit on it for the
full timeout. Send a request to a friend who waits, start an FFA fight, and have them accept
when you're losing — you're teleported out of the arena.

**Fix:** `isBusy` now also runs in `runSetupSequence`, rolling back cleanly if it fails.

---

## Medium

| ID | Issue | Location | Fix |
|---|---|---|---|
| M1 | Duel visibility restore unsealed other live duels | `DuelManager.removeDuelVisibility` | Skip players inside another active-combat duel; their own restore completes the pairing symmetrically |
| M2 | Offline forfeit loser lost their pre-duel inventory | `DuelManager.finishDuel` | Falls back to the restore-on-next-join queue |
| M3 | GUIs reopened from inside `InventoryClickEvent` | `GuiListener.onClick` | Handlers deferred one tick with a holder identity re-check |
| M4 | Refunds counted as lifetime income | `EconomyManager.deposit` | Added `refund()`; applied to all give-back paths |
| M5 | Bounty cap enforced only after withdrawing | `BountyManager.placeOrStack` | Cap pre-checked; authoritative re-check retained under the lock |
| M6 | Synchronous reward DB write on every quit | `RewardsManager.unload` | Async with safe cache eviction and a disable-time synchronous fallback |
| M7 | Map-vote GUI force-reopened over active fights | `MapVoteManager.keepOpenUntilVoted` | Duelists, grace-period and combat-tagged players exempt |
| M8 | Typo-catcher could swallow non-plugin commands | `PlayerListener.onCommandPreprocess` | Queries the real command map |
| M9 | Arena join could re-scan terrain and rewrite YAML per click | `ArenaManager.teleportToArena` | Throttled to once per minute per arena; no blocking file write |
| M10 | Profile sync spawned one async task per online player | `LeaderboardManager.startProfileSync` | Single batched upsert |
| M11 | Action-bar balance TTL below its own tick interval | `ActionBarManager` | TTL raised above the tick; deposits already invalidate |

---

## Low

| ID | Issue | Location | Fix |
|---|---|---|---|
| L1 | MiniMessage injection via echoed search text | `BountyManager.handleChat` | `MessageUtil.escapeUserInput` applied at both echo sites |
| L2 | Name lookup was O(all players ever seen) | `LeaderboardManager.findUuidByName` | Maintained lower-cased reverse index |
| L3 | Chat prompts swallowed all chat until answered | Duel wager, bounty amount, leaderboard search | All three bounded by `chat-prompt-timeout-seconds` (default 60), re-armed prompts get a fresh window |
| L4 | Pending inventory restores were not persisted | `DuelManager.pendingCrashRestores` | New `pending_restores` table; queued on owe, cleared on apply, reloaded at boot |
| L5 | Placed-block set unbounded, arbitrary truncation | `ArenaManager.placedBlocks` | Insertion-ordered set, oldest-first eviction, in-memory cap via `arena.max-placed-blocks` |
| L6 | Kit slots 52–53 silently discarded | `KitManager.applyKit` | Slots stay reserved for the editor's buttons, but a load-time warning now names the kit and slot instead of dropping in silence |
| L7 | Blocking Mojang lookups on the main thread | `LeaderboardManager.resolveName`, `DuelManager.nameOf`, `DuelAdminCommand.nameOf`, `DuelHistoryGui` | Non-blocking cache lookups; unknown names resolved off-thread and appear next refresh |
| L8 | "Match found" announced before it could fail | `DuelQueueManager` | Announce and dequeue only after `createQueueMatch` succeeds; failures stay queued for retry |
| L9 | `/spec` allowed from inside an FFA arena | `SpectatorManager.startSpectating` | Arena residency now refused, matching duel requests and the queue |

`L7` was worse than first assessed: `resolveName` runs once per leaderboard entry — up to
`max-entries` (500) — so one cold leaderboard open could stall the server for as long as 500
sequential web requests.

---

## Info

| ID | Issue | Fix |
|---|---|---|
| I1 | Shop console dispatch interpolated `{player}` unsanitised | Command-path placeholders sanitised separately from display ones; a space in a bridged Bedrock name can no longer shift a reward command's arguments |
| I2 | Kit YAML reload race | `load()` holds `saveLock` across the config reassignment and `loadPlayerData()`, so an already-running batched save can't wipe the freshly-loaded config |
| I3 | Reward claims synchronous on the main thread | Kept synchronous by design — atomicity prevents double-claiming — but now on a dedicated lock so they can't queue behind bulk saves |

---

## Exploit categories tested

**Pass:** money duplication · reward duplication · negative/NaN/infinite values · double and
race claiming · disconnect and death exploits · transaction cancellation · shift-click, drag,
double-click and number-key GUI handling · GUI cursor retention · arena overlap and double
reservation · ELO farming detection · permission bypass · argument injection · tab-completion
leaks · offline-player/UUID confusion · alias and case bypass · async database races · cache
inconsistency · chunk and world unload handling · client-trust boundaries

**Was failing, now fixed:** item duplication (C2, C3, C6) · item generation (C4, C5, C7) ·
item loss (C5, M2, H1) · kit duplication · cooldown bypass · combat-tag bypass (H2) · arena
escape (H6) · duel kit bypass (H3) · fake kills / reward denial (H1) · GUI reopen desync (M3) ·
crash and freeze conditions (H4, M9) · spectator abuse (L9)

**Not applicable:** NBT / illegal item creation (no NMS, no packet handling, no custom NBT
parsing) · cross-server synchronisation (single-server plugin; no proxy messaging, plugin
channels or Redis)

---

## Config keys added

```yaml
chat-prompt-timeout-seconds: 60   # bounds duel/bounty/leaderboard chat prompts

arena:
  max-placed-blocks: 50000        # oldest-first eviction cap

loadout:
  give-on-respawn: true           # cooldown-aware, non-destructive respawn re-gear
```

`duels.yml` gained `messages.drop-blocked`, `messages.loadout-blocked` and
`messages.disposal-blocked`.

## Schema added

`pending_restores (uuid PRIMARY KEY, snapshot TEXT, created_at BIGINT)` — pre-duel
inventories owed to an offline player, so a restart in that window no longer drops them.
Created automatically on startup; no migration needed.

---

## Remaining risks

- **Not runtime-tested.** The project has no test suite and no server was available. The
  code compiles and packages cleanly, but the duel, kit and reward flows deserve manual
  play-testing before a production rollout.
- **Gameplay-visible change.** Free kits are now genuinely rate-limited. Respawn re-gear
  still happens by default but consumes `loadout.cooldown-seconds`, and arena auto-kit does
  the same. On a server used to unlimited kits this will feel like a difficulty increase —
  that is the cooldown working as configured. Lower `cooldown-seconds` for a faster pace, or
  set `give-on-respawn: false` for a stricter ruleset.
- **Third-party surface not reviewed.** Vault's economy provider, LuckPerms and WorldGuard
  are trusted as-is; a duplication bug inside the economy provider is invisible from here.
- **Economy deposit failures are best-effort.** A rejected duel payout logs `SEVERE` with the
  player and amount, but there is no retry queue or ledger. Worth monitoring for that line.
- **Single-server assumption.** Bounty ID allocation, claim atomicity and arena reservation
  all assume this is the only process writing the database. Do not point two servers at one
  MySQL instance without revisiting them.
- **No regression tests.** Nothing prevents these bugs returning. The architectural pattern
  behind most of them — several subsystems each holding their own idea of "is this player
  busy" and "does this player deserve a kit" — is corrected at every site found, but is not
  yet centralised or enforced by the type system.
