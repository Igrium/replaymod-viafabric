# ReplayMod ViaFabricPlus Compat: development notes

This is the full background for working on this mod: what problem it solves, how each piece works, why it is built
that way, how it is tested, and what is still open. The user-facing summary is in the [README](../README.md).

## 1. Goal and scope

Replay Mod plays replays recorded on older Minecraft versions by converting their packets to the running client's
protocol. It does this through **ReplayStudio**, its packet library, which embeds its own **relocated, outdated
ViaVersion fork** (`com.replaymod.replaystudio.lib.viaversion`). That fork is buggy: for example, it throws an NPE on
the demo replay in its 1.12→1.13 block-connection code (see §8).

This mod makes Replay Mod use the ViaVersion stack that **ViaFabricPlus** already ships instead: current
ViaVersion, ViaBackwards, ViaLegacy and ViaAprilFools, plus ViaFabricPlus' own protocol fixes. It also makes
ViaFabricPlus' **client-side emulation** (rendering, movement, entity and item fixes for old versions) active while
a replay plays, as if the client were connected to a server on the replay's version.

Decisions the user made:

- **Old→new conversion only.** Replays recorded on versions newer than the client are out of scope.
- **Use the embedded Via as little as possible**, without rewriting large parts of Replay Mod. It still loads,
  because Replay Mod models its *own* (native) protocol through it, but it no longer converts or reads any
  old-version packet (§3.6).
- **ViaFabricPlus' client fixes must stay active during replays** (§4).

## 2. Environment and dependencies

