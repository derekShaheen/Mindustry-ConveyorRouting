package conveyoroptimizer.graph;

import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.*;

/**
 * Scans a rectangular area of the world and builds a LogisticsGraph.
 *
 * Steps:
 * 1. Iterate tiles, classify blocks, create LogisticsNode for each logistics block.
 * 2. Wire edges (inputs/outputs) based on block type and rotation.
 * 3. Detect boundary ports (connections crossing the selection edge).
 * 4. Derive flows from input ports to output ports.
 */
public class GraphBuilder {

    /**
     * Build the logistics graph for the selected rectangle.
     *
     * @param x1 left edge (inclusive)
     * @param y1 bottom edge (inclusive)
     * @param x2 right edge (inclusive)
     * @param y2 top edge (inclusive)
     * @return the populated LogisticsGraph
     */
    public static LogisticsGraph build(int x1, int y1, int x2, int y2) {
        LogisticsGraph graph = new LogisticsGraph();
        graph.minX = Math.min(x1, x2);
        graph.minY = Math.min(y1, y2);
        graph.maxX = Math.max(x1, x2);
        graph.maxY = Math.max(y1, y2);

        // Phase 1: Create nodes
        for (int x = graph.minX; x <= graph.maxX; x++) {
            for (int y = graph.minY; y <= graph.maxY; y++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile == null) continue;

                Block block = tile.block();
                BlockRole role = BlockRole.classify(block);
                if (!role.isLogistics()) continue;

                // Skip multi-tile blocks (size > 1)
                if (block.size > 1) continue;

                Building build = tile.build;
                int rotation = (build != null) ? build.rotation : 0;
                Item filterItem = getFilterItem(build, role);
                Team team = (build != null) ? build.team : Team.derelict;

                LogisticsNode node = new LogisticsNode(
                        x, y, block, rotation, role, filterItem, team
                );
                graph.nodes.put(node.key(), node);

                if (node.pinned) {
                    graph.pinnedTiles.add(node.key());
                }
            }
        }

        // Phase 2: Wire edges
        for (LogisticsNode node : graph.nodes.values()) {
            wireEdges(graph, node);
        }

        // Phase 3: Detect boundary ports
        detectBoundaries(graph);

        // Phase 4: Derive flows
        graph.deriveFlows();

