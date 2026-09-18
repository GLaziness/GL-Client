package mindustry.client.fallen;

import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.Timer;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Unit;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.storage.CoreBlock;

import java.util.Objects;

import static mindustry.Vars.control;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.world;

public class CustomBuildLogic {

    private static final Seq<FDConfigRequest> configQueue = new Seq<>();
    private static float configTimer = 0f;
    private static final float CONFIG_DELAY = 10f;

    private static final Seq<FDRotateRequest> rotateQueue = new Seq<>();
    private static float rotateTimer = 0f;
    private static final float ROTATE_DELAY = 10f;

    public static void update() {
        if (state.isPaused() || !state.isGame()) return;

        updateConfigQueue();
        updateRotateQueue();
    }

    private static void updateConfigQueue() {
        if (configQueue.isEmpty()) return;

        configTimer += Time.delta;
        if (configTimer < CONFIG_DELAY) return;
        configTimer = 0f;

        // Берем первый запрос, но НЕ удаляем его пока не обработаем
        FDConfigRequest req = configQueue.first();
        Building building = req.resolve();

        // Если здание не найдено (например, еще строится, снесено или заменено другим блоком)
        if (building == null) {
            req.attempts++;
            if (req.attempts > 100) {
                configQueue.remove(0);
            }
            return;
        }

        configQueue.remove(0);

        Object currentConfig = (building instanceof ConstructBlock.ConstructBuild co) ? co.lastConfig : building.config();

        if (!Objects.equals(currentConfig, req.config)) {
            Call.tileConfig(player, building, req.config);
        }
    }

    private static void updateRotateQueue() {
        if (rotateQueue.isEmpty()) return;

        rotateTimer += Time.delta;
        if (rotateTimer < ROTATE_DELAY) return;
        rotateTimer = 0f;

        FDRotateRequest req = rotateQueue.first();
        Building building = req.resolve();

        if (building == null) {
            req.attempts++;
            if (req.attempts > 100) {
                rotateQueue.remove(0);
            }
            return;
        }

        // Блок ещё в процессе стройки — поворот не имеет смысла, ждём завершения
        if (building instanceof ConstructBlock.ConstructBuild) {
            req.attempts++;
            if (req.attempts > 100) {
                rotateQueue.remove(0);
            }
            return;
        }

        if (building.rotation == req.target) {
            rotateQueue.remove(0);
            return;
        }

        req.attempts++;
        if (req.attempts > 20) {
            // не удаётся довернуть за разумное время — сдаёмся, чтобы не спамить
            rotateQueue.remove(0);
            return;
        }

        int diff = ((req.target - building.rotation) % 4 + 4) % 4;
        boolean clockwise = diff <= 2; // при diff == 2 направление не важно, выбираем по часовой
        Call.rotateBlock(player, building, clockwise);
    }

    private static boolean isProtected(Building b) {
        if (b == null) return false;
        if (b.block instanceof CoreBlock) return true;

        // блоки, которые не должны сноситься (источники/поглотители, спавн)
        if (b.block == Blocks.spawn ||
                b.block == Blocks.powerSource || b.block == Blocks.powerVoid ||
                b.block == Blocks.itemSource || b.block == Blocks.itemVoid ||
                b.block == Blocks.liquidSource || b.block == Blocks.liquidVoid) {
            return true;
        }

        return false;
    }

    /**
     * Турели наводятся автоматически, вручную довернуть их через Call.rotateBlock нельзя,
     * поэтому для них разница в повороте не должна считаться причиной сноса/доворота.
     */
    private static boolean isRotationIgnorable(Block block) {
        return block instanceof Turret;
    }

    private static boolean isUnplaceable(Block block) {
        if (block instanceof CoreBlock) return true;
        if (block == Blocks.spawn || block == Blocks.powerVoid) return true;
        return false;
    }

    private static class FDConfigRequest {
        final int x, y;
        final Object config;
        final Team team;
        final Block expectedBlock;
        int attempts = 0;

        FDConfigRequest(Building b, Object c) {
            this.x = b.tileX();
            this.y = b.tileY();
            this.config = c;
            this.team = b.team;
            this.expectedBlock = b.block;
        }