| Thing | Version / note |
|---|---|
| Minecraft | 26.3. Unobfuscated, so names are Mojang's; no intermediary or yarn |
| Java | 25 (`release = 25`, mixins `JAVA_25`) |
| Gradle / Loom | Gradle 9.5.1, Fabric Loom 1.17-SNAPSHOT, Loader 0.20.1 |
| Fabric API | 0.161.0+26.3 (only needed by the client gametest; the mod itself doesn't depend on it) |
| Replay Mod | `maven.modrinth:replaymod:26.3-2.6.28` |
| ViaFabricPlus | `maven.modrinth:viafabricplus:5.1.3` |
| ViaVersion | 5.12.3-SNAPSHOT, nested in the ViaFabricPlus jar |

**Why the API artifacts are `compileOnly` from repo.viaversion.com.** ViaFabricPlus' API is *not* on Maven Central;
it is published on `https://repo.viaversion.com`. The Modrinth ViaFabricPlus jar already nests the API and all Via
libraries (jar-in-jar), so it is the right runtime dependency. However, nested jars aren't on the compile classpath.
So `build.gradle` adds `com.viaversion:viafabricplus-api` and `com.viaversion:viaversion-common` as `compileOnly`,
and also for the test and gametest source sets. `viaversion_version` in `gradle.properties` **must match the build
nested in ViaFabricPlus**. When updating ViaFabricPlus, check its jar's `META-INF/jars`.

We also use some ViaFabricPlus **internals** that are outside its API, so a ViaFabricPlus update can break them:

- `ProtocolTranslationImpl.TARGET_VERSION_ATTRIBUTE_KEY` and `NATIVE_VERSION`
- `IConnection.viaFabricPlus$setUserConnection` and `viaFabricPlus$setTargetVersion`
- `ViaFabricPlusProtocol.INSTANCE`
- `ISDLEventHandler.viaFabricPlus$getPendingScreenEvents`
- the private `SettingsImpl.snapshot()`

**Reference sources** are git submodules in `reference/`: `ReplayMod` (ReplayStudio is a nested submodule of it)
and `ViaFabricPlus` at 5.1.3. Replay Mod uses a **preprocessor**: its sources contain
`//#if MC>=...` / `//$$` blocks for many MC versions, so read code with that in mind. For the
classes that actually run, the shipped jar (`javap`) is the ground truth. `reference/demo_replay.mcpr` is the test fixture
(see §6). `reference/ViaFabricPlus/AGENTS.md` contains contribution rules for *that* repo. They don't apply here,
because we never edit the submodules.

## 3. How conversion works

### 3.1 ReplayStudio's pipeline, and where we hook in

Every reader of replay packets goes through `ReplayInputStream` (ReplayStudio):

- `FullReplaySender` (normal playback)
- `QuickReplaySender` / `RandomAccessReplay` (quick mode)
- the marker processor, entity position tracker, `RestoreReplayGui`, and so on

`ReplayInputStream.<init>` creates a `ViaVersionPacketConverter` when the replay's protocol differs from the target.
`fillBuffer()` then reads each raw packet and does the following:

1. It types the packet as `rawPacket.getType()` using a `PacketTypeRegistry` for the *input* version. That registry's
   ids are reflected out of the embedded Via's mappings.
2. It switches on the type to track state (LoginSuccess, ConfigFinish, Reconfigure, Config registries) and to
   rewrite JoinGame, which ReplayStudio parses with `PacketJoinGame.read` using input-version registries.
3. It calls `converter.convertPacket(buf, rawPacket.getType().getState())`.
4. It calls `loginAcknowledged()` / `finishConfiguration()` on phase changes.

`ViaVersionPacketConverter.isProtocolVersionSupported(in, out)` backs `ReplayStudio.isCompatible`, and through
`ReplayMod.isCompatible` it decides whether the replay viewer enables "Load".

So ViaVersionPacketConverter is the single choke point. Its embedded-Via pipeline is replaced, and the input-side
typing in `fillBuffer()` is neutralised.

**Runtime type detail:** inside the replaymod jar, ReplayStudio's netty is relocated back to `io.netty`. So the
`ByteBuf`s that ReplayStudio hands us are real netty buffers, and they can go straight to real ViaVersion without
copying. ReplayStudio's `State` and `ProtocolVersion` are the *relocated* Via types; we map them by `name()` and
protocol id.

### 3.2 `MixinViaVersionPacketConverter` (`remap = false`)

- **`@Redirect` on `ProtocolManager.getProtocolPath(II)` in `<init>`.** When the mod is enabled and Via is ready,
  this creates our `ViaFabricPlusPacketConverter` delegate and returns `null`, so ReplayStudio builds no embedded
  pipeline and acts as a pass-through. Otherwise it calls the original.
- **HEAD cancellable injects on `convertPacket(ByteBuf, State)`, `loginAcknowledged` and `finishConfiguration`.**
  These forward to the delegate. The `State` argument is ignored, because the delegate tracks the state itself.
- **Static inject on `isProtocolVersionSupported`.** This returns `ViaVersions.isSupported` (same id, or a real
  Via protocol path exists).
- It implements the duck interface `ConverterHolder` to expose the delegate.

### 3.3 `ViaFabricPlusPacketConverter` (the core)

**Constructor `(inputProtocolId, outputProtocolId)`:**

- `ViaVersions.fromId` resolves ids, preferring `VersionType.RELEASE`. This matters because old ids are ambiguous
  with snapshots and special versions.
- It gets the path with `getProtocolPath(output, input)`. If the versions are the same or there's no path, the
  converter passes packets through and `isConverting()` is false.
- It creates a `CapturingUserConnection`, an offline `UserConnectionImpl(new EmbeddedChannel(), true)`, and sets
  `TARGET_VERSION_ATTRIBUTE_KEY` on its channel to the **input** version. ViaFabricPlus' providers and protocol read
  the "server version" from that attribute.
- It builds a `ProtocolPipelineImpl(user)` (which adds the base protocol) and sets `ProtocolInfo`:
  - protocol versions
  - client and server state: CONFIGURATION if ≥1.20.2, else PLAY
  - username `$Camera$` and a random UUID
- It adds every path protocol; `add()` also calls `init(user)`.
  - The path is ordered client→server, and a clientbound transform runs it in reverse.
  - The **input protocol**, the one that knows the replay version's packet ids, is the entry whose
    `outputProtocolVersion()` equals the input version, falling back to the last entry (e.g. `Protocol1_8To1_9`).
- It appends `ViaFabricPlusProtocol.INSTANCE`, exactly like a live ViaFabricPlus connection (`MixinConnection_1`).

**`convert(ByteBuf)`:**

1. It reads the varint id and names the packet with real Via:
   `inputProtocol.getPacketTypesProvider().unmappedClientboundType(state, id).getName()`, falling back to
   `ClientboundLoginPackets` for LOGIN.
2. It does state tracking and special cases by name:
   - LOGIN: `LOGIN_FINISHED`/`GAME_PROFILE` goes to CONFIGURATION (input ≥1.20.2) or PLAY. `LOGIN_COMPRESSION` is
     **dropped**.
   - CONFIGURATION: `FINISH_CONFIGURATION` goes to PLAY.
   - PLAY: `START_CONFIGURATION` goes to CONFIGURATION. `SET_COMPRESSION` is **dropped**. For `LOGIN`/`JOIN_GAME`,
     the first int (the entity id, the first field in every version from 1.7 to 26.3) is **bitwise negated**
     *before* Via sees it, so Via doesn't treat the recording player as "self" and skips its self-entity fix-ups
     (e.g. 1.8 armor). This matches ReplayStudio, whose filters expect `~id`.
     The id is only negated if it is non-negative: some replays already store `~id` (e.g. the Hypixel Bed Wars
     fixture, recorded with Replay Mod 2.6.7), and negating again would restore the real id. Via's 1.8→1.9
     `SET_EQUIPPED_ITEM` maps the *self* entity's slots 0-3 to 2-5 and drops slot 4, which put the recording
     player's chestplate on their head. ReplayStudio's own unconditional `~` has the same bug.
3. It runs `PacketWrapper.create` and `pipeline.transform(CLIENTBOUND, state, wrapper)`. The output is the packets
   Via emitted on its own (captured by `CapturingUserConnection`) followed by the transformed packet, unless the
   packet was cancelled.
4. On any other exception, strict mode (`-Dreplaymod_viafabric.strict=true`) throws an `IOException`. Otherwise it
   drops the packet, increments `DROPPED`, and warns once per `state/name`. One broken packet must not kill a replay.
5. It runs `postProcess`, which tracks the **native (output)** state with ReplayStudio's native
   `PacketTypeRegistry`:
   - LoginSuccess → `withLoginSuccess()`
   - Reconfigure → CONFIGURATION
   - ConfigCustomPayload, ConfigSelectKnownPacks and ConfigRegistries feed a `RegistriesBuilder`
   - ConfigFinish → PLAY, and `finish` on the builder
   - JoinGame is parsed with `PacketJoinGame.read(packet, registries)`, its `gameMode` is set to 3 (spectator), and
     the packet is overwritten

   This is the native-side half of ReplayStudio's JoinGame rewrite. It only ever uses the native registry.

**Why compression packets are dropped instead of converted:** ViaFabricPlus' compression provider would call
`setupCompression` on the Minecraft connection, which it reads from the channel attribute
`MINECRAFT_CONNECTION_ATTRIBUTE_KEY`. We deliberately don't set that attribute on the capture channel. Replays are
stored uncompressed, so dropping these packets is correct.

**`loginAcknowledged` / `finishConfiguration`** mirror ReplayStudio. They use Via's
`ConfigurationState.setBridgePhase(...)` and `sendQueuedPackets(user)`, and `clear()` on finish.

**Static counters** `DROPPED` (`droppedPacketCount()`) and `CREATED` (`createdCount()`) exist for diagnostics and
tests.

### 3.4 `CapturingUserConnection`

- **Clientbound** sends (`sendRawPacket`, `scheduleSendRawPacket`, `sendRawPacketFuture`, the last returning a
  succeeded future) are **captured** and returned by `drainCaptured()`, so packets Via generates itself (extra
  chunks, queued config packets) reach Replay Mod in order.
- **Serverbound** sends (`sendRawPacketToServer`, `scheduleSendRawPacketToServer`) are **released and discarded**.
  During playback, ViaFabricPlus features that "talk to the server" (swing, item pick, inventory sync) therefore
  become no-ops.

**Why not ViaFabricPlus' `NoPacketSendChannel` / `ConversionsImpl.createDummyUserConnection`:** that channel drops
the packets Via generates itself, and its attributes are global. We need our own channel per stream.

### 3.5 `MixinReplayInputStream` (`remap = false`)

It applies two `@WrapOperation`s on `Packet.getType()` inside `fillBuffer`:

- ordinal 0 is the `switch (rawPacket.getType())`
- ordinal 1 is `rawPacket.getType().getState()`

When our delegate is converting, both return a neutral `UnknownLogin`, `UnknownConfiguration` or `UnknownPlay`,
matching the state the delegate tracks. ReplayStudio's switch then never consults the embedded-Via input registry or
parses input JoinGame packets, and the state it passes to `convertPacket` comes from us. Before that, it calls
`delegate.initInputState(rawPacket.getRegistry().getState())`. That initial state depends only on the file format
(whether the login phase was recorded), not on packet ids.

When the delegate is null or not converting (same version, or mod disabled), the original call runs. It also
injects `close` to close the delegate.

### 3.6 What still uses the embedded Via, and why

| Use | Status |
|---|---|
| Building the conversion pipeline | Replaced (`@Redirect`) |
| `isProtocolVersionSupported` | Replaced |
| Typing input-version packets, input JoinGame parsing | Replaced (`MixinReplayInputStream` + converter) |
| **Native 26.3 `PacketTypeRegistry`** (filters, sender, editor, our `postProcess`) | **Kept.** This is Replay Mod's model of its own version; replacing it means rewriting Replay Mod |
| Relocated `ProtocolVersion`/`State` in ReplayStudio signatures, `CustomViaManager` static init | **Kept, unavoidable.** We convert to real Via types at the boundary |

## 4. Keeping ViaFabricPlus' client-side fixes active (`ReplaySession`)

About 588 ViaFabricPlus features gate on the **global** `ViaFabricPlus.api().targetVersion()` or on the connection's
`userConnection()`. Replay Mod's replay connection is a hand-made `Connection` on an `EmbeddedChannel`, so out of
the box ViaFabricPlus treats a replay as native. `ReplaySession` (global state, since only one replay is open at a
time) fixes this.

- **`MixinReplayHandler`**: at `setup()` RETURN it calls `ReplaySession.begin(replayFile, channel)`. `setup()` runs
  on open and again on every restart, e.g. jumping backwards, which is why the log shows "Replay opened" twice. At
  `endReplay()` RETURN it calls `ReplaySession.end()`.
- **`begin`**:
  - It takes the `Connection` from `channel.pipeline().get("packet_handler")`.
  - It reads the replay protocol from metadata. `replayProtocol()` reimplements `ReplayMetaData.getProtocolVersion`
    without the embedded Via, using `PROTOCOL_FOR_FILE_FORMAT` for file format < 10.
  - The target is the replay version, or `NATIVE_VERSION` when `-Dreplaymod_viafabric.emulateReplayVersion=false`.
  - It sets `TARGET_VERSION_ATTRIBUTE_KEY` on the replay channel and calls `IConnection.viaFabricPlus$setTargetVersion`.
  - It remembers the user's previous target in `previousTargetVersion`, only the first time, so restarts don't
    overwrite it, then calls `setTargetVersion(target)`.
- **`MixinFullReplaySender`** (targets `FullReplaySender` and its anonymous `FullReplaySender$2`): a
  `@ModifyExpressionValue(method = "*")` on `ReplayFile.getPacketData(...)` calls `ReplaySession.bind(delegate)`
  each time the real-time sender opens a stream. `bind` calls `IConnection.viaFabricPlus$setUserConnection(...)`, so
  ViaFabricPlus sees the Via connection that actually translated what is on screen, with its entity trackers and
  storages filled. Jumping backwards opens a new stream, which rebinds.
- **`end`**: restores `previousTargetVersion`.

### 4.1 Side effects of emulating an old version, and their fixes

Setting the global target to an old version turns on ViaFabricPlus behaviour that assumes a live server. Two
problems were found in manual testing, both fixed in commit `2a9b0d2`:

- **Frozen menus in a paused replay → `MixinMinecraft`.** For targets ≤1.12.2, ViaFabricPlus
  (`features/v1_12_2/MixinSDLEventHandler`) queues screen input (keys, clicks, scrolling, text) while a screen is
  open, and only drains the queue during `Minecraft.tick` (`features/v1_12_2/MixinMinecraft`), emulating old input
  timing. Replay Mod runs **no game ticks while paused** (and pressing Escape pauses). So the queue never drained,
  every screen ignored input, and the user had to Alt+F4. Our mixin drains the queue at the HEAD of
  `Minecraft.runTick` (every frame) while `ReplaySession.replayTargetVersion() != null`. That timing only matters to
  a server, so nothing is lost.
- **The replay's version persisted as the user's selection → `MixinSettingsImpl`.** ViaFabricPlus'
  `SettingsImpl.snapshot()` saves `ViaFabricPlus.api().targetVersion()` as `selected_protocol_version`. Quitting
  mid-replay (Alt+F4 included) saved 1.8.x, so the next launch started on 1.8.x, and `previousTargetVersion` became
  1.8.x as well. Our inject at `snapshot` RETURN overwrites the property with `ReplaySession.userTargetVersion()`
  (the pre-replay selection) while a replay is open.

**If more problems like this appear**, they are likely ViaFabricPlus features that assume ticks, a server, or a
real player. Plan-time risks that haven't been seen yet:

- movement emulation affecting the camera entity
- the ≤1.20.3 `setAutoRead` mixins acting on Replay Mod's channel

The intended pattern for fixes is a targeted mixin that checks `ReplaySession.replayTargetVersion() != null`.

### 4.2 Quick mode

`QuickReplaySender` / `RandomAccessReplay` pre-convert the whole replay, and their converters are never bound; the
last full-mode connection stays attached. ViaFabricPlus' "sync task" payload rewrites (1.18.2 block-break ack,
1.13.2 large chests) depend on being handled in the same session that converted them, so they may misbehave in
quick mode. This is documented in the README.

