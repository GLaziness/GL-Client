package mindustry.client.fallen.assistai;

import arc.Core;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.ai.UnitStance;
import mindustry.ai.types.CommandAI;
import mindustry.ai.types.FlyingAI;
import mindustry.ai.types.GroundAI;
import mindustry.ai.types.PrebuildAI;
import mindustry.entities.Units;
import mindustry.entities.units.*;
import mindustry.game.Team;
import mindustry.game.Teams.BlockPlan;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild;
import mindustry.world.meta.BlockGroup;

import static mindustry.Vars.*;

public class SelfBuilderAI extends AIController{
    public static float buildRadius = 1500f, retreatDst = 110f, retreatDelay = Time.toSeconds * 2f, defaultRebuildPeriod = 60f * 2f;

    // --- НАСТРОЙКИ ---
    public static boolean checkEnemyTurrets = Core.settings.getBool("poly-check-turrets", true);
    public static boolean checkResources = Core.settings.getBool("poly-check-res", true);
    public static boolean prioritizeDefenses = Core.settings.getBool("poly-prio-defense", true);
    public static boolean healDamaged = Core.settings.getBool("poly-heal", true);
    public static boolean findClosestPlan = Core.settings.getBool("poly-closest", true);
    public static boolean rebuildBlocks = Core.settings.getBool("poly-rebuild-blocks", true);
    /** GL: AFK mode, the unit goes mining after helping nobody for {@link #afkMineDelay} seconds. */
    public static boolean afkMine = Core.settings.getBool("poly-afk-mine", false);
    public static int afkMineDelay = Core.settings.getInt("poly-afk-delay", 5);

    public @Nullable Unit assistFollowing;
    public @Nullable Unit following;
    public @Nullable Teamc enemy;
    public @Nullable BlockPlan lastPlan;

    public float fleeRange = 370f, rebuildPeriod = defaultRebuildPeriod;
    public boolean alwaysFlee;
    public boolean onlyAssist;

    boolean found = false;
    /** GL: ticks spent with nothing to do, and the mining path started by the AFK mode. */
    private float idleTime;
    private @Nullable mindustry.client.navigation.MinePath afkPath;
    private final Interval workTimer = new Interval();
    /** GL: damaged block the unit is flying to and repairing. */
    public @Nullable Building healTarget;
    /** GL: how often an idle player unit looks for destroyed blocks, and how many it queues at once. */
    private static final float playerRebuildPeriod = 10f;
    private static final int maxQueued = 12;
    private final arc.struct.Seq<BlockPlan> queued = new arc.struct.Seq<>();
    private final arc.struct.FloatSeq queuedWeights = new arc.struct.FloatSeq();
    /** GL: plans this AI put into the queue. Everything else in the queue is the player's own, and the AI never removes it. */
    private final arc.struct.Seq<BuildPlan> aiPlans = new arc.struct.Seq<>();

    /** GL: the player queued something by hand, it is built first and the AI does not touch it. */
    private boolean hasOwnPlans(){
        for(BuildPlan plan : unit.plans){
            if(!aiPlans.contains(plan, true)) return true;
        }
        return false;
    }

    /** GL: scrap walls and mines are neither rebuilt nor healed. */
    public static boolean ignored(Block block){
        return block instanceof mindustry.world.blocks.defense.ShockMine || block.name.startsWith("scrap-wall");
    }

    private boolean isAiPlan(BuildPlan plan){
        return aiPlans.contains(plan, true);
    }

    /** GL: removes only the plans this AI added, the player's own plans stay. */
    public void clearAiPlans(){
        if(unit != null){
            for(BuildPlan plan : aiPlans) unit.plans.remove(plan, true);
        }
        aiPlans.clear();
    }

    private void addAiPlan(BuildPlan plan, boolean first){
        aiPlans.add(plan);
        if(first) unit.plans.addFirst(plan); else unit.addBuild(plan);
    }
    float retreatTimer;
    private static final float maxTurretCheckRange = 600f;