        Building resolve() {
            Building b = world.build(x, y);
            // Проверяем не только команду, но и что это всё ещё тот же блок:
            // иначе конфиг может улететь на здание, построенное на месте снесённого.
            if (b != null && b.team == team && b.block == expectedBlock) return b;
            return null;
        }
    }

    private static class FDRotateRequest {
        final int x, y, target;
        final Team team;
        final Block expectedBlock;
        int attempts = 0;

        FDRotateRequest(Building b, int target) {
            this.x = b.tileX();
            this.y = b.tileY();
            this.target = target;
            this.team = b.team;
            this.expectedBlock = b.block;
        }

        Building resolve() {
            Building b = world.build(x, y);
            if (b != null && b.team == team && b.block == expectedBlock) return b;
            return null;
        }
    }

    private static void queueConfig(Building building, Object config) {
        if (building == null) return;

        for (var other : configQueue) {
            if (other.x == building.tileX() && other.y == building.tileY()) {
                return;
            }
        }
        configQueue.add(new FDConfigRequest(building, config));
    }

    private static void queueRotation(Building building, int targetRotation) {
        if (building == null) return;

        for (var other : rotateQueue) {
            if (other.x == building.tileX() && other.y == building.tileY()) {
                return;
            }
        }
        rotateQueue.add(new FDRotateRequest(building, targetRotation));
    }

    /** Тот же блок в той же точке, поворот не учитывается. */
    private static boolean isSameBlock(Building b, BuildPlan s) {
        if (b == null || s == null || s.block == null) return false;

        if (b instanceof ConstructBlock.ConstructBuild co) {
            return co.current == s.block && co.tileX() == s.x && co.tileY() == s.y;
        }

        return b.tileX() == s.x && b.tileY() == s.y && b.block == s.block;
    }

    /** Тот же блок, в той же точке, и (если важно) с тем же поворотом. */
    private static boolean isSameStructure(Building b, BuildPlan s) {
        if (!isSameBlock(b, s)) return false;

        if (isRotationIgnorable(s.block)) return true;

        if (b instanceof ConstructBlock.ConstructBuild co) {
            return co.rotation == s.rotation || !s.block.rotate;
        }

        if (!s.block.rotate) return true;

        return b.rotation == s.rotation;
    }