## 5. Configuration

`CompatConfig` reads JVM system properties:

| Property | Default | Effect |
|---|---|---|
| `replaymod_viafabric.disable` | false | Mod fully off; ReplayStudio's embedded Via is used (A/B comparison) |
| `replaymod_viafabric.strict` | false | Throw on conversion failures instead of dropping and logging. The unit tests set it |
| `replaymod_viafabric.emulateReplayVersion` | true | false keeps ViaFabricPlus on the native version during playback |

Everything also falls back to the original behaviour until `ViaVersions.ready()` is true, i.e. `Via.isLoaded()`
and the protocol manager exists. Via initialises asynchronously and is ready by the time the title screen shows.

## 6. Testing

**Does Minecraft have a testing framework?** Mojang's GameTest is server-side and structure-based, so it doesn't
fit here. Fabric API provides `fabric-client-gametest-api-v1`, which drives a real client (waiting on conditions,
screenshots, input). There is also `fabric-loader-junit` for JUnit with Knot and mixins but no window. Both are
used.

### 6.1 Unit tests: `./gradlew test` (no display, about 30 s)

- They run under `fabric-loader-junit`, so our mixins apply to ReplayStudio classes.
- The working dir is `build/junit-run`, because Minecraft and ViaFabricPlus write config and logs to the working
  directory. An earlier run polluted the repo root; see §8.
