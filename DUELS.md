# /duel — Design Overview

> Status: **planned, not yet implemented.** This doc captures the agreed design so it can be built and reviewed against it later.

## Flow

1. `/duel <player> [wager]` — opens a **map-select GUI** for the challenger (icon grid, same visual pattern as the existing FFA `MapVoteManager`/`VoteGui`). Challenger picks the map as part of sending the request — one step, no back-and-forth map negotiation.
2. Request is sent with the chosen map attached. Target gets a clickable accept/deny prompt (chat or small GUI). Request auto-expires after a timeout (config).
3. **On accept:**
   - Both players' wagers are validated (`has()`) and withdrawn into escrow (nothing paid out yet).
   - Inventories are snapshotted, then both players are teleported to a **dedicated duel arena** (new arena type, not the public FFA pool) and kitted via `KitManager.applyKit`.
4. **On win:** loser's original inventory is restored, winner gets the full wager pot (`deposit()`), win/loss recorded to new `duel_wins`/`duel_losses` columns (separate from FFA killstreak stats).
5. **On decline / timeout / cancel before the fight starts:** no-op, nothing was ever withdrawn.
6. **On disconnect mid-duel:** counts as a forfeit — pot pays out to whoever's left. Reuses the same disconnect-credit approach already built for the FFA combat-log fix (`CombatListener.handleQuitCombatTag`), just pointed at duel payout instead of killstreak.
7. No-wager duels still pay a small flat coin reward on win (config-driven, same idea as `rewardKill`).

## Key decisions

- **Dedicated duel arenas**, not the shared FFA pool — no third-party interference, fair 1v1.
- **Escrow at accept**, not at request — doesn't lock the challenger's coins while waiting on a response; re-validated at accept time.
- **Separate stats table** for duels — doesn't pollute FFA killstreak/leaderboard numbers.
- Optional config `%` house cut on wager payouts as a coin sink (off by default, tunable).
- **Map picker reuses existing UI patterns** — the same icon-grid style already used for FFA map voting, just scoped to arenas flagged as duel-eligible.

## Map-select GUI

- Opens immediately when `/duel <player>` is run, before the request is sent. Shows all duel-eligible maps as icons (name, thumbnail material, live player count if useful).
- Challenger clicks a map → request is sent to the target with that map attached.
- The GUI also shows the **target's player head** (real skin, not the default), with a live online/offline indicator:
  - Target online → normal head, request can be sent.
  - Target goes offline while this GUI is open → head slot greys out / shows an "offline, can't duel" state instead of silently letting a stale request go out.
- **Skin fetching:** use `Player.getPlayerProfile()` for the target (they're online by definition when this GUI opens) instead of an async Mojang session-server lookup. Avoids the cold-lookup path that causes the `429` rate-limit warnings already seen in the server log for other head-rendering GUIs (leaderboard, etc.) — no reason to add to that problem here.
- Live refresh while open: reuse `LiveGuiRefresher`, the same mechanism other menus already use for periodic redraws, rather than building a new polling loop.

## Still open / not decided yet

- Matchmaking (random opponent queue) vs. direct challenge only — direct challenge only for v1.
- Spectator mode — not in v1.
- Best-of-N duels — not in v1, single round only.
- Anti-abuse for wager farming via alt accounts — not addressed in v1, flag if it becomes a problem.

## Testing checklist (once built)

- Request/accept/deny/timeout/cancel all resolve cleanly with no stuck state (player not left "in a duel" forever).
- Wager: can't request more than you have; can't accept if you can no longer afford it by then; declined/cancelled/timed-out requests never touch balances; won pot amount is exactly right (no double-pay, no lost coins).
- Disconnect mid-duel pays out correctly and doesn't also let the disconnecting player's stuff persist/duplicate.
- Inventory is fully restored on loss/end — no item loss, no duplication, armor and offhand included.
- Concurrent duels (multiple pairs duelling at once) don't cross-contaminate arenas, kits, or payouts.
- Can't `/duel` yourself, an offline player, or someone already in another duel/arena.
- Death during a duel doesn't also trigger unrelated systems (FFA killstreak, bounty claims, arena leave messages) unless intentionally wired in.
- Map-select GUI: target going offline mid-selection is reflected (greyed out/blocked), not just silently allowed to send a dead request.
- Map-select GUI: no player-head lookups spam Mojang's session server (verify via server log — should see zero new `429`s tied to this feature).
- Map-select GUI: picking a map with no free/valid spawn spots is handled gracefully (same fallback `/arenas` already has), not a silent failure or crash.
