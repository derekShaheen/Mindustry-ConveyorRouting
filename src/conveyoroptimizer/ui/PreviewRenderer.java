package conveyoroptimizer.ui;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import conveyoroptimizer.graph.LogisticsGraph;
import conveyoroptimizer.graph.LogisticsNode;
import conveyoroptimizer.optimizer.PlannedBlock;
import conveyoroptimizer.optimizer.ProposedLayout;
import mindustry.Vars;
import mindustry.graphics.Layer;

/**
 * Renders a ghost preview of the proposed optimized layout.
 *
 * - Green tint: new or changed blocks
 * - Red tint: blocks being removed
 * - White tint: blocks staying the same
 */
public class PreviewRenderer {

    private ProposedLayout layout;
    private LogisticsGraph originalGraph;
    private boolean active = false;

    /** Activate the preview with a proposed layout. */
    public void show(ProposedLayout layout, LogisticsGraph originalGraph) {
        this.layout = layout;
        this.originalGraph = originalGraph;
        this.active = true;
    }

    /** Deactivate the preview. */
    public void hide() {
        this.active = false;
        this.layout = null;
        this.originalGraph = null;
    }

    public boolean isActive() {
        return active;
    }

    /** Draw the preview overlay. Call from the draw trigger. */
    public void draw() {
        if (!active || layout == null || originalGraph == null) return;

        Draw.z(Layer.overlayUI - 1f);
        float ts = Vars.tilesize;

        // Draw blocks being removed (original blocks not in proposed layout) in red
        for (LogisticsNode node : originalGraph.nodes.values()) {
            long key = node.key();
            PlannedBlock proposed = layout.blocks.get(key);

            if (proposed == null) {
                // Block will be removed
                drawBlockGhost(node.x, node.y, node.block, node.rotation,
                        new Color(1f, 0.2f, 0.2f, 0.5f));
            } else if (proposed.block != node.block || proposed.rotation != node.rotation) {
                // Block will be changed — show old in red
                drawBlockGhost(node.x, node.y, node.block, node.rotation,
                        new Color(1f, 0.2f, 0.2f, 0.3f));
            }
        }

        // Draw proposed blocks
        for (PlannedBlock pb : layout.blocks.values()) {
            long key = LogisticsNode.packTile(pb.x, pb.y);
            LogisticsNode orig = originalGraph.nodes.get(key);

            Color tint;
            if (orig == null) {
                // New block
                tint = new Color(0.2f, 1f, 0.2f, 0.6f);
            } else if (orig.block != pb.block || orig.rotation != pb.rotation) {
                // Changed block
                tint = new Color(0.2f, 1f, 0.5f, 0.6f);
            } else {
                // Unchanged
                tint = new Color(1f, 1f, 1f, 0.3f);
            }

            drawBlockGhost(pb.x, pb.y, pb.block, pb.rotation, tint);
        }

        Draw.reset();
    }

    /**
     * Draw a ghost block at the given position with a color tint.
     * Uses the block's full icon if available, otherwise draws a colored square.
     */
    private void drawBlockGhost(int x, int y, mindustry.world.Block block,
                                 int rotation, Color tint) {
        float ts = Vars.tilesize;
        float cx = x * ts + ts / 2f;
        float cy = y * ts + ts / 2f;

        Draw.color(tint);

        // Try to use the block's icon for a more informative preview
        TextureRegion icon = block.fullIcon;
        if (icon != null && icon.found()) {
            Draw.rect(icon, cx, cy, ts, ts, rotation * 90f);
        } else {
            // Fallback: colored square
            Draw.rect("whiteui", cx, cy, ts * 0.9f, ts * 0.9f);
        }
    }
}
