# ReplayMod ViaFabricPlus Compat

Fabric mod (MC 26.3, Java 25) that makes Replay Mod convert old replays with ViaFabricPlus' ViaVersion instead of
ReplayStudio's embedded fork, and keeps ViaFabricPlus' client-side emulation active during playback.

Read the full design, rationale, test setup and open items before making changes:

@docs/DEVELOPMENT.md

Quick reference:

- Build and unit tests: `./gradlew build`
- Client gametest (headless): `xvfb-run -a ./gradlew runClientGameTest`
- Dev client: `./gradlew runClient` (game dir `run/`, replays go in `run/replay_recordings/`)
- `reference/` holds the Replay Mod and ViaFabricPlus sources as submodules, for reading only. Don't edit them.
  Replay Mod's sources use a preprocessor (`//#if MC>=...`), so check the shipped jar with `javap` when unsure.
- Git: work on `claude/serene-tesla-130ki6`. Don't open PRs unless asked, and never force-push without permission.
