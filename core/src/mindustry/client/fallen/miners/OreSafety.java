package mindustry.client.fallen.miners;

import arc.Core;
import arc.struct.FloatSeq;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.Turret;

import static mindustry.Vars.player;

// === Карта рудных жил + защита майнеров от вражеских пушек ===
// При загрузке карты один раз сканируем все тайлы и собираем "жилы" -
// связные области одного ресурса (flood fill по drop() тайла).
// Каждый цикл ИИ обновляем угрозу: пушка врага = турель в радиусе её стрельбы.
// Жила "доступна", если в ней есть свободные (не занятые постройками) тайлы
// и (при включённой защите) её образцы не простреливаются врагом.
// Ресурс без доступных жил исключается из распределения квот,
// а юнитам, которых отправляют к простреливаемой руде, даётся отвод
// к ближайшей безопасной жиле того же ресурса.

public class OreSafety {

    /** Жила одного ресурса: связный набор тайлов с одинаковым drop(). */
    public static class Cluster {
        public Item item;
        /** Центр жилы в мировых пикселях. */
        public float x, y;
        /** Всего тайлов в жиле. */
        public int tiles;
        /** Есть образцы под огнём врага. */
        public boolean threatened;
        /** Есть свободные (не занятые постройками) образцы. */
        public boolean freeTiles;
        /** Точки-образцы для проверок (тайловые координаты). */
        int[] sampleX, sampleY;
    }

    /** Запас от края радиуса пушки, чтобы юнит не висел на самой границе огня (3 тайла). */
    //public static float turretSafeRadius = Core.settings.getFloat("MAI_tM", 3f);
    public static float turretSafeRadius = Core.settings.getFloat("fdmai-turrad", 3f);
    public static float spawnSafeRadius = Core.settings.getFloat("fdmai-spawnrad", 10f);

    /** Доля пути под огнём, выше которой полёт к жиле считается опасным. */
    private static final float PATH_BLOCKED_FRACTION = 0.1f;
    /** Максимум точек-образцов на жилу. */
    private static final int MAX_SAMPLES = 28;
    /** Шаг проверки линии пути, в пикселях. */
    private static final float PATH_STEP = 16f;
    /** Максимум точек на линии пути. */
    private static final int MAX_PATH_SAMPLES = 64;
    /** Максимальное количество тайлов в одной жиле/кластере. */
    private static final int MAX_CLUSTER_TILES = 64;
    /** Максимальный радиус жилы от стартовой точки (в тайлах). */
    private static final int MAX_CLUSTER_RADIUS = 10;

    static final Seq<Cluster> clusters = new Seq<>();
    /** Вражеские пушки тройками [x, y, радиус+запас], обновляется раз в цикл ИИ. */
    private static final FloatSeq dangerTilesData = new FloatSeq();

    private OreSafety() {}

    public static void reset() {
        clusters.clear();
        dangerTilesData.clear();
    }


    // ================== СКАНИРОВАНИЕ ЖИЛ ==================

    /** Строит жилы по всей карте. Вызывать один раз на WorldLoadEvent. */
    public static void scan() {
        clusters.clear();

        if (Vars.world == null || Vars.world.width() == 0 || Vars.world.height() == 0) return;

        int w = Vars.world.width(), h = Vars.world.height();

        // Один проход по карте для снятия карты дропов.
        Item[] dropGrid = new Item[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Tile t = Vars.world.tile(x, y);
                if (t == null) continue;
                dropGrid[x + y * w] = t.drop();
            }
        }

        boolean[] visited = new boolean[w * h];
        // Используем IntSeq как BFS-очередь (быстро и без аллокаций памяти)
        IntSeq queue = new IntSeq();