    public static void placeSchematicWithCleanup(Seq<BuildPlan> plans) {
        Unit unit = player.unit();
        if (unit == null || plans.isEmpty()) return;

        Seq<BuildPlan> filteredPlans = new Seq<>(plans.size);

        for (BuildPlan s : plans) {
            if (isUnplaceable(s.block)) {
                continue;
            }
            filteredPlans.add(s);
        }
        if (filteredPlans.isEmpty()) return;

        plans = filteredPlans;

        Seq<PendingBuild> toBuild = new Seq<>();
        ObjectSet<Building> allToRemove = new ObjectSet<>();

        for (BuildPlan s : plans) {
            var it = unit.plans.iterator();
            while (it.hasNext()) {
                BuildPlan p = it.next();
                if (p.x == s.x && p.y == s.y) {
                    control.input.playerPlanTree.remove(p);
                    it.remove();
                }
            }

            final boolean[] fits = {true};
            final Building[] currentBuild = {null};
            ObjectSet<Building> blockers = new ObjectSet<>();

            s.block.iterateTaken(s.x, s.y, (tx, ty) -> {
                Tile tile = world.tile(tx, ty);
                if (tile == null) {
                    fits[0] = false;
                    return;
                }

                Building other = tile.build;
                if (other == null) return;

                if (other.team != player.team() || isProtected(other)) {
                    fits[0] = false;
                    return;
                }

                if (isSameBlock(other, s)) {
                    currentBuild[0] = other;
                } else {
                    if (other.tileX() != s.x || other.tileY() != s.y || !s.block.canReplace(other.block)) {
                        blockers.add(other);
                    }
                }
            });

            if (!fits[0]) {
                continue;
            }

            if (currentBuild[0] != null) {
                Building existing = currentBuild[0];

                if (!(existing instanceof ConstructBlock.ConstructBuild)) {
                    // Тот же блок уже стоит: донастраиваем конфиг и/или доворачиваем, ничего не сносим.
                    if (!Objects.equals(existing.config(), s.config)) {
                        queueConfig(existing, s.config);
                    }
                    if (s.block.rotate && existing.rotation != s.rotation && !isRotationIgnorable(s.block)) {
                        queueRotation(existing, s.rotation);
                    }
                    continue;
                }
                // Блок ещё строится - оставляем план, чтобы стройка завершилась/доворот произошёл позже.
            }

            allToRemove.addAll(blockers);
            toBuild.add(new PendingBuild(new BuildPlan(s.x, s.y, s.rotation, s.block, s.config), blockers));
        }

        if (!allToRemove.isEmpty()) {
            for (Building b : allToRemove) {
                if (isProtected(b)) continue;

                BuildPlan breakPlan = new BuildPlan(b.tileX(), b.tileY());
                breakPlan.breaking = true;

                boolean exists = false;
                for (BuildPlan p : unit.plans) {
                    if (p.breaking && p.x == breakPlan.x && p.y == breakPlan.y) {
                        exists = true;
                        break;
                    }
                }

                if (!exists) {
                    unit.plans.addLast(breakPlan);
                    control.input.playerPlanTree.insert(breakPlan);
                }
            }
        }

        if (!toBuild.isEmpty()) {
            final Unit startUnit = player.unit();

            Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    if (!state.isGame() || player.unit() != startUnit || player.unit() == null) {
                        this.cancel();
                        return;
                    }

                    var pendingIt = toBuild.iterator();
                    while (pendingIt.hasNext()) {
                        PendingBuild pending = pendingIt.next();

                        // Проверяем, остались ли ещё не снесённые блокеры именно для этого плана.
                        boolean stillBlocked = false;
                        for (Building b : pending.blockers) {
                            if (b.isAdded() && world.build(b.tileX(), b.tileY()) == b) {
                                stillBlocked = true;
                                break;
                            }
                        }

                        if (stillBlocked) {
                            pending.attempts++;
                            if (pending.attempts > 25) {
                                // Снос завис (защищённый/заблокированный блок и т.п.) - план пропускаем,
                                // но НЕ ставим постройку поверх ещё существующего здания.
                                pendingIt.remove();
                            }
                            continue;
                        }

                        BuildPlan s = pending.plan;

                        // Финальная проверка перед постановкой в очередь: вдруг там уже что-то появилось
                        Building existing = world.build(s.x, s.y);
                        if (existing != null && existing.team == player.team() && isSameStructure(existing, s)
                                && !(existing instanceof ConstructBlock.ConstructBuild)) {
                            Object currentConfig = existing.config();
                            if (!Objects.equals(currentConfig, s.config)) {
                                queueConfig(existing, s.config);
                            }
                            pendingIt.remove();
                            continue;
                        }

                        if (existing != null && existing.team == player.team() && isProtected(existing)) {
                            // На месте плана оказался защищённый блок - пропускаем, чтобы не застрять навечно.
                            pendingIt.remove();
                            continue;
                        }

                        // Всё, что дошло сюда — либо пусто, либо чужой/другой блок, либо наша же
                        // стройка в процессе (тот же блок, ConstructBlock.ConstructBuild) — во всех
                        // этих случаях план нужно поставить, чтобы стройка либо началась, либо продолжилась.
                        player.unit().plans.addLast(s);
                        control.input.playerPlanTree.insert(s);

                        pendingIt.remove();
                    }

                    if (toBuild.isEmpty()) {
                        this.cancel();
                    }
                }
            }, 0.4f, 0.4f);
        }
    }

    private static class PendingBuild {
        final BuildPlan plan;
        final ObjectSet<Building> blockers;
        int attempts = 0;

        PendingBuild(BuildPlan plan, ObjectSet<Building> blockers) {
            this.plan = plan;
            this.blockers = blockers;
        }
    }

}