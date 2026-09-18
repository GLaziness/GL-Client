package mindustry.client.utils;

import arc.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;

import static mindustry.Vars.*;

/**
 * GL: when a plastanium (or surge) conveyor line is drawn straight across other conveyors, ducts or conduits,
 * the crossed lines are fixed with a bridge over the new line instead of being cut.
 * Enabled with the "plastbridges" setting (side panel button).
 */
public class PlastaniumCrossings{
    /** Line plans that can only be placed after the conduit under them is deconstructed. */
    private static final Seq<BuildPlan> pending = new Seq<>();
    private static final ObjectFloatMap<BuildPlan> pendingSince = new ObjectFloatMap<>();
    private static final float pendingTimeout = 60f * 120f;
    private static final Seq<BuildPlan> result = new Seq<>();

    static{
        Events.run(Trigger.update, PlastaniumCrossings::updatePending);
        Events.on(WorldLoadEvent.class, e -> {
            pending.clear();
            pendingSince.clear();
        });
    }

    public static boolean enabled(){
        return Core.settings.getBool("plastbridges", false);
    }

    /** Called from {@link StackConveyor#handlePlacementLine}: adds bridge plans for every crossed line. */
    public static void handleLine(Seq<BuildPlan> plans){
        if(!enabled() || plans.isEmpty()) return;
        result.clear();

        for(BuildPlan plan : plans){
            Tile tile = plan.tile();
            Block crossing = tile == null || tile.build == null || tile.build.tile != tile ? null : tile.block();
            Block bridge = crossing == null ? null : bridgeFor(crossing);
            int rot = tile == null || tile.build == null ? 0 : tile.build.rotation;

            // only lines running across ours, not along it
            if(bridge == null || rot % 2 == plan.rotation % 2 || !bridge.unlockedNow()){
                result.add(plan);
                continue;
            }

            Tile before = tile.nearby(Geometry.d4x(rot + 2), Geometry.d4y(rot + 2));
            Tile after = tile.nearby(Geometry.d4x(rot), Geometry.d4y(rot));
            if(!sameLine(before, crossing, rot) || !sameLine(after, crossing, rot) || onLine(plans, before) || onLine(plans, after)){
                result.add(plan);
                continue;
            }

            // bridges first, so the crossed line never feeds into the new conveyor
            if(bridge instanceof ItemBridge){
                result.add(new BuildPlan(before.x, before.y, rot, bridge, new Point2(after.x - before.x, after.y - before.y)));
                result.add(new BuildPlan(after.x, after.y, rot, bridge));
            }else{
                result.add(new BuildPlan(before.x, before.y, rot, bridge));
                result.add(new BuildPlan(after.x, after.y, rot, bridge));
            }

            if(plan.block.canReplace(crossing)){
                result.add(plan);
            }else{
                // conduits can't be replaced by a conveyor: break it, the conveyor is placed once the tile is free
                // (the conveyor plan rides in the config and is only queued when the line is confirmed, see flushed())
                BuildPlan breaking = new BuildPlan(plan.x, plan.y);
                breaking.block = crossing;
                breaking.config = plan.copy();
                result.add(breaking);
            }
        }

        plans.set(result);
        result.clear();
    }

    /** Called by {@link mindustry.input.InputHandler#flushPlans} for every confirmed breaking plan. */
    public static void flushed(BuildPlan plan){
        if(!(plan.config instanceof BuildPlan later)) return;
        pending.remove(p -> p.x == later.x && p.y == later.y);
        pending.add(later);
        pendingSince.put(later, Time.time);
    }

    /** The bridge that fits the crossed block, or null if it is not a line we can fix. */
    private static @Nullable Block bridgeFor(Block block){
        if(block instanceof StackConveyor) return null;
        if(block instanceof Conveyor) return Blocks.itemBridge;
        if(block instanceof Duct) return Blocks.ductBridge;
        if(block instanceof Conduit) return block == Blocks.reinforcedConduit ? Blocks.reinforcedBridgeConduit : Blocks.bridgeConduit;
        return null;
    }

    private static boolean sameLine(@Nullable Tile tile, Block crossing, int rotation){
        return tile != null && tile.build != null && tile.build.tile == tile && tile.build.rotation == rotation && bridgeFor(tile.block()) == bridgeFor(crossing);
    }

    private static boolean onLine(Seq<BuildPlan> plans, Tile tile){
        return plans.contains(p -> p.x == tile.x && p.y == tile.y);
    }

    private static void updatePending(){
        if(pending.isEmpty() || !state.isGame() || player.unit() == null) return;

        for(int i = pending.size - 1; i >= 0; i--){
            BuildPlan plan = pending.get(i);
            Tile tile = plan.tile();
            boolean expired = Time.time - pendingSince.get(plan, Time.time) > pendingTimeout;
            if(tile == null || expired || tile.block() == plan.block){
                pending.remove(i);
                pendingSince.remove(plan, 0f);
            }else if(tile.build == null && player.unit().canBuild()){
                player.unit().addBuild(plan);
                pending.remove(i);
                pendingSince.remove(plan, 0f);
            }
        }
    }
}
