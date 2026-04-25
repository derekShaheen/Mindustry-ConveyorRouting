package conveyoroptimizer.optimizer;

import conveyoroptimizer.graph.BlockRole;
import conveyoroptimizer.graph.BoundaryPort;
import conveyoroptimizer.graph.LogisticsGraph;
import conveyoroptimizer.graph.LogisticsNode;
import mindustry.content.Blocks;
import mindustry.world.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans optimized conveyor paths for all flows in the logistics graph.
 *
 * Strategy:
 * 1. Sort flows by Manhattan distance (shortest first — they claim less space).
 * 2. For each flow, run A* from source boundary tile to sink boundary tile.
 * 3. Claim tiles as paths are planned to prevent conflicts.
 * 4. Where two paths must cross, insert junctions or bridges.
 * 5. Assemble all paths into a ProposedLayout.
 */
public class PathPlanner {

    private final LogisticsGraph originalGraph;
    private final AStarPathfinder pathfinder;
    private final int minX, minY, maxX, maxY;

    /** Tiles claimed by planned paths: key -> rotation. */
    private final Map<Long, Integer> claimedDirections = new HashMap<Long, Integer>();

    /** Tiles that need to become junctions (two perpendicular paths cross). */
    private final Set<Long> junctionTiles = new HashSet<Long>();

    /** Bridge pairs to place: [startX, startY, endX, endY, rotation, bridgeBlock]. */
    private final List<int[]> bridgePairs = new ArrayList<int[]>();

    /** All planned path segments. */
    private final List<PlannedPath> plannedPaths = new ArrayList<PlannedPath>();

    public PathPlanner(LogisticsGraph graph) {
        this.originalGraph = graph;
        this.minX = graph.minX;
        this.minY = graph.minY;
        this.maxX = graph.maxX;
        this.maxY = graph.maxY;
        this.pathfinder = new AStarPathfinder(minX, minY, maxX, maxY, graph.pinnedTiles);
    }

    /**
     * Plan paths for all flows and assemble the proposed layout.
     *
     * @return the proposed layout, with valid=false if planning failed
     */
    public ProposedLayout plan() {
        ProposedLayout layout = new ProposedLayout();
        layout.minX = minX;
        layout.minY = minY;
        layout.maxX = maxX;
        layout.maxY = maxY;

        // Step 0: Check for cycles — refuse to optimize cyclic subgraphs
        if (originalGraph.hasCycles()) {
            // Pin all nodes in cycles; only optimize acyclic chains
            // For V1, just warn and continue — cyclic nodes are already pinned
            // if they involve routers.
        }

        // Step 1: Collect reroutable flows (both endpoints must be present)
        List<LogisticsGraph.Flow> reroutableFlows = new ArrayList<LogisticsGraph.Flow>();
        List<LogisticsGraph.Flow> pinnedFlows = new ArrayList<LogisticsGraph.Flow>();

        for (LogisticsGraph.Flow flow : originalGraph.flows) {
            if (isFlowReroutable(flow)) {
                reroutableFlows.add(flow);
            } else {
                pinnedFlows.add(flow);
            }
        }

        if (reroutableFlows.isEmpty()) {
            layout.valid = false;
            layout.failureReason = "No reroutable flows found. All paths contain pinned blocks (routers, sorters, etc.).";
            return layout;
        }

        // Step 2: Sort flows by Manhattan distance (shortest first)
        reroutableFlows.sort(new java.util.Comparator<LogisticsGraph.Flow>() {
            public int compare(LogisticsGraph.Flow a, LogisticsGraph.Flow b) {
                return Integer.compare(manhattan(a), manhattan(b));
            }
        });

        // Step 3: Pre-claim pinned tiles
        for (Long pinnedKey : originalGraph.pinnedTiles) {
            pathfinder.claimTile(pinnedKey);
        }

        // Step 4: Plan each reroutable flow
        boolean anyFailed = false;
        for (LogisticsGraph.Flow flow : reroutableFlows) {
            Block tier = flow.source.conveyorTier;
            if (tier == null) tier = Blocks.conveyor;

            List<int[]> path = pathfinder.findPath(
                    flow.source.x, flow.source.y,
                    flow.sink.x, flow.sink.y,
                    tier
            );

            if (path == null || path.isEmpty()) {
                // Could not find a path — fall back to keeping original
                anyFailed = true;
                keepOriginalPath(flow, layout);
                continue;
            }

            // Claim tiles and detect crossings
            PlannedPath pp = new PlannedPath(flow, path, tier);
            for (int[] step : path) {
                long key = LogisticsNode.packTile(step[0], step[1]);
                Integer existingDir = claimedDirections.get(key);

                if (existingDir != null && existingDir != step[2]) {
                    // Crossing detected
                    int diff = Math.abs(existingDir - step[2]);
                    if (diff == 1 || diff == 3) {
                        // Perpendicular — junction
                        junctionTiles.add(key);
                    } else {
                        // Parallel conflict — need a bridge
                        // For V1, mark this path as failed and keep original
                        anyFailed = true;
                        keepOriginalPath(flow, layout);
                        pp = null;
                        break;
                    }
                } else {
                    claimedDirections.put(key, step[2]);
                    pathfinder.claimTileWithDir(key, step[2]);
                }
            }

            if (pp != null) {
                plannedPaths.add(pp);
            }
        }

        // Step 5: Assemble layout from planned paths
        assembleLayout(layout);

        // Step 6: Add pinned blocks unchanged
        addPinnedBlocks(layout);

        if (anyFailed && plannedPaths.isEmpty()) {
            layout.valid = false;
            layout.failureReason = "Could not find valid paths for any flow. Selection may be too constrained.";
        } else {
            // Validation happens separately in GraphValidator
            layout.valid = true;
        }

        return layout;
    }

