package conveyorrouter;

import arc.*;
import arc.input.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.mod.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;

/**
 * ConveyorRouterMod — entry point.
 *
 * Lifecycle:
 *  1. Player presses C → enters "conveyor-connect" mode.
 *  2. Player clicks a tile → that becomes the destination.
 *  3. Mod searches outward from destination for nearest unconnected conveyor (source).
 *  4. A* pathfinds from source to destination using cardinal movement.
 *  5. Build plans are generated and queued on the player.
 */
public class ConveyorRouterMod extends Mod {

    /** True while waiting for the player to click a destination tile. */
    private boolean selectingDestination = false;

    public ConveyorRouterMod() {
        Log.info("[ConveyorRouter] Mod loaded.");
    }

    @Override
    public void init() {
        // Register the hotkey listener on the client update loop.
        Events.run(EventType.Trigger.update, this::update);
        Log.info("[ConveyorRouter] Initialized — press C to enter conveyor-connect mode.");
    }

    private void update() {
        // Only run on the client with a valid player.
        if (Vars.net.server() || Vars.player == null) return;

        // --- Hotkey: C to toggle conveyor-connect mode ---
        if (Core.input.keyTap(KeyCode.c) && !Core.scene.hasField() && !Core.scene.hasDialog()) {
            if (!selectingDestination) {
                selectingDestination = true;
                Vars.ui.hudfrag.showToast("[accent]Conveyor Connect:[white] Click a destination tile.");
            } else {
                selectingDestination = false;
                Vars.ui.hudfrag.showToast("[gray]Conveyor Connect cancelled.");
            }
            return;
        }

        // --- Destination selection on left-click ---
        if (selectingDestination && Core.input.keyTap(KeyCode.mouseLeft) && !Core.scene.hasMouse()) {
            selectingDestination = false;

            // Convert screen coords → world tile coords.
            float worldX = Core.input.mouseWorldX();
            float worldY = Core.input.mouseWorldY();
            int tx = (int)(worldX / Vars.tilesize);
            int ty = (int)(worldY / Vars.tilesize);

            Tile dest = Vars.world.tile(tx, ty);
            if (dest == null) {
                Vars.ui.hudfrag.showToast("[scarlet]Invalid destination tile.");
                return;
            }

            Vars.ui.hudfrag.showToast("[accent]Destination set:[white] (" + tx + ", " + ty + ")");

            // Run the routing pipeline.
            routeTo(dest);
        }
    }

    /**
     * Full pipeline: find source conveyor, pathfind, place build plans.
     */
    private void routeTo(Tile destination) {
        // 1. Find nearest unconnected conveyor as source.
        SourceResult source = SourceFinder.findNearest(destination, 80);

        if (source == null) {
            Vars.ui.hudfrag.showToast("[scarlet]No unconnected conveyor found nearby.");
            return;
        }

        Block conveyorBlock = source.block;
        Block bridgeBlock = guessBridge(conveyorBlock);

        Vars.ui.hudfrag.showToast("[accent]Source:[white] (" + source.tile.x + ", " + source.tile.y + ") — " + conveyorBlock.localizedName);

        // 2. A* pathfind from source → destination.
        Seq<PathNode> path = ConveyorPathfinder.findPath(
            source.tile, destination, conveyorBlock, bridgeBlock
        );

        if (path == null || path.isEmpty()) {
            Vars.ui.hudfrag.showToast("[scarlet]No valid path to destination.");
            return;
        }

        // 3. Convert path nodes → build plans and queue them.
        int placed = PlanPlacer.placeConveyorPath(path, conveyorBlock, bridgeBlock);

        Vars.ui.hudfrag.showToast("[green]Queued " + placed + " build plans.");
    }

    /**
     * Given a conveyor block, try to find its matching bridge conveyor.
     * Returns null if none found (bridges won't be used).
     */
    private static Block guessBridge(Block conveyor) {
        // Standard vanilla mappings.
        if (conveyor == Blocks.conveyor)          return Blocks.bridgeConveyor;
        if (conveyor == Blocks.titaniumConveyor)   return Blocks.phaseConveyor;
        if (conveyor == Blocks.plastaniumConveyor) return Blocks.phaseConveyor;
        if (conveyor == Blocks.armoredConveyor)    return Blocks.phaseConveyor;
        // Fallback — no bridge available.
        return null;
    }
}
