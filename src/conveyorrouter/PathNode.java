package conveyorrouter;

/**
 * A single node in the pathfinding result.
 * Stores tile coordinates and whether this node is part of a bridge span.
 */
public class PathNode {
    public final int x, y;
    /** If true, this tile is part of a bridge span (not a normal conveyor). */
    public boolean bridge;

    public PathNode(int x, int y) {
        this.x = x;
        this.y = y;
        this.bridge = false;
    }

    public PathNode(int x, int y, boolean bridge) {
        this.x = x;
        this.y = y;
        this.bridge = bridge;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PathNode)) return false;
        PathNode n = (PathNode) o;
        return x == n.x && y == n.y;
    }

    @Override
    public int hashCode() {
        return x * 31 + y;
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + (bridge ? " bridge" : "") + ")";
    }
}
