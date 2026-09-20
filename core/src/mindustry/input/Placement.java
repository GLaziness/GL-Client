package mindustry.input;

import arc.*;
import arc.func.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.pooling.*;
import mindustry.entities.units.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.payloads.*;

import java.util.*;

import static mindustry.Vars.*;

interface BridgePlacer{
    boolean unlockedNow();

    boolean positionsValid(int x1, int y1, int x2, int y2);

    void applyToPlans(BuildPlan cur, BuildPlan other);
}

class ItemBridgePlacer implements BridgePlacer{
    private final ItemBridge bridge;

    ItemBridgePlacer(ItemBridge bridge){
        this.bridge = bridge;
    }

    @Override
    public boolean unlockedNow(){
        return bridge.unlockedNow();
    }

    @Override
    public boolean positionsValid(int x1, int y1, int x2, int y2){
        return bridge.positionsValid(x1, y1, x2, y2);
    }

    @Override
    public void applyToPlans(BuildPlan cur, BuildPlan other){
        cur.block = bridge;
        other.block = bridge;
        other.config = new Point2(cur.x - other.x, cur.y - other.y);
    }
}

class DirectionBridgePlacer implements BridgePlacer{
    private final DirectionBridge bridge;

    DirectionBridgePlacer(DirectionBridge bridge){
        this.bridge = bridge;
    }

    @Override
    public boolean unlockedNow(){
        return bridge.unlockedNow();
    }

    @Override
    public boolean positionsValid(int x1, int y1, int x2, int y2){
        return bridge.positionsValid(x1, y1, x2, y2);
    }

    @Override
    public void applyToPlans(BuildPlan cur, BuildPlan other){
        cur.block = bridge;
        other.block = bridge;
    }
}

public class Placement{
    private static final Seq<BuildPlan> plans1 = new Seq<>();
    private static final Seq<Point2> tmpPoints = new Seq<>(), tmpPoints2 = new Seq<>();
    private static final NormalizeResult result = new NormalizeResult();
    private static final NormalizeDrawResult drawResult = new NormalizeDrawResult();
    private static final Bresenham2 bres = new Bresenham2();
    private static final Seq<Point2> points = new Seq<>();
    private static final IntSeq tmpInts = new IntSeq(), tmpInts2 = new IntSeq();

    //for pathfinding
    private static final IntFloatMap costs = new IntFloatMap();
    private static final IntIntMap parents = new IntIntMap();
    private static final IntSet closed = new IntSet();

    /** Normalize a diagonal line into points. */
    public static Seq<Point2> pathfindLine(boolean conveyors, int startX, int startY, int endX, int endY){
        Pools.freeAll(points);
        points.clear();
        if(conveyors && Core.settings.getBool("conveyorpathfinding")){
            if(astar(startX, startY, endX, endY)){
                return points;
            }else{
                return normalizeLine(startX, startY, endX, endY);
            }
        }else{
            return bres.lineNoDiagonal(startX, startY, endX, endY, Pools.get(Point2.class, Point2::new), points);
        }
    }


    /** GL: a bridge can stand on this tile (taken from Morj's client). */
    public static boolean canHostBridge(Block block, int x, int y, int rotation){
        if(block == null) return false;
        Tile t = world.tile(x, y);
        if(t == null) return false;
        if(t.block() == block) return true;
        if(t.build != null && t.build.block == block) return true;
        // hard obstacle - cannot stand a bridge here
        if(!t.block().alwaysReplace && !block.canReplace(t.block())) return false;
        if(t.floor().isDeep() && !block.placeableLiquid) return false;
        return Build.validPlace(block, player.team(), x, y, rotation, false);
    }

    /** GL: walks one straight segment, hopping by up to {@code range} and landing only where a bridge can stand. */
    private static void appendHostableSegment(Seq<Point2> out, int x1, int y1, int x2, int y2, Block block, int rotation, int range){
        if(x1 == x2 && y1 == y2) return;
        if(x1 != x2 && y1 != y2) return;
        int dx = Integer.signum(x2 - x1);
        int dy = Integer.signum(y2 - y1);
        int dist = Math.abs(x2 - x1) + Math.abs(y2 - y1);
        int traveled = 0;
        int cx = x1, cy = y1;
        while(traveled < dist){
            int step = Math.min(range, dist - traveled);
            boolean placed = false;
            for(int s = step; s >= 1; s--){ // a full hop when its landing is free, otherwise a shorter one
                int nx = cx + dx * s, ny = cy + dy * s;
                if((nx == x2 && ny == y2) || canHostBridge(block, nx, ny, rotation)){
                    if(out.peek().x != nx || out.peek().y != ny) out.add(new Point2(nx, ny));
                    cx = nx;
                    cy = ny;
                    traveled += s;
                    placed = true;
                    break;
                }
            }
            if(!placed) break; // blocked: stop instead of putting a bridge into a wall
        }
    }

