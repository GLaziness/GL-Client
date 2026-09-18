package mindustry.client.fallen.miners;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.scene.ui.Slider;
import arc.scene.ui.layout.Table;
import arc.util.Strings;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;


public class MinersSettingsDialog extends BaseDialog {

    private static MinersSettingsDialog instance;

    public static MinersSettingsDialog get() {
        if (instance == null) instance = new MinersSettingsDialog();
        return instance;
    }

    private MinersSettingsDialog() {
        super(Core.bundle.get("client.fdmami.mining.settings", "Mining AI Settings"));

        addCloseButton();
        setup();
        shown(this::setup);
    }

    private void setup() {
        cont.clear();
        cont.pane(all -> {
            all.add("@client.fdmami.mining").left().padTop(10).row();

            all.table(tt -> {
                tt.defaults().left().pad(4);

                tt.check("@client.fdmami.buildAssist", MinersFDAI.autoAssistBuild, b -> MinersFDAI.autoAssistBuild = b).row();
                tt.check("@client.fdmami.manualCommands", MinersFDAI.respectManualCommands, b -> {MinersFDAI.respectManualCommands = b; Core.settings.put("respmancommands", b);}).row();

                addSlider(tt, "@client.fdmami.minUnitsPerResource", 0, 15, 1,
                        (float) MinersFDAI.minUnitsPerResource,
                        v -> minMinUnitsSet(v.intValue()), " x");

                addSlider(tt, "@client.fdmami.crisisThreshold", 1f, 50f, 1f,
                        MinersFDAI.crisisThreshold * 100,
                        v -> MinersFDAI.crisisThreshold = v / 100f, " %");

                tt.check("@client.fdmami.mineMonos", MinersFDAI.mineMonos, b -> MinersFDAI.mineMonos = b).row();
                tt.check("@client.fdmami.minePolys", MinersFDAI.minePolys, b -> MinersFDAI.minePolys = b).row();
                tt.check("@client.fdmami.minePulss", MinersFDAI.minePulss, b -> MinersFDAI.minePulss = b).row();
                tt.check("@client.fdmami.mineQuazs", MinersFDAI.mineQuazs, b -> MinersFDAI.mineQuazs = b).row();
                tt.check("@client.fdmami.mineMegas", MinersFDAI.mineMegas, b -> MinersFDAI.mineMegas = b).row();
                tt.check("@client.fdmami.megaAutoHeal", MinersFDAI.autoHealMegas, b -> MinersFDAI.autoHealMegas = b).row();
                tt.check("@client.fdmami.resetDisabledUnits", Core.settings.getBool("resetDisabledUnits", false),
                        b -> {
                            MinersFDAI.resetDisabledUnits = b;
                            Core.settings.put("resetDisabledUnits", b);
                        }).row();
                tt.check("@client.fdmami.allManualCommands", MinersFDAI.allManualCommands, b -> {MinersFDAI.allManualCommands = b; Core.settings.put("allmancommands", b);}).row();

                addSlider(tt, "@client.fdmami.megadistheal", 10, 500, 10,
                        MinersFDAI.autoHealDist,
                        v -> MinersFDAI.autoHealDist = v, " x");

                addSlider(tt, "@client.fdmami.updatetime", 1, 20, 1,
                        (float) MinersFDAI.AIMiningUpdateTime,
                        v -> {
                            MinersFDAI.AIMiningUpdateTime = v.intValue();
                            Core.settings.put("AIUpTime", v.intValue());
                        }, " x");

                addSlider(tt, "@client.fdmami.helprad", 1, 50, 1,
                        (float) MinersFDAI.AIHelpRad,
                        v -> {
                            MinersFDAI.AIHelpRad = v;
                            Core.settings.put("AIHelpRad", v);
                        }, " tile");

                tt.add("@client.fdmami.resources").left().padTop(14).colspan(3).row();
                buildResourceMatrix(tt);

                tt.row();

                tt.check("@client.fdmami.resetMatrixOnWorldLoad", MinersFDAI.resetMatrixOnWorldLoad, b -> {
                    MinersFDAI.resetMatrixOnWorldLoad = b;
                    Core.settings.put("resetMatrixOnWorldLoad", b);
                }).row();

                addSlider(tt, "@client.fdmami.fullWeight", 0f, 0.1f, 0.005f,
                        MinersFDAI.fullCoreWeight,
                        v -> MinersFDAI.fullCoreWeight = v, " ");

                tt.check("@client.fdmami.oreSafety",Core.settings.getBool("fdmai-oresafe", false),
                        b -> {
                            MinersFDAI.oreSafetyEnabled = b;
                            Core.settings.put("fdmai-oresafe", b);
                        }).row();

                addSlider(tt, "@client.fdmami.turretSafeRadius", 0f, 60f, 1f,
                        OreSafety.turretSafeRadius,
                        v -> {
                            OreSafety.turretSafeRadius = v;
                            Core.settings.put("fdmai-turrad", v);
                        }, " t");

                addSlider(tt, "@client.fdmami.spawnSafeRadius", 0f, 60f, 1f,
                        OreSafety.spawnSafeRadius,
                        v ->{
                            OreSafety.spawnSafeRadius = v;
                            Core.settings.put("fdmai-spawnrad", v);
                        } , " t");

            }).left().row();
        }).fillX().fillY();
    }

