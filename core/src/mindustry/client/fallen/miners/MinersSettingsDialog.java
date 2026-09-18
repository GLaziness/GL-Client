package mindustry.client.fallen.miners;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

/** Settings of the unit auto-mining AI ({@link MinersFDAI}), in the same style as the other GL dialogs. */
public class MinersSettingsDialog extends BaseDialog{
    private static MinersSettingsDialog instance;

    private final Table all = new Table();
    private float width = 640f;

    public static MinersSettingsDialog get(){
        if(instance == null) instance = new MinersSettingsDialog();
        return instance;
    }

    private MinersSettingsDialog(){
        super("@client.fdmami.title");
        addCloseButton();
        shown(this::setup);
        onResize(this::setup);
        cont.pane(all).scrollX(false).grow();
    }

    private void setup(){
        all.clear();
        all.top().margin(10f).marginBottom(30f);
        width = Math.min(640f, Core.graphics.getWidth() / Scl.scl(1f) - 60f);

        section(Icon.units, "@client.fdmami.types", t -> {
            check(t, "@client.fdmami.mineMonos", MinersFDAI.mineMonos, b -> MinersFDAI.mineMonos = b);
            check(t, "@client.fdmami.minePolys", MinersFDAI.minePolys, b -> MinersFDAI.minePolys = b);
            check(t, "@client.fdmami.minePulss", MinersFDAI.minePulss, b -> MinersFDAI.minePulss = b);
            check(t, "@client.fdmami.mineMegas", MinersFDAI.mineMegas, b -> MinersFDAI.mineMegas = b);
            check(t, "@client.fdmami.mineQuazs", MinersFDAI.mineQuazs, b -> MinersFDAI.mineQuazs = b);
            check(t, "@client.fdmami.resetDisabledUnits", MinersFDAI.resetDisabledUnits, b -> {
                MinersFDAI.resetDisabledUnits = b;
                Core.settings.put("resetDisabledUnits", b);
            });
        });

        section(Icon.list, "@client.fdmami.resources", t -> {
            t.add("@client.fdmami.resources.hint").color(Color.lightGray).wrap().growX().left().padBottom(4f).row();
            buildResourceMatrix(t);
            check(t, "@client.fdmami.resetMatrixOnWorldLoad", MinersFDAI.resetMatrixOnWorldLoad, b -> {
                MinersFDAI.resetMatrixOnWorldLoad = b;
                Core.settings.put("resetMatrixOnWorldLoad", b);
            });
        });

        section(Icon.chartBar, "@client.fdmami.distribution", t -> {
            slider(t, "@client.fdmami.minUnitsPerResource", 0, 15, 1, MinersFDAI.minUnitsPerResource,
                v -> MinersFDAI.minUnitsPerResource = v.intValue(), "");
            slider(t, "@client.fdmami.crisisThreshold", 1f, 50f, 1f, MinersFDAI.crisisThreshold * 100,
                v -> MinersFDAI.crisisThreshold = v / 100f, "%");
            slider(t, "@client.fdmami.fullWeight", 0f, 0.1f, 0.005f, MinersFDAI.fullCoreWeight,
                v -> MinersFDAI.fullCoreWeight = v, "");
            slider(t, "@client.fdmami.updatetime", 1, 20, 1, MinersFDAI.AIMiningUpdateTime, v -> {
                MinersFDAI.AIMiningUpdateTime = v.intValue();
                Core.settings.put("AIUpTime", v.intValue());
            }, " " + Core.bundle.get("unit.seconds"));
            slider(t, "@client.fdmami.refresh", 0, 120, 5, MinersFDAI.commandRefreshTime, v -> {
                MinersFDAI.commandRefreshTime = v.intValue();
                Core.settings.put("fdmai-refresh", v.intValue());
            }, " " + Core.bundle.get("unit.seconds"));
            check(t, "@client.fdmami.manualCommands", MinersFDAI.respectManualCommands, b -> {
                MinersFDAI.respectManualCommands = b;
                Core.settings.put("respmancommands", b);
            });
            check(t, "@client.fdmami.allManualCommands", MinersFDAI.allManualCommands, b -> {
                MinersFDAI.allManualCommands = b;
                Core.settings.put("allmancommands", b);
            });
        });

        section(Icon.warning, "@client.fdmami.safety", t -> {
            check(t, "@client.fdmami.oreSafety", MinersFDAI.oreSafetyEnabled, b -> {
                MinersFDAI.oreSafetyEnabled = b;
                Core.settings.put("fdmai-oresafe", b);
            });
            slider(t, "@client.fdmami.turretSafeRadius", 0f, 60f, 1f, OreSafety.turretSafeRadius, v -> {
                OreSafety.turretSafeRadius = v;
                Core.settings.put("fdmai-turrad", v);
            }, "");
            slider(t, "@client.fdmami.spawnSafeRadius", 0f, 60f, 1f, OreSafety.spawnSafeRadius, v -> {
                OreSafety.spawnSafeRadius = v;
                Core.settings.put("fdmai-spawnrad", v);
            }, "");
        });

        section(Icon.add, "@client.fdmami.repair", t -> {
            check(t, "@client.fdmami.autoUnitRepair", MinersFDAI.autoUnitRepair, b -> {
                MinersFDAI.autoUnitRepair = b;
                Core.settings.put("fd-autoUnitRepair", b);
            });
            slider(t, "@client.fdmami.unitRepairGoHp", 10f, 95f, 5f, MinersFDAI.unitRepairGoHp * 100, v -> {
                MinersFDAI.unitRepairGoHp = v / 100f;
                Core.settings.put("fd-unitRepairGoHp", v / 100f);
            }, "%");
            check(t, "@client.fdmami.megaAutoHeal", MinersFDAI.autoHealMegas, b -> MinersFDAI.autoHealMegas = b);
            slider(t, "@client.fdmami.megadistheal", 10, 500, 10, MinersFDAI.autoHealDist,
                v -> MinersFDAI.autoHealDist = v, "");
        });

        section(Icon.hammer, "@client.fdmami.assist", t -> {
            check(t, "@client.fdmami.buildAssist", MinersFDAI.autoAssistBuild, b -> MinersFDAI.autoAssistBuild = b);
            t.table(types -> {
                types.left().defaults().left().padRight(20f).padTop(4f);
                types.check("@client.fdmami.assistPoly", MinersFDAI.assistBuildPoly, b -> MinersFDAI.setAssistBuild("Poly", b));
                types.check("@client.fdmami.assistPulsar", MinersFDAI.assistBuildPulsar, b -> MinersFDAI.setAssistBuild("Pulsar", b));
                types.row();
                types.check("@client.fdmami.assistMega", MinersFDAI.assistBuildMega, b -> MinersFDAI.setAssistBuild("Mega", b));
                types.check("@client.fdmami.assistQuasar", MinersFDAI.assistBuildQuasar, b -> MinersFDAI.setAssistBuild("Quasar", b));
            }).left().padLeft(20f).row();
            slider(t, "@client.fdmami.helprad", 1, 50, 1, MinersFDAI.AIHelpRad, v -> {
                MinersFDAI.AIHelpRad = v;
                Core.settings.put("AIHelpRad", v);
            }, "");
        });
    }

