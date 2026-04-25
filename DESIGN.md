# Conveyor Optimizer Mod — Design Document

## 1. Overview

The Conveyor Optimizer ("ConvOpt") lets a player select a rectangular area, analyzes the conveyor logistics graph inside it, generates a cleaner layout that preserves identical external behavior, previews it, and applies it on confirmation.

## 2. Architecture

```
User Interaction Layer
  ├── SelectionTool        — drag-select rectangle, hotkey binding
  ├── PreviewRenderer      — ghost overlay of proposed layout
  └── ConfirmDialog        — apply / cancel / undo

Analysis Layer
  ├── TileScanner          — reads tiles in selection, classifies blocks
  ├── LogisticsGraph       — directed graph of logistics connections
  └── BoundaryDetector     — identifies entry/exit points at selection edges

Optimization Layer
  ├── PathPlanner          — A* pathing for each source→sink pair
  ├── BridgePlacer         — replaces unavoidable crossings with bridges
  └── LayoutAssembler      — merges individual paths into a tile grid

Validation Layer
  ├── GraphComparator      — compares before/after logistics graphs
  └── SafetyChecker        — rejects layouts with changed behavior
```

## 3. Data Structures

### 3.1 Block Classification

```
enum BlockRole {
    CONVEYOR,         // conveyor, titaniumConveyor, armoredConveyor
    JUNCTION,         // junction
    ROUTER,           // router, distributor
    SORTER,           // sorter, invertedSorter
    OVERFLOW,         // overflowGate, underflowGate
    BRIDGE,           // itemBridge, phaseConveyor
    UNLOADER,         // unloader
    NON_LOGISTICS,    // everything else — never touched
    AIR               // empty tile
}
```

### 3.2 LogisticsNode

Each logistics block inside the selection becomes a node:

```
class LogisticsNode {
    int x, y;                          // tile coords
    Block block;                       // mindustry block type
    int rotation;                      // 0-3
    BlockRole role;
    Item filterItem;                   // for sorters/unloaders, null otherwise
    Team team;
    boolean isBoundaryInput;           // receives items from outside selection
    boolean isBoundaryOutput;          // sends items to outside selection
    int boundaryDirection;             // which edge direction crosses boundary
    Set<LogisticsNode> inputs;         // nodes feeding into this one
    Set<LogisticsNode> outputs;        // nodes this one feeds
}
```

### 3.3 LogisticsGraph

```
class LogisticsGraph {
    Map<Long, LogisticsNode> nodes;    // key = packTile(x,y)
    List<BoundaryPort> inputPorts;     // items entering selection
    List<BoundaryPort> outputPorts;    // items leaving selection
    List<SourceSinkPair> flows;        // derived source→sink connections
}
```

### 3.4 BoundaryPort

```
class BoundaryPort {
    int x, y;                          // tile inside selection at boundary
    int direction;                     // direction of external connection
    boolean isInput;                   // true = items flow IN
    Block externalBlock;               // what's on the other side
    Block internalBlock;               // what's on this side
    Item filterItem;                   // if connected through sorter
}
```

### 3.5 SourceSinkPair

```
class SourceSinkPair {
    BoundaryPort source;               // where items enter
    BoundaryPort sink;                 // where items leave
    Block conveyorType;                // tier to use for the path
}
```

### 3.6 ProposedLayout

```
class ProposedLayout {
    Map<Long, PlannedBlock> blocks;    // tile coords → what to build
    List<BuildPlan> buildPlans;        // Mindustry build queue
    List<BuildPlan> removePlans;       // blocks to break first
    boolean valid;                     // passed validation?
    String failureReason;              // why validation failed (if any)
}

class PlannedBlock {
    int x, y, rotation;
    Block block;
}
```

## 4. Algorithm

### Phase 1: Scan & Classify

1. Iterate every tile in the user-selected rectangle.
2. Classify each block into a `BlockRole`.
3. Skip `NON_LOGISTICS` and `AIR` — they are obstacles or free space.
4. Build a `LogisticsNode` for each logistics block.

### Phase 2: Build the Graph

For each node, determine connectivity:

- **Conveyor**: outputs to the tile it faces (rotation). Accepts input from the 3 non-facing sides IF the neighbor is a conveyor/router/junction/bridge pointing toward it.
- **ArmoredConveyor**: same as conveyor but only accepts side-input from other conveyors (not routers, etc.).
- **Junction**: passes items straight through. An item entering from the east exits to the west.
- **Router**: accepts from any side, distributes to all adjacent logistics blocks (round-robin).
- **Sorter**: routes the configured item one direction, everything else the other.
- **Bridge**: start endpoint connects to end endpoint; items teleport across.

### Phase 3: Detect Boundaries

For each node adjacent to the selection edge:
- Check each cardinal neighbor outside the selection.
- If external neighbor is a logistics block that feeds INTO this node → `BoundaryPort(isInput=true)`.
- If this node feeds OUT to external neighbor → `BoundaryPort(isInput=false)`.

### Phase 4: Derive Flows

Walk the graph from each input port, following edges, to find all reachable output ports. Each input→output reachable pair is a `SourceSinkPair`.

For V1, if routers/sorters create ambiguous multi-path flows, the optimizer treats the subgraph as "pinned" and does not reroute those segments — only clear conveyor-only chains get optimized.