    public static void minMinUnitsSet(int min){
        MinersFDAI.minUnitsPerResource = min;
    }

    // Таблица-матрица "тип юнита x ресурс": какие юниты каким ресурсам разрешено копать.
    // Строки - типы юнитов, колонки - ресурсы.
    private void buildResourceMatrix(Table tt) {
        tt.table(grid -> {
            grid.defaults().pad(4).left();

            // Угол таблицы: кнопка "Включить всё / Выключить всё" для всей матрицы
            grid.button("All", Styles.flatt, () -> {
                // Определяем, включено ли вообще хоть что-то
                boolean anyOff = false;
                for(UnitType t : MinersFDAI.MINER_TYPES) {
                    for(Item i : MinersFDAI.MINE_ITEMS) {
                        if(!MinersFDAI.canMine(t, i)) anyOff = true;
                    }
                }
                // Если есть выключенные — включаем всё, иначе выключаем всё
                for(UnitType t : MinersFDAI.MINER_TYPES) {
                    for(Item i : MinersFDAI.MINE_ITEMS) MinersFDAI.setCanMine(t, i, anyOff);
                }
                setup();
            }).width(90f).color(Color.acid);

            // Шапка: Названия ресурсов (кликабельные колонки)
            for (Item item : MinersFDAI.MINE_ITEMS) {
                grid.button(item.localizedName, Styles.flatt, () -> {
                    boolean nextState = !isColumnFullyOn(item);
                    for (UnitType type : MinersFDAI.MINER_TYPES) {
                        MinersFDAI.setCanMine(type, item, nextState);
                    }
                    setup();
                }).width(70f).center();
            }
            grid.row();

            // Строки юнитов
            for (UnitType type : MinersFDAI.MINER_TYPES) {
                // Название юнита слева (кликабельная строка)
                grid.button(type.localizedName, Styles.flatt, () -> {
                    boolean nextState = !isRowFullyOn(type);
                    for (Item item : MinersFDAI.MINE_ITEMS) {
                        MinersFDAI.setCanMine(type, item, nextState);
                    }
                    setup();
                }).width(90f).left();

                // Чекбоксы на пересечении
                for (Item item : MinersFDAI.MINE_ITEMS) {
                    boolean on = MinersFDAI.canMine(type, item);
                    grid.check("", on, b -> MinersFDAI.setCanMine(type, item, b)).width(70f);
                }
                grid.row();
            }
        }).left().padTop(6f).row();
    }

    // Вспомогательный метод для проверки состояния колонки
    private boolean isColumnFullyOn(Item item) {
        for (UnitType type : MinersFDAI.MINER_TYPES) {
            if (!MinersFDAI.canMine(type, item)) return false;
        }
        return true;
    }

    // Вспомогательный метод для проверки состояния строки
    private boolean isRowFullyOn(UnitType type) {
        for (Item item : MinersFDAI.MINE_ITEMS) {
            if (!MinersFDAI.canMine(type, item)) return false;
        }
        return true;
    }

    // Вспомогательный метод для создания слайдеров
    private void addSlider(Table table, String text, float min, float max, float step, float def, Cons<Float> changed, String suffix) {
        table.table(t -> {
            Label val = new Label(formatSliderValue(def, step) + suffix);
            t.add(text).left().width(180f); // Фиксированная ширина для выравнивания
            Slider slider = new Slider(min, max, step, false);
            slider.setValue(def);
            slider.changed(() -> {
                changed.get(slider.getValue());
                val.setText(formatSliderValue(slider.getValue(), step) + suffix);
            });
            t.row();
            t.add(slider).width(150f).padLeft(10);
            t.add(val).padLeft(10).width(40f);
        }).row();
    }

    /** Целые значения для крупных шагов, два знака после запятой для дробных. */
    private static String formatSliderValue(float value, float step) {
        if (step >= 1f) return String.valueOf((int) value);
        return Strings.autoFixed(value, 2);
    }
}
