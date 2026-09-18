package mindustry.client.fallen;

import arc.*;
import arc.func.Cons;
import arc.graphics.*;
import arc.input.*;
import arc.math.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.client.ClientVars;
import mindustry.client.utils.AutoTransfer;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.InputHandler;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.blocks.logic.LogicBlock;

import static arc.Core.*;
import static mindustry.Vars.*;

public class TrashDialog extends BaseDialog {
    private final Table all = new Table();

    public static float iconunitsize = 48f;


    public TrashDialog(){
        super("@trashbase");

        shouldPause = true;
        addCloseButton();
        shown(this::rebuild);
        onResize(this::rebuild);

        all.margin(20).marginTop(0f);
        cont.pane(all).scrollX(false).grow();
    }

    void rebuild(){
        all.clear();
        all.top();

        all.add("@client.fdtrash.slidicon").left().row();

        all.table(sl -> {
            var s = new Slider(16, 128, 4, false);
            s.setValue(iconunitsize);
            var l = new Label("" + (int)iconunitsize, Styles.outlineLabel);

            s.changed(() -> {
                iconunitsize = s.getValue();
                l.setText("" + (int)iconunitsize);
            });

            sl.add(s).width(400);
            sl.add(l).padLeft(10);

            sl.button(Icon.refresh, this::rebuild).size(40).padLeft(20);
        }).left().row();

        addSeparator(Pal.accent);

        all.table(list -> {
            list.left();
            int cols = (int) Mathf.clamp((graphics.getWidth() - Scl.scl(60)) / Scl.scl(iconunitsize + 10), 1, 40);
            int count = 0;

            for(UnitType unit : Vars.content.units()){
                if(unit.isHidden()) continue;

                Image image = new Image(unit.uiIcon).setScaling(Scaling.fit);
                Cell<Image> cell = list.add(image).size(iconunitsize).pad(3);

                image.addListener(new HandCursorListener());
                image.addListener(new Tooltip(t -> t.background(Tex.button).add(unit.localizedName)));

                image.clicked(() -> {
                    if(Core.input.keyDown(KeyCode.shiftLeft)){
                        hide();
                        InputHandler.last_select_units_type = unit;
                        InputHandler.selectUnitsType(unit);
                    }else{
                        hide();
                        String message = "!uc " + unit.localizedName;
                        ClientVars.clientCommandHandler.handleMessage(message, player);
                    }
                });

                if(++count % cols == 0) list.row();
            }
        }).growX().left().padBottom(15).row();

        all.add("@client.fdtrash.autoshoot").left().row();
        addSeparator(Pal.accent);
        all.table(ash -> {
            addSlider(ash, "@client.fdtrash.overrange", -20, 70, 1,
                    Core.settings.getFloat("overrange", 0f) ,
                    v -> Core.settings.put("overrange", v ), "%");
        }).left().row();

        all.add("@client.fdtrash.assistset").left().row();
        addSeparator(Pal.accent);

        all.table(tas -> {
            tas.defaults().left().pad(4);

            tas.check("@client.setting.circleassist.name", Core.settings.getBool("circleassist"), b -> Core.settings.put("circleassist", b)).row();
            //tas.check("@setting.assistbutnuance.name", Core.settings.getBool("assistbutnuance"), b -> Core.settings.put("assistbutnuance", b)).row();

            addSlider(tas, "@client.fdtrash.circleassistspeed", 0, 100, 1,
                    Core.settings.getFloat("circleassistspeed") * 100,
                    v -> Core.settings.put("circleassistspeed", v / 100f), "%");

            addSlider(tas, "@client.fdtrash.assistdistance", 0, 50, 1,
                    Core.settings.getFloat("assistdistance"),
                    v -> Core.settings.put("assistdistance", v), "px");
        }).left().row();

        all.add("@client.fdtrash.autotransfer").left().padTop(10).row();
        addSeparator(Pal.accent);
        all.table(ttt -> {
            ttt.defaults().left().pad(4);


            ttt.check("@client.fdtrash.at.enabled", Core.settings.getBool("autotransfer", false), b -> {
                Core.settings.put("autotransfer", b);
                AutoTransfer.enabled = b;
            }).row();

            ttt.check("@client.fdtrash.at.fromcores", Core.settings.getBool("autotransfer-fromcores", true), b -> {
                Core.settings.put("autotransfer-fromcores", b);
                AutoTransfer.Settings.setFromCores(b);
            }).row();

            ttt.check("@client.fdtrash.at.fromcontainers", Core.settings.getBool("autotransfer-fromcontainers", true), b -> {
                Core.settings.put("autotransfer-fromcontainers", b);
                AutoTransfer.Settings.setFromContainers(b);
            }).row();

            addSlider(ttt, "@client.fdtrash.at.mincore", 0, 5000, 1,
                    (float)Core.settings.getInt("autotransfer-mincoreitems", 10),
                    v -> {
                        Core.settings.put("autotransfer-mincoreitems", v.intValue());
                        AutoTransfer.Settings.setMinCoreItems(v.intValue());
                    }, "");

            addSlider(ttt, "@client.fdtrash.at.delay", 0, 500, 1,
                    Core.settings.getFloat("autotransfer-transferdelay", 60F),
                    v -> {
                        Core.settings.put("autotransfer-transferdelay", v);
                        AutoTransfer.Settings.setDelay(v);
                    }, " ticks");


            all.add("@client.fdtrash.autotransfer.filters").left().padTop(5).row();
            all.table(filters -> {
                filters.defaults().left().pad(4);

                filters.check("@client.fdtrash.at.t_turrets", Core.settings.getBool("autotransfer-t-turrets"), b -> {
                    Core.settings.put("autotransfer-t-turrets", b);
                    AutoTransfer.Settings.setTargetTurrets(b);
                });

                filters.check("@client.fdtrash.at.t_prod", Core.settings.getBool("autotransfer-t-prod"), b -> {
                    Core.settings.put("autotransfer-t-prod", b);
                    AutoTransfer.Settings.setTargetProduction(b);
                });

                filters.row();

                filters.check("@client.fdtrash.at.t_units", Core.settings.getBool("autotransfer-t-units"), b -> {
                    Core.settings.put("autotransfer-t-units", b);
                    AutoTransfer.Settings.setTargetUnitFactories(b);
                });

                filters.check("@client.fdtrash.at.t_recons", Core.settings.getBool("autotransfer-t-recons"), b -> {
                    Core.settings.put("autotransfer-t-recons", b);
                    AutoTransfer.Settings.setTargetReconstructors(b);
                });
                addSeparator(Pal.gray);
            }).left().row();

        }).left().row();

        addSeparator(Pal.accent);

//        all.add("@client.fdtrash.mining").left().padTop(10).row();
//
//        all.table(tt -> {
//            tt.defaults().left().pad(4);
//            addSlider(tt, "@client.fdtrash.minUnitsPerResource", 0, 15, 1,
//                    Core.settings.getFloat("fd-minUMine", 1f),
//                    v -> {
//                        Core.settings.put("fd-minUMine", v);
//                        PanelFragment.minMinUnitsSet(v.intValue());
//                    }, " x");
//
//            addSlider(tt, "@client.fdtrash.crisisThreshold", 1f, 50f, 1f,
//                    PanelFragment.crisisThreshold * 100,
//                    v -> {
//                        PanelFragment.crisisThreshold = v/100;
//                    }, " %");
//            tt.check("@client.fdtrash.mineMonos", PanelFragment.mineMonos, b -> PanelFragment.mineMonos = b).row();
//            tt.check("@client.fdtrash.minePolys", PanelFragment.minePolys, b -> PanelFragment.minePolys = b).row();
//            tt.check("@client.fdtrash.minePulss", PanelFragment.minePulss, b -> PanelFragment.minePulss = b).row();
//            tt.check("@client.fdtrash.mineQuazs", PanelFragment.mineQuazs, b -> PanelFragment.mineQuazs = b).row();
//            tt.check("@client.fdtrash.mineMegas", PanelFragment.mineMegas, b -> PanelFragment.mineMegas = b).row();
//            tt.check("@client.fdtrash.megaAutoHeal", PanelFragment.autoHealMegas, b -> PanelFragment.autoHealMegas = b).row();
//            addSlider(tt, "@client.fdtrash.megadistheal", 10, 500, 10,
//                    PanelFragment.autoHealDist,
//                    v -> {
//                        PanelFragment.autoHealDist = v;
//                    }, " x");
//
//            addSlider(tt, "@client.fdtrash.updatetime", 1, 20, 1,
//                    MinersFDAI.AIMiningUpdateTime,
//                    v -> {
//                        MinersFDAI.AIMiningUpdateTime = v.intValue();
//                        Core.settings.put("AIUpTime", v.intValue());
//                    }, " x");
//
//
//        }).left().row();

        all.add("@client.fdtrash.light").left().padTop(10).row();
        addSeparator(Pal.accent);

        all.table(tt -> {
            tt.defaults().left().pad(4);
            tt.check("@client.fdtrash.enableDarkness", enableDarkness, b -> enableDarkness = b).row();
            tt.check("@client.fdtrash.enableLight", enableLight, b -> enableLight = b).row();
            tt.check("@client.fdtrash.fog", state.rules.fog, b -> state.rules.fog = b).row();
        }).left().row();

        all.add("@client.fdtrash.frags").left().padTop(10).row();
        addSeparator(Pal.accent);

        all.add("@client.fdtrash.frags").left().color(Pal.accent).row();

        // --- WAVE FRAGMENT SETTINGS ---
        all.add("@client.fdtrash.wavefrag").left().padTop(10).row();
        all.table(main -> {
            main.left();

            // Левая колонка: вертикальная таблица со слайдерами
            main.table(sliders -> {
                sliders.defaults().left();
                addSlider(sliders, "@client.fdtrash.wavefragheigh", 50, 700, 50,
                        Core.settings.getFloat("wavefragheigh", 400),
                        vh -> {
                            Core.settings.put("wavefragheigh", vh);
                            if(ui.waveInfoFrag != null) ui.waveInfoFrag.updateSize();
                        }, "px");

                addSlider(sliders, "@client.fdtrash.wavefragwidt", 50, 700, 50,
                        Core.settings.getFloat("wavefragwidth", 400),
                        vw -> {
                            Core.settings.put("wavefragwidth", vw);
                            if(ui.waveInfoFrag != null) ui.waveInfoFrag.updateSize();
                        }, "px");

                addSlider(sliders, "@client.fdtrash.wave_font_offset", 1, 3, 0.1f,
                        Core.settings.getFloat("wave_font_offset", 1),
                        vo -> {
                            Core.settings.put("wave_font_offset", vo);
                            if(ui.waveInfoFrag != null) ui.waveInfoFrag.updateSize();
                        }, "px");
            }).left();

            // Правая часть: Кнопка сброса (будет справа от таблицы слайдеров)
            main.button(t -> {
                t.image(Icon.refresh).row();
                t.add("Update").fontScale(0.7f);
            }, Styles.defaulti, () -> {
                Core.settings.remove("wavefrag-x");
                Core.settings.remove("wavefrag-y");
                if(ui.waveInfoFrag != null) ui.waveInfoFrag.updateSize(); // Вызываем обновление размеров
                ui.showInfoFade("Position Reset");
            }).size(70f, 80f).padLeft(10f); // Задаем размер и отступ слева

            main.button(t -> {
                t.image(Icon.refresh).row();
                t.add("Reset").fontScale(0.7f);
            }, Styles.defaulti, () -> {
                Core.settings.remove("wavefrag-x");
                Core.settings.remove("wavefrag-y");
                if(ui.waveInfoFrag != null) ui.waveInfoFrag.resetPos();
                ui.showInfoFade("Position Reset");
            }).size(70f, 80f).padLeft(10f); // Задаем размер и отступ слева


        }).left().row();

        addSeparator(Pal.accent); // Разделительная линия

        // --- MAP FRAGMENT SETTINGS ---
        all.add("@client.fdtrash.mapfrag").left().padTop(10).row();
        all.table(main -> {
            main.left();

            main.table(sliders -> {
                sliders.defaults().left();
                addSlider(sliders, "@client.fdtrash.mapfragheigh", 50, 1000, 50,
                        Core.settings.getFloat("mapfragheigh", 700),
                        v -> {
                            Core.settings.put("mapfragheigh", v);
                            if(ui.mapInfoFrag != null) ui.mapInfoFrag.updateSize();
                        }, "px");

                addSlider(sliders, "@client.fdtrash.mapfragwidt", 50, 1000, 50,
                        Core.settings.getFloat("mapfragwidth", 500),
                        v -> {
                            Core.settings.put("mapfragwidth", v);
                            if(ui.mapInfoFrag != null) ui.mapInfoFrag.updateSize();
                        }, "px");
            }).left();

            main.button(t -> {
                t.image(Icon.refresh).row();
                t.add("Update").fontScale(0.7f);
            }, Styles.defaulti, () -> {
                Core.settings.remove("mapfrag-x");
                Core.settings.remove("mapfrag-y");
                if(ui.mapInfoFrag != null) ui.mapInfoFrag.updateSize();
                ui.showInfoFade("Position Reset");
            }).size(70f, 80f).padLeft(10f);

            main.button(t -> {
                t.image(Icon.refresh).row();
                t.add("Reset").fontScale(0.7f);
            }, Styles.defaulti, () -> {
                Core.settings.remove("mapfrag-x");
                Core.settings.remove("mapfrag-y");
                if(ui.mapInfoFrag != null) ui.mapInfoFrag.resetPos();
                ui.showInfoFade("Position Reset");
            }).size(70f, 80f).padLeft(10f);

        }).left().row();

        // --- UCONTROL FRAG SETTINGS ---
        addSeparator(Pal.accent);

        all.add("@client.fdtrash.uicontrolfrag").left().padTop(10).row();
        all.table(main -> {
            main.left();
            main.table(sliders -> {
                sliders.defaults().left();
                addSlider(sliders, "@client.fdtrash.uicontrolfragoffset", -2000, 2000, 50,
                        Core.settings.getFloat("uicontrolfragoffset", 0),
                        vh -> {
                            Core.settings.put("uicontrolfragoffset", vh);
                        }, "px");
            }).left();
        }).left().row();
        addSeparator(Pal.accent);



        // --- AG SETTINGS ---
//
//        all.add("@client.fdtrash.agbuttons, dont touch").left().padTop(10).row();
//
//        all.add("Triangle Unit Control").left().padTop(10).row();
//        all.table(t -> {
//            t.left();
//
//            t.table(sliders -> {
//                sliders.defaults().left().padLeft(10);
//
//                // 1. Слайдер выбора типа юнита (Сортировка по ХП)
//                addSlider(sliders, "Unit Type", 0, PanelFragment.sortedUnitTypes.size - 1, 1,
//                        PanelFragment.triUnitTypeIndex, v -> { PanelFragment.triUnitTypeIndex = v.intValue();}, "");
//                // Подпись текущего юнита и его ХП
//                sliders.label(() -> {
//                    UnitType ut = PanelFragment.sortedUnitTypes.get(PanelFragment.triUnitTypeIndex);
//                    return "[accent]" + ut.localizedName + " [white](" + (int)ut.health + " HP)";
//                }).row();
//
//                // 2. Кол-во
//                addSlider(sliders, "Count", 3, 100, 1, PanelFragment.triUnitCount, v -> PanelFragment.triUnitCount = v.intValue(), "");
//
//                // 3. Размер
//                addSlider(sliders, "Size", 30, 800, 10, PanelFragment.triSize, v -> PanelFragment.triSize = v, "px");
//
//                // 4. Скорость
//                addSlider(sliders, "Speed", -2, 2, 0.1f, PanelFragment.triRotSpeed, v -> PanelFragment.triRotSpeed = v, "x");
//
//            });
//        }).left().row();

//        all.add("@client.fdtrash.agbuttons").left().color(Pal.accent).padTop(10).row();
//        all.table(main -> {
//            main.left();
//            //main.button("Stop All Processors", Icon.star, Styles.flatBordert, this::stopAllProcessors).size(280f, 50f).color(Color.scarlet);
//        }).left().row();


    }

