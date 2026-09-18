package mindustry.client.fallen.miners;

import arc.Core;
import arc.struct.FloatSeq;
import arc.struct.IntMap;
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

    static final Seq<Cluster> clusters = new Seq<>();
    /** Юниты в полёте к безопасной жиле: id -> момент (мс), до которого их не трогаем. */
    private static final IntMap<Long> redirectUntil = new IntMap<>();
    //private static final IntMap<Cluster> redirectTargets = new IntMap<>();
    /** Вражеские пушки тройками [x, y, радиус+запас], обновляется раз в цикл ИИ. */
    private static final FloatSeq dangerTilesData = new FloatSeq();

    private static final IntMap<Cluster> redirectTargets = new IntMap<>();
    private static final IntMap<Long> redirectStartTime = new IntMap<>(); // время начала редиректа
    private static final long REDIRECT_TIMEOUT_MS = 30000L; // 30 секунд

    private OreSafety() {}

    public static void reset() {
        clusters.clear();
        redirectUntil.clear();
        dangerTilesData.clear();
    }


    // ================== СКАНИРОВАНИЕ ЖИЛ ==================

    /** Строит жилы по всей карте. Вызывать один раз на WorldLoadEvent. */
    public static void scan() {
        clusters.clear();
        redirectUntil.clear();

        if (Vars.world == null || Vars.world.width() == 0 || Vars.world.height() == 0) return;

        int w = Vars.world.width(), h = Vars.world.height();

        // Один проход по карте.
        Item[] dropGrid = new Item[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Tile t = Vars.world.tile(x, y);
                if (t == null) continue;
                dropGrid[x + y * w] = t.drop();
            }
        }

        boolean[] visited = new boolean[w * h];
        IntSeq stack = new IntSeq();
        IntSeq fill = new IntSeq();

        for (int start = 0; start < w * h; start++) {
            if (visited[start] || dropGrid[start] == null) continue;

            Item item = dropGrid[start];
            fill.clear();
            stack.clear();
            stack.add(start);
            visited[start] = true;
            long sumX = 0, sumY = 0;

            // Flood fill по 8 соседям на совпадение ресурса.
            while (stack.size > 0) {
                int cur = stack.pop();
                fill.add(cur);
                int cx = cur % w, cy = cur / w;
                sumX += cx;
                sumY += cy;

                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int nidx = nx + ny * w;
                        if (visited[nidx] || dropGrid[nidx] != item) continue;
                        visited[nidx] = true;
                        stack.add(nidx);
                    }
                }
            }

            Cluster c = new Cluster();
            c.item = item;
            c.tiles = fill.size;
            c.x = (sumX / (float) fill.size) * 8f + 4f;
            c.y = (sumY / (float) fill.size) * 8f + 4f;

            // Образцы раскидываем равномерно по порядку заполнения.
            int sampleCount = Math.min(MAX_SAMPLES, fill.size);
            int stride = Math.max(1, fill.size / sampleCount);
            c.sampleX = new int[sampleCount];
            c.sampleY = new int[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                int idx = fill.get(i * stride);
                c.sampleX[i] = idx % w;
                c.sampleY[i] = idx / w;
            }

            clusters.add(c);
        }

        Log.info("OreSafety: found @ veins until first cycle.", clusters.size);
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

    /**
     * Безопасен ли перелёт по прямой до точки: доля пути под огнём
     * не должна превышать PATH_BLOCKED_FRACTION.
     */
    public static boolean pathSafe(float fromX, float fromY, float toX, float toY) {
        float dist = (float) Math.sqrt((toX - fromX) * (toX - fromX) + (toY - fromY) * (toY - fromY));
        int steps = Math.min(MAX_PATH_SAMPLES, Math.max(2, (int) (dist / PATH_STEP)));
        int blocked = 0;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float px = fromX + (toX - fromX) * t;
            float py = fromY + (toY - fromY) * t;
            if (isThreatened(px, py)) blocked++;
        }
        return blocked / (float) (steps + 1) <= PATH_BLOCKED_FRACTION;
    }

    // ================== ЗАПРОСЫ ==================

    /**
     * Есть ли у ресурса хоть одна пригодная жила: руда существует физически,
     * в ней есть свободные тайлы, и (при включённой защите) она не под огнём.
     */
    public static boolean itemAvailable(Item item, boolean safetyEnabled) {
        for (Cluster c : clusters) {
            if (c.item != item) continue;
            if (!c.freeTiles) continue;
            if (safetyEnabled && c.threatened) continue;
            return true;
        }
        return false;
    }

    /** Ближайшая пригодная жила ресурса к точке или null. */
    public static Cluster nearestAvailable(Item item, float x, float y, boolean safetyEnabled) {
        Cluster best = null;
        float bestDst = Float.MAX_VALUE;
        for (Cluster c : clusters) {
            if (c.item != item || !c.freeTiles) continue;
            if (safetyEnabled && c.threatened) continue;
            float dst = (c.x - x) * (c.x - x) + (c.y - y) * (c.y - y);
            if (dst < bestDst) {
                bestDst = dst;
                best = c;
            }
        }
        return best;
    }

    // ================== ОТВОД ЮНИТОВ ==================

    public static void forget(int unitId) {
        redirectTargets.remove(unitId);
        redirectStartTime.remove(unitId);
    }

    public static void markRedirect(int unitId, Cluster target) {
        redirectTargets.put(unitId, target);
        redirectStartTime.put(unitId, Time.millis());
    }

    public static boolean isRedirecting(Unit unit) {
        Cluster target = redirectTargets.get(unit.id);
        if (target == null) return false;

        // Если юнит достиг цели (радиус 20 пикселей) – редирект завершён
        if (unit.dst(target.x, target.y) < 20f) {
            redirectTargets.remove(unit.id);
            redirectStartTime.remove(unit.id);
            return false;
        }

        // Запасной вариант: если прошло больше 30 секунд – сбрасываем
        Long start = redirectStartTime.get(unit.id);
        if (start != null && Time.millis() - start > REDIRECT_TIMEOUT_MS) {
            redirectTargets.remove(unit.id);
            redirectStartTime.remove(unit.id);
            return false;
        }

        return true;
    }
//
}