- **`TestBootstrap`**, in this order:
  1. Invoke the `viafabricplus` entrypoint `onPreLoading`. This must come **before** `Bootstrap`, otherwise you get
     "ViaFabricPlus has not been initialized!".
  2. Call `SharedConstants.tryDetectVersion()` and `Bootstrap.bootStrap()`.
  3. Register ViaFabricPlus content manually: unfreeze `PARTICLE_TYPE` via reflection on `MappedRegistry.frozen`,
     invoke `onPostRegistryLoading`, and refreeze. In game this happens from a mixin that doesn't fire under JUnit.
     Without it, the full demo fails on the `FootStepParticle1_12_2` id.
  4. Wait for `ViaVersions.ready()` and `hasLoadedMappings()`.
- **`ReplayReader`** streams a replay through `ZipReplayFile.getPacketData(PacketTypeRegistry.get(native, LOGIN))`
  and returns counts, order, JoinGame entity ids, and unknown login/config packets.
- **`SyntheticReplay`** writes an `.mcpr` from hand-written raw packets.
- **`ConverterSmokeTest`**:
  - It converts a synthetic 1.12.2 replay (LoginSuccess, JoinGame with eid 42, SpawnPosition, Chat, KeepAlive,
    TimeUpdate) and asserts it is compatible and converted, with the expected counts and the entity id `~42`.
  - A same-version replay must pass through unchanged.
