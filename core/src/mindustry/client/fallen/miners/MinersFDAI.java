package mindustry.client.fallen.miners;

import arc.Core;
import arc.Events;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Interval;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.ai.ItemUnitStance;
import mindustry.ai.UnitCommand;
import mindustry.ai.UnitStance;
import mindustry.ai.types.CommandAI;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.entities.Units;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.world.Tile;

import static mindustry.Vars.player;

// === Суть работы ИИ ===
// Управляет выбранными типами юнитов (моно, поли, пульсары, меги, квазары)
// Распределяет их по ресурсам на основе:
//   - весовых коэффициентов (зависит от заполненности хранилища)
//   - квот (минимальное количество юнитов на ресурс)
//   - приоритетов (кризисные ресурсы получают приоритет)
// Поддерживает два уровня кризиса:
//   - обычный: переключение на любые ресурсы ниже порога
//   - критический: переключение на базовые ресурсы (медь/свинец/титан) при сильном дефиците
// Дополнительные функции:
//   - авто-починка построек у ядра мегами
//   - помощь в строительстве (в радиусе от игрока)
//   - приоритет ручных команд (не перехватывает управление)
//   - автоматический возврат юнитов на свободу при отключении их типа
//   - фильтр ресурсов по фактическому наличию руды и свободных тайлов на карте (OreSafety)
//   - опциональный обход вражеских пушек: отвод майнеров к безопасным жилам (OreSafety)
// Всё управляется через таймеры с настраиваемыми интервалами

public class MinersFDAI {
    public static boolean autoMiningActive = false;
    public static boolean autoAssistBuild = false;
    public static boolean respectManualCommands = Core.settings.getBool("respmancommands", true);
    public static boolean allManualCommands = Core.settings.getBool("allmancommands", true);
    private static boolean wasAutoMiningActive = false;

    public static boolean mineMonos = true;
    public static boolean minePolys = false;
    public static boolean minePulss = true;
    public static boolean mineMegas = true;
    public static boolean mineQuazs = true;
    public static boolean autoHealMegas = false;
    public static float autoHealDist = 50f;
    public static int minUnitsPerResource = 0;
    public static float crisisThreshold = 0.05f;
    public static float fullCoreWeight = 0.05f;
    public static boolean oreSafetyEnabled = Core.settings.getBool("fdmai-oresafe", false);

    // ================== МАТРИЦА РАЗРЕШЕНИЙ "ТИП ЮНИТА -> РЕСУРС" ==================
    // Порядок ресурсов и типов важен - используется и для дефолтов, и для UI-таблицы.
    public static final Item[] MINE_ITEMS = {
            Items.copper, Items.lead, Items.sand, Items.coal, Items.titanium, Items.scrap, Items.beryllium
    };
    public static final UnitType[] MINER_TYPES = {
            UnitTypes.mono, UnitTypes.poly, UnitTypes.pulsar, UnitTypes.mega, UnitTypes.quasar
    };


    // Дефолты соответствуют исходной таблице: mono/poly/pulsar не могут титан и берилл,
    // mono вдобавок не копает уголь; mega/quasar могут всё.
    private static final boolean[][] DEFAULT_PERMISSIONS = {
            // copper, lead, sand,  coal,  titan, scrap, berill
            {true, true, true, false, false, false, false}, // mono
            {true, true, true, true, false, false, false}, // poly
            {true, true, true, true, false, false, false}, // pulsar
            {true, true, true, true, true, false, false},  // mega
            {true, true, true, true, true, false, false},  // quasar
    };

    private static final ObjectMap<UnitType, ObjectMap<Item, Boolean>> minePermissions = new ObjectMap<>();
    // Флаг того, что матрица уже подгружена из Core.settings.
    private static boolean permissionsLoaded = false;
    public static boolean resetMatrixOnWorldLoad = Core.settings.getBool("resetMatrixOnWorldLoad", false);


    private static String permKey(UnitType type, Item item) {
        return "fdai-perm-" + type.name + "-" + item.name;
    }

