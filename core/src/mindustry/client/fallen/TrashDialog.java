package mindustry.client.fallen;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.input.*;
import arc.math.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.client.ClientVars;
import mindustry.client.navigation.*;
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
    private final Table unitGrid = new Table();
    /** Width of the section column, recalculated on every rebuild to fit the screen. */
    private float width = 600f;

    public static float iconunitsize = 48f;

    private static final String[][] assistShapes = {
        {"circle", "@client.fdtrash.shape.circle"},
        {"square", "@client.fdtrash.shape.square"},
        {"star", "@client.fdtrash.shape.star"},
        {"hold", "@client.fdtrash.shape.hold"},
        {"line", "@client.fdtrash.shape.line"},
        {"figure8", "@client.fdtrash.shape.figure8"}
    };

    public TrashDialog(){
        super("@trashbase");

        shouldPause = true;
        addCloseButton();
        shown(this::rebuild);
        onResize(this::rebuild);

        cont.pane(all).scrollX(false).grow();
    }

    void rebuild(){
        all.clear();
        all.top().margin(10f).marginBottom(30f);
        width = Math.min(600f, Core.graphics.getWidth() / Scl.scl(1f) - 60f);

        section(Icon.units, "@client.fdtrash.units", t -> {
            t.add("@client.fdtrash.units.hint").color(Color.lightGray).wrap().growX().left().padBottom(6f).row();
            t.add(unitGrid).left().row();
            rebuildUnitGrid();
            slider(t, "@client.fdtrash.slidicon", 16, 128, 4, iconunitsize, v -> {
                iconunitsize = v;
                rebuildUnitGrid();
            }, "");
        });

        section(Icon.commandAttack, "@client.fdtrash.autoshoot", t -> {
            slider(t, "@client.fdtrash.overrange", -20, 70, 1, Core.settings.getFloat("overrange", 0f),
                v -> Core.settings.put("overrange", v), "%");
        });

        section(Icon.players, "@client.fdtrash.assistset", t -> {
            check(t, "@client.setting.circleassist.name", Core.settings.getBool("circleassist"), b -> {
                Core.settings.put("circleassist", b);
                if(Navigation.currentlyFollowing instanceof AssistPath path) path.setCircling(b);
            });

            t.add("@client.fdtrash.circleassistshape").color(Color.lightGray).left().padTop(8f).row();
            t.table(shapes -> {
                shapes.left().defaults().height(40f).growX().uniformX().pad(2f);
                ButtonGroup<TextButton> group = new ButtonGroup<>();
                String current = Core.settings.getString("circleassistshape", "circle");
                for(int i = 0; i < assistShapes.length; i++){
                    String key = assistShapes[i][0];
                    shapes.button(assistShapes[i][1], Styles.flatTogglet, () -> Core.settings.put("circleassistshape", key))
                        .group(group).checked(key.equals(current))
                        .disabled(b -> !Core.settings.getBool("circleassist"));
                    if(i == 2) shapes.row();
                }
            }).growX().padBottom(4f).row();

            slider(t, "@client.fdtrash.circleassistspeed", -100, 100, 1, Core.settings.getFloat("circleassistspeed", 0.25f) * 100,
                v -> Core.settings.put("circleassistspeed", v / 100f), "%");
            slider(t, "@client.fdtrash.assistdistance", 0, 50, 1, Core.settings.getFloat("assistdistance", 5f),
                v -> Core.settings.put("assistdistance", v), "");
        });

        section(Icon.box, "@client.fdtrash.autotransfer", t -> {
            check(t, "@client.fdtrash.at.enabled", Core.settings.getBool("autotransfer", false), b -> {
                Core.settings.put("autotransfer", b);
                AutoTransfer.enabled = b;
            });
            check(t, "@client.fdtrash.at.fromcores", Core.settings.getBool("autotransfer-fromcores", true), b -> {
                Core.settings.put("autotransfer-fromcores", b);
                AutoTransfer.Settings.setFromCores(b);
            });
            check(t, "@client.fdtrash.at.fromcontainers", Core.settings.getBool("autotransfer-fromcontainers", true), b -> {
                Core.settings.put("autotransfer-fromcontainers", b);
                AutoTransfer.Settings.setFromContainers(b);
            });

            slider(t, "@client.fdtrash.at.mincore", 0, 5000, 10, Core.settings.getInt("autotransfer-mincoreitems", 10), v -> {
                Core.settings.put("autotransfer-mincoreitems", v.intValue());
                AutoTransfer.Settings.setMinCoreItems(v.intValue());
            }, "");
            slider(t, "@client.fdtrash.at.delay", 0, 500, 5, Core.settings.getFloat("autotransfer-transferdelay", 60f), v -> {
                Core.settings.put("autotransfer-transferdelay", v);
                AutoTransfer.Settings.setDelay(v);
            }, " " + Core.bundle.get("client.fdtrash.ticks"));

            t.add("@client.fdtrash.autotransfer.filters").color(Color.lightGray).left().padTop(8f).row();
            t.table(filters -> {
                filters.left().defaults().left().padRight(20f).padTop(4f);
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
            }).left().row();
        });

        section(Icon.eye, "@client.fdtrash.light", t -> {
            check(t, "@client.fdtrash.enableDarkness", enableDarkness, b -> enableDarkness = b);
            check(t, "@client.fdtrash.enableLight", enableLight, b -> enableLight = b);
            check(t, "@client.fdtrash.fog", state.rules.fog, b -> state.rules.fog = b);
        });

        section(Icon.layers, "@client.fdtrash.frags", t -> {
            subheader(t, "@client.fdtrash.wavefrag", () -> {
                Core.settings.remove("wavefrag-x");
                Core.settings.remove("wavefrag-y");
                if(ui.waveInfoFrag != null){
                    ui.waveInfoFrag.resetPos();
                    ui.waveInfoFrag.updateSize();
                }
            });
            slider(t, "@client.fdtrash.wavefragheigh", 50, 700, 50, Core.settings.getFloat("wavefragheigh", 400), v -> {
                Core.settings.put("wavefragheigh", v);
                if(ui.waveInfoFrag != null) ui.waveInfoFrag.updateSize();
            }, "px");
            slider(t, "@client.fdtrash.wavefragwidt", 50, 700, 50, Core.settings.getFloat("wavefragwidth", 400), v -> {
                Core.settings.put("wavefragwidth", v);
                if(ui.waveInfoFrag != null) ui.waveInfoFrag.updateSize();
            }, "px");
            slider(t, "@client.fdtrash.wave_font_offset", 1, 3, 0.1f, Core.settings.getFloat("wave_font_offset", 1), v -> {
                Core.settings.put("wave_font_offset", v);
                if(ui.waveInfoFrag != null) ui.waveInfoFrag.updateSize();
            }, "x");

            subheader(t, "@client.fdtrash.mapfrag", () -> {
                Core.settings.remove("mapfrag-x");
                Core.settings.remove("mapfrag-y");
                if(ui.mapInfoFrag != null){
                    ui.mapInfoFrag.resetPos();
                    ui.mapInfoFrag.updateSize();
                }
            });
            slider(t, "@client.fdtrash.mapfragheigh", 50, 1000, 50, Core.settings.getFloat("mapfragheigh", 700), v -> {
                Core.settings.put("mapfragheigh", v);
                if(ui.mapInfoFrag != null) ui.mapInfoFrag.updateSize();
            }, "px");
            slider(t, "@client.fdtrash.mapfragwidt", 50, 1000, 50, Core.settings.getFloat("mapfragwidth", 500), v -> {
                Core.settings.put("mapfragwidth", v);
                if(ui.mapInfoFrag != null) ui.mapInfoFrag.updateSize();
            }, "px");

            subheader(t, "@client.fdtrash.uicontrolfrag", null);
            slider(t, "@client.fdtrash.uicontrolfragoffset", -2000, 2000, 50, Core.settings.getFloat("uicontrolfragoffset", 0),
                v -> Core.settings.put("uicontrolfragoffset", v), "px");
        });
    }

    private void rebuildUnitGrid(){
        unitGrid.clear();
        unitGrid.left();
        int cols = Math.max(1, (int)((width - 30f) / (iconunitsize + 6f)));
        int count = 0;

        for(UnitType unit : Vars.content.units()){
            if(unit.isHidden()) continue;

            unitGrid.button(new TextureRegionDrawable(unit.uiIcon), Styles.clearNonei, iconunitsize, () -> {
                hide();
                if(Core.input.keyDown(KeyCode.shiftLeft)){
                    InputHandler.last_select_units_type = unit;
                    InputHandler.selectUnitsType(unit);
                }else{
                    ClientVars.clientCommandHandler.handleMessage("!uc " + unit.localizedName, player);
                }
            }).size(iconunitsize + 6f).tooltip(unit.localizedName);

            if(++count % cols == 0) unitGrid.row();
        }
    }

    /** A titled block in the style of the vanilla settings categories: accent title, accent line, dark panel. */
    private void section(Drawable icon, String title, Cons<Table> content){
        all.table(head -> {
            head.left();
            head.image(icon).color(Pal.accent).size(24f).padRight(8f);
            head.add(title).color(Pal.accent).left();
        }).width(width).padTop(16f).left().row();
        all.image().color(Pal.accent).height(3f).width(width).padTop(4f).padBottom(6f).row();

        all.table(Styles.grayPanel, t -> {
            t.left().top().margin(10f);
            t.defaults().left();
            content.get(t);
        }).width(width).row();
    }

    /** Small grey title inside a section, with an optional "reset position" button on the right. */
    private void subheader(Table t, String title, @Nullable Runnable resetPos){
        t.table(h -> {
            h.left();
            h.add(title).color(Color.lightGray).left().growX();
            if(resetPos != null){
                h.button("@client.fdtrash.resetpos", Icon.refreshSmall, Styles.flatBordert, () -> {
                    resetPos.run();
                    ui.showInfoFade("@client.fdtrash.resetpos.done");
                }).height(36f).padLeft(8f);
            }
        }).growX().padTop(12f).row();
    }

    private void check(Table t, String name, boolean current, Boolc changed){
        t.check(name, current, changed).left().padTop(4f).row();
    }

    /** Slider with the name and value drawn over it, same as the vanilla settings sliders. */
    private void slider(Table t, String name, float min, float max, float step, float current, Cons<Float> changed, String unit){
        Slider slider = new Slider(min, max, step, false);
        slider.setValue(current);

        Label value = new Label("", Styles.outlineLabel);
        Runnable updateValue = () -> {
            float v = slider.getValue();
            value.setText((step < 1f ? Strings.autoFixed(v, 1) : String.valueOf((int)v)) + unit);
        };
        updateValue.run();

        Table content = new Table();
        content.add(name, Styles.outlineLabel).left().growX().wrap();
        content.add(value).padLeft(10f).right();
        content.margin(3f, 33f, 3f, 33f);
        content.touchable = Touchable.disabled;

        slider.changed(() -> {
            changed.get(slider.getValue());
            updateValue.run();
        });

        t.stack(slider, content).growX().padTop(6f).row();
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