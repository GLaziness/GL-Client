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
import mindustry.world.blocks.units.RepairTower;
import mindustry.world.blocks.units.RepairTurret;
import mindustry.world.meta.BlockFlag;

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
    private static Interval panicTimer = new Interval();

    public static int AIMiningUpdateTime = Core.settings.getInt("AIUpTime", 5);
    public static float AIHelpRad = Core.settings.getFloat("AIHelpRad", 10);
    public static boolean resetDisabledUnits = Core.settings.getBool("resetDisabledUnits", false);

    // Which builder types help build near the player (from Morj's client)
    public static boolean assistBuildPoly = Core.settings.getBool("AIAssistPoly", true);
    public static boolean assistBuildPulsar = Core.settings.getBool("AIAssistPulsar", true);
    public static boolean assistBuildMega = Core.settings.getBool("AIAssistMega", true);
    public static boolean assistBuildQuasar = Core.settings.getBool("AIAssistQuasar", true);

    // Damaged miners fly to a repair point and come back (from Morj's client)
    public static boolean autoUnitRepair = Core.settings.getBool("fd-autoUnitRepair", true);
    public static float unitRepairGoHp = Core.settings.getFloat("fd-unitRepairGoHp", 0.5f);
    public static float unitRepairDoneHp = 0.98f;
    private static final IntSet healingUnits = new IntSet();
    /** Ore each healing unit was mining, to send it back to the same ore. */
    private static final IntMap<Item> healingItem = new IntMap<>();
    private static final Seq<Building> repairPads = new Seq<>();
    private static final Interval repairTimer = new Interval();

    /** name: Poly, Pulsar, Mega or Quasar. */
    public static void setAssistBuild(String name, boolean value){
        switch(name){
            case "Poly" -> assistBuildPoly = value;
            case "Pulsar" -> assistBuildPulsar = value;
            case "Mega" -> assistBuildMega = value;
            case "Quasar" -> assistBuildQuasar = value;
        }
        Core.settings.put("AIAssist" + name, value);
    }

    private static boolean isAssistBuilderType(UnitType type){
        if(type == UnitTypes.poly) return assistBuildPoly;
        if(type == UnitTypes.pulsar) return assistBuildPulsar;
        if(type == UnitTypes.mega) return assistBuildMega;
        if(type == UnitTypes.quasar) return assistBuildQuasar;
        return true;
    }

    private static final IntMap<UnitCommand> lastAiCommand = new IntMap<>();
    private static final IntSet manualUnits = new IntSet();
    private static final IntSet assistingUnits = new IntSet();

    private static void forgetUnit(int id) {
        lastAiCommand.remove(id);
        manualUnits.remove(id);
        assistingUnits.remove(id);
        healingUnits.remove(id);
        healingItem.remove(id);
        retreating.remove(id);
    }

    public static void init() {
        loadPermissions();
        Events.on(EventType.WorldLoadEvent.class, e -> {
            // autoMiningActive is kept: the panel button remembers its state between maps and launches
            wasAutoMiningActive = false;
            manualUnits.clear();
            assistingUnits.clear();
            lastAiCommand.clear();
            healingUnits.clear();
            healingItem.clear();
            retreating.clear();
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

            // === 1. БЫСТРЫЙ ЧЕК ПАНИКИ (4 раза в секунду) ===
            if (autoMiningActive && oreSafetyEnabled && panicTimer.get(15f)) {
                handleEmergencyPanicRetreat();
            }

            // === 2. ЗАПУСК КОПКИ ПРИ ВКЛЮЧЕНИИ ===
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
                panicTimer.clear();

                // Распределяем по квотам сразу, не ждём первого тика таймера.
                autoAssignMiningUnitsEqually();
            } else if (!autoMiningActive) {
                wasAutoMiningActive = false;
            }

            // === 3. ВОЗВРАТ ИЗ АССИСТА ПРИ ВЫКЛЮЧЕНИИ ПОМОЩИ СТРОЙКЕ ===
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

            // === 4. РЕМОНТ ПОВРЕЖДЁННЫХ ЮНИТОВ ===
            if (autoMiningActive && repairTimer.get(60f)) {
                if (autoUnitRepair) handleUnitRepair();
                else if (healingUnits.notEmpty()) releaseHealing(null);
            }

            // === 5. АССИСТ СТРОЙКИ ===
            if (autoMiningActive && autoAssistBuild && assistTimer.get(60f)) {
                handleAssistNearPlayer();
            }

            // === 5. ПЛАНОВЫЙ ПЕРЕСЧЕТ КВОТ ===
            if (autoMiningActive && miningTimer.get(AIMiningUpdateTime * 60f)) {
                autoAssignMiningUnitsEqually();
            }
        });
    }

    /** Юниты, уже отведённые к безопасному ядру: повторно им команду не шлём, пока не назначим руду. */
    private static final IntSet retreating = new IntSet();

    /** Экстренный отвод юнитов к ближайшему ядру, которое не под огнём **/
    private static void handleEmergencyPanicRetreat() {
        // Группируем ID юнитов по их ближайшему безопасному ядру: Core -> список ID
        ObjectMap<Building, IntSeq> retreatGroups = new ObjectMap<>();

        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable() || !isManagedMinerType(u.type)) continue;
            // ручных, лечащихся и уже отведённых не дёргаем
            if (manualUnits.contains(u.id) || healingUnits.contains(u.id) || retreating.contains(u.id)) continue;

            if (OreSafety.isThreatened(u.x, u.y)) {
                // GL: к ядру под огнём не ведём, иначе юнит мечется. Нет безопасного ядра — оставляем как есть.
                Building closeCore = OreSafety.nearestSafeCore(u);
                if (closeCore == null) continue;
                retreating.add(u.id);

                if (!retreatGroups.containsKey(closeCore)) {
                    retreatGroups.put(closeCore, new IntSeq());
                }
                retreatGroups.get(closeCore).add(u.id);
            }
        }

        // Отправляем одной пачкой для каждого ядра
        for (var entry : retreatGroups.entries()) {
            Building core = entry.key;
            int[] ids = entry.value.toArray();

            Call.setUnitCommand(player, ids, UnitCommand.moveCommand);
            Call.setUnitStance(player, ids, UnitStance.boost, true);
            Call.commandUnits(player, ids, null, null, new Vec2(core.x, core.y), false, true);

            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.moveCommand);
            }
        }
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
            if (manualUnits.contains(u.id) || healingUnits.contains(u.id)) continue; // Не трогаем ручных и лечащихся юнитов
            // Юниты на починке (авто-хил мег) не дёргаем в ассист, иначе флап ремонт/ассист.
            if (u.controller() instanceof CommandAI rep && rep.command == UnitCommand.repairCommand) continue;

            boolean inRange = u.dst(px, py) <= radiusPx;
            boolean isCurrentlyAssist = u.controller() instanceof CommandAI cai && cai.command == UnitCommand.assistCommand;

            if (buildingNearby && inRange && isAssistBuilderType(u.type)) {
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

        // --- Обновляем карту угроз
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

            if (manualUnits.contains(u.id) || assistingUnits.contains(u.id) || healingUnits.contains(u.id)) continue;

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
            if (toReleaseAsAssist.size > 0) Call.setUnitCommand(player, toReleaseAsAssist.toArray(), UnitCommand.assistCommand);
            if (toReleaseAsMine.size > 0) Call.setUnitCommand(player, toReleaseAsMine.toArray(), UnitCommand.mineCommand);
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

            // Назначение конкретных юнитов с проверкой БЕЗОПАСНОСТИ ДЛЯ ИХ ЯДРА
            for (Unit u : units) {
                // Выбираем только те ресурсы, которые БЕЗОПАСНЫ для конкретного ядра этого юнита
                Seq<Item> safeForUnit = possible.select(it -> !oreSafetyEnabled || OreSafety.isItemSafeForUnit(u, it));
                if (safeForUnit.isEmpty()) continue; // В районе ядра всё простреливается — не трогаем

                Item currentItem = currentMinedItem(u);

                // Липкость: если юнит уже копает безопасный нужный ресурс — оставляем
                if (currentItem != null && safeForUnit.contains(currentItem) && quotas.get(currentItem, 0) > 0) {
                    quotas.put(currentItem, quotas.get(currentItem, 0) - 1);
                    continue;
                }

                // Иначе выбираем лучший из доступных безопасных
                Item bestTarget = safeForUnit.max(it -> quotas.get(it, 0));
                if (bestTarget != null && quotas.get(bestTarget, 0) > 0) {
                    quotas.put(bestTarget, quotas.get(bestTarget, 0) - 1);

                    if (!toBatchSend.containsKey(bestTarget)) toBatchSend.put(bestTarget, new IntSeq());
                    toBatchSend.get(bestTarget).add(u.id);
                } else {
                    Item fallback = safeForUnit.max(it -> itemWeights.get(it, fullCoreWeight));
                    if (fallback != null && !(u.controller() instanceof CommandAI cai && cai.command == UnitCommand.mineCommand && cai.hasStance(ItemUnitStance.getByItem(fallback)))) {
                        if (!toBatchSend.containsKey(fallback)) toBatchSend.put(fallback, new IntSeq());
                        toBatchSend.get(fallback).add(u.id);
                    }
                }
            }
        }

        // 5. Массовая отправка команд
        for (var entry : toBatchSend.entries()) {
            int[] ids = entry.value.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.mineCommand);
            Call.setUnitStance(player, ids, UnitStance.mineAuto, false);
            Call.setUnitStance(player, ids, ItemUnitStance.getByItem(entry.key), true);
            for (int id : ids) {
                lastAiCommand.put(id, UnitCommand.mineCommand);
                retreating.remove(id);
            }
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

    // ================== РЕМОНТ: ПОВРЕЖДЁННЫЕ ЮНИТЫ ЛЕТЯТ К РЕМОНТНОЙ ТОЧКЕ ==================

    /**
     * Miners below {@link #unitRepairGoHp} fly to the nearest repair point / repair tower of the team,
     * stay there until almost fully healed, then go back to the ore they were mining.
     */
    private static void handleUnitRepair() {
        if (player.team().core() == null) return;

        repairPads.clear();
        Seq<Building> flagged = Vars.indexer.getFlagged(player.team(), BlockFlag.repair);
        if (flagged != null) repairPads.addAll(flagged.select(b -> b.block instanceof RepairTurret || b.block instanceof RepairTower));
        for (Building b : player.team().data().buildings) {
            if (b.block instanceof RepairTower && !repairPads.contains(b)) repairPads.add(b);
        }
        if (repairPads.isEmpty()) {
            if (healingUnits.notEmpty()) releaseHealing(null);
            return;
        }

        ObjectMap<Building, IntSeq> moves = new ObjectMap<>();
        IntSeq boost = new IntSeq(), healed = new IntSeq();

        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable() || !isManagedMinerType(u.type)) continue;
            if (manualUnits.contains(u.id)) continue;

            float hp = u.maxHealth <= 0f ? 1f : u.health / u.maxHealth;
            Building pad = nearestPad(u);

            if (healingUnits.contains(u.id)) {
                if (pad == null || hp >= unitRepairDoneHp) {
                    healed.add(u.id);
                } else if (u.dst(pad) > repairRadius(pad) * 0.75f) {
                    moves.get(pad, IntSeq::new).add(u.id);
                }
                continue;
            }

            if (pad != null && hp < unitRepairGoHp) {
                healingUnits.add(u.id);
                assistingUnits.remove(u.id);
                Item mined = currentMinedItem(u);
                if (mined != null) healingItem.put(u.id, mined);
                moves.get(pad, IntSeq::new).add(u.id);
                // mechs (pulsar/quasar) fly to the pad instead of walking
                if (u.type.canBoost) boost.add(u.id);
            }
        }

        for (var e : moves.entries()) {
            int[] ids = e.value.toArray();
            Call.setUnitCommand(player, ids, UnitCommand.moveCommand);
            Call.commandUnits(player, ids, null, null, new Vec2(e.key.x, e.key.y), false, true);
            for (int id : ids) lastAiCommand.put(id, UnitCommand.moveCommand);
        }
        if (boost.size > 0) Call.setUnitStance(player, boost.toArray(), UnitStance.boost, true);
        if (healed.size > 0) releaseHealing(healed);
    }

    /** Sends healed units (or all healing units, if ids is null) back to mining. */
    private static void releaseHealing(IntSeq ids) {
        if (ids == null) {
            ids = new IntSeq();
            IntSeq all = ids;
            healingUnits.each(all::add);
        }
        ObjectMap<Item, IntSeq> byItem = new ObjectMap<>();
        IntSeq plain = new IntSeq(), unboost = new IntSeq();
        for (int i = 0; i < ids.size; i++) {
            int id = ids.get(i);
            healingUnits.remove(id);
            Item item = healingItem.remove(id);
            Unit u = Groups.unit.getByID(id);
            if (u == null) continue;
            if (u.type.canBoost) unboost.add(id);
            if (item != null) byItem.get(item, IntSeq::new).add(id);
            else plain.add(id);
        }
        if (unboost.size > 0) Call.setUnitStance(player, unboost.toArray(), UnitStance.boost, false);
        for (var e : byItem.entries()) {
            int[] arr = e.value.toArray();
            Call.setUnitCommand(player, arr, UnitCommand.mineCommand);
            Call.setUnitStance(player, arr, UnitStance.mineAuto, false);
            Call.setUnitStance(player, arr, ItemUnitStance.getByItem(e.key), true);
            for (int id : arr) lastAiCommand.put(id, UnitCommand.mineCommand);
        }
        if (plain.size > 0) {
            Call.setUnitCommand(player, plain.toArray(), UnitCommand.mineCommand);
            for (int i = 0; i < plain.size; i++) lastAiCommand.put(plain.get(i), UnitCommand.mineCommand);
        }
    }

    /** Nearest repair pad, preferring powered ones. */
    private static Building nearestPad(Unit u) {
        Building best = null, bestAny = null;
        float bestD = Float.MAX_VALUE, bestAnyD = Float.MAX_VALUE;
        for (Building b : repairPads) {
            if (!b.isValid()) continue;
            if (oreSafetyEnabled && OreSafety.isThreatened(b.x, b.y)) continue; // GL: не лечиться под огнём
            float d = u.dst2(b);
            if (d < bestAnyD) { bestAnyD = d; bestAny = b; }
            if (b.efficiency > 0.01f && d < bestD) { bestD = d; best = b; }
        }
        return best != null ? best : bestAny;
    }

    private static float repairRadius(Building b) {
        if (b.block instanceof RepairTurret rt) return Math.max(24f, rt.repairRadius);
        if (b.block instanceof RepairTower rt) return Math.max(24f, rt.range);
        return 48f;
    }

    private static boolean isPlayerBuilding() {
        Unit u = player.unit();
        if (u == null) return false;

        float maxDist = (AIHelpRad * 8f) * 2f;

        if (u.activelyBuilding()) {
            var plan = u.buildPlan();
            if (plan != null && u.dst(plan.drawx(), plan.drawy()) <= maxDist) return true;
        }

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