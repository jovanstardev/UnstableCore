# Unstable Announcer resource pack

Killstreak announcer voice lines. The plugin plays these by name; a client that does not have
the pack silently ignores the unknown sound and just hears the vanilla fallback instead, so
this pack is always optional and nothing breaks without it.

## What's in it

| Sound key | File | Played at |
|---|---|---|
| `unstable:announcer.mega_kill` | `mega_kill.ogg` | killstreak milestone 5–9 |
| `unstable:announcer.monster_kill` | `monster_kill.ogg` | killstreak milestone 10+ |

Thresholds, volume, fallbacks and who hears each line live in `config.yml` under
`killstreak.announcer.tiers` — no code change needed to retune them.

## Hosting it

Upload `UnstableAnnouncer.zip` (in the project root) anywhere that serves a **direct download**.
A GitHub raw URL or Release asset works and is free. Then pick ONE of the two ways to send it.

### If another plugin already sends a pack (ItemsAdder, SkinVault, ...)

Use the plugin's own sender, which stacks this pack **on top of** the existing one rather than
replacing it. `server.properties` holds a single pack, so putting it there makes the two fight
and one set of assets disappears.

In `config.yml`, under `killstreak.announcer`:

```yaml
    resource-pack:
      url: "https://your-host/UnstableAnnouncer.zip"
      sha1: "a575663f98eb28e57d5cb6f5515e31732ff19561"
      prompt: "&dUnstable &fAnnouncer &7- adds killstreak voice lines"
      required: false
```

### If nothing else sends a pack

`server.properties` is fine - leave the plugin sender's `url` blank:

```properties
resource-pack=https://your-host/UnstableAnnouncer.zip
resource-pack-sha1=a575663f98eb28e57d5cb6f5515e31732ff19561
require-resource-pack=false
```

Either way the pack stays optional, so players who decline still hear the vanilla fallbacks.

**The SHA1 must match the uploaded file exactly.** A stale hash makes clients re-download on
every join, or refuse the pack outright.


## Changing the sounds

Audio must be **Ogg Vorbis** (`.ogg`). Mono attenuates with distance; stereo plays at full
volume regardless, which is usually what you want for an announcer. Both are fine here since
the sound is played at the listener's own location.

After editing anything in this folder, rebuild the zip and recompute the hash:

```powershell
cd resourcepack
Compress-Archive -Path "pack.mcmeta","assets" -DestinationPath "..\UnstableAnnouncer.zip" -Force
(Get-FileHash "..\UnstableAnnouncer.zip" -Algorithm SHA1).Hash.ToLower()
```

Then update `resource-pack-sha1` in `server.properties` with the printed value.

To add a new line: drop `whatever.ogg` into `assets/unstable/sounds/announcer/`, add an entry
to `assets/unstable/sounds.json` pointing at `unstable:announcer/whatever`, and reference the
key `unstable:announcer.whatever` from `config.yml`.

## Notes

- `pack_format` is `75`, with `supported_formats` declaring 46-99 so one zip covers a wide
  range of client versions without a "made for a different version" warning. Widen that range
  in `pack.mcmeta` if a future update falls outside it.
- Zip the **contents**, not the folder — `pack.mcmeta` must sit at the archive root, not inside
  a wrapper directory. The command above does this correctly.
- Bedrock clients connected through Geyser do not receive Java resource packs; they hear the
  vanilla fallbacks.
