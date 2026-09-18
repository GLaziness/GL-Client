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
import mindustry.world.blocks.power.*;

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
    /** Ends of the phase bridges of the line being drawn, they need a power node. */
    private static final Seq<Tile> phaseEnds = new Seq<>();
    /** Phase bridges each generated power node has to be linked to once everything is built. */
    private static final ObjectMap<BuildPlan, IntSeq> nodeTargets = new ObjectMap<>();
    /** Built (or being built) power nodes waiting to be linked to their phase bridges. */
    private static final Seq<NodeLinks> pendingLinks = new Seq<>();
    private static final Interval linkTimer = new Interval();
    /** Power nodes under the line being drawn that were (or could not be) moved to the side. */
    private static final IntSet movedNodes = new IntSet(), failedNodes = new IntSet();

    private static class NodeLinks{
        int pos;
        IntSeq targets;
        float since = Time.time;
    }

    static{
        Events.run(Trigger.update, PlastaniumCrossings::updatePending);
        Events.on(WorldLoadEvent.class, e -> {
            pending.clear();
            pendingSince.clear();
            noAutoLink.clear();
            pendingLinks.clear();
        });
    }

    public static boolean enabled(){
        return Core.settings.getBool("plastbridges", false);
    }

    /** Called from {@link StackConveyor#handlePlacementLine}: adds bridge plans for every crossed line. */
    public static void handleLine(Seq<BuildPlan> plans){
        generated.clear();
        nodeTargets.clear();
        if(!enabled() || plans.isEmpty()) return;
        result.clear();
        phaseEnds.clear();

        movedNodes.clear();
        failedNodes.clear();
        for(BuildPlan plan : plans){
            if(!moveNode(plans, plan) && !crossLine(plans, plan)) result.add(plan);
        }

        if(!phaseEnds.isEmpty()) placeNodes();

        plans.set(result);
        result.clear();
    }

    /** Tries to bridge the line crossed at this plan. Returns false if the tile is left to the vanilla behaviour. */
    private static boolean crossLine(Seq<BuildPlan> plans, BuildPlan plan){
        Tile tile = plan.tile();
        if(tile == null || tile.build == null || tile.build.tile != tile) return false;
        Block family = bridgeFor(tile.block());
        if(family == null || !family.unlockedNow()) return false;

        int dir = flowDirection(tile.build);
        // only lines running across ours, not along it
        if(dir == -1 || dir % 2 == plan.rotation % 2) return false;

        // too many conveyors for a normal bridge: phase bridges reach further
        Block phase = phaseFor(family);
        int range = family instanceof ItemBridge b ? b.range : ((DirectionBridge)family).range;
        int maxRange = phase != null && phase.unlockedNow() ? ((ItemBridge)phase).range : range;

        Tile src = skipConveyors(plans, tile, (dir + 2) % 4, maxRange);
        Tile dst = skipConveyors(plans, tile, dir, maxRange);
        if(!partOfLine(src, family, dir) || !partOfLine(dst, family, dir)) return false;
        int distance = Math.abs(src.x - dst.x) + Math.abs(src.y - dst.y);
        if(distance > maxRange) return false;
        Block bridge = distance > range ? phase : family;
        // an end that already is a bridge (e.g. the input of a phase bridge next to the new line) is kept and chained to,
        // so the new bridge has to be of the same kind; the bigger one wins if both ends are bridges
        ItemBridge kept = null;
        for(Tile end : new Tile[]{src, dst}){
            if(end.block() instanceof ItemBridge existing && existing.unlockedNow() && existing.range >= distance
                && (kept == null || existing.range > kept.range)){
                kept = existing;
            }
        }
        if(kept != null) bridge = kept;

        // bridges first, so the crossed line never feeds into the new conveyor
        if(bridge instanceof ItemBridge){
            // both ends must be the same bridge: an existing one of that kind only gets relinked, anything else is replaced
            add(new BuildPlan(src.x, src.y, dir, bridge, new Point2(dst.x - src.x, dst.y - src.y)));
            if(dst.block() != bridge) add(new BuildPlan(dst.x, dst.y, dir, bridge));
            if(bridge.hasPower && bridge.consumesPower){ // consumesPower alone is true by default
                if(!powered(src, bridge)) phaseEnds.add(src);
                if(!powered(dst, bridge)) phaseEnds.add(dst);
            }
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
    private static boolean partOfLine(@Nullable Tile tile, Block family, int dir){
        if(tile == null || tile.build == null || tile.build.tile != tile || bridgeFor(tile.block()) != family) return false;
        if(tile.block() instanceof ItemBridge) return true;
        return tile.build.rotation == dir;
    }

    /** An existing phase bridge that already has power does not need a new node. */
    private static boolean powered(Tile tile, Block bridge){
        return tile.block() == bridge && tile.build.power != null && tile.build.power.status > 0.01f;
    }

    private static @Nullable Block phaseFor(Block family){
        if(family == Blocks.itemBridge) return Blocks.phaseConveyor;
        if(family == Blocks.bridgeConduit) return Blocks.phaseConduit;
        return null;
    }

    /**
     * Adds power nodes that reach every phase bridge end of the line. A power node is used where it reaches as many
     * ends as a large one would, a large node otherwise. The nodes connect to the grid by themselves when built.
     */
    private static void placeNodes(){
        IntSet occupied = occupiedBy(result, null);
        Seq<Tile> left = phaseEnds.copy();
        Seq<Tile> linked = new Seq<>();

        while(!left.isEmpty()){
            Tile center = left.first();
            Tile best = null;
            PowerNode bestNode = null;
            int bestCount = 0;
            float bestDst = Float.MAX_VALUE;

            for(Block block : new Block[]{Blocks.powerNode, Blocks.powerNodeLarge}){
                if(!(block instanceof PowerNode node) || !node.unlockedNow()) continue;
                int r = (int)node.laserRange + 1;
                // keep one link free for the grid
                int maxTargets = node.maxNodes - 1;

                for(int dx = -r; dx <= r; dx++){
                    for(int dy = -r; dy <= r; dy++){
                        Tile c = world.tile(center.x + dx, center.y + dy);
                        if(c == null || !node.overlaps(c, center) || !free(c, node, occupied)) continue;
                        int count = Math.min(left.count(t -> node.overlaps(c, t)), maxTargets);
                        float dst = c.dst2(center);
                        // the large node only wins when it reaches more ends
                        if(count > bestCount || (count == bestCount && node == bestNode && dst < bestDst)){
                            best = c;
                            bestNode = node;
                            bestCount = count;
                            bestDst = dst;
                        }
                    }
                }
            }

            if(best == null) break;

            Tile at = best;
            PowerNode node = bestNode;
            linked.clear();
            for(Tile t : left){
                if(linked.size < bestCount && node.overlaps(at, t)) linked.add(t);
            }
            left.removeAll(linked);

            BuildPlan plan = new BuildPlan(at.x, at.y, 0, node);
            add(plan);
            IntSeq targets = new IntSeq();
            for(Tile t : linked) targets.add(t.pos());
            nodeTargets.put(plan, targets);
            at.getLinkedTilesAs(node, tempTiles).each(t -> occupied.add(t.pos()));
        }
    }

    private static final Seq<Tile> tempTiles = new Seq<>();

    /** Every tile covered by the given plans (and optionally by the line itself). */
    private static IntSet occupiedBy(Seq<BuildPlan> plans, @Nullable Seq<BuildPlan> line){
        IntSet occupied = new IntSet();
        occupy(plans, occupied);
        if(line != null) occupy(line, occupied);
        return occupied;
    }

    private static void occupy(Seq<BuildPlan> plans, IntSet occupied){
        for(BuildPlan plan : plans){
            Tile tile = plan.tile();
            if(tile == null) continue;
            if(plan.block == null || plan.block.size == 1) occupied.add(tile.pos());
            else tile.getLinkedTilesAs(plan.block, tempTiles).each(t -> occupied.add(t.pos()));
        }
    }

    /**
     * A power node under the line is rebuilt next to it with the same links, then the old one is removed
     * and the conveyor takes its place. Returns false if there is no power node here or no room for it.
     */
    private static boolean moveNode(Seq<BuildPlan> plans, BuildPlan plan){
        Tile tile = plan.tile();
        if(tile == null || !(tile.build instanceof PowerNode.PowerNodeBuild build) || build.team != player.team()) return false;
        if(!(build.block instanceof PowerNode node) || !node.unlockedNow()) return false;

        int key = build.pos();
        if(failedNodes.contains(key)) return false;
        if(movedNodes.add(key)){
            Tile to = findNodeSpot(plans, build, node);
            if(to == null){
                failedNodes.add(key);
                movedNodes.remove(key);
                return false;
            }
            // the new node goes first so the grid is never cut
            add(new BuildPlan(to.x, to.y, 0, node, nodeLinks(build, node, to).toArray(Point2.class)));
        }

        // the conveyor can't replace a node: break it, the conveyor is placed once the tile is free
        BuildPlan breaking = new BuildPlan(plan.x, plan.y);
        breaking.block = build.block;
        breaking.config = plan.copy();
        result.add(breaking);
        return true;
    }

    /** Closest free spot next to the line from which the node keeps as many of its links as possible. */
    private static @Nullable Tile findNodeSpot(Seq<BuildPlan> plans, Building build, PowerNode node){
        IntSet occupied = occupiedBy(result, plans);
        int total = nodeLinkCount(build);
        Tile best = null;
        int bestKept = -1;
        float bestDst = Float.MAX_VALUE;
        int r = 4 + node.size;

        for(int dx = -r; dx <= r; dx++){
            for(int dy = -r; dy <= r; dy++){
                Tile c = world.tile(build.tile.x + dx, build.tile.y + dy);
                if(c == null || !free(c, node, occupied)) continue;
                int kept = nodeLinks(build, node, c).size;
                float dst = c.dst2(build.tile);
                if(kept > bestKept || (kept == bestKept && dst < bestDst)){
                    best = c;
                    bestKept = kept;
                    bestDst = dst;
                }
            }
        }
        // better to leave the node where it is than to cut the grid
        return best != null && bestKept >= Math.min(total, 1) ? best : null;
    }

    private static int nodeLinkCount(Building build){
        int count = build.power.links.size;
        for(Building other : build.proximity){
            if(other.power != null && other.block.connectedPower && !(other.block instanceof StackConveyor)) count++;
        }
        return count;
    }

    /** Links (relative to the new spot) the moved node should have: its lasers, plus the buildings it powered by touching them. */
    private static Seq<Point2> nodeLinks(Building build, PowerNode node, Tile to){
        Seq<Point2> out = new Seq<>();
        IntSet added = new IntSet();
        tempTiles.clear();
        to.getLinkedTilesAs(node, tempTiles);

        for(int i = 0; i < build.power.links.size; i++){
            Building other = world.build(build.power.links.get(i));
            if(other != null && inRange(node, to, other) && added.add(other.pos())) out.add(new Point2(other.tileX() - to.x, other.tileY() - to.y));
        }
        for(Building other : build.proximity){
            if(other.power == null || !other.block.connectedPower || other.block instanceof StackConveyor || other.block instanceof PowerNode) continue;
            // buildings touching the new spot are powered by contact anyway
            if(tempTiles.contains(t -> t.build == other || t.nearby(0) != null && t.nearby(0).build == other || t.nearby(1) != null && t.nearby(1).build == other
                || t.nearby(2) != null && t.nearby(2).build == other || t.nearby(3) != null && t.nearby(3).build == other)) continue;
            if(inRange(node, to, other) && added.add(other.pos())) out.add(new Point2(other.tileX() - to.x, other.tileY() - to.y));
        }
        if(out.size > node.maxNodes) out.truncate(node.maxNodes);
        return out;
    }

    private static boolean inRange(PowerNode node, Tile from, Building other){
        return node.overlaps(from, other.tile) || (other.block instanceof PowerNode on && on.overlaps(other.tile, from));
    }

    private static boolean free(Tile tile, Block node, IntSet occupied){
        if(!Build.validPlace(node, player.team(), tile.x, tile.y, 0)) return false;
        return !tile.getLinkedTilesAs(node, tempTiles).contains(t -> occupied.contains(t.pos()));
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
        if(block == Blocks.phaseConveyor) return Blocks.itemBridge;
        if(block == Blocks.phaseConduit) return Blocks.bridgeConduit;
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

            IntSeq targets = nodeTargets.get(plan);
            if(targets != null){
                NodeLinks links = new NodeLinks();
                links.pos = Point2.pack(plan.x, plan.y);
                links.targets = new IntSeq(targets);
                pendingLinks.add(links);
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
        if(!pendingLinks.isEmpty() && state.isGame() && linkTimer.get(30f)) updateLinks();
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

    /** Links each generated power node to its phase bridges as soon as both are built. */
    private static void updateLinks(){
        for(int i = pendingLinks.size - 1; i >= 0; i--){
            NodeLinks links = pendingLinks.get(i);
            if(Time.time - links.since > pendingTimeout){
                pendingLinks.remove(i);
                continue;
            }

            Building node = world.build(links.pos);
            if(!(node instanceof PowerNode.PowerNodeBuild) || node.team != player.team()) continue;

            for(int j = links.targets.size - 1; j >= 0; j--){
                Building target = world.build(links.targets.get(j));
                // wait until the phase bridge itself is built (an old bridge conveyor there has no power module)
                if(target == null || !(target.block instanceof ItemBridge) || target.power == null || target.team != player.team()) continue;
                if(!node.power.links.contains(target.pos()) && !target.power.links.contains(node.pos())){
                    ClientVars.configs.add(new ConfigRequest(node, target.pos()));
                }
                links.targets.removeIndex(j);
            }

            if(links.targets.size == 0) pendingLinks.remove(i);
        }
    }
}
