package conveyoroptimizer.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import mindustry.Vars;
import mindustry.graphics.Layer;

/**
 * Handles the rectangle selection for choosing an area to optimize.
 *
 * States:
 * - IDLE: waiting for hotkey press
 * - SELECTING: user is dragging to select a rectangle
 * - SELECTED: rectangle is confirmed, ready to optimize
 * - PREVIEWING: optimization preview is showing
 */
public class SelectionHandler {

    public enum State {
        IDLE,
        SELECTING,
        SELECTED,
        PREVIEWING
    }

    private State state = State.IDLE;

    /** Selection corners in tile coordinates. */
    private int startX, startY, endX, endY;

    /** Normalized selection bounds. */
    private int selMinX, selMinY, selMaxX, selMaxY;

    /** Whether the drag has started. */
    private boolean dragging = false;

    /** The hotkey to toggle selection mode. */
    private static final KeyCode HOTKEY = KeyCode.f6;

    /** Maximum selection size (tiles per side). */
    private static final int MAX_SIZE = 64;

    /**
     * Called every frame. Handles input and state transitions.
     *
     * @return true if an optimization should be triggered this frame
     */
    public boolean update() {
        // Don't intercept when UI is focused
        if (Core.scene.hasField() || Core.scene.hasDialog()) return false;

        // Don't run on multiplayer clients
        if (Vars.net.client()) return false;

        switch (state) {
            case IDLE:
                if (Core.input.keyTap(HOTKEY)) {
                    state = State.SELECTING;
                    dragging = false;
                    Vars.ui.hudfrag.showToast("[accent]ConvOpt:[white] Click and drag to select area");
                }
                break;

            case SELECTING:
                if (Core.input.keyTap(KeyCode.escape)) {
                    reset();
                    Vars.ui.hudfrag.showToast("[gray]Selection cancelled");
                    break;
                }

                if (Core.input.keyTap(HOTKEY) && dragging) {
                    // Confirm selection and trigger optimize
                    normalizeSelection();
                    if (isSelectionValid()) {
                        state = State.SELECTED;
                        return true; // signal to run optimization
                    } else {
                        Vars.ui.hudfrag.showToast("[scarlet]Selection too large (max " + MAX_SIZE + "x" + MAX_SIZE + ")");
                        reset();
                    }
                    break;
                }

                handleDrag();
                break;

            case SELECTED:
                // Waiting for preview confirmation or cancel
                if (Core.input.keyTap(KeyCode.escape)) {
                    reset();
                    Vars.ui.hudfrag.showToast("[gray]Optimization cancelled");
                }
                break;

            case PREVIEWING:
                if (Core.input.keyTap(KeyCode.enter)) {
                    // Apply — handled by the mod class
                    state = State.IDLE;
                    return false; // mod class checks for ENTER in PREVIEWING state
                }
                if (Core.input.keyTap(KeyCode.escape)) {
                    reset();
                    Vars.ui.hudfrag.showToast("[gray]Preview cancelled");
                }
                break;
        }

        return false;
    }

    /** Handle mouse drag for rectangle selection. */
    private void handleDrag() {
        if (Core.scene.hasMouse()) return; // mouse over UI

        float worldX = Core.input.mouseWorldX();
        float worldY = Core.input.mouseWorldY();
        int tileX = (int) (worldX / Vars.tilesize);
        int tileY = (int) (worldY / Vars.tilesize);

        if (Core.input.keyDown(KeyCode.mouseLeft)) {
            if (!dragging) {
                startX = tileX;
                startY = tileY;
                dragging = true;
            }
            endX = tileX;
            endY = tileY;
        }
    }

    /** Draw the selection rectangle and any preview. */
    public void draw() {
        if (state == State.IDLE) return;

        if (state == State.SELECTING || state == State.SELECTED || state == State.PREVIEWING) {
            if (!dragging && state == State.SELECTING) return;

            Draw.z(Layer.overlayUI);
            float ts = Vars.tilesize;

            int x1 = Math.min(startX, endX);
            int y1 = Math.min(startY, endY);
            int x2 = Math.max(startX, endX);
            int y2 = Math.max(startY, endY);

            if (state == State.SELECTED || state == State.PREVIEWING) {
                x1 = selMinX;
                y1 = selMinY;
                x2 = selMaxX;
                y2 = selMaxY;
            }

            // Fill with translucent blue
            Draw.color(Color.royal, 0.15f);
            Draw.rect("whiteui",
                    (x1 + x2) / 2f * ts + ts / 2f,
                    (y1 + y2) / 2f * ts + ts / 2f,
                    (x2 - x1 + 1) * ts,
                    (y2 - y1 + 1) * ts);

            // Border
            Draw.color(Color.royal, 0.8f);
            Lines.stroke(2f);
            Lines.rect(
                    x1 * ts,
                    y1 * ts,
                    (x2 - x1 + 1) * ts,
                    (y2 - y1 + 1) * ts);

            Draw.reset();
        }
    }

    /** Normalize the selection so min <= max. */
    private void normalizeSelection() {
        selMinX = Math.min(startX, endX);
        selMinY = Math.min(startY, endY);
        selMaxX = Math.max(startX, endX);
        selMaxY = Math.max(startY, endY);
    }

    /** Check if selection is within size limits. */
    private boolean isSelectionValid() {
        return (selMaxX - selMinX + 1) <= MAX_SIZE &&
               (selMaxY - selMinY + 1) <= MAX_SIZE;
    }

    /** Reset to idle state. */
    public void reset() {
        state = State.IDLE;
        dragging = false;
    }

    public State getState() { return state; }
    public void setState(State s) { this.state = s; }

    public int getMinX() { return selMinX; }
    public int getMinY() { return selMinY; }
    public int getMaxX() { return selMaxX; }
    public int getMaxY() { return selMaxY; }
}