    /** GL: dense path around the obstacles, by the same A* the conveyors use (from Morj's client). */
    public static boolean tilePathAround(int startX, int startY, int endX, int endY, Block block, Seq<Point2> out){
        out.clear();
        if(startX == endX && startY == endY){
            out.add(new Point2(startX, startY));
            return true;
        }
        // the heuristics read the block being placed, so it is set for the search
        Block prev = control.input != null ? control.input.block : null;
        if(control.input != null) control.input.block = block;
        try{
            Pools.freeAll(points);
            points.clear();
            if(!astar(startX, startY, endX, endY)) return false;
            for(Point2 p : points) out.add(new Point2(p.x, p.y));
            return out.size > 0;
        }finally{
            if(control.input != null) control.input.block = prev;
        }
    }

    /**
     * GL: A* over bridge hops: every landing is a free tile, every step an orthogonal jump of 1..range, so the line
     * can span over an obstacle instead of walking around it (from Morj's client).
     */
    public static boolean findBridgePath(int startX, int startY, int endX, int endY, int range, Block block, int rotation, Seq<Point2> out){
        out.clear();
        if(block == null || range < 1) return false;
        if(startX == endX && startY == endY){
            out.add(new Point2(startX, startY));
            return true;
        }

        // a wide margin, so the way can go around a whole base
        int manh = Math.abs(endX - startX) + Math.abs(endY - startY);
        int margin = Math.max(48, manh + range * 6);
        int minX = Math.min(startX, endX) - margin, maxX = Math.max(startX, endX) + margin;
        int minY = Math.min(startY, endY) - margin, maxY = Math.max(startY, endY) + margin;

        costs.clear();
        closed.clear();
        parents.clear();

        int startPos = Point2.pack(startX, startY), endPos = Point2.pack(endX, endY);
        Tile endTile = world.tile(endX, endY);
        if(endTile == null) return false;
        // a solid wall as the end of the line is refused: that is running into the obstacle, not around it
        boolean endOk = canHostBridge(block, endX, endY, rotation) || endTile.block().alwaysReplace
            || endTile.block() == block || block.canReplace(endTile.block());
        if(!endOk) return false;

        int nodeLimit = 20000, totalNodes = 0;
        PQueue<Tile> queue = new PQueue<>(64, (Tile a, Tile b) -> Float.compare(
            costs.get(a.pos(), 0f) + bridgeHeuristic(a.x, a.y, endX, endY, range),
            costs.get(b.pos(), 0f) + bridgeHeuristic(b.x, b.y, endX, endY, range)));

        Tile startTile = world.tile(startX, startY);
        if(startTile == null) return false;
        queue.add(startTile);
        costs.put(startPos, 0f);

        boolean found = false;
        while(!queue.empty() && totalNodes++ < nodeLimit){
            Tile cur = queue.poll();
            if(cur == null) break;
            int cpos = cur.pos();
            if(!closed.add(cpos)) continue;
            if(cpos == endPos){
                found = true;
                break;
            }

            float base = costs.get(cpos, 0f);
            // the way the line came here, to keep it going straight where it can
            int inDir = -1;
            int from = parents.get(cpos, -1);
            if(from != -1){
                int fdx = Integer.signum(cur.x - Point2.x(from)), fdy = Integer.signum(cur.y - Point2.y(from));
                for(int d = 0; d < 4; d++){
                    if(Geometry.d4x(d) == fdx && Geometry.d4y(d) == fdy){
                        inDir = d;
                        break;
                    }
                }
            }
            for(int d = 0; d < 4; d++){
                int dx = Geometry.d4x(d), dy = Geometry.d4y(d);
                for(int dist = 1; dist <= range; dist++){
                    int nx = cur.x + dx * dist, ny = cur.y + dy * dist;
                    if(nx < minX || nx > maxX || ny < minY || ny > maxY) break;
                    Tile child = world.tile(nx, ny);
                    if(child == null) break;

                    boolean isStart = nx == startX && ny == startY;
                    boolean isEnd = nx == endX && ny == endY;
                    // every landing but the one under the first click has to be free
                    if(!isStart && !isEnd && !canHostBridge(block, nx, ny, rotation)) continue;
                    if(closed.contains(child.pos())) continue;

                    float newCost = base + 1f + (range - dist) * 0.02f; // a long hop is cheaper than a short one
                    if(d != inDir && inDir != -1) newCost += 1.5f; // and a turn costs more than going straight on
                    if(newCost < costs.get(child.pos(), Float.POSITIVE_INFINITY)){
                        costs.put(child.pos(), newCost);
                        parents.put(child.pos(), cpos);
                        queue.add(child);
                    }
                }
            }
        }

        if(!found) return false;

        int curPos = endPos, guard = 0;
        while(guard++ < nodeLimit){
            out.add(new Point2(Point2.x(curPos), Point2.y(curPos)));
            if(curPos == startPos) break;
            int parent = parents.get(curPos, -1);
            if(parent == -1){
                out.clear();
                return false;
            }
            curPos = parent;
        }
        out.reverse();
        return out.size > 0;
    }