- **`ReplayFixtureTest`**:
  - It is parameterised over `*.mcpr` in `reference/` (or `-PreplayFixtures=<dir>`) plus
    `src/test/resources/replays/`.
  - It converts the first 60 s of replay time in strict mode, or everything with `-PfullReplay`, and asserts no
    `UnknownLogin`/`UnknownConfiguration` appears.
  - `UnknownPlay` is expected and allowed: ReplayStudio only models some packets.
  - It also prints a comparison against the embedded converter (kill switch on).
- **Results:**
  - Everything passes.
  - The full demo converts all 778,092 packets (1,066,225 ms of replay time) in strict mode, with zero failures.
  - The demo already stores the entity id as -67 (= ~66), consistent with the negation.

### 6.2 Client gametest: `xvfb-run -a ./gradlew runClientGameTest`

- It lives in source set `src/gametest` (created by `fabricApi.configureTests`), with mod id
  `replaymod-viafabric-test`.
- **`ReplayPlaybackClientGameTest`**, for each fixture:
  1. Set the native target, copy the fixture to `replay_recordings`, and call `startReplay`.
  2. Wait for the level, the camera entity and the replay handler. Fail on `DisconnectedScreen` (read via
     `mc.gui.screen()`).
  3. Call `setReplaySpeed(1)`, play 15 s, run `lookAtTerrain`, then play another 15 s.
  4. Assert that the target is the replay version and that `userConnection() == ReplaySession.boundUserConnection()`.
  5. Take the screenshot `replay-<name>-start`.
  6. Jump to `min(duration - 1 s, 5:00)` with `jumpToTime`, polling in **real time**, then play, look at terrain and
     take another screenshot.
  7. Assert 0 dropped packets, call `endReplay`, and assert the target is restored.