    private void stopAllProcessors() {
        Threads.daemon(() -> {
                try {
                    Seq<Building> targets = new Seq<>();

                    for(Building build : Groups.build) {
                        if(build.team == player.team() && build instanceof LogicBlock.LogicBuild) {
                            targets.add(build);
                        }
                    }
                    if(targets.isEmpty()) {
                        ui.hudfrag.showToast("Процессоры не найдены");
                        return;
                    }
                    for(Building build : targets) {
                        if(!state.isGame()) break;

                        String stopCode = "print \"Save the code, kill the griefer\"\n";
                        if(build instanceof LogicBlock.LogicBuild logic) {
                            logic.updateCode(stopCode);
                        }

                        Call.tileConfig(player, build, stopCode);

                        Thread.sleep(100);
                    }
                } catch(Exception e) {
                    Log.err(e);
                }
            });
    }

    private void addSeparator(Color color){
        all.image().growX().pad(5).height(2).color(color).row();
    }

    private void addSlider(Table t, String name, float min, float max, float step, float current, Cons<Float> changed, String unit){
        t.table(s -> {
            s.add(name).minWidth(180).left();
            Slider slider = new Slider(min, max, step, false);
            slider.setValue(current);
            Label label = new Label((int)current + unit, Styles.outlineLabel);

            slider.changed(() -> {
                changed.get(slider.getValue());
                label.setText((int)slider.getValue() + unit);
            });

            s.add(slider).width(250).padLeft(10);
            s.add(label).width(60).padLeft(10);
        }).row();
    }

    private static final String[] ATTEM_STUFF = {
            "greaterThanEq attem 83", "op mul fx @thisx -10000", "read flag cell1 0",
            "write fullness cell1 8", "sensor silicon5 reconstructor1", "ubind unitType"
    };

    public static boolean ihateattems(String code){
        for(String s : ATTEM_STUFF){
            if(code.contains(s)) return true;
        }
        return false;
    }
}