    private static float bridgeHeuristic(int x, int y, int endX, int endY, int range){
        return (Math.abs(x - endX) + Math.abs(y - endY)) / (float)Math.max(1, range);
    }

    /**
     * GL: the nodes of a bridge line that never stands on a wall: first a way around the obstacles, then hops over
     * them, and as a last resort just the two ends (from Morj's client).
     */
    public static boolean buildBridgePath(int startX, int startY, int endX, int endY, int range, Block block, int rotation, Seq<Point2> out){
        out.clear();
        if(block == null) return false;

        if(findBridgePath(startX, startY, endX, endY, range, block, rotation, out)){
            sanitizeBridgeNodes(out, block, rotation, range, startX, startY);
            if(out.size > 0) return true;
        }

        // nothing to jump from or to: walk around instead
        Seq<Point2> dense = new Seq<>();
        if(tilePathAround(startX, startY, endX, endY, block, dense)){
            sampleBridgeNodes(dense, range, out);
            sanitizeBridgeNodes(out, block, rotation, range, startX, startY);
            if(out.size > 0) return true;
        }

        out.clear();
        out.add(new Point2(startX, startY));
        if((startX == endX || startY == endY) && Math.abs(startX - endX) + Math.abs(startY - endY) <= range
                && canHostBridge(block, endX, endY, rotation)){
            out.add(new Point2(endX, endY));
        }
        return out.size > 0;
    }

    /** GL: throws away the nodes that stand on something solid and links the rest through free corners and hops. */
    public static void sanitizeBridgeNodes(Seq<Point2> nodes, Block block, int rotation, int range, int startX, int startY){
        if(nodes.size <= 1) return;
        int r = Math.max(1, range);
        Seq<Point2> cleaned = new Seq<>();
        for(int i = 0; i < nodes.size; i++){
            Point2 p = nodes.get(i);
            if((p.x == startX && p.y == startY) || canHostBridge(block, p.x, p.y, rotation)){
                if(cleaned.isEmpty() || cleaned.peek().x != p.x || cleaned.peek().y != p.y) cleaned.add(new Point2(p.x, p.y));
            }
        }
        Seq<Point2> linked = new Seq<>();
        if(cleaned.size > 0) linked.add(cleaned.first());
        for(int i = 1; i < cleaned.size; i++){
            Point2 a = linked.peek(), b = cleaned.get(i);
            if(a.x == b.x || a.y == b.y){
                appendHostableSegment(linked, a.x, a.y, b.x, b.y, block, rotation, r);
            }else{
                Point2 c1 = new Point2(b.x, a.y), c2 = new Point2(a.x, b.y);
                Point2 corner = canHostBridge(block, c1.x, c1.y, rotation) ? c1
                    : canHostBridge(block, c2.x, c2.y, rotation) ? c2 : null;
                if(corner != null){
                    appendHostableSegment(linked, a.x, a.y, corner.x, corner.y, block, rotation, r);
                    Point2 tip = linked.peek();
                    appendHostableSegment(linked, tip.x, tip.y, b.x, b.y, block, rotation, r);
                }
            }
        }
        nodes.clear();
        nodes.addAll(linked);
    }

