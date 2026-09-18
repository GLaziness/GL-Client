package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.input.*;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.client.Spectate;
import mindustry.content.StatusEffects;

import static arc.Core.scene;
import static mindustry.Vars.*;

public class WaveInfoFrag extends Table {
    private Table container = new Table();
    private Table contenttab = new Table();
    private int waveOffset = 0;
    private boolean visible = false;

    private float lastX = 0, lastY = 0;
    private boolean centered = false;
    public enum DisplayMode { MIN, STD, DET }
    private DisplayMode displayMode = DisplayMode.values()[Core.settings.getInt("wavefrag-mode", 1)];

    public void build(Group parent) {
        parent.addChild(this);
        //setSize(300f, 400f);

        float w = Core.settings.getFloat("wavefragwidth", 300f);
        float h = Core.settings.getFloat("wavefragheight", 400f);
        if(w < 50f) w = 300f;
        if(h < 50f) h = 400f;
        setSize(w, h);

        Events.on(EventType.WaveEvent.class, e -> rebuild());


        visible(() -> ui.hudfrag.shown && visible && state.isGame() && Core.settings.getBool("wavefragment", false));

        table(Styles.black6, main -> {
            main.table(ctrl -> {
                ImageButton drag = ctrl.button(Icon.move, Styles.cleari, () -> {}).size(35f).get();
                drag.addListener(new InputListener() {
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        lastX = x; lastY = y; return true;
                    }
                    @Override
                    public void touchDragged(InputEvent event, float x, float y, int pointer) {
                        moveBy(x - lastX, y - lastY);
                    }

                    @Override
                    public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        Core.settings.put("wavefrag-x", WaveInfoFrag.this.x);
                        Core.settings.put("wavefrag-y", WaveInfoFrag.this.y);
                    }
                });

                ctrl.add().growX().top();
                ctrl.button(Icon.left, Styles.cleari, () -> { waveOffset -= Core.input.shift() ? 10 : 1; rebuild(); }).size(40f).disabled(b -> state.wave + waveOffset <= 1);
                ctrl.button(Icon.refresh, Styles.cleari, () -> { waveOffset = 0; rebuild(); }).size(40f);
                ctrl.button(Icon.right, Styles.cleari, () -> { waveOffset += Core.input.shift() ? 10 : 1; rebuild(); }).size(40f);
                ctrl.button(Icon.uploadSmall, Styles.cleari, () -> {
                    displayMode = DisplayMode.values()[(displayMode.ordinal() + 1) % DisplayMode.values().length];
                    Core.settings.put("wavefrag-mode", displayMode.ordinal());
                    rebuild();
                }).size(35f).tooltip("Toggle display mode:\n[gray]MIN[] - Icons only\n[accent]STD[] - With HP/Shield bars\n[orange]DET[] - +Per-unit stats");
                ctrl.add().growX();
                ctrl.button(Icon.cancel, Styles.cleari, this::toggle).size(35f);
            }).growX().top().pad(2f);

            main.row().top();
            main.image().growX().height(2f).color(Pal.accent);
            main.row();

            main.pane(p -> {
                p.top();
                p.add(contenttab).growX().top();
                container = contenttab;
                rebuild();
            }).growX().top().update(pane -> {
                if(scene.getScrollFocus() == pane && !Core.input.shift()){
                    scene.setScrollFocus(null);
                }
            });
        }).growX().top();

        update(() -> {
            if(!centered && Core.graphics.getWidth() > 0){
                if(Core.settings.has("wavefrag-x") && Core.settings.has("wavefrag-y")){
                    float sx = Core.settings.getFloat("wavefrag-x");
                    float sy = Core.settings.getFloat("wavefrag-y");

                    sx = Mathf.clamp(sx, 0, Core.graphics.getWidth() - width);
                    sy = Mathf.clamp(sy, 0, Core.graphics.getHeight() - height);
                    setPosition(sx, sy);
                } else {
                    setPosition(Core.graphics.getWidth() / 2f, 150f, Align.bottom);
                }
                centered = true;
            }
        });

    }
    public void updateSize(){
        rebuild();
        float w = Core.settings.getFloat("wavefragwidth", 300f);
        float h = Core.settings.getFloat("wavefragheight", 400f);
        if(w < 50f) w = 300f;
        if(h < 50f) h = 400f;
        setSize(w, h);
    }
    public void resetPos(){
        setPosition(Core.graphics.getWidth() / 2f, 150f, Align.bottom);
        rebuild();
    }

    public void rebuild() {
        if (container == null) return;
        container.clear();
        container.defaults().growX().margin(0f).pad(0f);

        int startWave = state.wave + waveOffset;
        int spawnCount = Math.max(spawner.getSpawns().size, 1);
        float font_offset = Core.settings.getFloat("wave_font_offset", 1);

        for (int i = 0; i < 4; i++) {
            int displayWave = startWave + i;
            if (displayWave <= 0) continue;
            int internalWave = displayWave - 1;

            container.table(Styles.black3, card -> {
                card.margin(2f).top();
                card.touchable = Touchable.enabled;
                card.addListener(new HandCursorListener());
                card.clicked(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Wave ").append(displayWave).append(":\n");
                    int spawnCountCopy = Math.max(spawner.getSpawns().size, 1);
                    for (SpawnGroup group : state.rules.spawns) {
                        int amt = group.getSpawned(internalWave);
                        if (amt <= 0) continue;
                        if (group.spawn == -1 || spawnCountCopy <= 1) {
                            sb.append(" ").append(Fonts.getUnicodeStr(group.type.name))
                                    .append("x").append(amt * (spawnCountCopy <= 1 ? 1 : spawnCountCopy));
                        } else {
                            sb.append("- (").append(Point2.x(group.spawn)).append(",").append(Point2.y(group.spawn))
                                    .append(") ").append(Fonts.getUnicodeStr(group.type.name))
                                    .append("x").append(amt);
                        }
                        if (group.effect != null && group.effect != StatusEffects.none) {
                            sb.append(Fonts.getUnicodeStr(group.effect.name));
                        }
                        sb.append("\n");
                    }
                    Core.app.setClipboardText(sb.toString());
                });

                // Заголовок волны с барами ХП/щита
                card.table(ht -> {
                    ht.defaults().fontScale(0.8f * font_offset);

                    if (displayMode != DisplayMode.MIN) {
                        WaveTotals totals = calculateWaveTotals(internalWave, spawnCount);
                        ht.add((displayWave == state.wave ? "[accent]" : "[white]") + "WAVE " + displayWave)
                                .left().fontScale(0.8f * font_offset);

                        ht.add().growX();

                        Table hpBar = new Table();
                        hpBar.defaults().right();
                        hpBar.add("[scarlet]HP:[] ").fontScale(0.7f * font_offset);
                        hpBar.add(totals.formatHp()).color(Pal.health).fontScale(0.75f * font_offset);
                        ht.add(hpBar).right().width(100f).height(18f).padRight(5f);

                        Table shieldBar = new Table();
                        shieldBar.defaults().right();
                        shieldBar.add("[sky]Sh:[] ").fontScale(0.7f * font_offset);
                        shieldBar.add(totals.formatShield()).color(Pal.shield).fontScale(0.75f * font_offset);
                        ht.add(shieldBar).right().width(100f).height(18f);
                    } else {
                        ht.add((displayWave == state.wave ? "[accent]" : "[white]") + "WAVE " + displayWave);
                    }
                }).growX().center().row();

                card.image().height(1f).growX().color(Color.darkGray).row();

                // Тело карточки
                card.table(units -> {
                    units.left().defaults().left();

                    if (displayMode == DisplayMode.MIN) {
                        // РЕЖИМ 1: МИНИМУМ (Иконки в ряд)
                        ObjectMap<SpawnKey, Integer> flatMap = new ObjectMap<>();
                        for (SpawnGroup group : state.rules.spawns) {
                            int amt = group.getSpawned(internalWave);
                            if (amt <= 0) continue;
                            int finalAmt = amt * (group.spawn == -1 ? spawnCount : 1);

                            StatusEffect eff = (group.effect == StatusEffects.none) ? null : group.effect;
                            SpawnKey key = new SpawnKey(group.type, eff);
                            flatMap.put(key, flatMap.get(key, 0) + finalAmt);
                        }

                        if (flatMap.isEmpty()) {
                            units.add("No units").color(Color.gray).fontScale(0.7f * font_offset).center().growX();
                        } else {
                            int col = 0;
                            for (var entry : flatMap.entries()) {
                                if (col > 0 && col % 6 == 0) units.row();
                                Table uRow = new Table();
                                uRow.image(entry.key.type.uiIcon).size(16f * font_offset);
                                if (entry.key.effect != null) uRow.image(entry.key.effect.uiIcon).size(10f).padLeft(1f);
                                units.add(uRow).padRight(4f);
                                col++;
                            }
                        }
                    } else {
                        // РЕЖИМ 2 (STD) и 3 (DET)
                        ObjectMap<SpawnKey, UnitStats> allStatsMap = new ObjectMap<>();
                        ObjectMap<Integer, ObjectMap<SpawnKey, UnitStats>> groupedSpecificStats = new ObjectMap<>();

                        for (SpawnGroup group : state.rules.spawns) {
                            int amt = group.getSpawned(internalWave);
                            if (amt <= 0) continue;

                            float shieldPerUnit = group.getShield(internalWave);
                            StatusEffect eff = (group.effect == StatusEffects.none) ? null : group.effect;
                            SpawnKey key = new SpawnKey(group.type, eff);

                            if (displayMode == DisplayMode.STD) {
                                // В STD всё суммируем в одну общую таблицу (allStatsMap)
                                int finalAmt = amt * (group.spawn == -1 ? spawnCount : 1);
                                if (!allStatsMap.containsKey(key)) {
                                    allStatsMap.put(key, new UnitStats(group.type, 0, shieldPerUnit));
                                }
                                allStatsMap.get(key).add(group.type, finalAmt, shieldPerUnit);
                            } else {
                                // В DET разделяем: общие отдельно, точечные отдельно
                                if (group.spawn == -1 || spawnCount <= 1) {
                                    int finalAmt = amt * (spawnCount <= 1 ? 1 : spawnCount);
                                    if (!allStatsMap.containsKey(key)) allStatsMap.put(key, new UnitStats(group.type, 0, shieldPerUnit));
                                    allStatsMap.get(key).add(group.type, finalAmt, shieldPerUnit);
                                } else {
                                    if (!groupedSpecificStats.containsKey(group.spawn)) groupedSpecificStats.put(group.spawn, new ObjectMap<>());
                                    if (!groupedSpecificStats.get(group.spawn).containsKey(key)) {
                                        groupedSpecificStats.get(group.spawn).put(key, new UnitStats(group.type, 0, shieldPerUnit));
                                    }
                                    groupedSpecificStats.get(group.spawn).get(key).add(group.type, amt, shieldPerUnit);
                                }
                            }
                        }

                        if (allStatsMap.isEmpty() && groupedSpecificStats.isEmpty()) {
                            units.add("No units").color(Color.gray).fontScale(0.7f * font_offset).center().growX();
                        } else {
                            // Отрисовка суммарной таблицы (в STD это будет всё, в DET - только юниты "All Spawns")
                            if (!allStatsMap.isEmpty()) {
                                units.table(allTable -> {
                                    allTable.left();
                                    if (displayMode == DisplayMode.DET) {
                                        String label = spawnCount <= 1 ? "(All) " : "(All*" + spawnCount + ") ";
                                        allTable.add("[lightgray]" + label).fontScale(0.65f * font_offset);
                                    } else if (displayMode == DisplayMode.STD && spawnCount > 1) {
                                        // В STD просто пишем количество источников, если их больше одного
                                        allTable.add("[lightgray](x" + spawnCount + ") ").fontScale(0.65f * font_offset);
                                    }

                                    int col = 0;
                                    int itemsPerRow = (displayMode == DisplayMode.DET) ? 3 : 5;

                                    for (var entry : allStatsMap.entries()) {
                                        if (col > 0 && col % itemsPerRow == 0) {
                                            allTable.row();
                                            if (displayMode == DisplayMode.DET) allTable.add(); // Отступ под лейблом (All)
                                        }

                                        Table uRow = new Table();
                                        uRow.image(entry.key.type.uiIcon).size(16f * font_offset);
                                        UnitStats stats = entry.value;

                                        if (displayMode == DisplayMode.DET) {
                                            uRow.add("[white]" + stats.count).fontScale(0.65f * font_offset).padLeft(1f).width(22f);
                                            uRow.add("[scarlet]" + stats.formatHp()).fontScale(0.55f * font_offset).padLeft(1f).width(35f);
                                            if (stats.totalShield > 0) {
                                                uRow.add("[sky]" + stats.formatShield()).fontScale(0.55f * font_offset).padLeft(1f).width(35f);
                                            }
                                        } else {
                                            // В STD просто иконка + количество
                                            uRow.add("[white]x" + stats.count).fontScale(0.75f * font_offset).padLeft(2f);
                                        }

                                        if (entry.key.effect != null) uRow.image(entry.key.effect.uiIcon).size(10f).padLeft(1f);
                                        allTable.add(uRow).padRight(6f).padBottom(2f);
                                        col++;
                                    }
                                }).growX().row();
                            }

                            // Отрисовка специфичных спавнов (только для DET)
                            if (displayMode == DisplayMode.DET) {
                                for (var spawnEntry : groupedSpecificStats.entries()) {
                                    units.table(row -> {
                                        row.left();
                                        String loc = "(" + Point2.x(spawnEntry.key) + "," + Point2.y(spawnEntry.key) + ")";
                                        Label l = row.add("[gray]" + loc + " ").fontScale(0.65f * font_offset).get();
                                        l.addListener(new HandCursorListener());
                                        l.clicked(() -> Spectate.INSTANCE.spectate(new Vec2(Point2.x(spawnEntry.key) * tilesize, Point2.y(spawnEntry.key) * tilesize)));

                                        for (var unitEntry : spawnEntry.value.entries()) {
                                            Table uRow = new Table();
                                            uRow.image(unitEntry.key.type.uiIcon).size(14f * font_offset).padLeft(2f);
                                            UnitStats stats = unitEntry.value;

                                            uRow.add("[white]" + stats.count).fontScale(0.65f * font_offset).padLeft(1f).width(20f);
                                            uRow.add("[scarlet]" + stats.formatHp()).fontScale(0.55f * font_offset).padLeft(1f).width(35f);
                                            if (stats.totalShield > 0) uRow.add("[sky]" + stats.formatShield()).fontScale(0.55f * font_offset).padLeft(1f).width(35f);
                                            if (unitEntry.key.effect != null) uRow.image(unitEntry.key.effect.uiIcon).size(10f).padLeft(1f);

                                            row.add(uRow);
                                        }
                                    }).growX().row();
                                }
                            }
                        }
                    }
                }).growX().padTop(1f);

// ... остальной код ...
            }).growX().pad(0f).row();
        }
    }

    // === Метод для расчёта суммарной статистики волны ===
    private WaveTotals calculateWaveTotals(int internalWave, int spawnCount) {
        WaveTotals totals = new WaveTotals();

        for (SpawnGroup group : state.rules.spawns) {
            int amt = group.getSpawned(internalWave);
            if (amt <= 0) continue;

            float shieldPerUnit = group.getShield(internalWave);
            int finalAmt = amt;

            if (group.spawn == -1 || spawnCount <= 1) {
                finalAmt = amt * (spawnCount <= 1 ? 1 : spawnCount);
            }

            totals.totalHp += group.type.health * finalAmt;
            totals.totalShield += shieldPerUnit * finalAmt;
            totals.totalCount += finalAmt;
        }

        return totals;
    }

    // === Класс для хранения статистики юнита ===
    private static class UnitStats {
        float totalHp, totalShield;
        int count;

        UnitStats(UnitType type, int count, float shieldPerUnit) {
            this.count = count;
            this.totalHp = type.health * count;
            this.totalShield = shieldPerUnit * count;
        }

        void add(UnitType type, int addCount, float shieldPerUnit) {
            this.count += addCount;
            this.totalHp += type.health * addCount;
            this.totalShield += shieldPerUnit * addCount;
        }

        String formatHp() { return formatNum(totalHp); }
        String formatShield() { return formatNum(totalShield); }

        private String formatNum(float v) {
            if (v <= 0) return "0";
            if (v >= 1_000_000) return Strings.autoFixed(v / 1_000_000, 1) + "M";
            if (v >= 1_000) return Strings.autoFixed(v / 1_000, 1) + "k";
            return Strings.autoFixed(v, 0);
        }
    }

    // === Класс для суммарной статистики волны ===
    private static class WaveTotals {
        float totalHp, totalShield;
        int totalCount;

        String formatHp() {
            if (totalHp <= 0) return "0";
            if (totalHp >= 1_000_000) return Strings.autoFixed(totalHp / 1_000_000, 1) + "M";
            if (totalHp >= 1_000) return Strings.autoFixed(totalHp / 1_000, 1) + "k";
            return Strings.autoFixed(totalHp, 0);
        }

        String formatShield() {
            if (totalShield <= 0) return "0";
            if (totalShield >= 1_000_000) return Strings.autoFixed(totalShield / 1_000_000, 1) + "M";
            if (totalShield >= 1_000) return Strings.autoFixed(totalShield / 1_000, 1) + "k";
            return Strings.autoFixed(totalShield, 0);
        }
    }

    private static class SpawnKey {
        UnitType type;
        StatusEffect effect;
        int spawnPos; // -1 для "All"

        SpawnKey(UnitType type, StatusEffect effect) {
            this(type, effect, -1);
        }
        SpawnKey(UnitType type, StatusEffect effect, int spawnPos) {
            this.type = type;
            this.effect = effect;
            this.spawnPos = spawnPos;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SpawnKey)) return false;
            SpawnKey key = (SpawnKey) o;
            return type == key.type && effect == key.effect && spawnPos == key.spawnPos;
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + (effect != null ? effect.hashCode() : 0) * 17 + spawnPos;
        }
    }

    public void toggle() { visible = !visible; rebuild(); if(visible) toFront(); }

    private ObjectMap<SpawnKey, UnitStats> calculateWaveStats(int internalWave, int spawnCount) {
        ObjectMap<SpawnKey, UnitStats> statsMap = new ObjectMap<>();
        for (SpawnGroup group : state.rules.spawns) {
            int amt = group.getSpawned(internalWave);
            if (amt <= 0) continue;

            // Получаем щиты для этой волны
            float shield = group.getShield(internalWave);

            StatusEffect eff = (group.effect == StatusEffects.none) ? null : group.effect;
            SpawnKey key = new SpawnKey(group.type, eff);

            if (group.spawn == -1 || spawnCount <= 1) {
                SpawnKey allKey = new SpawnKey(group.type, eff);
                if (!statsMap.containsKey(allKey))
                    statsMap.put(allKey, new UnitStats(group.type, 0, shield));
                statsMap.get(allKey).add(group.type, amt * (spawnCount <= 1 ? 1 : spawnCount), shield);
            } else {
                SpawnKey specKey = new SpawnKey(group.type, eff, group.spawn);
                if (!statsMap.containsKey(specKey))
                    statsMap.put(specKey, new UnitStats(group.type, 0, shield));
                statsMap.get(specKey).add(group.type, amt, shield);
            }
        }
        return statsMap;
    }

}