    /** GL: where the unit leaves a turret zone to, and the target the safe way below is planned for, see safeMoveTo. */
    private final Vec2 escapeTo = new Vec2();
    private @Nullable Position safeFor;
    private float safeRange, safeTime;
    private boolean safeFound;
    private final arc.struct.Seq<TurretBuild> threats = new arc.struct.Seq<>();
    /** GL: the way to the safe spot, around the turret zones; the last point is the spot itself. */
    private final arc.struct.Seq<Vec2> route = new arc.struct.Seq<>();
    private int routeAt;
    /** Largest grid for the way around, in cells: a bigger area gets bigger cells. */
    private static final int maxRouteCells = 90000;

    public SelfBuilderAI(boolean alwaysFlee, float fleeRange){
        this.alwaysFlee = alwaysFlee;
        this.fleeRange = fleeRange;
    }

    public SelfBuilderAI(){}

    @Override
    public void init(){
        if(rebuildPeriod == defaultRebuildPeriod && unit.team.rules().buildAi){
            rebuildPeriod = 10f;
        }
    }

    @Override
    public void updateMovement(){
        if(target != null && shouldShoot()){
            unit.lookAt(target);
        }else if(!unit.type.flying){
            unit.lookAt(unit.prefRotation());
        }

        unit.updateBuilding = true;

        // forget the plans that are done or were removed some other way
        aiPlans.removeAll(plan -> unit.plans.indexOf(plan, true) == -1);
        boolean own = hasOwnPlans();
        if(own){ // the player's own plans go first: stop helping until they are built
            following = null;
            clearAiPlans();
        }

        if(assistFollowing != null && !assistFollowing.isValid()) assistFollowing = null;
        if(following != null && !following.isValid()) following = null;

        // Проверяем валидность assistFollowing
        if(assistFollowing != null){
            Player p = assistFollowing.getPlayer();
            if(p == null || !PolyFilter.canAssist(p)){
                assistFollowing = null;
            }else if(assistFollowing.activelyBuilding() && !own){
                following = assistFollowing;
            }
        }

        boolean moving = false;
        boolean hold = hasStance(UnitStance.holdPosition);

        // GL: got into enemy turret range anyway (a new turret, knocked in, the player was there): leave it first
        if(checkEnemyTurrets && !hold){
            TurretBuild threat = threatAt(unit.x, unit.y);
            if(threat != null){
                float out = ((Turret)threat.block).range + unit.hitSize + 16f + tilesize * 3f;
                escapeTo.set(unit.x - threat.x, unit.y - threat.y);
                if(escapeTo.isZero()) escapeTo.set(1f, 0f);
                escapeTo.setLength(out).add(threat.x, threat.y);
                moveTo(escapeTo, 0f);
                if(!unit.type.flying) unit.updateBoosting(true);
                return;
            }
        }

        // 1. СЛЕДОВАНИЕ ЗА ДРУГИМ
        if(following != null){
            retreatTimer = 0f;

            // ИСПРАВЛЕНИЕ: Проверяем, что тот, за кем мы следуем — разрешенный игрок!
            Player p = following.getPlayer();
            if(!following.isValid() || !following.activelyBuilding() || p == null || !PolyFilter.canAssist(p)){
                following = null;
                clearAiPlans();
                return;
            }

            BuildPlan fPlan = following.buildPlan();
            if(fPlan != null && isPlanSafeAndAffordable(fPlan)){
                if(unit.buildPlan() != fPlan){
                    clearAiPlans();
                    addAiPlan(fPlan, true);
                }
                lastPlan = null;
            }else{
                following = null;
                clearAiPlans();
                return;
            }
        }else if((unit.buildPlan() == null || alwaysFlee) && !hold){
            // Отступление при опасности
            if(timer.get(timerTarget4, 40)){
                enemy = target(unit.x, unit.y, fleeRange, true, true);
            }

            if((retreatTimer += Time.delta) >= retreatDelay || alwaysFlee){
                if(enemy != null){
                    clearAiPlans();
                    var core = unit.closestCore();
                    if(core != null && !unit.within(core, retreatDst)){
                        moveTo(core, retreatDst);
                        moving = true;
                    }
                }
            }
        }

        // 2. ВЫПОЛНЕНИЕ ТЕКУЩЕГО ПЛАНА ПОСТРОЙКИ
        if(unit.buildPlan() != null){
            if(!alwaysFlee) retreatTimer = 0f;
            BuildPlan req = unit.buildPlan();
            boolean aiPlan = isAiPlan(req);

            if(aiPlan && !isPlanSafeAndAffordable(req)){
                unit.plans.removeFirst();
                lastPlan = null;
                return;
            }

            // Отмена разборки, если другой игрок ломает
            if(aiPlan && !req.breaking && timer.get(timerTarget2, 40f)){
                for(Player player : Groups.player){
                    if(player.isBuilder() && player.unit().activelyBuilding() && player.unit().buildPlan().samePos(req) && player.unit().buildPlan().breaking){
                        unit.plans.removeFirst();
                        unit.team.data().plans.remove(bp -> bp.x == req.x && bp.y == req.y);
                        return;
                    }
                }
            }

            boolean valid = !(lastPlan != null && lastPlan.removed) &&
                    ((req.tile() != null && req.tile().build instanceof ConstructBuild cons && cons.current == req.block) ||
                            (req.breaking ? Build.validBreak(unit.team(), req.x, req.y) : Build.validPlace(req.block, unit.team(), req.x, req.y, req.rotation)));

            if(valid){
                if(!hold){
                    float range = Math.min(unit.type.buildRange - unit.type.hitSize * 2f, buildRadius);
                    if(!safeMoveTo(req.tile(), range, 20f)){
                        // no way to reach it without turrets on the way: drop what the AI took, the player's own plan just waits
                        if(aiPlan){
                            unit.plans.removeFirst();
                            lastPlan = null;
                            if(following != null) following = null;
                        }
                        return;
                    }
                    moving = !unit.within(req.tile(), range);
                }else if(aiPlan && !unit.within(req, unit.type.buildRange - tilesize) && !state.rules.infiniteResources){
                    unit.plans.removeFirst();
                    lastPlan = null;
                }
            }else if(aiPlan){ // the player's own invalid plans are dropped by the builder itself, as without the AI
                unit.plans.removeFirst();
                lastPlan = null;
            }
        }else{
            // 3. ЕСЛИ НЕТ ПЛАНА - ИЩЕМ ИГРОКА ДЛЯ ПОМОЩИ
            if(assistFollowing != null && !hold){
                if(safeMoveTo(assistFollowing, assistFollowing.type.hitSize + unit.type.hitSize/2f + 60f, 100f)){
                    moving = !unit.within(assistFollowing, assistFollowing.type.hitSize + unit.type.hitSize/2f + 65f);
                }
            }

            if(timer.get(timerTarget2, 20f)){
                found = false;

                // --- 1. Поиск ближайшего строителя-ИГРОКА поблизости ---
                Units.nearby(unit.team, unit.x, unit.y, buildRadius, u -> {
                    if(found) return;

                    // ИСПРАВЛЕНИЕ: помогаем ТОЛЬКО живым игрокам, прошедшим фильтр
                    if(u.canBuild() && u != unit && u.activelyBuilding() && u.isPlayer()){
                        Player player = u.getPlayer();
                        if(player == null || !PolyFilter.canAssist(player)) return;

                        BuildPlan plan = u.buildPlan();
                        if(plan == null || !isPlanSafeAndAffordable(plan)) return;

                        Building build = world.build(plan.x, plan.y);
                        if(build instanceof ConstructBuild cons){
                            float dist = Math.min(cons.dst(unit) - unit.type.buildRange, 0);
                            if(dist / unit.speed() < cons.buildCost * 0.9f){
                                following = u;
                                found = true;
                            }
                        }
                    }
                });

                // --- 2. Поиск игрока в режиме onlyAssist ---
                if(onlyAssist){
                    float minDst = Float.MAX_VALUE;
                    Player closest = null;
                    for(var player : Groups.player){
                        if(!player.dead() && player.isBuilder() && player.team() == unit.team){
                            if(!PolyFilter.canAssist(player)) continue;

                            float dst = player.dst2(unit);
                            if(dst < minDst){
                                closest = player;
                                minDst = dst;
                            }
                        }
                    }
                    assistFollowing = closest == null ? null : closest.unit();
                }
            }

            // 4. ПОИСК УНИЧТОЖЕННЫХ БЛОКОВ В ОЧЕРЕДИ СТРОЙКИ
            // GL: the player's unit takes the next blocks right away (vanilla builder AI waits rebuildPeriod = 2 s after every block)
            // and queues several at once, so the builder component keeps building whatever is in range without idle gaps.
            if(!onlyAssist && rebuildBlocks && !unit.team.data().plans.isEmpty() && following == null && timer.get(timerTarget3, playerRebuildPeriod)){
                var blocks = unit.team.data().plans;
                queued.clear();
                queuedWeights.clear();

                for(int i = 0; i < blocks.size; i++){
                    BlockPlan bp = blocks.get(i);
                    if(world.tile(bp.x, bp.y) != null && world.tile(bp.x, bp.y).block() == bp.block){
                        blocks.removeIndex(i);
                        i--;
                        continue;
                    }

                    if(ignored(bp.block)) continue;
                    if(!Build.validPlace(bp.block, unit.team(), bp.x, bp.y, bp.rotation)) continue;
                    if(checkEnemyTurrets && isInEnemyTurretRange(bp.x * tilesize, bp.y * tilesize)) continue;
                    if(checkResources && !hasResources(bp.block)) continue;
                    if(alwaysFlee && nearEnemy(bp.x, bp.y)) continue;

                    if(hold && !unit.within(bp.x * tilesize, bp.y * tilesize, unit.type.buildRange)) continue;

                    float dist = unit.dst2(bp.x * tilesize, bp.y * tilesize);

                    float priorityMultiplier = 1f;
                    if(prioritizeDefenses){
                        if(bp.block.group == BlockGroup.turrets || bp.block.group == BlockGroup.walls) priorityMultiplier = 0.3f;
                        else if(bp.block.group == BlockGroup.power) priorityMultiplier = 0.5f;
                    }

                    float weight = dist * priorityMultiplier;
                    if(!findClosestPlan){
                        queued.add(bp);
                        if(queued.size >= maxQueued) break;
                        continue;
                    }

                    // keep the maxQueued lightest plans, sorted by weight
                    if(queued.size >= maxQueued && weight >= queuedWeights.peek()) continue;
                    int at = 0;
                    while(at < queuedWeights.size && queuedWeights.get(at) <= weight) at++;
                    queued.insert(at, bp);
                    queuedWeights.insert(at, weight);
                    if(queued.size > maxQueued){
                        queued.pop();
                        queuedWeights.pop();
                    }
                }

                if(queued.any()){
                    lastPlan = queued.first();
                    for(BlockPlan bp : queued){
                        addAiPlan(new BuildPlan(bp.x, bp.y, bp.rotation, bp.block, bp.config), false);
                        // plans taken now go to the end of the team queue, so other builders get different ones
                        blocks.remove(bp, true);
                        blocks.addLast(bp);
                    }
                }
            }

            // 5. АВТО-ЛЕЧЕНИЕ ПОВРЕЖДЕННЫХ БЛОКОВ
            // GL: the target is kept between searches and followed every frame (it used to move for a single frame out of 30),
            // the actual shooting is done by the input handler, see healing()
            if(healDamaged && unit.type.canHeal && unit.buildPlan() == null && following == null && !hold){
                if(timer.get(timerTarget, 30f) || (healTarget != null && !(healTarget.isValid() && healTarget.damaged() && !ignored(healTarget.block)))){
                    Building damaged = null;
                    float best = Float.MAX_VALUE;
                    for(Building b : indexer.getDamaged(unit.team)){
                        if(ignored(b.block)) continue;
                        float dst = b.dst2(unit);
                        if(dst < best){
                            best = dst;
                            damaged = b;
                        }
                    }
                    healTarget = damaged != null && damaged.within(unit, buildRadius) && !isInEnemyTurretRange(damaged.x, damaged.y) ? damaged : null;
                }
                if(healTarget != null){
                    if(safeMoveTo(healTarget, healRange() * 0.7f, 100f)){
                        moving = !unit.within(healTarget, healRange());
                    }else{
                        healTarget = null;
                    }
                }
            }else{
                healTarget = null;
            }
        }

        if(!unit.type.flying){
            unit.updateBoosting(unit.type.boostWhenBuilding || moving || unit.floorOn().isDuct || unit.floorOn().damageTaken > 0f || unit.floorOn().isDeep());
        }
    }