    /** GL: turns a dense path into bridge nodes: the corners of it, and a node every {@code range} tiles between them. */
    public static void sampleBridgeNodes(Seq<Point2> path, int range, Seq<Point2> out){
        out.clear();
        if(path.isEmpty()) return;
        if(path.size == 1 || range < 1){
            Point2 p = path.first();
            out.add(new Point2(p.x, p.y));
            return;
        }

        Seq<Point2> corners = new Seq<>();
        corners.add(new Point2(path.first().x, path.first().y));
        for(int i = 1; i < path.size; i++){
            Point2 prev = corners.peek(), cur = path.get(i);
            Point2 next = i + 1 < path.size ? path.get(i + 1) : null;
            if(next == null){
                if(prev.x != cur.x || prev.y != cur.y) corners.add(new Point2(cur.x, cur.y));
            }else{
                int dx1 = Integer.signum(cur.x - prev.x), dy1 = Integer.signum(cur.y - prev.y);
                int dx2 = Integer.signum(next.x - cur.x), dy2 = Integer.signum(next.y - cur.y);
                if((dx1 != dx2 || dy1 != dy2 || (cur.x != prev.x && cur.y != prev.y))
                        && (prev.x != cur.x || prev.y != cur.y)){
                    corners.add(new Point2(cur.x, cur.y));
                }
            }
        }

        out.add(new Point2(corners.first().x, corners.first().y));
        for(int i = 1; i < corners.size; i++){
            Point2 a = out.peek(), b = corners.get(i);
            appendSegmentNodes(out, a.x, a.y, b.x, b.y, range);
        }
    }

    /** GL: nodes spaced by {@code range} from (x1,y1) exclusive to (x2,y2) inclusive. */
    public static void appendSegmentNodes(Seq<Point2> out, int x1, int y1, int x2, int y2, int range){
        if(x1 == x2 && y1 == y2) return;
        if(x1 != x2 && y1 != y2){
            appendSegmentNodes(out, x1, y1, x2, y1, range);
            Point2 mid = out.peek();
            appendSegmentNodes(out, mid.x, mid.y, x2, y2, range);
            return;
        }
        int dx = Integer.signum(x2 - x1), dy = Integer.signum(y2 - y1);
        int dist = Math.abs(x2 - x1) + Math.abs(y2 - y1), traveled = 0;
        int cx = x1, cy = y1;
        while(traveled + range < dist){
            cx += dx * range;
            cy += dy * range;
            traveled += range;
            out.add(new Point2(cx, cy));
        }
        if(cx != x2 || cy != y2) out.add(new Point2(x2, y2));
    }

    /** Normalize two points into one straight line, no diagonals. */
    public static Seq<Point2> normalizeLine(int startX, int startY, int endX, int endY){
        Pools.freeAll(points);
        points.clear();
        if(Math.abs(startX - endX) > Math.abs(startY - endY)){
            //go width
            for(int i = 0; i <= Math.abs(startX - endX); i++){
                points.add(Pools.obtain(Point2.class, Point2::new).set(startX + i * Mathf.sign(endX - startX), startY));
            }
        }else{
            //go height
            for(int i = 0; i <= Math.abs(startY - endY); i++){
                points.add(Pools.obtain(Point2.class, Point2::new).set(startX, startY + i * Mathf.sign(endY - startY)));
            }
        }
        return points;
    }

    /** Normalize two points into a rectangle. */
    public static Seq<Point2> normalizeRectangle(int startX, int startY, int endX, int endY, int blockSize){
        Pools.freeAll(points);
        points.clear();

        int minX = Math.min(startX, endX), minY = Math.min(startY, endY), maxX = Math.max(startX, endX), maxY = Math.max(startY, endY);

        for(int y = 0; y <= maxY - minY; y += blockSize){
            for(int x = 0; x <= maxX - minX; x += blockSize){
                points.add(Pools.obtain(Point2.class, Point2::new).set(startX + x * Mathf.sign(endX - startX), startY + y * Mathf.sign(endY - startY)));
            }
        }

        return points;
    }