### Phase 5: Plan Paths (A*)

For each `SourceSinkPair`:

1. Clear the working grid (mark non-logistics obstacles as impassable).
2. Run A* from source boundary tile to sink boundary tile.
3. Cost model (from distribution-blocks reference):
   - Empty tile: 1.0
   - Existing aligned conveyor (same flow): 0.2
   - Turn: +0.4 penalty
   - Bridge hop: 4.0 + 0.5 × span
   - Misaligned conveyor: 5.0
   - Any non-logistics building: impassable
4. Cap at 20,000 node expansions to avoid freezing.
5. When two paths must cross, insert a junction or bridge pair.

### Phase 6: Resolve Crossings

After all paths are planned on the grid:

1. Detect tile conflicts (two different paths claim the same tile with different directions).
2. If paths cross perpendicularly → place a junction.
3. If paths are parallel or conflict otherwise → insert a bridge pair to hop one path over the other.
4. If no resolution fits → mark this optimization as failed for safety.

### Phase 7: Assemble Layout

Convert the path grid into `PlannedBlock` entries:
- Conveyors with correct rotation matching path direction.
- Junctions at crossings.
- Bridge pairs at bridge hops.
- Preserve conveyor tier from the original source→sink path.

### Phase 8: Validate

Rebuild a `LogisticsGraph` from the proposed layout. Compare against the original:

| Check | Condition |
|---|---|
| Same input ports | Every original input port exists at the same (x,y,direction) |
| Same output ports | Every original output port exists at the same (x,y,direction) |
| Same reachability | For each input port, the set of reachable output ports is identical |
| No new connections | No output port is reachable from an input port that couldn't reach it before |
| No lost connections | No previously-reachable output port is now unreachable |
| Filter preservation | Sorters/overflow gates in the new layout have the same filter config |
| Direction preservation | All conveyors are one-way in the correct direction |

If any check fails → `valid = false`, record reason, do not apply.

## 5. Edge Cases

| Edge Case | Handling |
|---|---|
| **Circular conveyor loops** | Detect cycles during flow analysis. Pin cyclic subgraphs — do not reroute. |
| **Router fan-out** | Pin routers and their immediate neighbors. Only optimize clear chains between pinned nodes. |
| **Sorter filter chains** | Preserve sorter position and config. Route around them. |
| **Bridge already in selection** | Include in graph as a single virtual edge. Preserve if endpoints are at boundary. |
| **Multi-block buildings** | Skip any tile belonging to a block with `size > 1`. Treat as impassable. |
| **Empty selection** | Show "nothing to optimize" toast. |
| **Selection too large** | Cap at ~64×64. Beyond that, warn and refuse. |
| **Conveyor tier mismatch** | Each path preserves the tier of its source conveyor. Never mix tiers. |
| **Armored conveyor side-input** | A* must respect armored rules — only conveyors can side-feed. |
| **Multiplayer** | Disable mod on `Vars.net.client()` to avoid desyncs. |
| **Items on conveyors** | Items in transit may be lost during reconstruction. Warn user. |

## 6. User Interaction Flow

```
1. Press F6 (configurable) to enter selection mode.
2. Click + drag to select rectangle. Selection highlighted in blue.
3. Press F6 again (or Enter) to run optimization.
4. If optimization succeeds:
   a. Show ghost preview (green tint for new blocks, red for removed).
   b. Press Enter to apply, Escape to cancel.
5. If optimization fails:
   a. Show toast with reason.
   b. Highlight problematic area if possible.
6. After applying, the old layout is stored for undo.
7. Press Ctrl+Z to undo (re-queues original build plans).
```

## 7. Implementation Plan

### Files

| File | Purpose |
|---|---|
| `ConveyorOptimizerMod.java` | Mod entry point, keybinds, event hooks |
| `graph/BlockRole.java` | Block classification enum |
| `graph/LogisticsNode.java` | Node in the logistics graph |
| `graph/BoundaryPort.java` | Entry/exit point at selection edge |
| `graph/LogisticsGraph.java` | Full graph + boundary analysis |
| `graph/GraphBuilder.java` | Scans tiles, builds graph |
| `optimizer/AStarPathfinder.java` | A* over tile grid |
| `optimizer/PathPlanner.java` | Plans paths for all flows |
| `optimizer/CrossingResolver.java` | Replaces crossings with junctions/bridges |
| `optimizer/LayoutAssembler.java` | Converts paths to build plans |
| `optimizer/GraphValidator.java` | Compares before/after graphs |
| `ui/SelectionHandler.java` | Rectangle selection + rendering |
| `ui/PreviewRenderer.java` | Ghost overlay of proposed layout |

### Build Order

1. `BlockRole` + `LogisticsNode` + `BoundaryPort` (data structures)
2. `GraphBuilder` (scan + classify + connect)
3. `LogisticsGraph` (boundary detection + flow derivation)
4. `AStarPathfinder` (core pathing)
5. `PathPlanner` + `CrossingResolver` + `LayoutAssembler`
6. `GraphValidator` (safety)
7. `SelectionHandler` + `PreviewRenderer` (UI)
8. `ConveyorOptimizerMod` (wire everything together)