    private float healRange(){
        return Math.max(unit.type.range, tilesize * 3f);
    }

    /** GL: the unit is close enough to its heal target to shoot it. */
    public boolean healing(){
        return healTarget != null && unit != null && healTarget.isValid() && healTarget.damaged() && unit.within(healTarget, healRange());
    }

    // region GL: AFK mining

    /** Called every frame in poly mode instead of {@link #updateMovement()} decisions: mine while idle, come back when there is work. */
    public boolean updateAfk(){
        if(afkPath != null && mindustry.client.navigation.Navigation.currentlyFollowing != afkPath){
            // the player stopped or replaced the path by hand
            afkPath = null;
            idleTime = 0f;
        }

        if(afkPath != null){
            if(!afkMine || workTimer.get(30f) && hasWork()) stopAfk();
            return afkPath != null;
        }

        boolean idle = unit.plans.isEmpty() && following == null && healTarget == null && (assistFollowing == null || !assistFollowing.activelyBuilding());
        idleTime = idle ? idleTime + Time.delta : 0f;

        if(afkMine && idleTime >= afkMineDelay * 60f && unit.canMine() && unit.type.mineTier >= 0 && unit.closestCore() != null
            && mindustry.client.navigation.Navigation.currentlyFollowing == null){
            arc.struct.Seq<Item> items = mindustry.client.ui.PanelFragment.itemtomine.isEmpty() ?
                unit.type.mineItems.select(unit::canMine) : mindustry.client.ui.PanelFragment.itemtomine.copy();
            if(items.isEmpty()) return false;
            afkPath = new mindustry.client.navigation.MinePath(items, -1, false, "", true);
            // the unit hovers up to its mine range away from the ore, so keep that much more away from enemy turrets
            afkPath.setOreFilter(t -> !isInEnemyTurretRange(t.worldx(), t.worldy(), unit.type.mineRange));
            mindustry.client.navigation.Navigation.follow(afkPath);
            return true;
        }
        return false;
    }

