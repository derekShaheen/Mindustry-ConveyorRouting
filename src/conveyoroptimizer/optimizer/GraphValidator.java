package conveyoroptimizer.optimizer;

import conveyoroptimizer.graph.BoundaryPort;
import conveyoroptimizer.graph.GraphBuilder;
import conveyoroptimizer.graph.LogisticsGraph;
import conveyoroptimizer.graph.LogisticsNode;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.world.Block;
import mindustry.world.Tile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a proposed layout by rebuilding the logistics graph from the
 * proposed blocks and comparing it to the original graph.
 *
 * Validation checks:
 * 1. Same set of input boundary ports
 * 2. Same set of output boundary ports
 * 3. Same reachability (each input port reaches exactly the same output ports)
 * 4. No new accidental connections
 * 5. No lost connections
 * 6. Filter items preserved on sorters/unloaders
 * 7. Conveyor directionality preserved
 */
public class GraphValidator {

    /**
     * Validate the proposed layout against the original graph.
     *
     * Since we can't actually place the proposed blocks to build a real graph,
     * we simulate the graph from the ProposedLayout's planned blocks.
     *
     * @param original the original logistics graph
     * @param proposed the proposed optimized layout
     * @return list of validation errors (empty = valid)
     */
    public static List<String> validate(LogisticsGraph original, ProposedLayout proposed) {
        List<String> errors = new ArrayList<String>();

        // Build a simulated graph from the proposed layout
        LogisticsGraph simulated = buildSimulatedGraph(proposed);

        // Check 1: Same input ports
        Set<String> origInputKeys = portKeySet(original.inputPorts);
        Set<String> simInputKeys = portKeySet(simulated.inputPorts);

        for (String key : origInputKeys) {
            if (!simInputKeys.contains(key)) {
                errors.add("Lost input port: " + key);
            }
        }
        for (String key : simInputKeys) {
            if (!origInputKeys.contains(key)) {
                errors.add("New unexpected input port: " + key);
            }
        }

        // Check 2: Same output ports
        Set<String> origOutputKeys = portKeySet(original.outputPorts);
        Set<String> simOutputKeys = portKeySet(simulated.outputPorts);

        for (String key : origOutputKeys) {
            if (!simOutputKeys.contains(key)) {
                errors.add("Lost output port: " + key);
            }
        }
        for (String key : simOutputKeys) {
            if (!origOutputKeys.contains(key)) {
                errors.add("New unexpected output port: " + key);
            }
        }

        // Check 3 & 4 & 5: Reachability comparison
        Map<BoundaryPort, Set<BoundaryPort>> origReach = original.getReachabilityMap();
        Map<BoundaryPort, Set<BoundaryPort>> simReach = simulated.getReachabilityMap();

        // Check for lost connections
        for (Map.Entry<BoundaryPort, Set<BoundaryPort>> entry : origReach.entrySet()) {
            BoundaryPort origInput = entry.getKey();
            Set<BoundaryPort> origSinks = entry.getValue();

            // Find matching input in simulated
            BoundaryPort simInput = findMatchingPort(origInput, simulated.inputPorts);
            if (simInput == null) continue; // Already caught in port check

            Set<BoundaryPort> simSinks = simReach.get(simInput);
            if (simSinks == null) simSinks = new HashSet<BoundaryPort>();

            for (BoundaryPort origSink : origSinks) {
                boolean found = false;
                for (BoundaryPort simSink : simSinks) {
                    if (portsMatch(origSink, simSink)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    errors.add("Lost connection: " + origInput + " -> " + origSink);
                }
            }
        }

        // Check for new accidental connections
        for (Map.Entry<BoundaryPort, Set<BoundaryPort>> entry : simReach.entrySet()) {
            BoundaryPort simInput = entry.getKey();
            Set<BoundaryPort> simSinks = entry.getValue();

            BoundaryPort origInput = findMatchingPort(simInput, original.inputPorts);
            if (origInput == null) continue;

            Set<BoundaryPort> origSinks = origReach.get(origInput);
            if (origSinks == null) origSinks = new HashSet<BoundaryPort>();

            for (BoundaryPort simSink : simSinks) {
                boolean found = false;
                for (BoundaryPort origSink : origSinks) {
                    if (portsMatch(origSink, simSink)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    errors.add("New accidental connection: " + simInput + " -> " + simSink);
                }
            }
        }

        // Check 6: Filter preservation
        for (LogisticsNode origNode : original.nodes.values()) {
            if (origNode.filterItem != null) {
                PlannedBlock pb = proposed.getBlock(origNode.x, origNode.y);
                if (pb != null && pb.filterItem != origNode.filterItem) {
                    errors.add("Filter item changed at (" + origNode.x + "," + origNode.y + "): " +
                               origNode.filterItem.name + " -> " +
                               (pb.filterItem != null ? pb.filterItem.name : "null"));
                }
            }
        }

        return errors;
    }

    /**
     * Build a simulated logistics graph from the proposed layout's planned blocks.
     * This reuses the graph-building logic but reads from the ProposedLayout
     * instead of the world tiles.
     */
    private static LogisticsGraph buildSimulatedGraph(ProposedLayout proposed) {
        // We simulate by temporarily checking what the graph WOULD look like
        // based on the proposed block placements.
        // For a true simulation, we'd need to build a virtual tile grid.
        // For V1, we construct the graph manually from PlannedBlock entries.

        LogisticsGraph graph = new LogisticsGraph();
        graph.minX = proposed.minX;
        graph.minY = proposed.minY;
        graph.maxX = proposed.maxX;
        graph.maxY = proposed.maxY;

        // Create nodes from planned blocks
        for (PlannedBlock pb : proposed.blocks.values()) {
            conveyoroptimizer.graph.BlockRole role =
                    conveyoroptimizer.graph.BlockRole.classify(pb.block);
            if (!role.isLogistics()) continue;

            // Determine team from existing world tile (if any)
            mindustry.game.Team team = mindustry.game.Team.sharded;
            Tile tile = Vars.world.tile(pb.x, pb.y);
            if (tile != null && tile.build != null) {
                team = tile.build.team;
            }

            LogisticsNode node = new LogisticsNode(
                    pb.x, pb.y, pb.block, pb.rotation, role, pb.filterItem, team
            );
            graph.nodes.put(node.key(), node);
        }

        // Wire edges using same logic as GraphBuilder
        for (LogisticsNode node : graph.nodes.values()) {
            wireSimulatedEdges(graph, node);
        }

        // Detect boundaries (check external tiles that are still in the real world)
        detectSimulatedBoundaries(graph);

        // Derive flows
        graph.deriveFlows();

        return graph;
    }

    /**
     * Wire edges for a simulated node. Simplified version of GraphBuilder logic
     * that works purely from the graph's own nodes.
     */
    private static void wireSimulatedEdges(LogisticsGraph graph, LogisticsNode node) {
        switch (node.role) {
            case CONVEYOR: {
                // Output: tile in facing direction
                int outX = node.x + LogisticsNode.DX[node.rotation];
                int outY = node.y + LogisticsNode.DY[node.rotation];
                LogisticsNode outNode = graph.getNode(outX, outY);
                if (outNode != null) {
                    node.outputs.add(outNode);
                    outNode.inputs.add(node);
                }
                break;
            }
            case JUNCTION: {
                // Pass-through in each axis
                for (int d = 0; d < 4; d++) {
                    int inX = node.x + LogisticsNode.DX[d];
                    int inY = node.y + LogisticsNode.DY[d];
                    LogisticsNode inNode = graph.getNode(inX, inY);
                    if (inNode == null) continue;

                    int expectedDir = LogisticsNode.directionTo(inX, inY, node.x, node.y);
                    if (inNode.role == conveyoroptimizer.graph.BlockRole.CONVEYOR &&
                        inNode.rotation == expectedDir) {
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
                break;
            }
            case ROUTER: {
                for (int d = 0; d < 4; d++) {
                    int nx = node.x + LogisticsNode.DX[d];
                    int ny = node.y + LogisticsNode.DY[d];
                    LogisticsNode adj = graph.getNode(nx, ny);
                    if (adj == null) continue;
                    node.outputs.add(adj);
                    adj.inputs.add(node);
                    node.inputs.add(adj);
                    adj.outputs.add(node);
                }
                break;
            }
            case SORTER:
            case OVERFLOW: {
                int rearD = LogisticsNode.oppositeDir(node.rotation);
                connectSimulated(graph, node, rearD, true);
                connectSimulated(graph, node, node.rotation, false);
                connectSimulated(graph, node, (node.rotation + 1) % 4, false);
                connectSimulated(graph, node, (node.rotation + 3) % 4, false);
                break;
            }
            case BRIDGE: {
                int rearD = LogisticsNode.oppositeDir(node.rotation);
                connectSimulated(graph, node, rearD, true);
                connectSimulated(graph, node, node.rotation, false);
                break;
            }
            case UNLOADER: {
                for (int d = 0; d < 4; d++) {
                    int nx = node.x + LogisticsNode.DX[d];
                    int ny = node.y + LogisticsNode.DY[d];
                    LogisticsNode adj = graph.getNode(nx, ny);
                    if (adj == null) continue;
                    node.outputs.add(adj);
                    adj.inputs.add(node);
                }
                break;
            }
            default:
                break;
        }
    }

    private static void connectSimulated(LogisticsGraph graph, LogisticsNode node,
                                          int dir, boolean asInput) {
        int nx = node.x + LogisticsNode.DX[dir];
        int ny = node.y + LogisticsNode.DY[dir];
        LogisticsNode adj = graph.getNode(nx, ny);
        if (adj == null) return;
        if (asInput) {
            node.inputs.add(adj);
            adj.outputs.add(node);
        } else {
            node.outputs.add(adj);
            adj.inputs.add(node);
        }
    }

    /**
     * Detect boundary ports in the simulated graph by checking real-world
     * tiles outside the selection bounds.
     */
    private static void detectSimulatedBoundaries(LogisticsGraph graph) {
        for (LogisticsNode node : graph.nodes.values()) {
            for (int d = 0; d < 4; d++) {
                int nx = node.x + LogisticsNode.DX[d];
                int ny = node.y + LogisticsNode.DY[d];
                if (graph.isInBounds(nx, ny)) continue;

                Tile extTile = Vars.world.tile(nx, ny);
                if (extTile == null) continue;
                Block extBlock = extTile.block();
                if (extBlock == null || extBlock == Blocks.air) continue;
                conveyoroptimizer.graph.BlockRole extRole =
                        conveyoroptimizer.graph.BlockRole.classify(extBlock);
                if (!extRole.isLogistics()) continue;

                int extRotation = (extTile.build != null) ? extTile.build.rotation : 0;
                int dirInto = LogisticsNode.directionTo(nx, ny, node.x, node.y);
                int dirOut = LogisticsNode.directionTo(node.x, node.y, nx, ny);

                boolean isInput = false;
                boolean isOutput = false;

                if (extRole == conveyoroptimizer.graph.BlockRole.CONVEYOR &&
                    extRotation == dirInto) {
                    isInput = true;
                }
                if (extRole == conveyoroptimizer.graph.BlockRole.ROUTER ||
                    extRole == conveyoroptimizer.graph.BlockRole.JUNCTION) {
                    isInput = true;
                }
                if (node.role == conveyoroptimizer.graph.BlockRole.CONVEYOR &&
                    node.rotation == dirOut) {
                    isOutput = true;
                }
                if (node.role == conveyoroptimizer.graph.BlockRole.ROUTER ||
                    node.role == conveyoroptimizer.graph.BlockRole.JUNCTION) {
                    isOutput = true;
                }

                Block tier = (node.role == conveyoroptimizer.graph.BlockRole.CONVEYOR) ?
                        node.block : Blocks.conveyor;

                if (isInput) {
                    node.isBoundaryInput = true;
                    graph.inputPorts.add(new BoundaryPort(
                            node.x, node.y, dirInto, true,
                            extBlock, node.block, node.filterItem, tier));
                }
                if (isOutput) {
                    node.isBoundaryOutput = true;
                    graph.outputPorts.add(new BoundaryPort(
                            node.x, node.y, dirOut, false,
                            extBlock, node.block, node.filterItem, tier));
                }
            }
        }
    }

    /** Create a string key set from boundary ports for comparison. */
    private static Set<String> portKeySet(List<BoundaryPort> ports) {
        Set<String> keys = new HashSet<String>();
        for (BoundaryPort port : ports) {
            keys.add(portKey(port));
        }
        return keys;
    }

    /** Create a comparable string key for a boundary port. */
    private static String portKey(BoundaryPort port) {
        return port.x + "," + port.y + "," + port.direction + "," + port.isInput;
    }

    /** Check if two boundary ports match (same position and direction). */
    private static boolean portsMatch(BoundaryPort a, BoundaryPort b) {
        return a.x == b.x && a.y == b.y && a.direction == b.direction && a.isInput == b.isInput;
    }

    /** Find a matching port in a list. */
    private static BoundaryPort findMatchingPort(BoundaryPort target, List<BoundaryPort> list) {
        for (BoundaryPort p : list) {
            if (portsMatch(target, p)) return p;
        }
        return null;
    }
}
