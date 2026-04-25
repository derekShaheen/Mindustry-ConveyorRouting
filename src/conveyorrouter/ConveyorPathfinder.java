package conveyorrouter;

import arc.struct.*;
import mindustry.*;
import mindustry.content.Blocks;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;

/**
 * A* pathfinder over world tiles.
 *
 * Cost model (lower is better):
 *   - Empty/buildable tile       → base cost 1.0
 *   - Existing aligned conveyor  → 0.2  (free to reuse)
 *   - Existing mis-aligned conv  → 5.0  (don't want to re-route)
 *   - Turn penalty               → +0.4 per direction change
 *   - Bridge hop                 → 4.0  per bridge span
 *   - Occupied / obstacle tile   → impassable (unless bridgeable)
 *
 * Movement is strictly cardinal (N/S/E/W).
 *
 * Bridge logic:
 *   When a cardinal neighbour is impassable, the pathfinder looks ahead up to
 *   MAX_BRIDGE_SPAN tiles in that direction. If it finds a valid landing tile,
 *   it adds a bridge jump.
 */
public class ConveyorPathfinder {

    // Cardinal directions: E, N, W, S  (matching Mindustry rotation 0-3)
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DY = {0, 1, 0, -1};

    /** Maximum tiles a bridge can span (excluding endpoints). Vanilla bridge = 4. */
    private static final int MAX_BRIDGE_SPAN = 4;

    /** Maximum nodes to expand before giving up. Prevents freezes on huge maps. */
    private static final int MAX_EXPANSIONS = 20_000;

    // ---- Internal node for the open/closed sets ----
    private static class Node implements Comparable<Node> {
        int x, y;
        float g;       // cost from start
        float f;       // g + heuristic
        int dir;       // incoming direction (0-3), -1 for start
        Node parent;
        boolean bridgeJump; // true if this node was reached via a bridge hop

        Node(int x, int y, float g, float f, int dir, Node parent, boolean bridgeJump) {
            this.x = x; this.y = y; this.g = g; this.f = f;
            this.dir = dir; this.parent = parent;
            this.bridgeJump = bridgeJump;
        }

        long key() { return ((long) x << 32) | (y & 0xFFFFFFFFL); }

        @Override
        public int compareTo(Node o) { return Float.compare(f, o.f); }
    }

    /**
     * Run A* from source to dest.
     *
     * @param source        starting tile (the unconnected conveyor)
     * @param dest          destination tile
     * @param conveyorBlock the conveyor type to match/place
     * @param bridgeBlock   the bridge type to use, or null to disable bridges
     * @return ordered list of PathNodes from source to dest (inclusive), or null if no path.
     */
    public static Seq<PathNode> findPath(Tile source, Tile dest, Block conveyorBlock, Block bridgeBlock) {
        if (source == null || dest == null) return null;

        int sx = source.x, sy = source.y;
        int dx = dest.x,   dy = dest.y;

        // Priority queue (min-heap on f).
        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>();
        ObjectMap<Long, Float> bestG = new ObjectMap<>();

        Node startNode = new Node(sx, sy, 0f, heuristic(sx, sy, dx, dy), -1, null, false);
        open.add(startNode);
        bestG.put(startNode.key(), 0f);

        int expansions = 0;

        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            Node cur = open.poll();
            expansions++;

            // Goal check.
            if (cur.x == dx && cur.y == dy) {
                return reconstructPath(cur);
            }

            // Skip if we already found a better route to this node.
            Float best = bestG.get(cur.key());
            if (best != null && cur.g > best) continue;

            // --- Expand cardinal neighbours ---
            for (int d = 0; d < 4; d++) {
                int nx = cur.x + DX[d];
                int ny = cur.y + DY[d];
                Tile nt = Vars.world.tile(nx, ny);
                if (nt == null) continue;

                float moveCost = tileCost(nt, d, conveyorBlock, dx, dy);

                if (moveCost < 0) {
                    // Tile is impassable — try bridging if we have a bridge block.
                    if (bridgeBlock != null) {
                        tryBridge(cur, d, conveyorBlock, bridgeBlock, dx, dy, open, bestG);
                    }
                    continue;
                }

                // Turn penalty: changing direction costs extra.
                float turnPenalty = (cur.dir >= 0 && cur.dir != d) ? 0.4f : 0f;
                float newG = cur.g + moveCost + turnPenalty;
                long nk = ((long) nx << 32) | (ny & 0xFFFFFFFFL);

                Float prevG = bestG.get(nk);
                if (prevG == null || newG < prevG) {
                    bestG.put(nk, newG);
                    Node nn = new Node(nx, ny, newG, newG + heuristic(nx, ny, dx, dy), d, cur, false);
                    open.add(nn);
                }
            }
        }