    public boolean afkMining(){
        return afkPath != null;
    }

    public void stopAfk(){
        if(afkPath != null && mindustry.client.navigation.Navigation.currentlyFollowing == afkPath){
            mindustry.client.navigation.Navigation.stopFollowing();
        }
        afkPath = null;
        idleTime = 0f;
        if(unit != null){
            unit.mineTile = null;
        }
    }

    /** Something to rebuild, a damaged block to heal or a player to help nearby. */
    private boolean hasWork(){
        if(rebuildBlocks && !onlyAssist){
            for(BlockPlan bp : unit.team.data().plans){
                Tile tile = world.tile(bp.x, bp.y);
                if(tile == null || tile.block() == bp.block || ignored(bp.block)) continue;
                if(!Build.validPlace(bp.block, unit.team(), bp.x, bp.y, bp.rotation)) continue;
                if(checkEnemyTurrets && isInEnemyTurretRange(bp.x * tilesize, bp.y * tilesize)) continue;
                if(checkResources && !hasResources(bp.block)) continue;
                return true;
            }
        }

        if(healDamaged && unit.type.canHeal){
            for(Building b : indexer.getDamaged(unit.team)){
                if(!ignored(b.block) && b.within(unit, buildRadius) && !isInEnemyTurretRange(b.x, b.y)) return true;
            }
        }

        for(Player p : Groups.player){
            if(p.unit() == unit || p.team() != unit.team || p.dead() || !PolyFilter.canAssist(p)) continue;
            Unit u = p.unit();
            if(u.activelyBuilding() && u.within(unit, buildRadius) && isPlanSafeAndAffordable(u.buildPlan())) return true;
        }
        return false;
    }