    public static Seq<Point2> upgradeLine(int startX, int startY, int endX, int endY){
        closed.clear();
        Pools.freeAll(points);
        points.clear();
        var build = world.build(startX, startY);
        points.add(Pools.obtain(Point2.class, Point2::new).set(startX, startY));
        while(build instanceof ChainedBuilding chain && (build.tile.x != endX || build.tile.y != endY) && closed.add(build.id)){
            if(chain.next() == null) return pathfindLine(true, startX, startY, endX, endY);
            build = chain.next();
            points.add(Pools.obtain(Point2.class, Point2::new).set(build.tile.x, build.tile.y));
        }
        return points;
    }

    /** Calculates optimal node placement for nodes with spacing. Used for bridges and power nodes. */
    public static void calculateNodes(Seq<Point2> points, Block block, int rotation, Boolf2<Point2, Point2> overlapper){
        var base = tmpPoints2;
        var result = tmpPoints.clear();

        base.selectFrom(points, p -> p == points.first() || p == points.peek() || Build.validPlace(block, player.team(), p.x, p.y, rotation));
        boolean addedLast = false;

        outer:
        for(int i = 0; i < base.size; ){
            var point = base.get(i);
            result.add(point);
            if(i == base.size - 1) addedLast = true;

            //find the furthest node that overlaps this one
            for(int j = base.size - 1; j > i; j--){
                var other = base.get(j);
                boolean over = overlapper.get(point, other);

                if(over){
                    //add node to list and start searching for node that overlaps the next one
                    i = j;
                    continue outer;
                }
            }

            //if it got here, that means nothing was found. try to proceed to the next node anyway
            i++;
        }

        if(!addedLast && !base.isEmpty()) result.add(base.peek());

        points.clear();
        points.addAll(result);
    }

    public static boolean isSidePlace(Seq<BuildPlan> plans){
        return plans.size > 1 && Mathf.mod(Tile.relativeTo(plans.first().x, plans.first().y, plans.get(1).x, plans.get(1).y) - plans.first().rotation, 2) == 1;
    }

    public static void calculateBridges(Seq<BuildPlan> plans, ItemBridge bridge){
        calculateBridges(plans, bridge, false, t -> false);
    }

    private static void calculateBridges(Seq<BuildPlan> plans, BridgePlacer bridge, boolean hasJunction, Boolf<Block> avoid){
        //common checks
        if(isSidePlace(plans) || plans.size == 0) return;

        //check for orthogonal placement + unlocked state
        if(!(plans.first().x == plans.peek().x || plans.first().y == plans.peek().y) || !bridge.unlockedNow()){
            return;
        }

        smartCalculateBridges(plans, bridge, hasJunction, avoid);
    }