        return graph;
    }

    /**
     * Wire the input and output edges for a single node based on its block type.
     */
    private static void wireEdges(LogisticsGraph graph, LogisticsNode node) {
        switch (node.role) {
            case CONVEYOR:
                wireConveyor(graph, node);
                break;
            case JUNCTION:
                wireJunction(graph, node);
                break;
            case ROUTER:
                wireRouter(graph, node);
                break;
            case SORTER:
                wireSorter(graph, node);
                break;
            case OVERFLOW:
                wireOverflow(graph, node);
                break;
            case BRIDGE:
                wireBridge(graph, node);
                break;
            case UNLOADER:
                wireUnloader(graph, node);
                break;
            default:
                break;
        }
    }

    /**
     * Conveyor: outputs to the tile it faces.
     * Accepts input from the 3 non-facing sides if the neighbor points toward this tile.
     * ArmoredConveyor: only accepts side-input from other conveyors.
     */
    private static void wireConveyor(LogisticsGraph graph, LogisticsNode node) {
        // Output: tile in facing direction
        int outX = node.x + LogisticsNode.DX[node.rotation];
        int outY = node.y + LogisticsNode.DY[node.rotation];
        LogisticsNode outNode = graph.getNode(outX, outY);
        if (outNode != null) {
            node.outputs.add(outNode);
            outNode.inputs.add(node);
        }

        // Inputs: 3 non-facing sides
        boolean isArmored = (node.block instanceof ArmoredConveyor);
        for (int d = 0; d < 4; d++) {
            if (d == node.rotation) continue; // skip output direction
            int inX = node.x + LogisticsNode.DX[d];
            int inY = node.y + LogisticsNode.DY[d];
            LogisticsNode inNode = graph.getNode(inX, inY);
            if (inNode == null) continue;

            // The neighbor must be pointing toward us
            int expectedDir = LogisticsNode.directionTo(inX, inY, node.x, node.y);

            if (inNode.role == BlockRole.CONVEYOR) {
                if (inNode.rotation == expectedDir) {
                    node.inputs.add(inNode);
                    inNode.outputs.add(node);
                }
            } else if (!isArmored) {
                // Non-armored accepts input from routers, junctions, etc.
                if (inNode.role == BlockRole.ROUTER ||
                    inNode.role == BlockRole.JUNCTION ||
                    inNode.role == BlockRole.SORTER ||
                    inNode.role == BlockRole.OVERFLOW ||
                    inNode.role == BlockRole.BRIDGE) {
                    // These blocks output in various ways — the connection is
                    // already handled when wiring the other block type.
                }
            }
        }
    }

    /**
     * Junction: items pass straight through. An item entering from direction D
     * exits from direction opposite(D).
     */
    private static void wireJunction(LogisticsGraph graph, LogisticsNode node) {
        for (int d = 0; d < 4; d++) {
            int inX = node.x + LogisticsNode.DX[d];
            int inY = node.y + LogisticsNode.DY[d];
            LogisticsNode inNode = graph.getNode(inX, inY);
            if (inNode == null) continue;

            // If the neighbor points toward us, this junction passes to the opposite side
            int expectedDir = LogisticsNode.directionTo(inX, inY, node.x, node.y);
            boolean feeds = false;
            if (inNode.role == BlockRole.CONVEYOR && inNode.rotation == expectedDir) {
                feeds = true;
            } else if (inNode.role == BlockRole.ROUTER || inNode.role == BlockRole.JUNCTION) {
                feeds = true; // routers/junctions can feed in any direction
            }

            if (feeds) {
                int oppD = LogisticsNode.oppositeDir(d);
                int outX = node.x + LogisticsNode.DX[oppD];
                int outY = node.y + LogisticsNode.DY[oppD];
                LogisticsNode outNode = graph.getNode(outX, outY);

                node.inputs.add(inNode);
                inNode.outputs.add(node);
                if (outNode != null) {
                    node.outputs.add(outNode);
                    outNode.inputs.add(node);
                }
            }
        }
    }

    /**
     * Router/Distributor: accepts from any adjacent logistics block,
     * distributes to all adjacent logistics blocks.
     */
    private static void wireRouter(LogisticsGraph graph, LogisticsNode node) {
        for (int d = 0; d < 4; d++) {
            int nx = node.x + LogisticsNode.DX[d];
            int ny = node.y + LogisticsNode.DY[d];
            LogisticsNode adj = graph.getNode(nx, ny);
            if (adj == null) continue;

            // Router outputs to all neighbors
            node.outputs.add(adj);
            adj.inputs.add(node);

            // Router accepts from all neighbors
            node.inputs.add(adj);
            adj.outputs.add(node);
        }
    }

    /**
     * Sorter: routes the filtered item in the facing direction,
     * everything else to the sides. For graph purposes, we connect
     * both the forward and side outputs.
     */
    private static void wireSorter(LogisticsGraph graph, LogisticsNode node) {
        // Accept input from rear
        int rearD = LogisticsNode.oppositeDir(node.rotation);
        connectInput(graph, node, rearD);

        // Forward output (filtered item direction)
        connectOutput(graph, node, node.rotation);

        // Side outputs (non-filtered items)
        int leftD = (node.rotation + 1) % 4;
        int rightD = (node.rotation + 3) % 4;
        connectOutput(graph, node, leftD);
        connectOutput(graph, node, rightD);
    }

    /**
     * OverflowGate/UnderflowGate: primary output is forward,
     * overflow/underflow goes to sides.
     */
    private static void wireOverflow(LogisticsGraph graph, LogisticsNode node) {
        // Accept input from rear
        int rearD = LogisticsNode.oppositeDir(node.rotation);
        connectInput(graph, node, rearD);

        // Forward output
        connectOutput(graph, node, node.rotation);

        // Side outputs for overflow
        int leftD = (node.rotation + 1) % 4;
        int rightD = (node.rotation + 3) % 4;
        connectOutput(graph, node, leftD);
        connectOutput(graph, node, rightD);
    }

    /**
     * Bridge: connects start to end endpoint. For V1, we treat the bridge
     * as a single edge from start tile to end tile. The bridge faces toward
     * its linked endpoint.
     */
    private static void wireBridge(LogisticsGraph graph, LogisticsNode node) {
        // Accept input from rear
        int rearD = LogisticsNode.oppositeDir(node.rotation);
        connectInput(graph, node, rearD);

        // Output in facing direction (either to bridge endpoint or next block)
        connectOutput(graph, node, node.rotation);

        // Also check for bridge link — the bridge teleports items to its endpoint.
        // The endpoint is found by scanning in the facing direction up to max range.
        // For the graph, this is handled by connectOutput already reaching the next block,
        // but for bridges spanning multiple tiles, we need to find the far endpoint.
        scanBridgeLink(graph, node);
    }

    /**
     * Unloader: outputs items from an adjacent container to an adjacent logistics block.
     * Pinned — not rerouted.
     */
    private static void wireUnloader(LogisticsGraph graph, LogisticsNode node) {
        for (int d = 0; d < 4; d++) {
            int nx = node.x + LogisticsNode.DX[d];
            int ny = node.y + LogisticsNode.DY[d];
            LogisticsNode adj = graph.getNode(nx, ny);
            if (adj == null) continue;
            node.outputs.add(adj);
            adj.inputs.add(node);
        }
    }

    /** Scan for a bridge's linked endpoint and add a graph edge. */
    private static void scanBridgeLink(LogisticsGraph graph, LogisticsNode node) {
        int maxRange = 10; // itemBridge = 4, phaseConveyor = 12; use a safe cap
        if (node.block == Blocks.phaseConveyor) {
            maxRange = 14;
        }

        for (int dist = 2; dist <= maxRange; dist++) {
            int tx = node.x + LogisticsNode.DX[node.rotation] * dist;
            int ty = node.y + LogisticsNode.DY[node.rotation] * dist;
            LogisticsNode target = graph.getNode(tx, ty);
            if (target != null && target.role == BlockRole.BRIDGE &&
                target.block == node.block) {
                // Found the other bridge endpoint
                node.outputs.add(target);
                target.inputs.add(node);
                break;
            }
            // If we hit a non-air block that isn't the same bridge type, stop scanning
            Tile tile = Vars.world.tile(tx, ty);
            if (tile != null && tile.block() != null && tile.block() != Blocks.air) {
                break;
            }
        }
    }

    /** Helper: connect an input from a specific direction. */
    private static void connectInput(LogisticsGraph graph, LogisticsNode node, int dir) {
        int nx = node.x + LogisticsNode.DX[dir];
        int ny = node.y + LogisticsNode.DY[dir];
        LogisticsNode adj = graph.getNode(nx, ny);
        if (adj != null) {
            node.inputs.add(adj);
            adj.outputs.add(node);
        }
    }

    /** Helper: connect an output in a specific direction. */
    private static void connectOutput(LogisticsGraph graph, LogisticsNode node, int dir) {
        int nx = node.x + LogisticsNode.DX[dir];
        int ny = node.y + LogisticsNode.DY[dir];
        LogisticsNode adj = graph.getNode(nx, ny);
        if (adj != null) {
            node.outputs.add(adj);
            adj.inputs.add(node);
        }
    }

    /** Extract the filter item from a Sorter or Unloader building. */
    private static Item getFilterItem(Building build, BlockRole role) {
        if (build == null) return null;
        if (role == BlockRole.SORTER) {
            if (build instanceof Sorter.SorterBuild) {
                return ((Sorter.SorterBuild) build).sortItem;
            }
        }
        if (role == BlockRole.UNLOADER) {
            // Unloader stores its configured item
            // Access through config() which returns the item
            Object config = build.config();
            if (config instanceof Item) {
                return (Item) config;
            }
        }
        return null;
    }

    /**
     * Detect boundary ports: nodes at the selection edge that connect
     * to blocks outside the selection.
     */
    private static void detectBoundaries(LogisticsGraph graph) {
        for (LogisticsNode node : graph.nodes.values()) {
            for (int d = 0; d < 4; d++) {
                int nx = node.x + LogisticsNode.DX[d];
                int ny = node.y + LogisticsNode.DY[d];

                // Only interested in connections leaving the selection
                if (graph.isInBounds(nx, ny)) continue;

                Tile extTile = Vars.world.tile(nx, ny);
                if (extTile == null) continue;
                Block extBlock = extTile.block();
                if (extBlock == null || extBlock == Blocks.air) continue;
                BlockRole extRole = BlockRole.classify(extBlock);
                if (!extRole.isLogistics()) continue;

                Building extBuild = extTile.build;
                int extRotation = (extBuild != null) ? extBuild.rotation : 0;

                // Determine flow direction:
                // If external block faces INTO the selection → items flow IN (input port)
                // If internal block faces OUT of the selection → items flow OUT (output port)

                int dirIntoSelection = LogisticsNode.directionTo(nx, ny, node.x, node.y);
                int dirOutOfSelection = LogisticsNode.directionTo(node.x, node.y, nx, ny);

                boolean isInput = false;
                boolean isOutput = false;

                // External conveyor pointing into selection
                if (extRole == BlockRole.CONVEYOR && extRotation == dirIntoSelection) {
                    isInput = true;
                }
                // External router/junction adjacent to selection edge → could be input
                if (extRole == BlockRole.ROUTER || extRole == BlockRole.JUNCTION) {
                    isInput = true;
                }

                // Internal node pointing out of selection
                if (node.role == BlockRole.CONVEYOR && node.rotation == dirOutOfSelection) {
                    isOutput = true;
                }
                // Internal router/junction → could output to external
                if (node.role == BlockRole.ROUTER || node.role == BlockRole.JUNCTION) {
                    isOutput = true;
                }

                Block conveyorTier = (node.role == BlockRole.CONVEYOR) ? node.block : Blocks.conveyor;

                if (isInput) {
                    node.isBoundaryInput = true;
                    graph.inputPorts.add(new BoundaryPort(
                            node.x, node.y, dirIntoSelection, true,
                            extBlock, node.block, node.filterItem, conveyorTier
                    ));
                }

                if (isOutput) {
                    node.isBoundaryOutput = true;
                    graph.outputPorts.add(new BoundaryPort(
                            node.x, node.y, dirOutOfSelection, false,
                            extBlock, node.block, node.filterItem, conveyorTier
                    ));
                }
            }
        }
    }
}