    // endregion

    public boolean isPlanSafeAndAffordable(BuildPlan plan){
        if(plan == null) return false;
        float wx = plan.x * tilesize, wy = plan.y * tilesize;

        if(checkEnemyTurrets && isInEnemyTurretRange(wx, wy)){
            return false;
        }

        if(checkResources && !plan.breaking && plan.block != null && !hasResources(plan.block)){
            return false;
        }

        return true;
    }

    public boolean isInEnemyTurretRange(float wx, float wy){
        return isInEnemyTurretRange(wx, wy, 0f);
    }

    public boolean isInEnemyTurretRange(float wx, float wy, float margin){
        return threatAt(wx, wy, margin) != null;
    }

    /** GL: a turret of this kind can shoot the unit: turrets for ground only leave a flying unit alone and back. */
    private boolean canHit(Turret t){
        return unit.isFlying() ? t.targetAir : t.targetGround;
    }

    private @Nullable TurretBuild threatAt(float wx, float wy){
        return threatAt(wx, wy, 0f);
    }

    private @Nullable TurretBuild threatAt(float wx, float wy, float margin){
        for(var teamData : state.teams.present){
            if(teamData.team != unit.team && teamData.team != Team.derelict){
                var tree = teamData.buildingTree;
                if(tree != null){
                    float check = maxTurretCheckRange + margin;
                    Building danger = tree.find(wx - check, wy - check, check * 2f, check * 2f, b -> {
                        if(b instanceof TurretBuild tb && tb.block instanceof Turret t && canHit(t)){
                            return tb.within(wx, wy, t.range + unit.hitSize + 16f + margin);
                        }
                        return false;
                    });
                    if(danger instanceof TurretBuild tb) return tb;
                }
            }
        }
        return null;
    }