    private static void smartCalculateBridges(Seq<BuildPlan> plans, BridgePlacer bridge, boolean hasJunction, Boolf<Block> avoid){
        Boolf<BuildPlan> placeable = plan ->
        (plan.placeable(player.team()) || (plan.tile() != null && plan.tile().block() == plan.block && plan.tile().interactable(player.team()))) &&  //don't count the same block as inaccessible
        !(plan != plans.first() && plan.build() != null && plan.build().rotation != plan.rotation && avoid.get(plan.tile().block()));

        var result = plans1.clear();

        // Use DP for smarter bridge placement
        final int conveyorCost = 3;
        final int junctionCost = 30;
        final int bridgeCost = 200;
        final int bridgeOverEmptyPenalty = 5;
        final int infCost = Integer.MAX_VALUE / 2; // Avoid overflow when adding

        int N = plans.size;
        var dp = tmpInts.setSize(2 * N);
        var parent = tmpInts2.setSize(2 * N);
        Arrays.fill(dp, 0, 2 * N, infCost);
        Arrays.fill(parent, 0, 2 * N, -1);
        dp[0] = 0;
        dp[N] = bridgeCost;

        for(int i = 1; i < N; i++){
            var cur = plans.get(i);
            boolean canPlace = placeable.get(cur);
            boolean needJunction = hasJunction && (cur.tile() == null || avoid.get(cur.tile().block()));

            if(!canPlace && !needJunction){
                continue;
            }

            if(canPlace){
                dp[i] = dp[i - 1] + conveyorCost;
            }else{
                dp[i] = dp[i - 1] + junctionCost;
            }
            parent[i] = i - 1;

            if(dp[i] < infCost && canPlace){
                dp[N + i] = dp[i] + bridgeCost;
                parent[N + i] = i - 1;
            }

            // Consider bridges from all previous positions
            if(i >= 2 && canPlace){
                int emptyPenalty = 0;
                if(placeable.get(plans.get(i - 1))){
                    emptyPenalty += bridgeOverEmptyPenalty;
                }

                for(int j = i - 2; j >= 0; j--){
                    var other = plans.get(j);
                    if(!bridge.positionsValid(cur.x, cur.y, other.x, other.y)){
                        break; // No need to check further back if this one is out of range
                    }

                    if(placeable.get(other)){
                        int cost = dp[N + j] + bridgeCost + emptyPenalty;
                        if(dp[N + i] > cost){
                            dp[N + i] = cost;
                            parent[N + i] = j;
                        }
                        emptyPenalty += bridgeOverEmptyPenalty;
                    }
                }
            }

            if(dp[N + i] < dp[i]){
                dp[i] = dp[N + i];
                parent[i] = parent[N + i];
            }

            if(canPlace && dp[i] >= infCost){
                // Unable to connect, restart a new segment
                dp[i] = 0;
                dp[N + i] = bridgeCost;
            }
        }

        // Backtrack to assign bridges
        int bridgeMode = 0;
        for(int i = N - 1; i >= 0; ){
            var cur = plans.get(i);
            int p = parent[bridgeMode + i];

            if(p == -1 || p == i - 1){
                // No connection, connected by conveyor, or junction, no bridge needed
                result.add(cur);
                bridgeMode = 0;
                i--;
            }else{
                // Connected by bridge, assign it
                var other = plans.get(p);
                bridge.applyToPlans(cur, other);
                result.add(cur);
                i = p;
                bridgeMode = N;
            }
        }

        result.reverse();
        plans.set(result);
    }

    public static void calculateBridges(Seq<BuildPlan> plans, ItemBridge bridge, boolean hasJunction, Boolf<Block> avoid){
        calculateBridges(plans, new ItemBridgePlacer(bridge), hasJunction, avoid);
    }

    public static void calculateBridges(Seq<BuildPlan> plans, DirectionBridge bridge, boolean hasJunction, Boolf<Block> avoid){
        calculateBridges(plans, new DirectionBridgePlacer(bridge), hasJunction, avoid);
    }

    private static float tileHeuristic(Tile tile, Tile other){
        Block block = control.input.block;

        if(!Build.validPlace(block, player.team(), other.x, other.y, -1)){ //-1 to allow placing right-facing conv on right-facing conv
            return 20;
            //why is this 20? I forgot how A* works but isn't that a bit low? Pathfinder uses 6000 right?
            //the planner seems to work fine anyway
        }else{
            if(parents.containsKey(tile.pos())){
                Tile prev = world.tile(parents.get(tile.pos(), 0));
                if(tile.relativeTo(prev) != other.relativeTo(tile)){
                    return 8;
                }
            }
        }
        return 1;
    }

