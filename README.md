# ReplayMod ViaFabricPlus Compat

A client-side Fabric mod that makes [Replay Mod](https://replaymod.com) use
[ViaFabricPlus](https://github.com/ViaVersion/ViaFabricPlus) for cross-version replay compatibility, instead of
the old, relocated ViaVersion fork that Replay Mod embeds (through its ReplayStudio library).

With it, replays recorded on older Minecraft versions are converted by the same ViaVersion stack that
ViaFabricPlus uses to join old servers. That stack is ViaVersion, ViaBackwards, ViaLegacy and ViaAprilFools, plus
ViaFabricPlus' own protocol fixes. ViaFabricPlus' client-side emulation (rendering, entity, movement, item fixes,
and so on) is also active while such a replay plays, just like when connected to a server running that version.

Requires Minecraft 26.3, Replay Mod `26.3-2.6.28`+ and ViaFabricPlus `5.1.3`+.

## How it works

ReplayStudio funnels all cross-version reading through one class, `ViaVersionPacketConverter`, used by
`ReplayInputStream`. The mod's mixins take over three things:

- **Conversion.** `MixinViaVersionPacketConverter` stops ReplayStudio from building a pipeline from its embedded
  ViaVersion. Instead, a `ViaFabricPlusPacketConverter` builds an offline, client-side ViaVersion connection on
  ViaFabricPlus' ViaVersion instance, with the protocol path from the replay's version to the client's version,
  plus ViaFabricPlus' own protocol.
- **Compatibility check.** `isProtocolVersionSupported` uses real ViaVersion protocol paths. This decides which
  replays the replay viewer lets you load.
- **Reading old packets.** ReplayStudio used its embedded ViaVersion's packet tables to read *input* packets for
  state tracking and for a camera-related join game rewrite. `MixinReplayInputStream` disables that. The converter
  identifies input packets with real ViaVersion's packet definitions and does the rewrite around the conversion.

ReplayStudio's own packet registry is still used for the client's **native** protocol, which is how all of Replay
Mod models its own version. The embedded ViaVersion therefore still loads, but it no longer converts anything.

During playback:

- `ReplaySession` sets ViaFabricPlus' target version to the replay's version, and restores your previous
  selection when the replay is closed.
- It exposes the ViaVersion connection that translates the replay as the replay connection's user connection, so
  ViaFabricPlus features read the replay's ViaVersion state.
- Anything ViaFabricPlus tries to send to the "server" is discarded.

### Options (JVM system properties)

| Property | Default | Effect |
|---|---|---|
| `-Dreplaymod_viafabric.disable=true` | `false` | Turn the mod off; Replay Mod uses its embedded ViaVersion again |
| `-Dreplaymod_viafabric.emulateReplayVersion=false` | `true` | Keep ViaFabricPlus on the native version during playback; conversion is unaffected |
| `-Dreplaymod_viafabric.strict=true` | `false` | Fail instead of dropping (and logging) packets that can't be converted |

### Known limitations

- Only old→new conversion. Replays from newer versions than the client aren't supported.
- Replays without a recorded login phase (file format < 14, very old Replay Mod versions) take ReplayStudio's
  synthetic-login path. That path is only covered by the hand-written test, not by a real recording.
- ViaFabricPlus' "sync task" rewrites rely on being handled in the same session that converted them. These are
  the 1.18.2 block-break ack and the 1.13.2 large chests. That holds in normal playback, but not in Replay Mod's
  quick mode, which pre-converts the whole replay.

## Development

Builds with Gradle and Java 25:

```bash
./gradlew build
```

Compiling uses `com.viaversion:viafabricplus-api` and `viaversion-common` from `repo.viaversion.com` as
`compileOnly`. At runtime the Modrinth ViaFabricPlus and Replay Mod jars are used; ViaFabricPlus nests its API and
the Via libraries inside its jar.

### Tests (headless)

There are two layers, and both run without a physical display.

**Unit tests** run under [`fabric-loader-junit`](https://github.com/FabricMC/fabric-loader), with mixins applied
and no window:

```bash
./gradlew test
```

- `ConverterSmokeTest` converts a hand-written 1.12.2 replay.
- `ReplayFixtureTest` converts every `*.mcpr` in `reference/`, or in the directory given by
  `-PreplayFixtures=<dir>`, plus anything in `src/test/resources/replays/`.
  - By default it converts the first minute of each replay; add `-PfullReplay` to convert all of it.
  - It also prints a comparison with Replay Mod's embedded ViaVersion.

**Client game test** ([Fabric client gametest API](https://docs.fabricmc.net/develop/automatic-testing)) launches
the real client, plays each fixture, checks that ViaFabricPlus' emulation is active during playback, jumps ahead,
and takes screenshots. Under Xvfb, with Mesa's software renderer:

```bash
xvfb-run -a ./gradlew runClientGameTest
```

Screenshots are written to `build/run/clientGameTest/screenshots/`. CI runs both layers and uploads the
screenshots.

## License

GPL-3.0
