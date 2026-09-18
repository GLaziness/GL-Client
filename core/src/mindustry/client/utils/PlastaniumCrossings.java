package mindustry.client.utils;

import arc.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.client.*;
import mindustry.client.antigrief.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.distribution.ItemBridge.*;
import mindustry.world.blocks.liquid.*;

import static mindustry.Vars.*;

/**
 * GL: when a plastanium (or surge) conveyor line is drawn straight across other conveyors, ducts or conduits,
 * the crossed lines get a bridge over the new line instead of being cut. Works next to or right against
 * lines that were already bridged: the existing bridges are relinked or moved so they jump over all of them.
 * Enabled with the "plastbridges" setting (side panel button).
 */
public class PlastaniumCrossings{
    /** Line plans that can only be placed after the conduit under them is deconstructed. */
    private static final Seq<BuildPlan> pending = new Seq<>();
    private static final ObjectFloatMap<BuildPlan> pendingSince = new ObjectFloatMap<>();
    private static final float pendingTimeout = 60f * 120f;
    /** Bridges placed by this class must not be auto-linked to the last placed bridge. */
    private static final IntSet noAutoLink = new IntSet();
    /** Bridge plans generated for the line currently being drawn. */
    private static final Seq<BuildPlan> generated = new Seq<>();
    private static final Seq<BuildPlan> result = new Seq<>();

    static{
        Events.run(Trigger.update, PlastaniumCrossings::updatePending);
        Events.on(WorldLoadEvent.class, e -> {
            pending.clear();
            pendingSince.clear();
            noAutoLink.clear();
        });
    }

    public static boolean enabled(){
        return Core.settings.getBool("plastbridges", false);
    }

    /** Called from {@link StackConveyor#handlePlacementLine}: adds bridge plans for every crossed line. */
    public static void handleLine(Seq<BuildPlan> plans){
        generated.clear();
        if(!enabled() || plans.isEmpty()) return;
        result.clear();

        for(BuildPlan plan : plans){
            if(!crossLine(plans, plan)) result.add(plan);
        }

        plans.set(result);
        result.clear();
    }

    /** Tries to bridge the line crossed at this plan. Returns false if the tile is left to the vanilla behaviour. */
    private static boolean crossLine(Seq<BuildPlan> plans, BuildPlan plan){
        Tile tile = plan.tile();
        if(tile == null || tile.build == null || tile.build.tile != tile) return false;
        Block bridge = bridgeFor(tile.block());
        if(bridge == null || !bridge.unlockedNow()) return false;

        int dir = flowDirection(tile.build);
        // only lines running across ours, not along it
        if(dir == -1 || dir % 2 == plan.rotation % 2) return false;

        int range = bridge instanceof ItemBridge b ? b.range : ((DirectionBridge)bridge).range;
        Tile src = skipConveyors(plans, tile, (dir + 2) % 4, range);
        Tile dst = skipConveyors(plans, tile, dir, range);
        if(!partOfLine(src, bridge, dir) || !partOfLine(dst, bridge, dir)) return false;
        if(Math.abs(src.x - dst.x) + Math.abs(src.y - dst.y) > range) return false;

        // bridges first, so the crossed line never feeds into the new conveyor
        if(bridge instanceof ItemBridge){
            // an existing bridge only gets relinked, a conveyor under it is replaced
            add(new BuildPlan(src.x, src.y, dir, bridge, new Point2(dst.x - src.x, dst.y - src.y)));
            if(dst.block() != bridge) add(new BuildPlan(dst.x, dst.y, dir, bridge));
        }else{
            // direction bridges link to the next bridge in front by themselves
            if(src.block() != bridge) add(new BuildPlan(src.x, src.y, dir, bridge));
            if(dst.block() != bridge) add(new BuildPlan(dst.x, dst.y, dir, bridge));
        }

        if(plan.block.canReplace(tile.block())){
            result.add(plan);
        }else{
            // conduits can't be replaced by a conveyor: break it, the conveyor is placed once the tile is free
            // (the conveyor plan rides in the config and is only queued when the line is confirmed, see flushed())
            BuildPlan breaking = new BuildPlan(plan.x, plan.y);
            breaking.block = tile.block();
            breaking.config = plan.copy();
            result.add(breaking);
        }
        return true;
    }