    private static float distanceHeuristic(int x1, int y1, int x2, int y2){
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    private static boolean validNode(Tile tile, Tile other){
        Block block = control.input.block;
        if(block != null && block.canReplace(other.block())){
            return true;
        }else{
            return other.block().alwaysReplace;
        }
    }

    private static boolean astar(int startX, int startY, int endX, int endY){
        Tile start = world.tile(startX, startY);
        Tile end = world.tile(endX, endY);
        if(start == end || start == null || end == null) return false;

        costs.clear();
        closed.clear();
        parents.clear();

        int nodeLimit = 10000;
        int totalNodes = 0;

        PQueue<Tile> queue = new PQueue<>(10, (a, b) -> Float.compare(costs.get(a.pos(), 0f) + distanceHeuristic(a.x, a.y, end.x, end.y), costs.get(b.pos(), 0f) + distanceHeuristic(b.x, b.y, end.x, end.y)));
        queue.add(start);
        boolean found = false;
        while(!queue.empty() && totalNodes++ < nodeLimit){
            Tile next = queue.poll();
            float baseCost = costs.get(next.pos(), 0f);
            if(next == end){
                found = true;
                break;
            }
            closed.add(Point2.pack(next.x, next.y));
            for(Point2 point : Geometry.d4){
                int newx = next.x + point.x, newy = next.y + point.y;
                Tile child = world.tile(newx, newy);
                if(child != null && validNode(next, child)){
                    if(closed.add(child.pos())){
                        parents.put(child.pos(), next.pos());
                        costs.put(child.pos(), tileHeuristic(next, child) + baseCost);
                        queue.add(child);
                    }
                }
            }
        }

        if(!found) return false;
        int total = 0;

        points.add(Pools.obtain(Point2.class, Point2::new).set(endX, endY));

        Tile current = end;
        while(current != start && total++ < nodeLimit){
            if(current == null) return false;
            int newPos = parents.get(current.pos(), -1);

            if(newPos == -1) return false;

            points.add(Pools.obtain(Point2.class, Point2::new).set(Point2.x(newPos), Point2.y(newPos)));
            current = world.tile(newPos);
        }

        points.reverse();

        return true;
    }

    /**
     * Normalizes a placement area and returns the result, ready to be used for drawing a rectangle.
     * Returned x2 and y2 will <i>always</i> be greater than x and y.
     * @param block block that will be drawn
     * @param startx starting X coordinate
     * @param starty starting Y coordinate
     * @param endx ending X coordinate
     * @param endy ending Y coordinate
     * @param snap whether to snap to a line
     * @param maxLength maximum length of area
     */
    public static NormalizeDrawResult normalizeDrawArea(Block block, int startx, int starty, int endx, int endy, boolean snap, int maxLength, float scaling){
        normalizeArea(startx, starty, endx, endy, 0, snap, maxLength);

        float offset = block.offset;

        drawResult.x = result.x * tilesize;
        drawResult.y = result.y * tilesize;
        drawResult.x2 = result.x2 * tilesize;
        drawResult.y2 = result.y2 * tilesize;

        drawResult.x -= block.size * scaling * tilesize / 2;
        drawResult.x2 += block.size * scaling * tilesize / 2;


        drawResult.y -= block.size * scaling * tilesize / 2;
        drawResult.y2 += block.size * scaling * tilesize / 2;

        drawResult.x += offset;
        drawResult.y += offset;
        drawResult.x2 += offset;
        drawResult.y2 += offset;

        return drawResult;
    }

    /**
     * Normalizes a placement area and returns the result.
     * Returned x2 and y2 will <i>always</i> be greater than x and y.
     * @param tilex starting X coordinate
     * @param tiley starting Y coordinate
     * @param endx ending X coordinate
     * @param endy ending Y coordinate
     * @param snap whether to snap to a line
     * @param rotation placement rotation
     * @param maxLength maximum length of area
     */
    public static NormalizeResult normalizeArea(int tilex, int tiley, int endx, int endy, int rotation, boolean snap, int maxLength){
        if(snap){
            if(Math.abs(tilex - endx) > Math.abs(tiley - endy)){
                endy = tiley;
            }else{
                endx = tilex;
            }
        }

        if(maxLength > 0){
            if(Math.abs(endx - tilex) > maxLength){
                endx = Mathf.sign(endx - tilex) * maxLength + tilex;
            }

            if(Math.abs(endy - tiley) > maxLength){
                endy = Mathf.sign(endy - tiley) * maxLength + tiley;
            }
        }

        int dx = endx - tilex, dy = endy - tiley;

        if(Math.abs(dx) > Math.abs(dy)){
            if(dx >= 0){
                rotation = 0;
            }else{
                rotation = 2;
            }
        }else if(Math.abs(dx) < Math.abs(dy)){
            if(dy >= 0){
                rotation = 1;
            }else{
                rotation = 3;
            }
        }

        if(endx < tilex){
            int t = endx;
            endx = tilex;
            tilex = t;
        }
        if(endy < tiley){
            int t = endy;
            endy = tiley;
            tiley = t;
        }

        result.x2 = endx;
        result.y2 = endy;
        result.x = tilex;
        result.y = tiley;
        result.rotation = rotation;

        return result;
    }

    public static class NormalizeDrawResult{
        public float x, y, x2, y2;
    }

    public static class NormalizeResult{
        public int x, y, x2, y2, rotation;
    }
}