- **`lookAtTerrain`** counts non-empty sections within 12 chunks and asserts there are more than 0, then moves the
  camera above them. This exists because the camera starts over the void in Bed Wars, and early screenshots were
  blank.
- **Why real-time polling:** Replay Mod pauses after a large jump (`setReplaySpeed(0)`, which makes `tickLength`
  infinite and stops game ticks). Waiting a number of ticks while it is paused hangs forever, so the test waits with
  sleeps against a deadline and then unpauses.
- The user asked to **let it play for a few seconds** because Replay Mod is slow to render chunks. The 30 s play
  time comes from that.
- **`MixinGlBackend` (test-only).** MC 26.3 uses SDL3 and renderpearl and requests `SDL_GL_FRAMEBUFFER_SRGB_CAPABLE`
  (attribute 22). Xvfb's GLX has no sRGB visuals, so the window fails with "Couldn't find matching GLX visual". This
  was verified with a ctypes `glXChooseVisual` probe. With `-Dreplaymod_viafabric.gametest.noSrgbFramebuffer=true`
  (set by the task), the mixin requests 0. Only screenshot colours are affected, and the mixin isn't in the mod jar.
- **Result:** it passes on Mesa llvmpipe (OpenGL 4.5): target 1.8.x during playback, user connection bound, 0
  dropped packets, target restored, Bed Wars islands visible in both screenshots (197 and 377 non-empty sections).
- **Known gap:** it never opens a screen while paused, so it would not have caught the frozen-menu bug (§4.1). A
  test that pauses, opens the pause menu, and closes it with a simulated Escape was offered but isn't written yet.

### 6.3 Running locally

