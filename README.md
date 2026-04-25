# Conveyor Router — Mindustry Java Mod

A logistics builder tool for Mindustry. Press **C**, click a destination tile, and the mod automatically finds the nearest unconnected conveyor, A\* pathfinds a route, and queues conveyor build plans for your unit.

## Features

- **One-hotkey workflow**: Press C to enter connect mode, click to set destination.
- **Automatic source detection**: BFS searches outward from the destination to find the nearest conveyor with an open side.
- **A\* pathfinding**: Cardinal-only movement with turn penalties, obstacle avoidance, and reuse of existing aligned conveyors.
- **Bridge support**: Automatically places bridge conveyors to hop over short blocked gaps when a normal route is impossible.
- **Type-preserving**: Uses the exact same conveyor type as the source — no upgrades or downgrades.
- **Non-destructive**: Never removes or replaces existing buildings.

## Controls

| Key | Action |
|-----|--------|
| **C** | Toggle conveyor-connect mode |
| **Left Click** | Set destination tile (while in connect mode) |
| **C** (again) | Cancel connect mode |

## Building

This project follows the [official Mindustry Java mod template](https://github.com/Anuken/MindustryJavaModTemplate).

### Prerequisites
- JDK **17**

### Desktop Testing

```bash
cd ConveyorRouter
./gradlew jar          # Linux/Mac
gradlew jar            # Windows
```

Output: `build/libs/ConveyorRouterDesktop.jar`

**This jar works for desktop testing only.** For an Android-compatible build, use GitHub Actions or run `./gradlew deploy` with the Android SDK configured (see the template README for details).

### Installing

Copy the jar into your Mindustry mods folder:

- **Linux**: `~/.local/share/Mindustry/mods/`
- **Windows**: `%appdata%/Mindustry/mods/`
- **macOS**: `~/Library/Application Support/Mindustry/mods/`
- **Steam**: `steam/steamapps/common/Mindustry/saves/mods/`

Restart Mindustry. Check the log for `[ConveyorRouter] Initialized`.

## Project Structure

```
ConveyorRouter/
├── mod.hjson                          # Mod metadata (hjson format)
├── build.gradle                       # Gradle build (matches official template)
├── gradle.properties
├── settings.gradle
├── .gitignore
├── .github/workflows/build.yml        # CI for auto-building
├── assets/sprites/                    # (empty — no custom sprites)
└── src/
    └── conveyorrouter/
        ├── ConveyorRouterMod.java     # Entry point, hotkey, orchestration
        ├── SourceFinder.java          # BFS to find nearest unconnected conveyor
        ├── SourceResult.java          # Data class for search results
        ├── ConveyorPathfinder.java    # A* pathfinder with bridge support
        ├── PathNode.java              # Path node data class
        └── PlanPlacer.java            # Converts path → BuildPlan objects
```

## How It Works

1. **Hotkey** (`C`): Toggles `selectingDestination` flag. Next left-click is captured.
2. **Source search** (`SourceFinder`): BFS expands outward from the destination tile. First conveyor with at least one open cardinal side is selected. Searches up to 80 tiles.
3. **Pathfinding** (`ConveyorPathfinder`): A\* from source to destination over tile coordinates.
   - **Costs**: Empty tiles 1.0, aligned conveyors 0.2, misaligned 5.0, turns +0.4, junctions 0.5, bridges 4.0+.
   - **Bridges**: When a cardinal neighbour is blocked, scans up to 4 tiles ahead for a valid landing.
   - **Safety**: Capped at 20,000 node expansions.
4. **Placement** (`PlanPlacer`): Walks the path, computes rotation, creates `BuildPlan` objects, queues on player's unit. Bridge endpoints get bridge block plans.

## Limitations (v1)

- No path preview rendering.
- No modded conveyor support.
- No diagonal routing.
- No automatic type upgrades/downgrades.
- Does not unpause the game.
- Does not enforce player build range.
- Bridge mapping is hardcoded for vanilla blocks only.

## License

MIT
