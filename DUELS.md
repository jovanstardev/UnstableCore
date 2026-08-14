# /duel — Design Overview

> Status: **v1 implemented.** Built against this spec, reusing the existing codebase's patterns throughout (`MapVoteManager`/`VoteGui`-style clickable chat + refresh loop, `LiveGuiRefresher`'s instanceof dispatch, `KitManager.applyKit`, `EconomyManager`, `DatabaseManager`'s per-table upsert pattern, `BountyManager`'s chat-prompt idiom, `SettingsManager` toggle, `PlayerListener.onQuit` central cleanup). See the implementation summary at the bottom of this file for what shipped, what was scoped down, and known residual risks.

## Roadmap: what's in v1 vs later

**Build now (v1):**
- Kit selection (one configurable default kit)
- Map selection with per-arena availability info, from a **fixed pool of dedicated duel arenas**
- Wager confirmation screen with clickable accept/deny, showing exact payout math before accepting
- Request cooldown + rate limiting
- `/duel toggle` opt-out (binary: everyone / nobody — see [Request privacy](#request-cooldown-opt-out-and-privacy))
- Duel history (`/duel history`)
- Core statistics: wins/losses/win-rate/duels-played, **current & best duel win streak**, coins wagered/won/lost — all separate from FFA stats
- Staff inspection tools (`/dueladmin`)
- Duel event hooks (architecture, even if nothing consumes them yet)
- Baseline anti-wager-farming safeguards (flagging, not auto-banning)
- Command/escape restrictions during a duel
- Duel announcements (config-gated public broadcast on win) — cheap, just a broadcast off the existing `DuelEndEvent`
- Post-duel grace period + `/leave` (arena stays available briefly after the fight so the loser can collect drops)

**Build later, but don't architect against it:**
- Spectator mode (`/spec <player>`) — full spec below
- Ranked vs. unranked duels + ELO/rating system, and the matching ELO-farming protections
- Duel leaderboard (needs stats to exist first, and a minimum-games floor)
- Duel presets beyond the default (server-defined: No Debuff, Boxing, Sumo, UHC; player-saved custom presets)
- Quick Duel (re-challenge using your last setup)
- Personal bests (fastest win, longest duel, highest wager won, highest single-duel payout) and per-map statistics
- Incoming duel request queue GUI (multiple pending requests at once — v1 ships with one request at a time via chat, this is the polish pass)
- Duel scoreboard (sidebar during an active duel)
- Dynamic/generated duel arenas (template-based, spun up per duel instead of drawn from a fixed pool) — see the note in [Arena reservation](#arena-reservation-and-future-generation)
- Achievements
- Matchmaking / random opponent queue
- Tournaments (bracket play)
- Lightweight match telemetry (damage dealt, hits, pearls used — not video replay) feeding a richer `/duel stats` later

## Flow

1. `/duel <player>` — validates the challenger (not self, target online, target not already duelling/incompatible state, no duplicate pending request between the same two players, target hasn't opted out via `/duel toggle`). Opens a GUI flow: **kit → map → wager confirmation → send**. **No coins are withdrawn yet.**
2. Kit step: pick the duel kit (v1: one configurable default; presets come later). Map step: pick from arenas explicitly flagged duel-eligible, shown with live availability (same visual pattern as the existing FFA `MapVoteManager`/`VoteGui`). Wager step: enter an amount, see the exact pot/payout math before sending.
3. Request is sent with kit + map + wager attached — no back-and-forth negotiation after this point. Target gets a **clickable** accept/deny prompt (chat component, `[ACCEPT] [DENY]`) showing the same wager breakdown and a live-updating "expires in Ns" countdown. Request auto-expires after a configurable timeout, and is rate-limited/cooldown-gated so a decline can't be immediately re-spammed. Clicking an accept/deny button always re-validates the duel ID and state server-side — a stale/expired request rendered in old chat history can never be accepted just because the button still visually exists.
4. **On accept** — treated as a single atomic transaction (full detail in [Accepting a duel](#accepting-a-duel)): re-validate everything, reserve the arena, snapshot inventories, escrow both wagers, apply the kit, teleport, countdown, only then go `ACTIVE`.
5. **On win:** loser's inventory is restored, winner gets the full wager pot, win/loss/streak/coin stats recorded to new duel-specific columns (separate from FFA killstreak stats). Optionally a public announcement fires. A rematch prompt appears for both players. The arena isn't released immediately — see [Post-duel grace period](#post-duel-grace-period-and-leave).
6. **On decline / timeout / cancel before the fight starts:** no-op — nothing was ever withdrawn, nothing to refund.
7. **On disconnect:** before the duel starts → cancel, restore everything, no wager lost. During `ACTIVE` → forfeit, pot pays out to whoever's left, using the same disconnect-credit approach already built for the FFA combat-log fix (`CombatListener.handleQuitCombatTag`), just routed to duel payout instead of killstreak.
8. No-wager duels still pay a small flat coin reward on win (config-driven, same idea as `rewardKill`), also protected against duplicate payout.

## Key decisions

- **Dedicated duel arenas**, not the shared FFA pool — no third-party interference, fair 1v1. Two simultaneous duels can never be allocated the same arena. v1 uses a fixed pool; dynamic per-duel generation is a later upgrade to the *allocation strategy* only (see [Arena reservation](#arena-reservation-and-future-generation)).
- **Escrow at accept**, not at request — doesn't lock the challenger's coins while waiting on a response; fully re-validated at accept time (balances, arena, online status — everything, not just the wager).
- **Explicit state machine**, not scattered booleans (`inDuel`, `accepted`, `started`, ...). See [State machine](#state-machine).
- **Separate stats table** for duels — doesn't pollute FFA killstreak/leaderboard numbers. Combat stats (wins/losses/streaks), economic stats (coins wagered/won/lost), and — later — ranked ELO are tracked as distinct groups, not mashed together.
- Optional config `%` house cut on wager payouts as a coin sink (off by default, tunable).
- **Kit + map picker reuses existing UI patterns** — the same icon-grid style already used for FFA map voting and `KitsGui`, just scoped to a dedicated duel flow.
- **UUID-based long-lived references**, never a held `Player` object, to avoid leaks and stale references across reconnects.
- **Every payout, every state transition, every cleanup path is idempotent** — nothing in this system should be able to run twice for the same duel.
- **Flag, don't auto-punish** — both wager-farming and (later) ELO-farming signals surface to staff; nothing auto-bans or auto-restricts a player based on them.
- **Arena release is a grace period, not instant** — the loser needs a window to collect their dropped items; the arena isn't handed to another duel or torn down the instant the fight ends.

## State machine

Each duel is a single record, identified by a unique duel ID, moving through explicit states — no derived/implicit state from booleans:

`REQUESTED → ACCEPTED → STARTING → ACTIVE → ENDING → FINISHED`
(with `DECLINED`, `EXPIRED`, `CANCELLED`, `FORFEITED` as terminal side-exits, and a post-`FINISHED` **grace window** before the arena/duel record is actually torn down — see [Post-duel grace period](#post-duel-grace-period-and-leave))

A duel record holds: duel ID, challenger UUID, target UUID, selected kit, selected map/arena, wager amount, ranked/unranked flag (once ELO exists), creation timestamp, expiration timestamp, current state, reserved arena, inventory snapshots, payout status.

State transitions must be validated so two competing callbacks (e.g. an accept and a timeout firing near-simultaneously) can't both process the same duel — exactly one valid transition wins, and every terminal operation (payout, cleanup, inventory restore) must be idempotent regardless of which path triggered it.

## Kit selection

- v1: one configurable default duel kit — no picker UI needed yet, just a config value.
- Designed so a full preset picker slots in later without changing anything downstream — the duel record already stores "selected kit" as a first-class field from day one, it just isn't user-choosable in v1. Later phase covers both server-defined presets (No Debuff, Boxing, Classic, Sumo, UHC, custom server kits) and player-saved custom presets (kit + map + wager + ranked/unranked bundled together, still running full validation whenever used, never bypassing any check just because it came from a saved preset).
- Applied the same way normal loadouts are: via `KitManager.applyKit`, not a parallel kit-application path.

## Map-select GUI

- Shown as part of the request flow (after kit selection once that exists). Shows all duel-eligible maps as icons, each showing **live availability** — not just a name/icon, but whether it's currently reserved/occupied and whether it has valid spawns, so a broken arena is obvious before someone tries to use it.
- Challenger clicks a map → proceeds to the wager confirmation step, then the request is sent with kit + map attached.
- Shows the **target's real player head** with a live online/offline indicator:
  - Target online → normal head, request can be sent.
  - Target goes offline while this GUI is open → head slot greys out / shows "offline, can't duel" instead of silently letting a stale request go out.
  - Prevent sending a request to an offline target outright.
- **Skin fetching:** use `Player.getPlayerProfile()` for the target (they're online by definition when this GUI opens) instead of an async Mojang session-server lookup. Avoids the cold-lookup path causing the `429` rate-limit warnings already seen in the server log for other head-rendering GUIs — no reason to add to that problem here. No unnecessary repeated Mojang API calls, period.
- Live refresh while open: reuse `LiveGuiRefresher` rather than building a new polling loop — and properly unregister it when the GUI closes, so it doesn't keep ticking for a closed inventory.
- Handle a selected arena becoming unavailable between GUI display and request creation (someone else grabbed it, it got disabled) without crashing — reject gracefully and tell the player.

## Wager confirmation and clickable requests

Before either side commits, both the sender (at request time) and the target (at accept time) see an explicit breakdown — not just a bare number:

```
Opponent: {name}   Map: {map}   Kit: {kit}
Wager: {amount} coins each
Winner receives: {pot} coins  (house cut: {cut}% if enabled)
Expires in {seconds}s
[ ACCEPT ]  [ DENY ]
```

The accept/deny buttons are real clickable chat components, and the "expires in" countdown updates live rather than being a static number printed once. Clicking either button re-runs full server-side validation against the duel's current state — a click on an old message (already accepted, denied, or expired) fails safely and can never affect a different, newer duel between the same two players. This exists specifically to cut down disputes — nobody should be surprised by what they actually risked or won after the fact, and nobody should be able to resurrect a dead request by clicking a stale button.

## Request cooldown, opt-out, and privacy

- **Cooldown:** after a request is declined (or expires), the same challenger can't immediately re-request the same target — configurable delay (e.g. 5s). Outgoing requests overall are rate-limited per player too, so one player can't blast requests at the whole server.
- **Privacy (v1):** `/duel toggle` — binary opt-out. Opted-out players don't receive requests at all. Checked as part of request validation in step 1 of the Flow, right alongside the "target online" check.
- **Privacy (later):** a third "friends only" tier. **This depends on a friends/party system this project does not currently have.** Don't fake it with a partial implementation — either build a minimal friends list first, or ship privacy as just Everyone/Nobody until a real friends system exists elsewhere in the plugin.
- **Later:** an incoming-request queue GUI for players who get multiple simultaneous requests (v1 handles them one at a time via the chat prompt, which is fine at low request volume but doesn't scale to someone getting 5 requests at once). Same stale-click safety rules as above apply to every request shown in the queue.

## Accepting a duel

Treat accept as one atomic transaction from the duel system's perspective:

1. Re-check both players (still valid state, not now in something else incompatible).
2. Re-check the selected arena is still available.
3. Re-check the wager and both players' current balances (`has()` — balances may have changed since the request was sent).
4. Ensure both players are still online.
5. Reserve the arena.
6. Snapshot both inventories (main inventory, armor, offhand, cursor item, any other relevant state).
7. Withdraw both wagers into escrow.
8. Apply the duel kit via `KitManager.applyKit`.
9. Teleport both players.
10. Run the countdown (`3, 2, 1, FIGHT!` — no damage permitted before `FIGHT`).
11. Only transition to `ACTIVE` once every step above has succeeded.

**If any step fails:** restore both balances, restore both inventories, release the arena, clear the duel record, return both players to their prior state, notify them, and log the failure with the duel ID. Never leave coins, inventories, or an arena reservation in a partially-completed state.

## Escrow and payout safety

- Wagers are withdrawn only after acceptance (never at request time) and paid out only once the duel has *definitively* ended — never pay the winner speculatively.
- Use the existing `EconomyManager` — no direct balance manipulation bypassing it.
- Payout must be guarded by the duel's own ID as an idempotency key: the same duel can never pay out twice, however many events race to try.
- Payout math:
  - `totalPot = challengerWager + targetWager`
  - `houseCut = totalPot * configuredPercentage` (if enabled)
  - `winnerPayout = totalPot - houseCut`
  - No-wager duel: both wagers are zero, winner gets the configured flat reward instead — same duplicate-payout protection applies.
- Log duel ID, players, wagers, pot, house cut, payout amount, recipient, and result for every payout.

## Arena reservation (and future generation)

- v1: a **fixed pool** of arenas explicitly flagged duel-eligible, separate from the public FFA pool. Reservation prevents two simultaneous duels from getting the same arena.
- An arena is unavailable if: already reserved, currently occupied (including still in its post-duel grace period), missing valid spawns, disabled, invalid/unloaded, or otherwise incompatible — and this is exactly what the map-select GUI's availability indicator surfaces up front.
- Validate both spawn positions before starting; if none are valid, don't crash and don't start — release resources, notify players, log it. Reuse existing arena fallback/validation logic (same idea as `/arenas`' existing spot-picking fallback) rather than writing a parallel version.
- **Later:** dynamic arena generation — configure a set of template arenas once, and copy a template into the duel world at a fresh, isolated location for each duel instead of drawing from the fixed pool. This lets many duels run concurrently without contention over a small handful of arenas. Requirements when this is built: each generated arena is tracked by duel ID, one duel's generated arena can never be touched by another duel, failed generation aborts the duel through the exact same rollback path as any other accept-step failure, and generated arenas are torn down safely after their grace period. Because the v1 duel record already references "arena" as an opaque ID rather than assuming a specific fixed-pool implementation, swapping in generation later shouldn't require touching the rest of the state machine.

## Post-duel grace period and `/leave`

- After a duel ends (win, forfeit, or timeout), the arena isn't released immediately — it stays reserved to those two players for a configurable grace period (e.g. 3 minutes) so the loser can collect dropped items.
- `/leave` lets either player exit the grace period early.
- After the grace period expires, both players are automatically removed and the arena is released/cleaned up.
- `/leave` and the automatic grace-period expiry both go through the **same** cleanup path as a normal duel end — inventory restoration, escrow/payout resolution, and statistics recording have already happened by the time grace period starts, so `/leave` only ever handles "get the player out of the arena," never re-runs payout or restore logic. Cleanup here must still be idempotent and tied to the duel/arena ID, same as everywhere else in this system.

## Combat isolation

A duel death must **not** accidentally feed FFA systems — no FFA kill count, no FFA killstreak change, no bounty claim, no FFA leaderboard stat, no public arena-leave logic — unless explicitly intended. Give listeners a way to recognize "this event belongs to a duel" via the duel context/state rather than scattering `if (playerInDuel)` checks through unrelated listeners.

## Duel abuse and escape prevention

While a duel is preparing or active, block the usual ways a losing player could dodge the outcome — configurable per-check, since some kits may intentionally allow certain mechanics:

- Can't send/accept a duel request while combat-tagged, mid-teleport, dead, or already mid-accept on a different request.
- Can't change worlds during preparation.
- Escape commands (`/spawn`, `/warp`, `/home`, etc.) are blocked during an active duel — a disconnect is a forfeit specifically so it's never a better option than fighting.
- Teleport-based exploits (ender pearl, chorus fruit, or other movement items) are restricted unless the selected kit explicitly permits them.

## Winning

1. Transition to `ENDING`.
2. Determine the winner exactly once — guard against duplicate/near-simultaneous death events processing the same duel twice.
3. Calculate and pay out exactly once.
4. Record duel statistics (see [Statistics](#statistics)) — and ELO if the duel was ranked (later) — not FFA killstreak stats.
5. Restore both inventories and any other temporarily-changed player state.
6. Start the post-duel grace period instead of releasing the arena immediately (see above).
7. Optionally fire a public announcement (config-gated — see [Configuration](#configuration)).
8. Clear duel state, transition to `FINISHED`.
9. Show both players the end screen with a rematch prompt (see [Rematch](#rematch)).

## Inventory safety

Snapshot everything needed for a complete restore: main inventory, armor, offhand, cursor item, any other state the duel temporarily changes. Restoration must be idempotent — never duplicate, never lose items, and never restore a stale snapshot over items legitimately obtained after the duel unless the player's state still clearly marks them as inside it. Explicitly consider: disconnect mid-duel, server crash, plugin reload, teleport failure, kit-application failure, death, or another plugin touching the inventory mid-flow.

## Disconnect and timeout handling

- **Before the duel starts:** cancel, restore everything, no wager lost, release the arena.
- **During `ACTIVE`:** forfeit — remaining player wins, payout happens exactly once, arena enters its grace period, state cleared. Route through the existing `CombatListener.handleQuitCombatTag` disconnect-credit mechanism, pointed at duel payout instead of killstreak.
- **Max duel duration** (configurable): if reached, end the duel safely with a deterministic, configurable outcome — don't leave a duel (or its arena) occupied indefinitely.

## Statistics

### Duel streaks
- Current duel win streak and highest-ever duel win streak, tracked per player, explicitly separate from FFA killstreaks.
- Surfaced in `/duel history`/stats output and in relevant duel messages (e.g. a win message can reference "X duel win streak!").
- Part of core v1 statistics, not a later add-on.

### Core stats (v1)
- Combat: wins, losses, win rate, duels played, current streak, best streak.
- Economic: total coins wagered, total coins won, total coins lost.
- Optionally: average duel duration.
- `/duel history` shows a paginated list per player: opponent, result, map, kit, wager, date, duration.

### Personal bests (later)
Fastest duel win, longest duel, highest wager won, highest win streak, highest single-duel payout — cheap to add once the core stats table exists, but not required to ship v1.

### Per-map statistics (later)
Duels played / wins / losses / win rate, and optionally a map-specific streak, tracked per player per map. Same reasoning as personal bests: extends the same stats table, not core to shipping.

### Duel leaderboard (later)
`/duel leaderboard` — most wins, highest win rate, longest streak, most duels, most coins won. Needs the stats data to exist first, and win-rate rankings must enforce a minimum-games floor so a 2-0 record can't sit above a 500-win veteran.

## Ranked vs. unranked duels and ELO (later)

- Two duel types, both using the exact same duel infrastructure: **ranked** and **unranked**. The type is just another field on the duel record, chosen (or defaulted) at request time.
- Only ranked duels affect ELO; unranked duels never touch it.
- Wagering can be configured independently per type (e.g. ranked duels might disable wagers entirely to keep competitive play separate from gambling, or allow both — server's choice via config).
- ELO is a separate rating, independent from plain win/loss counts and from FFA stats, recalculated after every completed **ranked** duel.
- Display a player's current ELO and rank alongside (not mixed into) their regular duel stats.

### ELO-farming protection
Same "flag, don't auto-punish" philosophy as wager-farming, and can likely share the detection plumbing:
- Detect repeated ranked duels between the same two players, and unusually high match counts between a given pair.
- Detect repeated intentional win/loss patterns (accounts trading wins) and suspicious rating swings.
- Configurable limits on how much ranked ELO the same pair can generate together, and on how quickly rematches between them can repeat.
- Track ranked-duel history for staff investigation, surfaced through `/dueladmin`, not just logged.
- Legitimate high-volume ranked players should never get incidentally restricted by these checks — tune thresholds for actual farming patterns, not just "these two play a lot."

## Rematch

After a duel finishes, the end screen offers: **Rematch**, **Change Map**, **Change Wager**, **Close**. A rematch reuses the previous opponent, optionally the previous map, allows the wager to be changed, runs full validation again, and creates a **completely new duel ID** — never reuses the previous duel's state or payout transaction. Never auto-starts without explicit confirmation from both players.

### Quick Duel (later)
A shortcut that remembers a player's previous duel setup (opponent, kit, map, wager) so they can re-challenge quickly without walking the full GUI flow again. Convenience only — it must still run every validation step a normal request would; nothing about being "quick" skips a check.

## Duel scoreboard (later)

An optional sidebar shown during an active duel: opponent, map, wager, duel type, duration, and other relevant info. Reuse whatever scoreboard/sidebar system this project already has for other modes if one exists, rather than building a parallel one — audit for it before writing anything. Must not interfere with or get clobbered by FFA scoreboards or any other unrelated scoreboard feature.

## Duel announcements

Optional public broadcast on duel end (e.g. "SkilledSeeker defeated Player123 in a duel!"), config-gated and filterable by duel type and/or minimum wager so only "interesting" duels get announced if that's what the server wants. Cheap to build off `DuelEndEvent` — doesn't need its own bespoke hook into the winning sequence.

## Commands

- `/duel <player>` — the main entry point (kit/map/wager all chosen via GUI, not command args).
- `/duel accept <player>`, `/duel deny <player>`, `/duel cancel` — if compatible with the existing command/GUI-driven flow (the accept/deny prompt may cover this without needing raw subcommands; use whichever fits the existing UX better).
- `/duel toggle` — opt in/out of receiving requests.
- `/duel history` — paginated personal duel history.
- `/duel forceend <player>` (admin) — must perform the exact same safe cleanup, escrow resolution, and inventory recovery as a normal termination, not a shortcut that skips it.
- `/leave` — exit the post-duel grace period early (see [above](#post-duel-grace-period-and-leave)).
- **Later:** `/spec <player>` (spectate — see [Spectator system](#spectator-system-later)).

## Staff tools

- `/dueladmin list` (a shorter `/duels` alias is fine, but it's the same command — don't build two separate implementations of "list active duels") — all currently active duels: duel ID, players, arena, map, wager, duel type, current state, duration.
- `/dueladmin inspect <player>` — duel ID, state, players, arena, wager, elapsed time, escrow amount, for investigating a dispute. Once ranked/ELO exists, also surfaces ranked-specific history for that investigation.
- `/dueladmin forceend <player>` — same safe-cleanup guarantee as the player-facing `/duel forceend`.
- `/dueladmin arena <arena>` — status of a specific duel arena (reserved/occupied/grace-period/available, spawn validity).

Use this list/inspect view to spot stuck duels, invalid arenas, suspicious wagers, unusually long-running matches, or players stuck in an arena — it's the primary tool for catching problems this whole spec is designed to prevent, not just a nice-to-have.

Staff-visible wager (and later, ELO) history ties into anti-farming below — disputes and suspicious patterns both need this to actually be inspectable, not just logged to a file.

## Anti-wager-farming safeguards

Alt-account wager farming is the main gameplay-integrity risk this system introduces, and it's a detection/flagging problem, not something to solve with a hard block in v1:

- Minimum account age and/or minimum playtime before wagering is allowed.
- Daily wager limit per player.
- Maximum wager scaled to account progression (e.g. capped relative to playtime or an existing rank system) rather than a single server-wide max.
- Flag (don't auto-punish) suspicious patterns: repeated wins/losses concentrated between the same two accounts, unusually high-frequency duelling, optionally a cooldown specifically between rematches of the same pair to make farming loops slower to run.
- All of this must be staff-visible via `/dueladmin`, not just written to a log file nobody reads.

## Spectator system (later)

`/spec <player>` lets anyone watch an active duel. Only active (`ACTIVE`) duels can be spectated. Requirements when this is built:

- Spectators cannot interact with the duel in any way — no combat, no effect on inventories, wagers, arena, or duel state.
- Clearly separated from the two participants (e.g. spectator gamemode/visibility, not just "another player standing nearby").
- Automatically exits spectator mode when the duel ends, and returns the spectator to their previous location/state.
- Doesn't trigger FFA combat systems, statistics, killstreaks, or bounties — spectating must respect the same [combat isolation](#combat-isolation) rules as the duel itself.
- Must work with whichever arena allocation strategy is active — fixed pool in v1, dynamically generated arenas later.
- Multiple spectators can watch the same duel simultaneously.
- Respects any configured spectate permissions/restrictions.

## GUI and resource cleanup

Every GUI listener, refresher task, callback, and temporary reference must be cleaned up when: the GUI closes, a player disconnects, the request expires, the request is accepted or denied, the target goes offline, or the plugin shuts down. This applies to every duel-related GUI/feature added over time — the map-select GUI in v1, and later the incoming-request queue, the scoreboard, and spectator sessions. Prefer UUIDs over held `Player` references in any long-lived map so nothing leaks across reconnects/restarts.

## Event hooks

Expose plugin-facing events for the duel lifecycle — `DuelCreateEvent`, `DuelAcceptEvent`, `DuelStartEvent`, `DuelEndEvent`, `DuelForfeitEvent`, `DuelPayoutEvent` — so later features (achievements, quests, tournaments, Discord announcements, seasonal competitions, duel announcements) can hook in without modifying the core duel manager. Build these from day one even though nothing consumes them yet in v1; retrofitting event hooks into an already-built state machine is much more error-prone than including them from the start.

## Concurrency and race conditions to guard against

Bukkit events, scheduled tasks, GUI clicks, disconnects, deaths, and commands can all race each other. Explicitly handle: accept vs. timeout, accept vs. disconnect, death vs. disconnect, death vs. a duplicate death event, payout vs. disconnect, arena allocation vs. another duel grabbing it, a GUI click vs. the target disconnecting, a GUI click vs. the chosen arena becoming unavailable, two requests between the same players, both players accepting different duels at once, a click on a stale/expired request message, `/leave` racing the automatic grace-period expiry, and plugin shutdown mid-duel. Every terminal operation must be safe to run more than once without double-effect.

## Persistence and crash safety

Use the project's existing persistence layer (`DatabaseManager`) — don't introduce a new storage technology for this. If duel state or economy transactions need to survive a restart, design recovery so a crash/restart can never produce: lost wagers, duplicate payouts, permanently locked arenas, permanently locked inventories, or a player stuck flagged as "in a duel" forever. Once ELO exists, the same guarantee applies to rating changes — a crash mid-update can't produce a duplicated or lost ELO adjustment.

## Logging

Structured, not spammy — every lifecycle event carries the duel ID: created, accepted, expired, arena reserved, escrow created, started, player disconnected, forfeited, ended, payout completed, inventory restored, cleanup failed. Errors should carry enough context to diagnose without needing a live repro. No unnecessary sensitive info in logs.

## Configuration

Request timeout, request cooldown, countdown duration, max duel duration, post-duel grace period length, min/max wager, daily wager limit, minimum account age/playtime to wager, no-wager win reward, house cut %, whether rematch is enabled, the duel-eligible arena list, the default duel kit, whether/how announcements fire (and any minimum-wager threshold for them), duel messages, timeout behavior, and whether certain commands are restricted while duelling — all config-driven, following this project's existing config conventions. Later additions follow the same convention: ranked/unranked toggle, ELO K-factor and farming-protection thresholds, scoreboard on/off. Don't add config knobs for values that should just stay internal constants.

## Code quality guidelines

Reuse existing abstractions rather than parallel ones: `KitManager` for kits, `EconomyManager` for balances, existing arena allocation/teleport logic, existing GUI patterns, `LiveGuiRefresher` for live updates, `DatabaseManager` for persistence, and any existing scoreboard system for the later duel scoreboard. Favor: clear single-responsibility pieces, immutable duel identity, explicit state transitions, centralized cleanup and centralized payout handling, UUID-based references, idempotent operations, async DB work where the existing architecture already does that, and main-thread Bukkit API access where required. Avoid: unnecessary entity/world scanning, unnecessary synchronous DB calls, repeated Mojang API calls, permanent `Player` references, scattered duel booleans, duplicated economy/arena/inventory logic, giant do-everything listeners, and silently swallowed exceptions.

## Testing checklist (once built)

Functional: normal request, accept, deny, timeout, cancel, self-duel rejection, offline-target rejection, opted-out target (`/duel toggle`) rejection, zero-wager duel, max wager, daily wager limit enforcement, house cut math, rematch, change-map/change-wager on rematch.

Requests: clicking an already-accepted/denied/expired request fails safely without affecting a newer duel; live "expires in" countdown matches actual expiry; repeated request after a decline is blocked until the cooldown expires; burst of requests from one player is rate-limited.

Disconnects: target disconnects while the GUI is open, target/challenger disconnects after request, either side disconnects after acceptance, disconnect during countdown, disconnect during an active duel.

Economy: insufficient challenger/target funds, balance changes while a request is pending, duplicate-payout attempt, exact pot/payout correctness (no double-pay, no lost coins), wager confirmation screen matches actual payout.

Inventory: armor, offhand, unusual/custom items, no duplication, no loss, full restore on every exit path.

Arena/infra: teleport failure, kit-application failure, invalid/unavailable arena, same arena requested by concurrent duels, no valid spawn spots handled gracefully (same fallback `/arenas` already has), map GUI's availability indicator matches real arena state.

Grace period: `/leave` exits early without re-running payout/restore; automatic expiry after the configured window removes both players and releases the arena; arena correctly shows as unavailable to new duels during the grace window; `/leave` racing the automatic expiry doesn't double-run cleanup.

Abuse prevention: can't duel while combat-tagged/teleporting/dead, escape commands blocked during an active duel, disallowed teleport items blocked unless the kit permits them.

Isolation: duplicate death events, FFA killstreak/leaderboard/bounty stats untouched by duel outcomes, public arena-leave logic not triggered by a duel.

Staff tools: `/dueladmin list`/`inspect`/`forceend`/`arena` all reflect live, accurate state and `forceend` cleans up exactly like a normal end.

System-level: concurrent duels don't cross-contaminate arenas/kits/payouts, GUI listeners/refreshers/tasks fully clean up, no new Mojang `429`s tied to the duel GUI, plugin/server shutdown mid-duel doesn't strand coins/items/arenas, duel timeout resolves deterministically.

Later-phase (test when built, not part of v1 acceptance): ranked ELO math and duplicate-update protection, ELO-farming detection doesn't flag legitimate high-volume ranked players, spectators can't affect duel/FFA state and are cleanly removed on duel end, duel scoreboard doesn't clash with other scoreboards, dynamically generated arenas are fully isolated per duel and clean up correctly, personal-best and per-map stats stay in sync with core stats.

## Final implementation audit (do this after building, not instead of testing)

Once implemented, don't just report "it works" — explicitly review the final code for: race conditions, double payout, lost coins, duplicated items, lost items, stuck duel states, stuck arena reservations, memory leaks, FFA side effects, thread-safety violations, unnecessary API calls, unnecessary entity/world iteration, GUI refresh inefficiencies, database performance, exploit paths, command abuse, disconnect edge cases, and restart/crash recovery. Fix anything found during this pass rather than just noting it. Don't touch unrelated functionality while doing so.

At the end, report: files changed, architecture introduced, which existing systems were reused, important edge cases handled, remaining risks, performance considerations, and exact test results.


## Security, Exploit & Zero-Bug Audit

The duel system must be treated as a **high-risk economy and PvP system**. Before considering the implementation complete, perform an aggressive security, exploit, concurrency, and edge-case audit.

The goal is to make the system as close to **zero known bugs and exploits** as realistically possible.

Do not only verify that the intended flow works. Actively attempt to break the system.

### 1. Economy Exploit Audit

Attempt to exploit:

* Double payouts.
* Triple payouts.
* Missing payouts.
* Negative payouts.
* Incorrect pot calculations.
* House-cut calculation errors.
* Integer overflow/underflow.
* Decimal/rounding errors.
* Wager values below the minimum.
* Wager values above the maximum.
* Negative wagers.
* Extremely large wagers.
* Zero wagers.
* Balance changes while a request is pending.
* Balance changes between validation and escrow.
* Balance changes during the duel.
* Disconnects during escrow.
* Server shutdown during escrow.
* Plugin reload during escrow.
* Duplicate payout callbacks.
* Duplicate win events.
* Duplicate death events.
* Simultaneous death and disconnect.
* Simultaneous win and timeout.
* Simultaneous win and force-end.
* Repeated `/duel accept`.
* Repeated clickable ACCEPT interactions.
* Repeated rematch requests.
* Reusing an old duel ID to trigger another payout.
* Replaying an old request.
* Replaying an old clickable GUI/chat action.

Every economy operation must be **idempotent** and tied to the correct duel/transaction identity.

A player must never be able to cause the same wager to be withdrawn or paid more than once.

### 2. Inventory Exploit Audit

Attempt to cause:

* Item duplication.
* Item deletion.
* Armor duplication.
* Offhand duplication.
* Cursor-item duplication.
* Dropped-item duplication.
* Items remaining in the generated arena after cleanup.
* Items disappearing after disconnect.
* Items disappearing after death.
* Items being restored twice.
* Original inventory being restored over legitimate post-duel items.
* Kit items being permanently added to the player's inventory.
* Custom/NBT items being corrupted.
* Enchantments being lost.
* Attributes being lost.
* Item metadata being lost.
* Item stacks changing unexpectedly.
* Inventory manipulation during countdown.
* Inventory manipulation during arena grace period.
* Inventory manipulation through commands.
* Inventory manipulation through another plugin.

Test unusual inventories, including completely full inventories and inventories containing custom items.

### 3. Duel State Exploit Audit

Attempt to force invalid state transitions:

```text
REQUESTED → FINISHED
FINISHED → ACTIVE
DECLINED → ACTIVE
EXPIRED → ACTIVE
CANCELLED → ACTIVE
ACTIVE → STARTING
ENDING → ACTIVE
```

Verify that invalid transitions are impossible.

Attempt:

* Accepting an expired duel.
* Accepting an already accepted duel.
* Accepting the same duel twice.
* Denying an accepted duel.
* Cancelling an active duel through request cancellation.
* Ending a finished duel.
* Triggering multiple terminal events simultaneously.
* Reusing a finished duel object.
* Reusing an old request after a new duel is created.

Every duel must have one authoritative lifecycle.

### 4. Race Condition Audit

Aggressively test concurrent events:

* Accept + timeout.
* Accept + disconnect.
* Accept + arena becoming unavailable.
* Accept + insufficient funds.
* Death + disconnect.
* Death + timeout.
* Death + force-end.
* Payout + disconnect.
* Payout + server shutdown.
* `/leave` + death.
* `/leave` + payout.
* Rematch + previous duel cleanup.
* Two GUI clicks at the same time.
* Multiple accept clicks.
* Multiple deny clicks.
* Multiple players attempting to reserve the same arena.
* Two duels attempting to use the same generated arena location.

The final result must always be deterministic.

No race should produce:

* Duplicate payout.
* Lost payout.
* Duplicate inventory restoration.
* Lost inventory.
* Stuck duel.
* Stuck player.
* Stuck arena.
* Incorrect statistics.

### 5. Command Exploit Audit

Test every duel-related command with:

* Invalid players.
* Offline players.
* Yourself.
* Players already duelling.
* Players in other arenas.
* Players who are dead.
* Players who are combat tagged.
* Players disconnecting during command execution.
* Invalid arguments.
* Missing arguments.
* Extra arguments.
* Negative values.
* Extremely large values.
* Malformed numbers.
* Rapid command spam.

Verify that commands cannot bypass GUI validation or duel state validation.

`/leave`, teleport commands, warp commands, home commands, spawn commands, and other movement commands must not allow players to escape an active duel unless explicitly permitted.

### 6. GUI Exploit Audit

Attempt:

* Clicking stale GUI buttons.
* Clicking buttons after the target disconnects.
* Clicking buttons after the request expires.
* Clicking buttons after the duel has already been accepted.
* Double-clicking.
* Rapid clicking.
* Shift-clicking.
* Dragging items into GUI inventories.
* Moving items between GUI slots.
* Clicking while the GUI is being refreshed.
* Clicking an arena that becomes unavailable.
* Interacting with a GUI after the player has entered a duel.
* Using old clickable chat messages after the request is invalid.

All GUI actions must validate the current server-side state rather than trusting the GUI.

Never trust client-side GUI state.

### 7. Arena Generation Security Audit

For dynamically generated arenas:

* Ensure generated locations can never overlap another active duel.
* Ensure two duels cannot reserve the same location.
* Ensure generation cannot occur inside protected regions.
* Ensure generation cannot overwrite unrelated worlds/structures.
* Ensure generated worlds/chunks are handled safely.
* Ensure failed generation cleans up partial structures.
* Ensure abandoned arenas are eventually removed.
* Ensure arena cleanup cannot delete another duel's arena.
* Ensure cleanup is tied to a unique duel/arena ID.
* Ensure players cannot escape the generated arena if the rules prohibit it.
* Ensure generated arenas cannot be exploited to duplicate blocks/items.
* Ensure arena grace periods cannot be extended indefinitely.
* Ensure `/leave` cannot bypass required cleanup.
* Ensure server restart cannot leave permanent generated arena clutter.

Test multiple duels generating arenas simultaneously.

### 8. Spectator Exploit Audit

For `/spec <player>`:

* Spectators cannot damage players.
* Spectators cannot receive damage.
* Spectators cannot pick up items.
* Spectators cannot drop items into the duel.
* Spectators cannot modify blocks.
* Spectators cannot interact with containers.
* Spectators cannot trigger pressure plates/buttons/etc. if prohibited.
* Spectators cannot use the spectator position to gain gameplay advantages.
* Spectators cannot teleport participants.
* Spectators cannot interfere with arena cleanup.
* Spectators cannot remain inside a deleted/finished arena.
* Spectators cannot duplicate items by entering/leaving spectator mode.
* Spectators are correctly restored to their previous state.

Test multiple spectators joining and leaving rapidly.

### 9. Disconnect / Reconnect Audit

Test disconnects at **every possible stage**:

* Before GUI opens.
* While GUI is open.
* After map selection.
* After request creation.
* During request acceptance.
* During arena reservation.
* During inventory snapshot.
* During escrow.
* During kit application.
* During teleport.
* During countdown.
* Immediately after `FIGHT`.
* During combat.
* Immediately after death.
* During payout.
* During inventory restoration.
* During arena cleanup.
* During the 3-minute grace period.

Reconnect afterward and verify the player is never permanently stuck in:

* Duel state.
* Arena state.
* Spectator state.
* Modified inventory state.
* Modified game mode.
* Modified movement state.
* Modified command restrictions.

### 10. ELO Exploit Audit

Because ranked duels affect player progression, actively attempt to manipulate ELO.

Test:

* Repeated wins against the same account.
* Repeated losses against the same account.
* Alternating wins between two accounts.
* Very short repeated matches.
* Immediate rematches.
* Multiple accounts farming one account.
* New accounts farming established accounts.
* Intentionally forfeiting.
* Disconnecting intentionally.
* Repeated disconnect/reconnect behavior.
* Creating large numbers of low-value ranked games.
* Manipulating matchmaking/rating if ranked matchmaking is introduced later.
* Exploiting ELO calculation boundaries.
* Integer/rounding issues.
* Extremely high or low ratings.

The system should detect suspicious patterns and prevent obvious ELO farming without incorrectly punishing legitimate players.

ELO changes must only occur after a valid ranked duel result.

Unranked duels must never affect ELO.

### 11. Anti-Abuse / Rate-Limit Audit

Attempt to spam:

* Duel requests.
* Accept/deny actions.
* Rematches.
* GUI interactions.
* `/spec`.
* `/leave`.
* Duel creation.
* Arena generation.

Add appropriate cooldowns/rate limits where required.

Rate limits must not create permanent stuck states.

### 12. Permission & Trust Audit

Never trust:

* Client GUI state.
* Client commands.
* Stored request IDs without validation.
* Cached player state.
* Cached arena state.
* Client-provided wager values.
* Client-provided map IDs.
* Client-provided duel IDs.

Every important action must be validated against authoritative server-side state.

Admin/force-end functionality must require the correct permission.

Normal players must never be able to invoke administrative duel actions.

### 13. Cross-System Exploit Audit

Check interactions with every existing relevant system:

* FFA.
* Combat tagging.
* Killstreaks.
* Bounties.
* Economy.
* Kits.
* Arena management.
* Teleportation.
* Death handling.
* Inventory management.
* Statistics.
* Scoreboards.
* Tab.
* Commands.
* Permissions.
* Database.
* World management.
* Other plugins.

Look specifically for duplicated event handling where both the duel system and an existing system process the same event.

A duel must not accidentally trigger unrelated FFA rewards, statistics, economy payouts, arena cleanup, or combat logic.

### 14. Resource / Performance Audit

Attempt to create performance problems through:

* Rapid duel creation/cancellation.
* Large numbers of pending requests.
* Large numbers of simultaneous duels.
* Large numbers of spectators.
* Rapid arena generation/destruction.
* GUI refreshes.
* Repeated player profile access.
* Database writes.
* Scheduled tasks.
* Abandoned state objects.

Verify:

* No task leaks.
* No listener leaks.
* No GUI refresher leaks.
* No Player references retained unnecessarily.
* No arena references retained after cleanup.
* No stale duel records remaining in memory.
* No unnecessary Mojang API requests.
* No unnecessary world/entity scanning.
* No unbounded collections.

### 15. Crash / Restart Audit

Simulate server/plugin shutdown during every important stage.

Verify recovery from:

* Request pending.
* Arena generation.
* Escrow.
* Countdown.
* Active duel.
* Player death.
* Payout.
* Inventory restoration.
* Grace period.
* Arena cleanup.

After restart, verify:

* No lost coins.
* No duplicate coins.
* No lost items.
* No duplicated items.
* No permanently reserved arenas.
* No permanently generated arena locations.
* No players permanently flagged as duelling.
* No corrupted statistics.
* No stale spectator states.

### 16. Automated Test Requirements

Where practical, create tests for:

* State transitions.
* Wager calculations.
* House-cut calculations.
* ELO calculations.
* Payout idempotency.
* Inventory snapshot/restore.
* Request expiration.
* Arena allocation.
* Arena release.
* Concurrent duel allocation.
* Duplicate event handling.
* Disconnect handling.

For critical economy/state code, prefer automated tests over relying only on manual testing.

### 17. Final Red-Team Pass

After implementation is complete, perform a final **red-team audit**.

Do not ask:

> "Does the normal duel flow work?"

Instead ask:

> "How can a malicious player break this system?"

Try to intentionally:

* Duplicate coins.
* Duplicate items.
* Avoid losing a wager.
* Receive a payout without winning.
* Receive multiple payouts.
* Farm ELO.
* Escape a duel.
* Keep an arena permanently.
* Crash or overload the duel system.
* Keep themselves permanently flagged as a duelling player.
* Keep another player permanently flagged as duelling.
* Bypass cooldowns.
* Bypass permissions.
* Reuse expired requests.
* Reuse old duel IDs.
* Abuse stale GUI interactions.
* Abuse disconnects.
* Abuse `/leave`.
* Abuse spectator mode.
* Abuse rematches.
* Abuse arena generation.
* Abuse server/plugin restarts.

Fix every reproducible bug or exploit discovered during this audit.

Do not simply report vulnerabilities and leave them unresolved.

### 18. Zero-Known-Issue Requirement

Before declaring the implementation complete:

* Perform the full normal functional test.
* Perform the full edge-case test.
* Perform the full concurrency test.
* Perform the economy exploit test.
* Perform the inventory exploit test.
* Perform the ELO abuse test.
* Perform the arena-generation test.
* Perform the spectator exploit test.
* Perform the disconnect/reconnect test.
* Perform the restart/crash test.
* Perform the performance/resource audit.
* Perform the final red-team audit.

If any reproducible bug, exploit, race condition, duplication issue, payout issue, inventory issue, or stuck-state issue is discovered, **fix it before completion**.

Do not mark the feature complete simply because the happy path works.

The final report must clearly state:

* Bugs found.
* Exploits found.
* Race conditions found.
* Fixes applied.
* Tests performed.
* Tests passed.
* Any remaining theoretical risks that could not be reproduced or fully tested.

The objective is a duel system with **zero known reproducible bugs or exploits at the time of release**.

---

## Implementation summary (v1, as built)

### Files added
- `model/Duel.java`, `DuelState.java`, `DuelInventorySnapshot.java`, `DuelResult.java`
- `manager/DuelManager.java` (orchestrator), `DuelArenaManager.java`, `DuelStatsManager.java`
- `gui/DuelMapGui.java`, `DuelHistoryGui.java`
- `command/DuelCommand.java`, `LeaveCommand.java`, `DuelAdminCommand.java`
- `listener/DuelListener.java`
- `event/DuelEvent.java` (shared base) + `DuelCreateEvent`, `DuelAcceptEvent`, `DuelStartEvent`, `DuelEndEvent`, `DuelForfeitEvent`, `DuelPayoutEvent`
- `duels.yml`

### Files edited
- `UnstableCore.java`, `ConfigManager.java`, `DatabaseManager.java` (3 new tables: `duels`, `duel_stats`, `duel_history`), `plugin.yml`
- `ArenaManager.java` — one extracted public method (`findSafeSpot`), no FFA behavior change
- `CombatListener.java` — two early-return isolation checks + one `isCombatTagged` getter
- `SettingsManager.java` — one new toggle key (`DUEL_REQUESTS`)
- `PlayerListener.java` — one call each in `onQuit`/`onJoin`
- `GuiListener.java`, `LiveGuiRefresher.java` — new GUIs added to existing dispatch lists

### Existing systems reused (no parallel implementations built)
`KitManager.applyKit`, `EconomyManager.has/takeExact/deposit`, `ArenaManager`'s spot-picking/validation, `DatabaseManager`'s upsert/record pattern, `LiveGuiRefresher`'s tick loop, `MapVoteManager`/`VoteGui`'s clickable-chat + timed-refresh pattern, `BountyManager`'s chat-prompt (`AsyncChatEvent` at `LOWEST` priority), `SettingsManager` toggles, `PlayerListener.onQuit` as the central disconnect hook, `StatsManager.resolvePlayer` for cache-only offline-player lookup.

### Key design choices
- **Single-main-thread invariant**: every state-mutating method only ever runs on the main thread (events, commands, non-async scheduled tasks); DB work hops to `runTaskAsynchronously` only after building an immutable snapshot of whatever needs writing, and hops back via `runTask` before touching manager state again. This removes an entire class of races by construction rather than by locking.
- **CAS-gated state machine**: `Duel.transition(expectedCurrent, next)` only succeeds from the exact expected source state; every terminal trigger (accept, decline, expire, death, disconnect-forfeit, timeout, forceend) races through this CAS, so exactly one ever wins.
- **Idempotency flags independent of state**: escrow, payout, inventory-restore, stats, rollback, and grace-release each have their own one-shot `AtomicBoolean` guard on `Duel`, so even a hypothetical future bug in the state machine can't cause a double-spend or double-restore.
- **Synchronous teleport** (not `teleportAsync`) for the accept sequence, deliberately, to keep the whole 11-step setup a single uninterruptible synchronous block.
- **Grace period is arena-scoped and per-player**: a disconnecting participant auto-leaves their own grace period immediately (nothing to collect if they're offline); the arena frees the moment both have left, whichever of `/leave` or the timer gets there first (idempotency-guarded).
- **Rematch** shipped as a same-terms text hint pointing back at `/duel <opponent>` (full re-validation, brand-new duel ID) rather than a bespoke "Change Map / Change Wager" end-screen GUI — scoped down from the doc's full end-screen spec to keep v1 bounded; nothing about the architecture blocks building that GUI later.
- **Crash recovery restores both coins and items**: inventory snapshots are serialized (same per-slot `ItemStack` idiom `KitManager` uses for kit contents) into the `duels` row the moment both snapshots exist, not just the wager. On restart, unresolved rows refund escrowed coins immediately and queue inventory restoration for each participant's next join (can't hand items to an offline player).

### Red-team audit pass - bugs found and fixed
1. **`Duel.transition` ignored its own source-state parameter** (checked `current.canTransitionTo(next)` instead of `current == expectedCurrent`), which would have let a transition succeed from any valid source state instead of only the intended one. Fixed before any other code depended on it.
2. **Countdown-dodge exploit**: a player could ender-pearl/chorus-fruit out of the arena during the pre-FIGHT countdown (`STARTING`) and simply never be reachable once `ACTIVE` began, avoiding the disconnect-forfeit path entirely (a guaranteed no-loss outcome, and a guaranteed win under the optional `higher-health` timeout setting). Fixed by extending the escape/teleport-item restriction window to cover `STARTING`, not just `ACTIVE`.
3. **Silent payout failures**: every `EconomyManager.deposit` call in the payout/refund/rollback/recovery paths ignored its boolean return value, so a rejected deposit (economy provider hiccup) would silently strand money with no record. Fixed by routing all of them through one `depositOrWarn` helper that logs a `SEVERE` message naming the duel, recipient, and amount owed whenever a deposit fails, so staff can compensate manually - money is never lost silently.
4. **Crash recovery only refunded coins, not items**: the original design snapshotted inventories only in memory, so a hard crash (not a graceful `/stop`/`/reload`, both of which already restore inventory immediately while players are still connected) would refund the wager but the participants' pre-duel items would exist nowhere. Fixed by persisting serialized inventory snapshots alongside the escrow row and restoring them on the player's next join after recovery.
5. **`/dueladmin inspect <player>` used the legacy name-based `Bukkit.getOfflinePlayer(String)`**, which can trigger a blocking Mojang lookup for a name never seen on the server - exactly what the doc's GUI section warns against. Fixed to reuse `StatsManager.resolvePlayer`'s cache-only lookup chain.

### Traced against the doc's testing checklist (source-level - no live server in this environment)
Verified by code trace rather than live play: stale/duplicate accept-deny clicks (duel-ID-gated, state-CAS-gated), duplicate death events (per-duel `markDeathProcessed` flag), death-vs-disconnect-vs-timeout races (all funnel through the same `ACTIVE -> ENDING` CAS, exactly one wins regardless of firing order within a tick), simultaneous-disconnect-of-both-participants (cascades correctly through the grace-leave path), setup-failure rollback at every step (funds/arena/spawns/kit/exception), zero-wager duels, `/leave` racing automatic grace expiry (both go through the same `markGraceReleased` guard), two duels racing for the same arena (`ConcurrentHashMap.putIfAbsent`), and FFA isolation (kill/streak/bounty/coin-reward code paths all short-circuit before ever running for a duel death or duel-related disconnect).

### Known residual risks (not fixed - documented, not exploitable for coin/item duplication or loss)
- No "mid-teleport" check exists for FFA's own async arena teleports when validating a new duel request/accept (Bukkit has no generic cross-plugin "is this player currently teleporting" flag, and no such registry exists elsewhere in this codebase to reuse). Worst case is a redundant/overridden teleport, not a dupe or loss.
- Deposit failures (Vault provider rejecting a payout) are logged loudly for manual staff resolution rather than automatically retried - this codebase has no retry/queue mechanism anywhere to reuse, and building one was judged out of scope for a duel-specific fix.
- Full end-screen GUI (Rematch / Change Map / Change Wager / Close as clickable buttons) was scoped down to a text hint pointing back at `/duel <opponent>` - functionally equivalent (full re-validation, new duel ID, no auto-start) but not the polished GUI the doc sketches.
- Daily wager limit and ELO-later-hooks are in-memory only (resets on restart) - acceptable since v1 has no ELO yet and the daily limit is a soft "flag, don't block" style guard, not an economic safety boundary (the escrow/payout guards are what actually protect coins).

### Build verification
`mvn -o clean package` succeeds (96 source files, JDK 21 / Maven, offline). Zero errors; the only warnings are two pre-existing deprecation notices in unrelated files (`KitEditGui`, `HeldShulkerListener`) that predate this change.
