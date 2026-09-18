package mindustry.client.fallen;

import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import arc.util.Log;



public class ActionsHistory {

    // Лимит записей для защиты бедной оперативки
    private static final int MAX_HISTORY_SIZE = 50000;

    public static Queue<BlockPlayerPlan> blocksplayersplans = new LimitedQueue<>(MAX_HISTORY_SIZE, "BlocksBuild");
    public static Queue<ItemPlayerPlan> playeritemsplans = new LimitedQueue<>(MAX_HISTORY_SIZE, "ItemsPickup");
    public static Queue<BlockConfigPlayerPlan> blockconfplayersplans = new LimitedQueue<>(MAX_HISTORY_SIZE, "BlockConfigs");
    public static Queue<UnitsKilledByPlayers> deathunitsplan = new LimitedQueue<>(MAX_HISTORY_SIZE, "UnitsKilled");
    public static Queue<UnitsKilledByControllPlayers> deathunitscontrolplan = new LimitedQueue<>(MAX_HISTORY_SIZE, "UnitsControlKill");
    public static Queue<UnitCommandHistoryPlan> unitcommandsplans = new LimitedQueue<>(MAX_HISTORY_SIZE, "UnitCommands");
    public static Queue<UnitStateHistoryPlan> unitstatesplans = new LimitedQueue<>(MAX_HISTORY_SIZE, "UnitStates");
    public static final Seq<String> warnedGriefers = new Seq<>();

    public static final Seq<Player> playeratmap = new Seq<>();

    public static void clearactionhistory() {
        blocksplayersplans.clear();
        blockconfplayersplans.clear();
        deathunitsplan.clear();
        deathunitscontrolplan.clear();
        playeratmap.clear();
        playeritemsplans.clear();
        unitcommandsplans.clear();
        unitstatesplans.clear();
        warnedGriefers.clear();

        ((LimitedQueue<?>)blocksplayersplans).resetWarning();
        ((LimitedQueue<?>)playeritemsplans).resetWarning();
        ((LimitedQueue<?>)blockconfplayersplans).resetWarning();
        ((LimitedQueue<?>)deathunitsplan).resetWarning();
        ((LimitedQueue<?>)deathunitscontrolplan).resetWarning();
        ((LimitedQueue<?>)unitcommandsplans).resetWarning();
        ((LimitedQueue<?>)unitstatesplans).resetWarning();
    }



    public static class LimitedQueue<T> extends Queue<T> {
        private final int limit;
        private final String name;
        private boolean hasWarned = false;

        public LimitedQueue(int limit, String name) {
            super();
            this.limit = limit;
            this.name = name;
        }

        public void resetWarning() {
            this.hasWarned = false;
        }

        @Override
        public void addLast(T object) {
            super.addLast(object);
            checkLimit(true);
        }

        @Override
        public void addFirst(T object) {
            super.addFirst(object);
            checkLimit(false);
        }

        public void checkLimit(boolean isLastAdd) {
            if (this.size > limit) {
                if (isLastAdd) {
                    this.removeFirst();
                } else {
                    this.removeLast();
                }

                if (!hasWarned) {
                    Log.info("[ActionsHistory] Очередь '" + name + "' заполнена (" + limit + ")");
                    if (Vars.ui != null && Vars.ui.hudfrag != null) {
                        Vars.ui.hudfrag.showToast("[orange]История " + name + " заполнена!");
                    }
                    hasWarned = true;
                }
            }
        }
    }


    public static class BlockPlayerPlan {
        public final short x, y, rotation, block;
        public final String lastacs;
        public final Object config;
        public final long timestamp;
        public boolean wasbreaking;

        public BlockPlayerPlan(int x, int y, short rotation, short block, Object config, String lastacs, boolean wasbreaking){
            this.x = (short)x;
            this.y = (short)y;
            this.rotation = rotation;
            this.block = block;
            this.config = config;
            this.lastacs = lastacs;
            this.wasbreaking = wasbreaking;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static class BlockConfigPlayerPlan {
        public final short x, y, block;
        public final String lastacs;
        public final long timestamp;

        public BlockConfigPlayerPlan(int x, int y, short block, String lastacs){
            this.x = (short)x;
            this.y = (short)y;
            this.block = block;
            this.lastacs = lastacs;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static class UnitsKilledByPlayers {
        public Player kplayer;
        public String playerName;
        public UnitType unitType;
        public final float x, y;
        public long timestamp;

        public UnitsKilledByPlayers(Player kplayer, UnitType unitType, float x, float y){
            this.kplayer = kplayer;
            this.playerName = kplayer != null ? kplayer.name : "Unknown";
            this.unitType = unitType;
            this.x = x;
            this.y = y;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static class UnitsKilledByControllPlayers extends UnitsKilledByPlayers {
        public UnitsKilledByControllPlayers(String kplayerName, UnitType unitType, float x, float y){
            super(null, unitType, x, y);
            this.playerName = Strings.stripColors(kplayerName == null ? "Unknown" : kplayerName);
        }
    }

    public static class UnitAtControl {
        public UnitType type;
        public float procX;
        public float procY;
        public int count;

        public UnitAtControl(UnitType type, float procX, float procY, int count){
            this.type = type;
            this.procX = procX;
            this.procY = procY;
            this.count = count;
        }
    }

    public static class ItemPlayerPlan {
        public Player player;
        public Tile tile;
        public Item item;
        public boolean take;
        public long timestamp;

        public ItemPlayerPlan(Player player, Tile tile, Item item, boolean take){
            this.player = player;
            this.tile = tile;
            this.item = item;
            this.take = take;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // Класс для хранения типа и количества
    public static class UnitTypeCount {
        public UnitType type;
        public int count;
        public UnitTypeCount(UnitType type, int count) {
            this.type = type;
            this.count = count;
        }
    }

    // Класс для приказов (Движение/Атака)
    public static class UnitCommandHistoryPlan {
        public final String playerName;
        public final Seq<UnitTypeCount> unitTypes;
        public final float x, y;
        public final String targetName;
        public final long timestamp;

        public UnitCommandHistoryPlan(String playerName,  Seq<UnitTypeCount> unitTypes, float x, float y, String targetName) {
            this.playerName = playerName;
            this.unitTypes = unitTypes;
            this.x = x;
            this.y = y;
            this.targetName = targetName;
            this.timestamp = System.currentTimeMillis();
        }
        public int getTotalCount() {
            int total = 0;
            for(var ut : unitTypes) total += ut.count;
            return total;
        }
    }

    // Класс для смены режима (Строить/Чинить и т.д.)
    public static class UnitStateHistoryPlan {
        public final String playerName;
        public final Seq<UnitTypeCount> unitTypes;
        public final String commandName;
        public final long timestamp;

        public UnitStateHistoryPlan(String playerName,  Seq<UnitTypeCount> unitTypes, String commandName) {
            this.playerName = playerName;
            this.unitTypes = unitTypes;
            this.commandName = commandName;
            this.timestamp = System.currentTimeMillis();
        }
        public int getTotalCount() {
            int total = 0;
            for(var ut : unitTypes) total += ut.count;
            return total;
        }
    }
}