    private static void add(BuildPlan plan){
        result.add(plan);
        generated.add(plan);
    }

    /** Walks from the crossing along the crossed line past every plastanium conveyor (planned or built). */
    private static @Nullable Tile skipConveyors(Seq<BuildPlan> plans, Tile from, int dir, int range){
        Tile tile = from;
        for(int i = 0; i <= range; i++){
            tile = tile.nearby(dir);
            if(tile == null) return null;
            boolean conveyor = onLine(plans, tile) || tile.build instanceof StackConveyor.StackConveyorBuild;
            if(!conveyor) return tile;
        }
        return null;
    }

    /** Whether the tile belongs to the crossed line: the same kind of conveyor/conduit flowing the same way, or its bridge. */
    private static boolean partOfLine(@Nullable Tile tile, Block bridge, int dir){
        if(tile == null || tile.build == null || tile.build.tile != tile || bridgeFor(tile.block()) != bridge) return false;
        if(tile.block() == bridge && bridge instanceof ItemBridge) return true;
        return tile.build.rotation == dir;
    }

    /** Direction items flow through this building, or -1 if unknown. */
    private static int flowDirection(Building build){
        if(build instanceof ItemBridgeBuild b){
            Tile link = world.tile(b.link);
            if(link != null && ((ItemBridge)b.block).linkValid(b.tile, link)) return dirTo(b.tile, link);
            for(int i = 0; i < b.incoming.size; i++){
                Tile in = world.tile(b.incoming.get(i));
                if(in != null) return dirTo(in, b.tile);
            }
            return -1;
        }
        return build.rotation;
    }

    private static int dirTo(Tile from, Tile to){
        if(from.x == to.x) return to.y > from.y ? 1 : 3;
        if(from.y == to.y) return to.x > from.x ? 0 : 2;
        return -1;
    }

    /** The bridge used for this block's kind of line, or null if it is not a line we can fix. */
    private static @Nullable Block bridgeFor(Block block){
        if(block instanceof StackConveyor) return null;
        if(block == Blocks.itemBridge || block == Blocks.bridgeConduit || block == Blocks.ductBridge || block == Blocks.reinforcedBridgeConduit) return block;
        if(block instanceof Conveyor) return Blocks.itemBridge;
        if(block instanceof Duct) return Blocks.ductBridge;
        if(block instanceof Conduit) return block == Blocks.reinforcedConduit ? Blocks.reinforcedBridgeConduit : Blocks.bridgeConduit;
        return null;
    }

    private static boolean onLine(Seq<BuildPlan> plans, Tile tile){
        return plans.contains(p -> p.x == tile.x && p.y == tile.y);
    }

    /** Called by {@link mindustry.input.InputHandler#flushPlans} for every plan of a confirmed line. */
    public static void flushed(BuildPlan plan){
        if(generated.contains(p -> p == plan)){
            Tile tile = plan.tile();
            if(tile != null && tile.build != null && tile.block() == plan.block){
                // an already built bridge can't be placed again, so it is relinked directly
                if(plan.config != null) ClientVars.configs.add(new ConfigRequest(tile.build, plan.config));
            }else{
                noAutoLink.add(Point2.pack(plan.x, plan.y));
            }
        }
        if(!plan.breaking || !(plan.config instanceof BuildPlan later)) return;
        pending.remove(p -> p.x == later.x && p.y == later.y);
        pending.add(later);
        pendingSince.put(later, Time.time);
    }

    /** Called when a bridge is placed: true if it must keep only the link from its plan. */
    public static boolean skipAutoLink(Tile tile){
        return noAutoLink.remove(tile.pos());
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