- `./gradlew runClient` starts the dev client. Its game dir is `run/`, so put replays in `run/replay_recordings/`.
- Check `run/logs/latest.log` for `(replaymod-viafabric)` lines:
  - "ReplayMod will convert replays with ViaFabricPlus' ViaVersion"
  - "Replay opened (recorded on …); ViaFabricPlus target version set to …"
  - "Replay closed; … restored to …"
- The persisted selection is `run/config/viafabricplus/settings.json` → `selected_protocol_version`.
- For headless runs without `xvfb-run`, use
  `Xvfb :77 -screen 0 1280x720x24 +extension GLX +render -noreset`, `DISPLAY=:77` and `LIBGL_ALWAYS_SOFTWARE=1`.
- The first gametest run needs network access to download assets, so don't use `--offline` for it.

### 6.4 CI (`.github/workflows/build.yml`)

1. `./gradlew build`, which includes the unit tests.
2. Install `xvfb libgl1-mesa-dri libglx-mesa0`.
3. `xvfb-run -a -s "-screen 0 1280x720x24 +extension GLX +render -noreset" ./gradlew runClientGameTest` with
   `LIBGL_ALWAYS_SOFTWARE=1`.
4. Upload the screenshots and `build/libs`.

CI hasn't been observed running yet.

## 7. File map

```
src/main/java/com/igrium/replaymod_viafabric/
  ReplayModViaFabricPlusCompat.java   client entrypoint; logs whether conversion is active
  CompatConfig.java                   system-property switches
  duck/ConverterHolder.java           exposes the delegate from converter / input stream
  via/ViaVersions.java                ready(), fromId(), path(), isSupported()
  via/CapturingUserConnection.java    offline Via connection; captures clientbound, drops serverbound
  via/ViaFabricPlusPacketConverter.java  the converter (§3.3)
  replay/ReplaySession.java           target version + user connection during playback (§4)
  mixin/MixinViaVersionPacketConverter.java  replaces ReplayStudio's converter (§3.2)
  mixin/MixinReplayInputStream.java   neutralises input-side typing (§3.5)
  mixin/MixinReplayHandler.java       replay open/close → ReplaySession
  mixin/MixinFullReplaySender.java    binds each new stream's user connection
  mixin/MixinMinecraft.java           drains ViaFabricPlus' queued screen input during replays (§4.1)
  mixin/MixinSettingsImpl.java        saves the pre-replay version selection (§4.1)
src/test/java/...                     JUnit layer (§6.1)
src/gametest/java/...                 client gametest + MixinGlBackend (§6.2)
reference/                            submodules + demo_replay.mcpr (1.8.9, protocol 47, file format 14, Hypixel Bed Wars, ~17 min)
```

## 8. Known issues and open items

- **The embedded Via fails on the demo** in the comparison test, with an NPE in `WorldPacketRewriter1_13` because
  `ConnectionData.blockConnectionProvider` is null. It also logs "Via.getAPI() is null CompletionException" from its
  own async init. This is not caused by our code, but it may be an artifact of the JUnit environment; it hasn't been
  confirmed in game.
- **Replays without a recorded login phase** (file format < 14) take ReplayStudio's synthetic-login path
  (`withLoginSuccess`). This is covered only by the synthetic test, not by a real recording.
- **Newer→older replays** are unsupported, by design.
- **Quick-mode caveats**: see §4.2.
- **Generated jars in branch history**: about 9.5 MB of `.fabric` jars plus `config/` and `logs/` were committed in
  `8f7ff45` and removed in `e3d5283`. They are now ignored by `.gitignore`. A force-push to rewrite history was
  blocked, so squash or rewrite before merging if a clean history matters.
- **Suggested next step**: the gametest for the pause menu (§6.2).

## 9. Git conventions for this repo

- Work on branch `claude/serene-tesla-130ki6`; `master` is the main branch. Don't open a PR unless asked.
- Commit and push when asked, and don't rewrite history (force-push) without explicit permission.
- End commit messages with a `Co-Authored-By:` trailer. Don't put model ids in code or docs.
- The latest commits are `2a9b0d2` (pause-menu and saved-version fixes) and `29156dd` (gametest, CI and docs).