    /**
     * GL: like moveTo, but the unit never flies into enemy turret range. It stops next to the target on a spot out of
     * range and flies there straight when it can, otherwise around the turret zones.
     * @return false when there is no such spot or way: the target can not be reached safely.
     */
    private boolean safeMoveTo(Position target, float range, float smooth){
        if(!checkEnemyTurrets || unit.within(target, range + 1f)){
            route.clear();
            moveTo(target, range, smooth);
            return true;
        }
        if(safeFor != target || Math.abs(safeRange - range) > 1f || (safeTime += Time.delta) >= 45f){
            safeFor = target;
            safeRange = range;
            safeTime = 0f;
            safeFound = planRoute(target.getX(), target.getY(), Math.max(range * 0.9f, 0f));
        }
        if(!safeFound || route.isEmpty()) return false;

        while(routeAt < route.size - 1 && unit.within(route.get(routeAt), tilesize * 1.5f)) routeAt++;
        Vec2 next = route.get(routeAt);
        if(routeAt < route.size - 1){
            moveTo(next, 0f); // a corner of the way around: full speed through it
        }else{
            moveTo(next, 1f, smooth);
        }
        return true;
    }

    private boolean planRoute(float tx, float ty, float radius){
        route.clear();
        routeAt = 0;

        // the turrets anywhere near the way and around the target, gathered once for all the checks below
        threats.clear();
        float pad = maxTurretCheckRange + radius;
        float minX = Math.min(unit.x, tx) - pad, minY = Math.min(unit.y, ty) - pad;
        float w = Math.abs(unit.x - tx) + pad * 2f, h = Math.abs(unit.y - ty) + pad * 2f;
        for(var teamData : state.teams.present){
            if(teamData.team == unit.team || teamData.team == Team.derelict || teamData.buildingTree == null) continue;
            teamData.buildingTree.intersect(minX, minY, w, h, b -> {
                if(b instanceof TurretBuild tb && tb.block instanceof Turret t && canHit(t)) threats.add(tb);
            });
        }
        if(threats.isEmpty()){
            Vec2 spot = new Vec2(tx, ty).sub(unit.x, unit.y);
            spot.setLength(Math.max(spot.len() - radius, 0f)).add(unit.x, unit.y);
            route.add(spot);
            return true;
        }

        // a spot straight away: the near side first, then more and more around the target
        float base = Angles.angle(tx, ty, unit.x, unit.y);
        for(int i = 0; i <= 8; i++){
            for(int sign = 1; sign >= -1; sign -= 2){
                if(i == 0 && sign < 0) continue;
                float a = base + sign * i * 22.5f;
                float x = tx + Angles.trnsx(a, radius), y = ty + Angles.trnsy(a, radius);
                if(!threatened(x, y) && pathSafe(unit.x, unit.y, x, y)){
                    route.add(new Vec2(x, y));
                    return true;
                }
            }
        }
        return planAround(tx, ty, radius);
    }

