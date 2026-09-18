package mindustry.client.fallen.assistai;

import arc.Core;
import arc.math.geom.Geometry;
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

    public @Nullable Unit assistFollowing;
    public @Nullable Unit following;
    public @Nullable Teamc enemy;
    public @Nullable BlockPlan lastPlan;

    public float fleeRange = 370f, rebuildPeriod = defaultRebuildPeriod;
    public boolean alwaysFlee;
    public boolean onlyAssist;

    boolean found = false;
    float retreatTimer;
    private static final float maxTurretCheckRange = 600f;

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

        if(assistFollowing != null && !assistFollowing.isValid()) assistFollowing = null;
        if(following != null && !following.isValid()) following = null;

        // Проверяем валидность assistFollowing
        if(assistFollowing != null){
            Player p = assistFollowing.getPlayer();
            if(p == null || !PolyFilter.canAssist(p)){
                assistFollowing = null;
            }else if(assistFollowing.activelyBuilding()){
                following = assistFollowing;
            }
        }

        boolean moving = false;
        boolean hold = hasStance(UnitStance.holdPosition);

        // 1. СЛЕДОВАНИЕ ЗА ДРУГИМ
        if(following != null){
            retreatTimer = 0f;

            // ИСПРАВЛЕНИЕ: Проверяем, что тот, за кем мы следуем — разрешенный игрок!
            Player p = following.getPlayer();
            if(!following.isValid() || !following.activelyBuilding() || p == null || !PolyFilter.canAssist(p)){
                following = null;
                unit.plans.clear();
                return;
            }

            BuildPlan fPlan = following.buildPlan();
            if(fPlan != null && isPlanSafeAndAffordable(fPlan)){
                unit.plans.clear();
                unit.plans.addFirst(fPlan);
                lastPlan = null;
            }else{
                following = null;
                unit.plans.clear();
                return;
            }
        }else if((unit.buildPlan() == null || alwaysFlee) && !hold){
            // Отступление при опасности
            if(timer.get(timerTarget4, 40)){
                enemy = target(unit.x, unit.y, fleeRange, true, true);
            }

            if((retreatTimer += Time.delta) >= retreatDelay || alwaysFlee){
                if(enemy != null){
                    unit.clearBuilding();
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

            if(!isPlanSafeAndAffordable(req)){
                unit.plans.removeFirst();
                lastPlan = null;
                return;
            }

            // Отмена разборки, если другой игрок ломает
            if(!req.breaking && timer.get(timerTarget2, 40f)){
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
                    moveTo(req.tile(), range, 20f);
                    moving = !unit.within(req.tile(), range);
                }else if(!unit.within(req, unit.type.buildRange - tilesize) && !state.rules.infiniteResources){
                    unit.plans.removeFirst();
                    lastPlan = null;
                }
            }else{
                unit.plans.removeFirst();
                lastPlan = null;
            }
        }else{
            // 3. ЕСЛИ НЕТ ПЛАНА - ИЩЕМ ИГРОКА ДЛЯ ПОМОЩИ
            if(assistFollowing != null && !hold){
                moveTo(assistFollowing, assistFollowing.type.hitSize + unit.type.hitSize/2f + 60f);
                moving = !unit.within(assistFollowing, assistFollowing.type.hitSize + unit.type.hitSize/2f + 65f);
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
            if(!onlyAssist && rebuildBlocks && !unit.team.data().plans.isEmpty() && following == null && timer.get(timerTarget3, rebuildPeriod)){
                var blocks = unit.team.data().plans;

                BlockPlan bestPlan = null;
                int bestIndex = 0;
                float bestWeight = Float.MAX_VALUE;

                for(int i = 0; i < blocks.size; i++){
                    BlockPlan bp = blocks.get(i);
                    if(world.tile(bp.x, bp.y) != null && world.tile(bp.x, bp.y).block() == bp.block){
                        blocks.removeIndex(i);
                        i--;
                        continue;
                    }

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
                    if(weight < bestWeight){
                        bestWeight = weight;
                        bestPlan = bp;
                        bestIndex = i;
                        if(!findClosestPlan) break;
                    }
                }

                if(bestPlan != null){
                    lastPlan = bestPlan;
                    unit.addBuild(new BuildPlan(bestPlan.x, bestPlan.y, bestPlan.rotation, bestPlan.block, bestPlan.config));
                    blocks.addLast(blocks.removeIndex(bestIndex));
                }
            }

            // 5. АВТО-ЛЕЧЕНИЕ ПОВРЕЖДЕННЫХ БЛОКОВ
            if(healDamaged && unit.type.canHeal && unit.buildPlan() == null && following == null && !hold){
                if(timer.get(timerTarget, 30f)){
                    Building damaged = Geometry.findClosest(unit.x, unit.y, indexer.getDamaged(unit.team));
                    if(damaged != null && damaged.within(unit, buildRadius) && !isInEnemyTurretRange(damaged.x, damaged.y)){
                        moveTo(damaged, unit.type.buildRange * 0.7f);
                        unit.aim(damaged);
                    }
                }
            }
        }

        if(!unit.type.flying){
            unit.updateBoosting(unit.type.boostWhenBuilding || moving || unit.floorOn().isDuct || unit.floorOn().damageTaken > 0f || unit.floorOn().isDeep());
        }
    }

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
        for(var teamData : state.teams.present){
            if(teamData.team != unit.team && teamData.team != Team.derelict){
                var tree = teamData.buildingTree;
                if(tree != null){
                    Building danger = tree.find(wx - maxTurretCheckRange, wy - maxTurretCheckRange, maxTurretCheckRange * 2f, maxTurretCheckRange * 2f, b -> {
                        if(b instanceof TurretBuild tb && tb.block instanceof Turret t){
                            return tb.within(wx, wy, t.range + unit.hitSize + 16f);
                        }
                        return false;
                    });
                    if(danger != null) return true;
                }
            }
        }
        return false;
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