        for (int start = 0; start < w * h; start++) {
            if (visited[start] || dropGrid[start] == null) continue;

            Item item = dropGrid[start];
            queue.clear();
            queue.add(start);
            visited[start] = true;

            int startX = start % w, startY = start / w;
            long sumX = 0, sumY = 0;
            int head = 0;

            // BFS-обход с ограничением по размеру и радиусу
            while (head < queue.size && queue.size < MAX_CLUSTER_TILES) {
                int cur = queue.get(head++);
                int cx = cur % w, cy = cur / w;
                sumX += cx;
                sumY += cy;

                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;

                        // Ограничение: не уходим дальше MAX_CLUSTER_RADIUS от начала жилы
                        if (Math.abs(nx - startX) > MAX_CLUSTER_RADIUS || Math.abs(ny - startY) > MAX_CLUSTER_RADIUS) continue;

                        int nidx = nx + ny * w;
                        if (visited[nidx] || dropGrid[nidx] != item) continue;

                        visited[nidx] = true;
                        queue.add(nidx);

                        // Если достигли лимита тайлов на жилу — прекращаем расширение
                        if (queue.size >= MAX_CLUSTER_TILES) break;
                    }
                    if (queue.size >= MAX_CLUSTER_TILES) break;
                }
            }

            // Досчитываем координаты для оставшихся в очереди тайлов (если вышли по брейку)
            while (head < queue.size) {
                int cur = queue.get(head++);
                sumX += (cur % w);
                sumY += (cur / w);
            }

            int count = queue.size;
            if (count == 0) continue;

            Cluster c = new Cluster();
            c.item = item;
            c.tiles = count;
            c.x = (sumX / (float) count) * 8f + 4f;
            c.y = (sumY / (float) count) * 8f + 4f;

            // Образцы раскидываем равномерно внутри этого локального кластера
            int sampleCount = Math.min(MAX_SAMPLES, count);
            int stride = Math.max(1, count / sampleCount);
            c.sampleX = new int[sampleCount];
            c.sampleY = new int[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                int idx = queue.get(i * stride);
                c.sampleX[i] = idx % w;
                c.sampleY[i] = idx / w;
            }

            clusters.add(c);
        }

        Log.info("OreSafety: found @ veins after clustering.", clusters.size);
    }

    // ================== УГРОЗА ==================

    /** Обновляет список вражеских пушек/зон и пересчитывает угрозу/свободность жил. Раз в цикл ИИ. */
    public static void refreshThreat(boolean enabled) {
        dangerTilesData.clear();
        if (enabled) {
            // --- 1. СБОР ПУШЕК ---
            if (player != null) {
                for (Building b : Groups.build) {
                    if (b == null || b.team == player.team()) continue;
                    if (!(b.block instanceof Turret turret)) continue;
                    if (!turret.targetAir) continue;
                    dangerTilesData.add(b.x, b.y, turret.range + turretSafeRadius * 8f);
                }
            }

            // --- 2. ЗОНЫ ВЫСАДКИ ---
            float baseDropZone = Vars.state.rules.dropZoneRadius;
            float extraMargin = spawnSafeRadius * 8f;
            float totalDangerRadius = baseDropZone + extraMargin;

            for (Tile spawnTile : Vars.spawner.getSpawns()) {
                float px = spawnTile.worldx();
                float py = spawnTile.worldy();

                dangerTilesData.add(px, py, totalDangerRadius);
            }
        }

        // --- 3. ПЕРЕСЧЕТ ЖИЛ ---
        for (Cluster c : clusters) {
            c.threatened = false;
            int free = 0;
            for (int i = 0; i < c.sampleX.length; i++) {
                Tile t = Vars.world.tile(c.sampleX[i], c.sampleY[i]);
                if (t == null) continue;
                if (!t.solid()) free++;
                if (isThreatened(t.worldx(), t.worldy())) c.threatened = true;
            }
            c.freeTiles = free > 0;
        }
    }

    /** Находится ли тайл под огнём вражеских пушек. */
    public static boolean isThreatened(float x, float y) {
        float[] d = dangerTilesData.items;
        for (int i = 0; i < dangerTilesData.size; i += 3) {
            float dx = x - d[i], dy = y - d[i + 1];
            float r = d[i + 2];
            if (dx * dx + dy * dy <= r * r){
                return true;
            }
        }
        return false;
    }

    // ================== ЗАПРОСЫ ==================

    /**
     * Глобальная проверка: есть ли на карте ХОТЯ БЫ ОДНО ядро,
     * у которого ближайшая жила этого ресурса БЕЗОПАСНА.
     */
    public static boolean itemAvailable(Item item, boolean safetyEnabled) {
        if (player == null || player.team() == null) return false;
        var cores = player.team().cores();
        if (cores.isEmpty()) return false;

        if (!safetyEnabled) {
            // Если безопасность выключена — смотрим просто физическое наличие
            for (Cluster c : clusters) {
                if (c.item == item && c.freeTiles) return true;
            }
            return false;
        }

        // Ищем, есть ли хоть одно ядро с безопасной ближайшей рудой
        for (Building core : cores) {
            if (isCoreOreSafe(core, item)) return true;
        }

        return false;
    }

    /**
     * Локальная проверка для конкретного юнита:
     * Безопасна ли эта руда для этого юнита с учетом его позиции и ближайшего ядра?
     */
    public static boolean isItemSafeForUnit(Unit u, Item item) {
        if (u == null) return false;
        Building nearestCore = u.closestCore();
        if (nearestCore == null) return false;

        return isCoreOreSafe(nearestCore, item);
    }

    /** Вспомогательный метод: проверяет, безопасна ли ближайшая жила ресурса от конкретного ядра */
    public static boolean isCoreOreSafe(Building core, Item item) {
        Cluster nearest = null;
        float minDst = Float.MAX_VALUE;

        for (Cluster c : clusters) {
            if (c.item != item || !c.freeTiles) continue;
            float dst = (c.x - core.x) * (c.x - core.x) + (c.y - core.y) * (c.y - core.y);
            if (dst < minDst) {
                minDst = dst;
                nearest = c;
            }
        }

        // Если жилы нет вообще или ближайшая к ядру жила простреливается — небезопасно.
        // GL: юниты возят руду между ядром и жилой, поэтому и дорога туда-обратно не должна идти через огонь.
        return nearest != null && !nearest.threatened && pathSafe(core.x, core.y, nearest.x, nearest.y);
    }

    /** GL: safe flight along a straight line: at most PATH_BLOCKED_FRACTION of the samples under fire. */
    public static boolean pathSafe(float fromX, float fromY, float toX, float toY) {
        float dist = (float) Math.sqrt((toX - fromX) * (toX - fromX) + (toY - fromY) * (toY - fromY));
        int steps = Math.min(MAX_PATH_SAMPLES, Math.max(2, (int) (dist / PATH_STEP)));
        int blocked = 0;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            if (isThreatened(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t)) blocked++;
        }
        return blocked / (float) (steps + 1) <= PATH_BLOCKED_FRACTION;
    }

    /** GL: nearest core of the team that is not under enemy fire, or null. */
    public static Building nearestSafeCore(Unit u) {
        Building best = null;
        float bestDst = Float.MAX_VALUE;
        for (Building core : u.team.cores()) {
            if (isThreatened(core.x, core.y)) continue;
            float dst = u.dst2(core);
            if (dst < bestDst) {
                bestDst = dst;
                best = core;
            }
        }
        return best;
    }
}