    /** A* on a coarse grid with the turret zones blocked, to any free cell within {@code radius} of the target. */
    private boolean planAround(float tx, float ty, float radius){
        float worldW = world.unitWidth(), worldH = world.unitHeight();
        float pad = maxTurretCheckRange + tilesize * 10f;
        float x0 = Mathf.clamp(Math.min(unit.x, tx) - pad, 0f, worldW), y0 = Mathf.clamp(Math.min(unit.y, ty) - pad, 0f, worldH);
        float x1 = Mathf.clamp(Math.max(unit.x, tx) + pad, 0f, worldW), y1 = Mathf.clamp(Math.max(unit.y, ty) + pad, 0f, worldH);
        float cell = tilesize * 2f;
        float area = (x1 - x0) * (y1 - y0);
        if(area / (cell * cell) > maxRouteCells) cell = (float)Math.sqrt(area / maxRouteCells) + 1f;
        int gw = Math.max(1, Mathf.ceil((x1 - x0) / cell)), gh = Math.max(1, Mathf.ceil((y1 - y0) / cell));
        int total = gw * gh;

        // blocked cells: a cell counts when any of it can be in range
        boolean[] blocked = new boolean[total];
        for(TurretBuild tb : threats){
            float r = ((Turret)tb.block).range + unit.hitSize + 16f + cell * 0.75f;
            int cx0 = Math.max(0, (int)((tb.x - r - x0) / cell)), cx1 = Math.min(gw - 1, (int)((tb.x + r - x0) / cell));
            int cy0 = Math.max(0, (int)((tb.y - r - y0) / cell)), cy1 = Math.min(gh - 1, (int)((tb.y + r - y0) / cell));
            for(int cy = cy0; cy <= cy1; cy++){
                for(int cx = cx0; cx <= cx1; cx++){
                    if(Mathf.dst(x0 + (cx + 0.5f) * cell, y0 + (cy + 0.5f) * cell, tb.x, tb.y) <= r) blocked[cx + cy * gw] = true;
                }
            }
        }

        int sx = Mathf.clamp((int)((unit.x - x0) / cell), 0, gw - 1), sy = Mathf.clamp((int)((unit.y - y0) / cell), 0, gh - 1);
        int start = sx + sy * gw;
        float[] cost = new float[total];
        int[] parent = new int[total];
        java.util.Arrays.fill(cost, Float.MAX_VALUE);
        cost[start] = 0f;
        parent[start] = -1;
        java.util.PriorityQueue<Long> open = new java.util.PriorityQueue<>();
        open.add(key(0f, start));
        int goal = -1;

        while(!open.isEmpty()){
            long k = open.poll();
            int at = (int)(k & 0xffffffffL);
            float f = Float.intBitsToFloat((int)(k >>> 32));
            int ax = at % gw, ay = at / gw;
            float cxw = x0 + (ax + 0.5f) * cell, cyw = y0 + (ay + 0.5f) * cell;
            if(f > cost[at] + Math.max(Mathf.dst(cxw, cyw, tx, ty) - radius, 0f) / cell + 0.001f) continue; // stale entry
            if(at != start && !blocked[at] && Mathf.within(cxw, cyw, tx, ty, radius)){
                goal = at;
                break;
            }
            for(int dx = -1; dx <= 1; dx++){
                for(int dy = -1; dy <= 1; dy++){
                    if(dx == 0 && dy == 0) continue;
                    int nx = ax + dx, ny = ay + dy;
                    if(nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue;
                    int n = nx + ny * gw;
                    if(blocked[n]) continue;
                    // no cutting a corner of a zone
                    if(dx != 0 && dy != 0 && (blocked[ax + dx + ay * gw] || blocked[ax + (ay + dy) * gw])) continue;
                    float c = cost[at] + (dx != 0 && dy != 0 ? 1.4142f : 1f);
                    if(c >= cost[n]) continue;
                    cost[n] = c;
                    parent[n] = at;
                    float hx = x0 + (nx + 0.5f) * cell, hy = y0 + (ny + 0.5f) * cell;
                    open.add(key(c + Math.max(Mathf.dst(hx, hy, tx, ty) - radius, 0f) / cell, n));
                }
            }
        }
        if(goal == -1) return false;

        arc.struct.Seq<Vec2> cells = new arc.struct.Seq<>();
        for(int at = goal; at != -1 && at != start; at = parent[at]){
            cells.add(new Vec2(x0 + (at % gw + 0.5f) * cell, y0 + (at / gw + 0.5f) * cell));
        }
        cells.reverse();

        // keep only the corners: from each point, straight to the farthest one that is still safe
        float px = unit.x, py = unit.y;
        int i = 0;
        while(i < cells.size){
            int far = i;
            while(far + 1 < cells.size && pathSafe(px, py, cells.get(far + 1).x, cells.get(far + 1).y)) far++;
            Vec2 corner = cells.get(far);
            route.add(corner);
            px = corner.x;
            py = corner.y;
            i = far + 1;
        }
        return route.any();
    }

    private static long key(float f, int index){
        return ((long)Float.floatToIntBits(f) << 32) | (index & 0xffffffffL);
    }

    private boolean threatened(float x, float y){
        for(TurretBuild tb : threats){
            if(tb.within(x, y, ((Turret)tb.block).range + unit.hitSize + 16f)) return true;
        }
        return false;
    }

    private boolean pathSafe(float x1, float y1, float x2, float y2){
        float dst = Mathf.dst(x1, y1, x2, y2);
        int steps = Math.max(1, Mathf.ceil(dst / (tilesize * 2f)));
        for(int i = 1; i <= steps; i++){
            float f = i / (float)steps;
            if(threatened(Mathf.lerp(x1, x2, f), Mathf.lerp(y1, y2, f))) return false;
        }
        return true;
    }

    public boolean hasResources(Block block){
        if(state.rules.infiniteResources || block.requirements == null) return true;
        Building core = unit.closestCore();
        if(core == null || core.items == null) return false;

        for(ItemStack stack : block.requirements){
            if(!core.items.has(stack.item, 1)){
                return false;
            }
        }
        return true;
    }

    protected boolean nearEnemy(int x, int y){
        return Units.nearEnemy(unit.team, x * tilesize - fleeRange/2f, y * tilesize - fleeRange/2f, fleeRange, fleeRange);
    }

    @Override
    public AIController fallback(){
        if(unit.team.isAI() && unit.team.rules().prebuildAi){
            return new PrebuildAI();
        }
        return unit.type.flying ? new FlyingAI() : new GroundAI();
    }

    @Override
    public boolean useFallback(){
        if(unit.team.isAI() && unit.team.rules().prebuildAi){
            return true;
        }
        return state.rules.waves && unit.team == state.rules.waveTeam && !unit.team.rules().rtsAi;
    }

    @Override
    public boolean shouldFire(){
        return !(unit.controller() instanceof CommandAI ai) || ai.shouldFire();
    }

    @Override
    public boolean shouldShoot(){
        return !unit.isBuilding() && unit.type.canAttack;
    }
}