    /** Unit type x ore table: which unit types may mine which ore. Row, column and corner buttons toggle many at once. */
    private void buildResourceMatrix(Table tt){
        tt.table(grid -> {
            grid.defaults().pad(2f).size(52f, 40f);

            grid.button("@client.fdmami.all", Styles.flatt, () -> {
                boolean anyOff = false;
                for(UnitType t : MinersFDAI.MINER_TYPES){
                    for(Item i : MinersFDAI.MINE_ITEMS){
                        if(!MinersFDAI.canMine(t, i)) anyOff = true;
                    }
                }
                for(UnitType t : MinersFDAI.MINER_TYPES){
                    for(Item i : MinersFDAI.MINE_ITEMS) MinersFDAI.setCanMine(t, i, anyOff);
                }
                setup();
            }).width(80f);

            for(Item item : MinersFDAI.MINE_ITEMS){
                grid.button(new TextureRegionDrawable(item.uiIcon), Styles.flati, 28f, () -> {
                    boolean next = !isColumnFullyOn(item);
                    for(UnitType type : MinersFDAI.MINER_TYPES) MinersFDAI.setCanMine(type, item, next);
                    setup();
                }).tooltip(item.localizedName);
            }
            grid.row();

            for(UnitType type : MinersFDAI.MINER_TYPES){
                grid.button(b -> {
                    b.image(type.uiIcon).size(28f).padRight(6f);
                }, Styles.flatt, () -> {
                    boolean next = !isRowFullyOn(type);
                    for(Item item : MinersFDAI.MINE_ITEMS) MinersFDAI.setCanMine(type, item, next);
                    setup();
                }).width(80f).tooltip(type.localizedName);

                for(Item item : MinersFDAI.MINE_ITEMS){
                    grid.check("", MinersFDAI.canMine(type, item), b -> MinersFDAI.setCanMine(type, item, b));
                }
                grid.row();
            }
        }).left().padTop(4f).row();
    }

    private boolean isColumnFullyOn(Item item){
        for(UnitType type : MinersFDAI.MINER_TYPES){
            if(!MinersFDAI.canMine(type, item)) return false;
        }
        return true;
    }

    private boolean isRowFullyOn(UnitType type){
        for(Item item : MinersFDAI.MINE_ITEMS){
            if(!MinersFDAI.canMine(type, item)) return false;
        }
        return true;
    }

    /** Accent title, accent line and a dark panel, same as the other GL dialogs. */
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

    private void check(Table t, String name, boolean current, Boolc changed){
        t.check(name, current, changed).left().padTop(4f).row();
    }

    /** Slider with the name and value drawn over it, same as the vanilla settings sliders. */
    private void slider(Table t, String name, float min, float max, float step, float current, Cons<Float> changed, String unit){
        Slider slider = new Slider(min, max, step, false);
        slider.setValue(current);

        Label value = new Label("", Styles.outlineLabel);
        Runnable update = () -> {
            float v = slider.getValue();
            value.setText((step >= 1f ? String.valueOf((int)v) : Strings.autoFixed(v, 3)) + unit);
        };
        update.run();

        Table content = new Table();
        content.add(name, Styles.outlineLabel).left().growX().wrap();
        content.add(value).padLeft(10f).right();
        content.margin(3f, 33f, 3f, 33f);
        content.touchable = Touchable.disabled;

        slider.changed(() -> {
            changed.get(slider.getValue());
            update.run();
        });

        t.stack(slider, content).growX().padTop(6f).row();
    }
}
