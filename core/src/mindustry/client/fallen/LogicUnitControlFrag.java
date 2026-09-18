package mindustry.client.fallen;

import arc.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.types.LogicAI;
import mindustry.game.EventType;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.blocks.logic.LogicBlock.*;
import mindustry.client.Spectate;

import static arc.Core.scene;
import static mindustry.Vars.*;

public class LogicUnitControlFrag extends Table {
    private Table listTable;
    private final Interval timer = new Interval();
    private final float tableWidth = 320f;
    private float lastOffset = -1;


    public void build(Group parent) {
        parent.fill(cont -> {
            cont.name = "unit-control-root";
            cont.bottom();
            cont.touchable = Touchable.childrenOnly;

            cont.table(Styles.black6, main -> {
                main.top();
                //main.touchable = Touchable.enabled;

                main.table(h -> {
                    h.add("[accent]LOGIC CONTROL[]").left().growX();
                    h.button(Icon.refresh, Styles.cleari, this::rebuild).size(30f);
                }).growX().pad(4).row();

                main.image().growX().height(2f).color(Pal.accent).row();

                main.pane(p -> {
                    p.top();
                    p.touchable = Touchable.enabled;
                    listTable = p;
                }).grow().maxHeight(300f).scrollX(false).update(pane -> {
                    if(scene.getScrollFocus() == pane && !Core.input.shift()){
                        scene.setScrollFocus(null);
                    }
                });

                main.update(() -> {
                    //main.translation.x = -Core.settings.getFloat("uicontrolfragoffset", 0f);
                    float offset = Core.settings.getFloat("uicontrolfragoffset", 0f);

                    if (lastOffset != offset) {
                        var cell = cont.getCell(main);
                        if (cell != null) {
                            cell.padLeft(offset);
                            lastOffset = offset;
                            cont.invalidateHierarchy();
                        }
                    }
                });

            }).width(tableWidth).margin(4f).touchable(Touchable.enabled);

            cont.visible(() -> state.isGame() &&
                    Core.settings.getBool("unitcontrolfragment", false) &&
                    ui.hudfrag.shown
            );
        });

        Events.on(EventType.UnitSpawnEvent.class, event -> rebuild());
        Events.on(EventType.WorldLoadEvent.class, event -> rebuild());
        Events.run(EventType.Trigger.update, () -> {
            if(Core.settings.getBool("unitcontrolfragment", false) && timer.get(120)){
                rebuild();
            }
        });
    }

    public void rebuild() {
        if (listTable == null || !state.isGame()) return;
        listTable.clear();

        ObjectMap<Building, ObjectIntMap<UnitType>> procData = new ObjectMap<>();

        // 1. СКАНИРУЕМ ВСЕХ ЮНИТОВ
        for (Unit unit : Groups.unit) {
            if (unit.controller() instanceof LogicAI lai && lai.controller != null) {
                Building processor = lai.controller;
                if (processor.team != player.team()) continue;

                if (!procData.containsKey(processor)) procData.put(processor, new ObjectIntMap<>());
                procData.get(processor).put(unit.type, procData.get(processor).get(unit.type, 0) + 1);
            }
        }

        // --- 2: СОРТИРОВКА ПО КОЛИЧЕСТВУ ---
        Seq<Building> sortedProcessors = procData.keys().toSeq();

        sortedProcessors.sort(p -> {
            int totalUnits = 0;
            ObjectIntMap<UnitType> counts = procData.get(p);
            for(var entry : counts.entries()) totalUnits += entry.value;
            return -totalUnits; // Минус для сортировки по убыванию (descending)
        });

        // 2. СТРОИМ СПИСОК ИЗ ОРТСОРТИРОВАННЫХ ДАННЫХ
        for (Building proc : sortedProcessors) {
            ObjectIntMap<UnitType> unitCounts = procData.get(proc);

            listTable.table(Styles.black3, row -> {
                row.left().margin(4f);
                row.touchable = Touchable.enabled;
                row.addListener(new HandCursorListener());
                row.clicked(() -> Spectate.INSTANCE.spectate(proc));

                row.image(proc.block.uiIcon).size(18f).padRight(4f);

                String task = "Idle";
                if (proc instanceof LogicBuild lb) task = detectTask(lb.code);
                row.add("[accent]" + task).width(70f).left().fontScale(0.75f).ellipsis(true);

                row.table(uList -> {
                    uList.left();
                    for (var unitEntry : unitCounts.entries()) {
                        uList.image(unitEntry.key.uiIcon).size(14f).padLeft(4f);
                        uList.add("[white]" + unitEntry.value).fontScale(0.75f).padLeft(2f);
                    }
                }).growX();

                row.add("[gray](" + (int)(proc.x/8) + "," + (int)(proc.y/8) + ")").fontScale(0.65f).padLeft(4f);

            }).growX().padBottom(2f).row();
        }

        if (procData.isEmpty()) {
            listTable.add("[gray]< No logic units >").pad(10f);
        }
    }

    private String detectTask(String code) {
        if (code == null) return "Idle";
        if (code.contains("ulocate ore")) return "Mining";
        if (code.contains("ucontrol itemTake") || code.contains("ucontrol itemDrop")) return "Delivery";
        if (code.contains("ucontrol target")) return "Attack";
        if (code.contains("ucontrol build")) return "Building";
        if (code.contains("ucontrol boost")) return "Boosting";
        if (code.contains("ucontrol pathfind")) return "Moving";
        return "Logic";
    }
}