    /** Гарантирует, что матрица разрешений загружена из настроек. Безопасно вызывать многократно. */
    private static void ensurePermissionsLoaded() {
        if (permissionsLoaded) return;
        loadPermissions();
    }

    private static void loadPermissions() {
        for (int ti = 0; ti < MINER_TYPES.length; ti++) {
            UnitType type = MINER_TYPES[ti];
            ObjectMap<Item, Boolean> row = new ObjectMap<>();
            for (int ii = 0; ii < MINE_ITEMS.length; ii++) {
                Item item = MINE_ITEMS[ii];
                boolean def = DEFAULT_PERMISSIONS[ti][ii];
                row.put(item, Core.settings.getBool(permKey(type, item), def));
            }
            minePermissions.put(type, row);
        }
        permissionsLoaded = true;
    }

    /** Может ли юнит данного типа копать данный ресурс (согласно таблице в настройках). */
    public static boolean canMine(UnitType type, Item item) {
        ensurePermissionsLoaded();
        ObjectMap<Item, Boolean> row = minePermissions.get(type);
        if (row == null) return false;
        return row.get(item, false);
    }

    public static void setCanMine(UnitType type, Item item, boolean value) {
        ensurePermissionsLoaded();
        ObjectMap<Item, Boolean> row = minePermissions.get(type);
        if (row == null) {
            row = new ObjectMap<>();
            minePermissions.put(type, row);
        }
        row.put(item, value);
        Core.settings.put(permKey(type, item), value);
    }


    /** Сбрасывает матрицу "тип юнита -> ресурс" на дефолтные значения из DEFAULT_PERMISSIONS. */
    public static void resetPermissionsToDefaults() {
        for (int ti = 0; ti < MINER_TYPES.length; ti++) {
            UnitType type = MINER_TYPES[ti];
            for (int ii = 0; ii < MINE_ITEMS.length; ii++) {
                Item item = MINE_ITEMS[ii];
                setCanMine(type, item, DEFAULT_PERMISSIONS[ti][ii]);
            }
        }
    }

    private static Interval miningTimer = new Interval();
    private static Interval assistTimer = new Interval();
    public static int AIMiningUpdateTime = Core.settings.getInt("AIUpTime", 5);
    public static float AIHelpRad = Core.settings.getFloat("AIHelpRad", 10);
    public static boolean resetDisabledUnits = Core.settings.getBool("resetDisabledUnits", false);

    private static final IntMap<UnitCommand> lastAiCommand = new IntMap<>();
    private static final IntSet manualUnits = new IntSet();
    private static final IntSet assistingUnits = new IntSet();