    /**
     * Check if a flow can be rerouted (all intermediate nodes are reroutable).
     */
    private boolean isFlowReroutable(LogisticsGraph.Flow flow) {
        for (LogisticsNode node : flow.path) {
            if (node.pinned) return false;
        }
        return true;
    }

    /** Manhattan distance of a flow. */
    private int manhattan(LogisticsGraph.Flow flow) {
        return Math.abs(flow.source.x - flow.sink.x) +
               Math.abs(flow.source.y - flow.sink.y);
    }

    /**
     * When a path can't be rerouted, keep the original blocks.
     */
    private void keepOriginalPath(LogisticsGraph.Flow flow, ProposedLayout layout) {
        for (LogisticsNode node : flow.path) {
            long key = node.key();
            if (layout.blocks.containsKey(key)) continue;
            PlannedBlock pb = new PlannedBlock(
                    node.x, node.y, node.rotation, node.block, node.filterItem
            );
            layout.addBlock(pb);
            claimedDirections.put(key, node.rotation);
            pathfinder.claimTileWithDir(key, node.rotation);
        }
    }

    /**
     * Convert all planned paths into PlannedBlock entries in the layout.
     */
    private void assembleLayout(ProposedLayout layout) {
        for (PlannedPath pp : plannedPaths) {
            for (int i = 0; i < pp.steps.size(); i++) {
                int[] step = pp.steps.get(i);
                int x = step[0];
                int y = step[1];
                int rotation = step[2];
                long key = LogisticsNode.packTile(x, y);

                // Junction tiles get a junction block
                if (junctionTiles.contains(key)) {
                    if (!layout.blocks.containsKey(key)) {
                        layout.addBlock(new PlannedBlock(x, y, 0, Blocks.junction));
                    }
                    continue;
                }

                // Already placed by another path (shared aligned tile)
                if (layout.blocks.containsKey(key)) continue;

                // Determine correct rotation: conveyor should face the direction of travel
                // For the last step, use the same direction as the previous step
                int convRotation = rotation;
                if (i < pp.steps.size() - 1) {
                    int[] next = pp.steps.get(i + 1);
                    convRotation = LogisticsNode.directionTo(x, y, next[0], next[1]);
                } else if (i > 0) {
                    int[] prev = pp.steps.get(i - 1);
                    convRotation = LogisticsNode.directionTo(prev[0], prev[1], x, y);
                }

                layout.addBlock(new PlannedBlock(x, y, convRotation, pp.conveyorType));
            }
        }
    }

    /**
     * Add pinned blocks (routers, sorters, etc.) to the layout unchanged.
     */
    private void addPinnedBlocks(ProposedLayout layout) {
        for (Long pinnedKey : originalGraph.pinnedTiles) {
            if (layout.blocks.containsKey(pinnedKey)) continue;
            LogisticsNode node = originalGraph.nodes.get(pinnedKey);
            if (node == null) continue;
            layout.addBlock(new PlannedBlock(
                    node.x, node.y, node.rotation, node.block, node.filterItem
            ));
        }
    }

    /**
     * Internal representation of a single planned path.
     */
    private static class PlannedPath {
        final LogisticsGraph.Flow flow;
        final List<int[]> steps; // [x, y, rotation]
        final Block conveyorType;

        PlannedPath(LogisticsGraph.Flow flow, List<int[]> steps, Block conveyorType) {
            this.flow = flow;
            this.steps = steps;
            this.conveyorType = conveyorType;
        }
    }
}