        // No path found.
        return null;
    }

    /**
     * Attempt to bridge over an obstacle in direction dir from cur.
     * Scans up to MAX_BRIDGE_SPAN tiles ahead; the landing tile must be passable.
     */
    private static void tryBridge(Node cur, int dir, Block conveyorBlock, Block bridgeBlock,
                                  int dx, int dy,
                                  java.util.PriorityQueue<Node> open, ObjectMap<Long, Float> bestG) {
        for (int span = 1; span <= MAX_BRIDGE_SPAN; span++) {
            int lx = cur.x + DX[dir] * (span + 1);
            int ly = cur.y + DY[dir] * (span + 1);
            Tile landing = Vars.world.tile(lx, ly);
            if (landing == null) break;

            float landCost = tileCost(landing, dir, conveyorBlock, dx, dy);
            if (landCost < 0) continue; // landing blocked too — keep scanning

            // Valid landing found.
            float bridgeCost = 4.0f + span * 0.5f;
            float newG = cur.g + bridgeCost + landCost;
            long nk = ((long) lx << 32) | (ly & 0xFFFFFFFFL);

            Float prevG = bestG.get(nk);
            if (prevG == null || newG < prevG) {
                bestG.put(nk, newG);
                Node bn = new Node(lx, ly, newG, newG + heuristic(lx, ly, dx, dy), dir, cur, true);
                open.add(bn);
            }
            break; // only use the first valid landing
        }
    }

    /**
     * Cost to move onto a tile. Returns -1 if impassable.
     */
    private static float tileCost(Tile t, int incomingDir, Block conveyorBlock, int dx, int dy) {
        if (t == null) return -1;

        // Destination tile is always valid.
        if (t.x == dx && t.y == dy) {
            Block b = t.block();
            if (b instanceof Conveyor || b instanceof Junction || b instanceof Router) return 0.1f;
            if (b == null || b == Blocks.air) return 1.0f;
            return 1.0f;
        }

        Block b = t.block();

        // Floor checks: void, deep water, etc.
        if (t.floor().isLiquid && !t.floor().placeableOn) return -1;

        // Empty tile — ideal.
        if (b == null || b == Blocks.air) return 1.0f;

        // Existing conveyor of the same type.
        if (b == conveyorBlock) {
            if (t.build != null) {
                int rot = t.build.rotation;
                if (rot == incomingDir) return 0.2f;
                return 5.0f; // wrong direction — expensive
            }
            return 0.2f;
        }

        // Junctions are passable.
        if (b instanceof Junction) return 0.5f;

        // Any other building — impassable.
        return -1;
    }

    /** Manhattan distance heuristic. */
    private static float heuristic(int x, int y, int dx, int dy) {
        return Math.abs(x - dx) + Math.abs(y - dy);
    }

    /**
     * Walk the parent chain from goal to start, producing an ordered path.
     * Fills in bridge span nodes for any bridge jumps.
     */
    private static Seq<PathNode> reconstructPath(Node goal) {
        Seq<PathNode> reversed = new Seq<>();

        Node cur = goal;
        while (cur != null) {
            if (cur.bridgeJump && cur.parent != null) {
                int bx = cur.parent.x, by = cur.parent.y;
                int ddx = Integer.signum(cur.x - bx);
                int ddy = Integer.signum(cur.y - by);
                // Landing node is a normal conveyor.
                reversed.add(new PathNode(cur.x, cur.y, false));
                // Intermediate tiles are bridge spans.
                for (int i = Math.abs(cur.x - bx) + Math.abs(cur.y - by) - 1; i >= 1; i--) {
                    int mx = bx + ddx * i;
                    int my = by + ddy * i;
                    reversed.add(new PathNode(mx, my, true));
                }
            } else {
                reversed.add(new PathNode(cur.x, cur.y, false));
            }
            cur = cur.parent;
        }

        // Reverse to get source → dest order.
        Seq<PathNode> path = new Seq<>();
        for (int i = reversed.size - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return path;
    }
}