    private static void forgetUnit(int id) {
        lastAiCommand.remove(id);
        manualUnits.remove(id);
        assistingUnits.remove(id);
        OreSafety.forget(id);
    }
    public static void init() {
        loadPermissions();
        Events.on(EventType.WorldLoadEvent.class, e -> {
            autoMiningActive = false;
            wasAutoMiningActive = false;
            manualUnits.clear();
            assistingUnits.clear();
            lastAiCommand.clear();
            if (resetMatrixOnWorldLoad) {
                resetPermissionsToDefaults();
            }
            // Сканируем жилы руды под фильтр наличия ресурсов и систему обхода пушек.
            OreSafety.scan();
        });

        Events.on(EventType.UnitDestroyEvent.class, e -> {
            if (e.unit == null) return;
            forgetUnit(e.unit.id);
        });

        Events.run(EventType.Trigger.update, () -> {
            if (Vars.state.isMenu()) return;


            // === ЗАПУСК КОПКИ ПРИ ВКЛЮЧЕНИИ ИИ ===
            if (autoMiningActive && !wasAutoMiningActive) {
                IntSeq toTakeOver = new IntSeq();
                IntSeq polysToAssist = new IntSeq(); // поли не копают - сразу в помощь стройке
                for (Unit u : Groups.unit) {
                    if (u.team != player.team() || !u.isCommandable()) continue;

                    if (isManagedMinerType(u.type)) {
                        if (u.type == UnitTypes.poly && u.type.mineTier <= 0) {
                            polysToAssist.add(u.id);
                        } else {
                            toTakeOver.add(u.id);
                        }
                        forgetUnit(u.id); // Стираем историю, чтобы ИИ не считал их "ручными"
                    }
                }

                if (toTakeOver.size > 0) {
                    // Просто даем всем команду копать. Без сложного распределения.
                    Call.setUnitCommand(player, toTakeOver.toArray(), UnitCommand.mineCommand);
                }
                if (polysToAssist.size > 0) {
                    Call.setUnitCommand(player, polysToAssist.toArray(), UnitCommand.assistCommand);
                }

                wasAutoMiningActive = true;
                miningTimer.clear();
                assistTimer.clear();

                // Распределяем по квотам сразу, не ждём первого тика таймера.
                autoAssignMiningUnitsEqually();
            } else if (!autoMiningActive) {
                wasAutoMiningActive = false;
            }

            // === ВОЗВРАТ ЮНИТОВ ИЗ АССИСТА ПРИ ВЫКЛЮЧЕНИИ ПОМОЩИ СТРОЙКЕ ===
            if (autoMiningActive && !autoAssistBuild && assistingUnits.notEmpty()) {
                IntSeq backToMine = new IntSeq();
                assistingUnits.each(id -> {
                    if (Groups.unit.getByID(id) != null) backToMine.add(id);
                });
                if (backToMine.size > 0) {
                    int[] ids = backToMine.toArray();
                    Call.setUnitCommand(player, ids, UnitCommand.mineCommand);
                    for (int id : ids) lastAiCommand.put(id, UnitCommand.mineCommand);
                }
                assistingUnits.clear();
            }

            if (autoMiningActive && autoAssistBuild && assistTimer.get(60f)) {
                handleAssistNearPlayer();
            }

            if (autoMiningActive && miningTimer.get(AIMiningUpdateTime * 60f)) {
                autoAssignMiningUnitsEqually();
            }
        });
    }

    private static boolean isManagedMinerType(UnitType type) {
        if (type == UnitTypes.mono) return mineMonos;
        if (type == UnitTypes.poly) return minePolys;
        if (type == UnitTypes.mega) return mineMegas;
        if (type == UnitTypes.pulsar) return minePulss;
        if (type == UnitTypes.quasar) return mineQuazs;
        return false; // По умолчанию не трогаем неопознанные типы
    }

    // ================== ФИЧА: АССИСТ БЛИЖАЙШИХ ЮНИТОВ ПРИ СТРОЙКЕ ==================
    private static void handleAssistNearPlayer() {
        if (player.unit() == null) return;

        boolean buildingNearby = isPlayerBuilding();
        float px = player.x, py = player.y;
        float radiusPx = AIHelpRad * 8f;

        IntSeq toAssist = new IntSeq();
        IntSeq toReturn = new IntSeq();

        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable()) continue;
            if (u.type.buildSpeed <= 0f) continue;
            if (!isManagedMinerType(u.type)) continue; // Если тип выключен — не берем в ассист
            if (manualUnits.contains(u.id)) continue; // Не трогаем ручных юнитов
            // Юниты на починке (авто-хил мег) не дёргаем в ассист, иначе флап ремонт/ассист.
            if (u.controller() instanceof CommandAI rep && rep.command == UnitCommand.repairCommand) continue;

            boolean inRange = u.dst(px, py) <= radiusPx;
            boolean isCurrentlyAssist = u.controller() instanceof CommandAI cai && cai.command == UnitCommand.assistCommand;

