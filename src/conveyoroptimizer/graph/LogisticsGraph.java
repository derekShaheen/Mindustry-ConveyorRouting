package conveyoroptimizer.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * The complete logistics graph for a selected area.
 * Contains all logistics nodes, boundary ports, and derived source-to-sink flows.
 */
public class LogisticsGraph {

    /** All logistics nodes keyed by packed tile coordinates. */
    public final Map<Long, LogisticsNode> nodes = new HashMap<Long, LogisticsNode>();

    /** Boundary ports where items enter the selection. */
    public final List<BoundaryPort> inputPorts = new ArrayList<BoundaryPort>();

    /** Boundary ports where items leave the selection. */
    public final List<BoundaryPort> outputPorts = new ArrayList<BoundaryPort>();

    /** Derived flows: which input ports can reach which output ports. */
    public final List<Flow> flows = new ArrayList<Flow>();

    /** Nodes that are pinned (must not be moved). */
    public final Set<Long> pinnedTiles = new HashSet<Long>();

    /** Selection bounds. */
    public int minX, minY, maxX, maxY;

    /**
     * Represents a reachable flow from an input port to an output port.
     */
    public static class Flow {
        public final BoundaryPort source;
        public final BoundaryPort sink;
        public final List<LogisticsNode> path; // intermediate nodes

        public Flow(BoundaryPort source, BoundaryPort sink, List<LogisticsNode> path) {
            this.source = source;
            this.sink = sink;
            this.path = path;
        }
    }

    public LogisticsNode getNode(int x, int y) {
        return nodes.get(LogisticsNode.packTile(x, y));
    }

    public boolean hasNode(int x, int y) {
        return nodes.containsKey(LogisticsNode.packTile(x, y));
    }

    public boolean isInBounds(int x, int y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    /**
     * Derive all flows by BFS from each input port.
     * A flow is recorded for each (inputPort, outputPort) pair that is reachable.
     */
    public void deriveFlows() {
        flows.clear();
        for (BoundaryPort input : inputPorts) {
            LogisticsNode startNode = getNode(input.x, input.y);
            if (startNode == null) continue;

            // BFS from the input node following output edges
            Set<Long> visited = new HashSet<Long>();
            Queue<LogisticsNode> queue = new LinkedList<LogisticsNode>();
            Map<Long, LogisticsNode> parent = new HashMap<Long, LogisticsNode>();

            queue.add(startNode);
            visited.add(startNode.key());

            while (!queue.isEmpty()) {
                LogisticsNode current = queue.poll();

                // Check if this node is a boundary output
                if (current.isBoundaryOutput) {
                    BoundaryPort matchedOutput = findOutputPort(current.x, current.y);
                    if (matchedOutput != null) {
                        // Reconstruct path
                        List<LogisticsNode> path = reconstructPath(parent, startNode, current);
                        flows.add(new Flow(input, matchedOutput, path));
                    }
                }

                // Continue BFS through outputs
                for (LogisticsNode next : current.outputs) {
                    if (!visited.contains(next.key())) {
                        visited.add(next.key());
                        parent.put(next.key(), current);
                        queue.add(next);
                    }
                }
            }
        }
    }

    /** Detect cycles in the graph (conveyor loops). Returns true if any exist. */
    public boolean hasCycles() {
        Set<Long> globalVisited = new HashSet<Long>();
        for (LogisticsNode node : nodes.values()) {
            if (globalVisited.contains(node.key())) continue;
            Set<Long> stack = new HashSet<Long>();
            if (dfsCycleCheck(node, globalVisited, stack)) return true;
        }
        return false;
    }

    private boolean dfsCycleCheck(LogisticsNode node, Set<Long> visited, Set<Long> stack) {
        long key = node.key();
        visited.add(key);
        stack.add(key);
        for (LogisticsNode next : node.outputs) {
            if (stack.contains(next.key())) return true;
            if (!visited.contains(next.key())) {
                if (dfsCycleCheck(next, visited, stack)) return true;
            }
        }
        stack.remove(key);
        return false;
    }

    /**
     * Build a reachability map: for each input port, which output ports can it reach?
     * Used for validation comparison.
     */
    public Map<BoundaryPort, Set<BoundaryPort>> getReachabilityMap() {
        Map<BoundaryPort, Set<BoundaryPort>> reachMap =
                new HashMap<BoundaryPort, Set<BoundaryPort>>();
        for (Flow flow : flows) {
            Set<BoundaryPort> sinks = reachMap.get(flow.source);
            if (sinks == null) {
                sinks = new HashSet<BoundaryPort>();
                reachMap.put(flow.source, sinks);
            }
            sinks.add(flow.sink);
        }
        return reachMap;
    }

    private BoundaryPort findOutputPort(int x, int y) {
        for (BoundaryPort port : outputPorts) {
            if (port.x == x && port.y == y) return port;
        }
        return null;
    }

    private List<LogisticsNode> reconstructPath(Map<Long, LogisticsNode> parent,
                                                 LogisticsNode start,
                                                 LogisticsNode end) {
        List<LogisticsNode> path = new ArrayList<LogisticsNode>();
        LogisticsNode current = end;
        while (current != null && !current.equals(start)) {
            path.add(0, current);
            current = parent.get(current.key());
        }
        path.add(0, start);
        return path;
    }

    /** Count how many logistics tiles are in the graph. */
    public int size() {
        return nodes.size();
    }

    /** Count how many tiles can be rerouted (not pinned). */
    public int reroutableCount() {
        int count = 0;
        for (LogisticsNode node : nodes.values()) {
            if (!node.pinned) count++;
        }
        return count;
    }
}
