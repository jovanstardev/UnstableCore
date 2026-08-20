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

1. Upload `UnstableAnnouncer.zip` (in the project root) anywhere that serves a **direct
   download** over HTTPS. A GitHub Release asset works well and is free.
2. Put the URL and hash in `server.properties`:

```properties
resource-pack=https://your-host/UnstableAnnouncer.zip
resource-pack-sha1=12a863e2f27492e97cf721a783b6bd138e30800f
require-resource-pack=false
```

`require-resource-pack=false` keeps the pack optional — players who decline still hear the
vanilla fallbacks. Set it to `true` only if you want to kick players who refuse.

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

- `pack_format` is `46` (Minecraft 1.21.4). If the client shows an "incompatible pack" warning
  after a game update, bump it in `pack.mcmeta`; the pack usually still loads regardless.
- Zip the **contents**, not the folder — `pack.mcmeta` must sit at the archive root, not inside
  a wrapper directory. The command above does this correctly.
- Bedrock clients connected through Geyser do not receive Java resource packs; they hear the
  vanilla fallbacks.