            if (buildingNearby && inRange) {
                if (!isCurrentlyAssist) toAssist.add(u.id);
                assistingUnits.add(u.id);
            } else if (assistingUnits.contains(u.id) || isCurrentlyAssist) {
                toReturn.add(u.id);
                assistingUnits.remove(u.id);
            }
        }

        if (toAssist.size > 0) {
            int[] ids = toAssist.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.assistCommand);
            for (int id : ids) lastAiCommand.put(id, UnitCommand.assistCommand);
        }

        if (toReturn.size > 0) {
            int[] ids = toReturn.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.mineCommand);
            for (int id : ids) lastAiCommand.put(id, UnitCommand.mineCommand);
        }
    }

    private static void autoAssignMiningUnitsEqually() {
        if (player.unit() == null) return;
        Building core = player.team().core();
        if (core == null) return;

        // --- Проверка режима починки ---
        boolean needsRepairNearCore = false;
        if (autoHealMegas) {
            for (Building ncore : player.team().cores()) {
                if (ncore == null) continue;
                Building nearest = Units.findDamagedTile(player.team(), ncore.x, ncore.y);
                if (nearest != null && nearest.dst(ncore) / 8f < autoHealDist) {
                    needsRepairNearCore = true;
                    break;
                }
            }
        }

        // --- Обновляем пушки врага и угрозу по жилам (для фильтра и отводов) ---
        OreSafety.refreshThreat(oreSafetyEnabled);

        int megaCounter = 0; // Счетчик для разделения Мег пополам
        int capacity = core.core().storageCapacity;

        // 1. Считаем веса ресурсов (спрос ядра).
        // Ресурс участвует в распределении, только если его руда физически есть на карте
        // и у жилы есть свободные тайлы (OreSafety). При включённой защите простреливаемые
        // жилы тоже отбрасываются. Разрешение "какой тип юнита что копает" задаётся
        // матрицей MinersFDAI.canMine(type, item) и проверяется ниже (см. "possible").
        Seq<Item> allEnabled = new Seq<>(MINE_ITEMS).select(it -> OreSafety.itemAvailable(it, oreSafetyEnabled));
        if (allEnabled.isEmpty()) {
            Log.warn("No available ores! oreSafetyEnabled=" + oreSafetyEnabled + ", clusters=" + OreSafety.clusters.size);
            return;
        }

        ObjectMap<Item, Float> itemWeights = new ObjectMap<>();
        float totalWeight = 0;

        for (Item it : allEnabled) {
            float progress = (float) core.items.get(it) / capacity;
            float weight = Math.max(fullCoreWeight, 1.0f - progress);
            if (progress < 0.1f) weight *= 5f;
            itemWeights.put(it, weight);
            totalWeight += weight;
        }
        if (totalWeight <= 0) return;

        ObjectMap<Item, IntSeq> toBatchSend = new ObjectMap<>();
        // Отводы к безопасным жилам: жила -> пакет юнитов (массовая отправка).
        ObjectMap<OreSafety.Cluster, IntSeq> moveBatches = new ObjectMap<>();

        // ============================================================
        // 2. ГЛОБАЛЬНОЕ ОПРЕДЕЛЕНИЕ КРИЗИСА
        // ============================================================
        final float CRITICAL_CORE_THRESHOLD = crisisThreshold / 2f;
        final float NORMAL_CRISIS_THRESHOLD = crisisThreshold;

        Seq<Item> globalCrisisItems = new Seq<>();
        boolean isCriticalCoreCrisis = false;

        Item[] coreResources = {Items.copper, Items.lead, Items.titanium};
        for (Item it : coreResources) {
            if (!allEnabled.contains(it)) continue;
            float progress = (float) core.items.get(it) / capacity;
            if (progress < CRITICAL_CORE_THRESHOLD) {
                globalCrisisItems.add(it);
                isCriticalCoreCrisis = true;
            }
        }

        if (!isCriticalCoreCrisis) {
            for (Item it : allEnabled) {
                float progress = (float) core.items.get(it) / capacity;
                if (progress < NORMAL_CRISIS_THRESHOLD) {
                    globalCrisisItems.add(it);
                }
            }
        }
        boolean isGlobalCrisis = !globalCrisisItems.isEmpty();

        // ================== ФИЧА: игнор ручных юнитов + возврат чужих ==================
        IntSeq toReleaseAsAssist = new IntSeq(); // Для полей (poly)
        IntSeq toReleaseAsMine = new IntSeq();   // Для мег, квазаров, пульсаров и моно
        IntSeq toForceRestore = new IntSeq();
        IntSeq toRepair = new IntSeq();

        // 3. Группируем юнитов по типам
        ObjectMap<UnitType, Seq<Unit>> unitGroups = new ObjectMap<>();
        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable()) continue;

            // Тип выключен в панели — снимаем с учета, если раньше управляли им
            if (!isManagedMinerType(u.type)) {
                if (lastAiCommand.containsKey(u.id)) {
                    // Распределяем по типам для правильной команды при сбросе
                    if (u.type == UnitTypes.poly) {
                        toReleaseAsAssist.add(u.id);
                    } else if (u.type == UnitTypes.mega || u.type == UnitTypes.quasar ||
                            u.type == UnitTypes.pulsar || u.type == UnitTypes.mono) {
                        toReleaseAsMine.add(u.id);
                    }
                    lastAiCommand.remove(u.id);
                }
                continue;
            }

            // Ручной режим: если игрок сам дал юниту другую команду — не трогаем его.
            // Если команду дал кто-то другой — забираем обратно под управление ИИ.
            if (respectManualCommands && u.controller() instanceof CommandAI cai) {
                UnitCommand current = cai.command;
                UnitCommand expected = lastAiCommand.get(u.id);
                if (current != UnitCommand.mineCommand && expected != null && current != expected) {
                    if (current != UnitCommand.repairCommand && current != UnitCommand.assistCommand) {
                        String cmdr = u.lastCommanded != null ? Strings.stripColors(u.lastCommanded) : "";
                        String myName = Strings.stripColors(Vars.player.name);
                        boolean isMyCommand = cmdr.equals(myName);
                        if (isMyCommand || allManualCommands) {
                            manualUnits.add(u.id);
                            continue;
                        } else {
                            toForceRestore.add(u.id);
                            manualUnits.remove(u.id);
                        }
                    }
                }
            }

            if (manualUnits.contains(u.id) || assistingUnits.contains(u.id)) continue;

            // Юнит в отводе к безопасной жиле — не трогаем, пока не прилетит.
            if (oreSafetyEnabled && OreSafety.isRedirecting(u)) continue;

            if (u.type == UnitTypes.mega) {
                if (!mineMegas) continue;
                megaCounter++;

                if (autoHealMegas && needsRepairNearCore && (!isGlobalCrisis || megaCounter % 2 == 0)) {
                    if (!(u.controller() instanceof CommandAI cai && cai.command == UnitCommand.repairCommand)) {
                        toRepair.add(u.id);
                    }
                    continue; // Отправляем хилить, в майнинг не пускаем
                }
            }

            if (u.type.mineTier > 0) {
                if (!unitGroups.containsKey(u.type)) unitGroups.put(u.type, new Seq<>());
                unitGroups.get(u.type).add(u);
            }
        }

        // Разовая отправка сервисных команд
        if (resetDisabledUnits) {
            if (toReleaseAsAssist.size > 0) {
                Call.setUnitCommand(player, toReleaseAsAssist.toArray(), UnitCommand.assistCommand);
            }
            if (toReleaseAsMine.size > 0) {
                Call.setUnitCommand(player, toReleaseAsMine.toArray(), UnitCommand.mineCommand);
            }
        }
        if (toForceRestore.size > 0) Call.setUnitCommand(player, toForceRestore.toArray(), UnitCommand.mineCommand);
        if (toRepair.size > 0) {
            int[] ids = toRepair.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.repairCommand);
            for (int id : ids) lastAiCommand.put(id, UnitCommand.repairCommand);
        }

        if (unitGroups.isEmpty()) return;

        // ============================================================
        // 4. ОБРАБОТКА КАЖДОЙ ГРУППЫ — ЛОГИКА КВОТ
        // ============================================================
        for (var entry : unitGroups.entries()) {
            UnitType type = entry.key;
            Seq<Unit> units = entry.value;

            // Ресурс доступен типу, только если хватает mineTier И он разрешен в таблице настроек для этого типа.
            Seq<Item> possible = allEnabled.select(it -> type.mineTier >= it.hardness && canMine(type, it));
            if (possible.isEmpty()) continue;

            Seq<Item> targets = possible;
            boolean isCrisisMode = false;

            if (isGlobalCrisis) {
                Seq<Item> myCrisisTargets = new Seq<>();
                for (Item it : globalCrisisItems) {
                    if (possible.contains(it)) myCrisisTargets.add(it);
                }
                if (!myCrisisTargets.isEmpty()) {
                    targets = myCrisisTargets;
                    isCrisisMode = true;
                }
            }

            ObjectMap<Item, Integer> quotas = new ObjectMap<>();
            int assignedCount = 0;

            float currentTotalWeight = 0;
            for (Item it : targets) currentTotalWeight += itemWeights.get(it, fullCoreWeight);
            if (currentTotalWeight <= 0) continue;

            for (Item it : possible) {
                int target;

                if (isCrisisMode) {
                    if (!targets.contains(it)) {
                        target = 0;
                    } else {
                        int baseShare = units.size / targets.size;
                        int remainder = units.size % targets.size;
                        int index = targets.indexOf(it);
                        target = baseShare + (index < remainder ? 1 : 0);

                        float progress = (float) core.items.get(it) / capacity;
                        if (progress < 0.1f) target += 1;
                    }
                } else {
                    int baseTarget = Math.round((itemWeights.get(it, fullCoreWeight) / currentTotalWeight) * units.size);
                    target = Math.max(minUnitsPerResource, baseTarget);
                }

                quotas.put(it, target);
                assignedCount += target;
            }

            // 5. КОРРЕКТИРОВКА (БАЛАНСИРОВКА)
            while (assignedCount > units.size) {
                Item toReduce = possible.max(it -> {
                    int q = quotas.get(it, 0);
                    if (q <= 0) return -1f;
                    return (float) q / itemWeights.get(it, fullCoreWeight);
                });
                if (toReduce != null) {
                    quotas.put(toReduce, quotas.get(toReduce, 0) - 1);
                    assignedCount--;
                } else break;
            }

            while (assignedCount < units.size) {
                Item toBoost = targets.max(it -> itemWeights.get(it, fullCoreWeight));
                if (toBoost != null) {
                    quotas.put(toBoost, quotas.get(toBoost, 0) + 1);
                    assignedCount++;
                } else break;
            }

            // --- ЛОГИКА "ЛИПКОСТИ" ---
            Seq<Unit> unassignedUnits = new Seq<>();

            for (Unit u : units) {
                Item currentItem = currentMinedItem(u);
                if (currentItem == null || !possible.contains(currentItem)) currentItem = null;

                if (currentItem != null && quotas.get(currentItem, 0) > 0) {
                    quotas.put(currentItem, quotas.get(currentItem, 0) - 1);
                    // Отвод: юнит копает по плану, но оказался под огнём врага.
                    if (oreSafetyEnabled && OreSafety.isThreatened(u.x, u.y)) {
                        Log.info("Юнит @ [@, @] испугался и убегает!", u.type.name, (int)u.x/8, (int)u.y/8);
                        handleOreSafety(u, currentItem, moveBatches);
                    }
                } else {
                    unassignedUnits.add(u);
                }
            }

            for (Unit u : unassignedUnits) {
                Item bestTarget = possible.max(it -> quotas.get(it, 0));

                if (bestTarget != null && quotas.get(bestTarget, 0) > 0) {
                    quotas.put(bestTarget, quotas.get(bestTarget, 0) - 1);

                    // Проверка безопасности цели: отвод либо отказ от смены руды.
                    if (oreSafetyEnabled && handleOreSafety(u, bestTarget, moveBatches)) continue;

                    if (!toBatchSend.containsKey(bestTarget)) toBatchSend.put(bestTarget, new IntSeq());
                    toBatchSend.get(bestTarget).add(u.id);
                } else {
                    Item fallback = possible.max(it -> itemWeights.get(it, fullCoreWeight));

                    if (oreSafetyEnabled && handleOreSafety(u, fallback, moveBatches)) continue;

                    // Не слать команду повторно, если юнит уже и так копает fallback-ресурс
                    if (!(u.controller() instanceof CommandAI cai && cai.command == UnitCommand.mineCommand && cai.hasStance(ItemUnitStance.getByItem(fallback)))) {
                        if (!toBatchSend.containsKey(fallback)) toBatchSend.put(fallback, new IntSeq());
                        toBatchSend.get(fallback).add(u.id);
                    }
                }
            }
        }

        // 6. Отправка пакетов
        for (var entry : toBatchSend.entries()) {
            int[] ids = entry.value.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.mineCommand);
            Call.setUnitStance(player, ids, UnitStance.mineAuto, false);
            Call.setUnitStance(player, ids, ItemUnitStance.getByItem(entry.key), true);
            for (int id : ids) lastAiCommand.put(id, UnitCommand.mineCommand);
        }

        // Отвод к безопасным жилам: по одному массовому пакету на жилу.
        for (var entry : moveBatches.entries()) {
            int[] ids = entry.value.toArray();
            OreSafety.Cluster vein = entry.key;
            Call.setUnitCommand(player, ids, UnitCommand.moveCommand);
            Call.setUnitStance(player, ids, UnitStance.boost, true);
            Call.commandUnits(player, ids, null, null, new Vec2(vein.x, vein.y), false, true);
            for (int id : ids) lastAiCommand.put(id, UnitCommand.moveCommand);
        }
    }

    // ================== БЕЗОПАСНОСТЬ РУДЫ (ОБХОД ПУШЕК ВРАГА) ==================

    /** Что юнит копает прямо сейчас (по текущему mine-стенсу) или null. */
    private static Item currentMinedItem(Unit u) {
        if (!(u.controller() instanceof CommandAI cai) || cai.command != UnitCommand.mineCommand) return null;
        for (Item it : MINE_ITEMS) {
            if (cai.hasStance(ItemUnitStance.getByItem(it))) return it;
        }
        return null;
    }

    /**
     * Решает, что делать с юнитом, которого хотят отправить копать item:
     * если ближайшая к нему руда этого типа под огнём врага — отводит его
     * к ближайшей безопасной жиле (пакетом, отдельно от обычных команд).
     * Возвращает true, если команду копки слать не надо: юнит отведён,
     * либо путь до безопасной жилы тоже простреливается, а юнит уже занят копкой
     * (в этом случае оставляем его на текущей руде).
     */
    private static boolean handleOreSafety(Unit u, Item item, ObjectMap<OreSafety.Cluster, IntSeq> moveBatches) {
        Tile near = Vars.indexer.findClosestOre(u, item);
        if (near == null) return false; // Руды нет физически — пусть решает ванила
        if (!OreSafety.isThreatened(near.worldx(), near.worldy())) return false; // Ближайшая безопасна

        OreSafety.Cluster safe = OreSafety.nearestAvailable(item, u.x, u.y, true);
        if (safe == null) return false; // Безопасных жил нет — ресурс исключён из квот

        if (!OreSafety.pathSafe(u.x, u.y, safe.x, safe.y)) {
            // Путь до безопасной жилы тоже под огнём: если юнит уже что-то копает — не меняем.
            return currentMinedItem(u) != null;
        }

        if (!moveBatches.containsKey(safe)) moveBatches.put(safe, new IntSeq());
        moveBatches.get(safe).add(u.id);
        //OreSafety.markRedirect(u.id, u.dst(safe.x, safe.y));
        OreSafety.markRedirect(u.id, safe); // передаём кластер вместо времени

        return true;
    }

    private static boolean isPlayerBuilding() {
        Unit u = player.unit();
        if (u == null) return false;

        // Дистанция, в пределах которой стройка считается "рядом" (2 * радиус ассиста)
        // AIHelpRad у вас в клетках, поэтому умножаем на 8 (пиксели) и на 2 (условие)
        float maxDist = (AIHelpRad * 8f) * 2f;

        // 1. Проверяем то, что строится прямо сейчас (луч зажат)
        if (u.activelyBuilding()) {
            var plan = u.buildPlan();
            if (plan != null && u.dst(plan.drawx(), plan.drawy()) <= maxDist) return true;
        }

        // 2. Проверяем очередь планов (чертежи на земле)
        if (u.plans.size > 0) {
            for (var plan : u.plans) {
                if (u.dst(plan.drawx(), plan.drawy()) <= maxDist) {
                    return true;
                }
            }
        }

        return false;
    }
}