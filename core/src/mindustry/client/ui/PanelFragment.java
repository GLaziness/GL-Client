package mindustry.client.ui;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;

import arc.input.KeyCode;
import arc.math.Mathf;

import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.style.*;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.ItemUnitStance;
import mindustry.ai.UnitCommand;
import mindustry.ai.UnitStance;
import mindustry.ai.types.BuilderAI;
import mindustry.client.ClientVars;
import mindustry.client.fallen.*;
import mindustry.client.fallen.assistai.*;
import mindustry.client.fallen.miners.MinersFDAI;
import mindustry.client.fallen.miners.MinersSettingsDialog;
import mindustry.client.navigation.BuildPath;
import mindustry.client.navigation.MinePath;
import mindustry.client.navigation.Navigation;
import mindustry.client.navigation.RepairPath;
import mindustry.client.utils.AutoTransfer;
import mindustry.client.utils.BuilderAssist;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.core.NetClient;
import mindustry.entities.Units;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.DesktopInput;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.fragments.ChatFragment;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.sandbox.ItemSource;
import mindustry.world.blocks.sandbox.LiquidSource;
import mindustry.world.blocks.sandbox.PowerSource;
import mindustry.world.blocks.sandbox.PowerVoid;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.blocks.units.UnitFactory;
import arc.struct.Seq;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static arc.Core.*;
import static mindustry.Vars.*;
import static mindustry.client.fallen.miners.MinersFDAI.minePolys;

public class PanelFragment extends Table{
    public Table fdpanel; //Создание интерфейса дял кнопок
    public static Seq<Item> itemtomine = new Seq<>(); //Создание выборки для копания
    public static boolean minecopper = true, minelead = true, minetitan = true, minesand = false, minecoal = false, minescrap = false;
    public static boolean mineBerylliumwall, mineGraphiticwall;
    private float brokenFade = 0f;
    public static int max_length = 146;
    private final IntMap<EffState> effStorage = new IntMap<>();


    private boolean eneblemining = false;
    private boolean viewunitshealth = false;
    private boolean viewunitseffects = false;
    private boolean viewprogressunit = false;
    private boolean viewprogresbuild  = false;
    private boolean viewEfficiency = false;


    public static int currentfollowmode = 0; // 1 - mine, 2 - build, 3 - heal
    public static int prevfollowmode = 0;
    public static boolean forcesavelogs = false;
    public static boolean rtvWaveKey = false;
    public static boolean rtvKey = false;
    /** GL: run "!fixpower c" by itself once a minute (right click on the power grids button). */
    public static boolean autoFixPower = false;
    private static final Interval fixPowerTimer = new Interval();

    private static final GlyphLayout layout = new GlyphLayout();
    private static final StringBuilder sb = new StringBuilder();
    private static final Color tmpCol = new Color();
    private static final Bits tempBits = new Bits();

    //шизааааааааааааааа
    public static boolean triEnabled = false;
    public static int triUnitCount = 11;
    public static float triSize = 90f;
    public static float triRotSpeed = 0.1f;
    public static int triUnitTypeIndex = 0; // Для слайдера типов

    // Отсортированный список типов юнитов
    public static Seq<UnitType> sortedUnitTypes = new Seq<>();
    public static final Seq<Unit> followers = new Seq<>();
    private static int syncTimer = 0;

    public static boolean polyAiMode = Core.settings.getBool("polyAiMode", false);
    public static final SelfBuilderAI aiNotPolyAi = new SelfBuilderAI();



    public PanelFragment(){ //Основной класс


        Events.run(Trigger.update, () -> {
            if(!Vars.state.isMenu()) {
//                FDAutoFill.update();
                CustomBuildLogic.update();
                if(autoFixPower && state.isGame() && fixPowerTimer.get(60f * 60f)) fixPowerQuiet();
            }
        });

        Events.run(Trigger.draw, () -> { //Постоянный вызов прорисовки
            if(ui.hudfrag.shown) {
                drawBuildings();
                drawUnits();
                FDAutoShoot.drawTarget();
                FDAutoShoot.drawUnitAim();
                FDAutoShoot.update();
            }
        });

        Events.on(WaveEvent.class, e -> {
            if (net.client() && rtvWaveKey) {
                Timer.schedule(() -> Call.sendChatMessage("/rtv wave"), 10f);
            }
        });

        Events.on(WorldLoadEvent.class, e -> { //Ивент, срабатывающий при загрузке карты
//            FDEnemyWarning.init();
            initTypes();
            Timer.schedule(() -> {
                if(net.client() && player.unit() != null && player.unit().id != -1){
                    //Call.sendChatMessage("/vanish 1");
                }
                if (net.client() && rtvKey) {
                    Call.sendChatMessage("/rtv");
                }
            }, 5f);
            rebuild();
            itemtomine.clear();
            updatemineitems();
            effStorage.clear();
        });
        Events.on(UnitControlEvent.class, e -> { //Проверка ресурсов при смене юнита
            updatemineitems();
        });
        Events.on(UnitChangeEvent.class, e -> { //Проверка ресурсов при смене юнита
            updatemineitems();
        });

        Events.run(Trigger.update, () -> { //currentfollowmode // 1 - mine, 2 - build, 3 - heal //переключения в режиме афк
            if (player == null || player.unit() == null) return;
            updateTriControl();
//            FDEnemyWarning.update();
            if(Core.settings.getBool("afkmode")){
                if(Navigation.currentlyFollowing == null){currentfollowmode = 1; startmining();}
                else if(Navigation.currentlyFollowing instanceof BuildPath){
                    if(player.unit().plans.size == 0 && control.input.isBuilding ){ currentfollowmode = 1; if(prevfollowmode == 1){startmining();}else{Navigation.follow(new RepairPath(), true); } }
                } else if(Navigation.currentlyFollowing instanceof MinePath){
                    if(player.unit().plans.size != 0 && control.input.isBuilding ) {prevfollowmode = 1; currentfollowmode = 1; Navigation.follow(new BuildPath("self")); } else return;
                }else if(Navigation.currentlyFollowing instanceof RepairPath){
                    if(player.unit().plans.size != 0 && control.input.isBuilding ) {prevfollowmode = 3; currentfollowmode = 3; Navigation.follow(new BuildPath("self")); } else return;
                }
            }
        });
    }

    public static void startInit() {
        mindustry.client.fallen.ActivityLogger.init();
        MinersFDAI.init();
        BuilderAssist.init();
        AntiAttemPatcher.load();
        Log.info("Start init");
    }

    void rebuild(){         //category does not change on rebuild anymore, only on new world load
        Group group = fdpanel.parent;
        int index = fdpanel.getZIndex();
        fdpanel.remove();
        build(group);
        fdpanel.setZIndex(index);
    }

    public void build(Group parent){
        parent.fill(full -> {
            fdpanel = full;
            full.top().left().visible(() -> ui.hudfrag.shown);
            Cell<Table> rootCell = full.table(Tex.buttonEdge4, root -> {
                root.margin(6f, 6f, 8f, 8f);
                root.defaults().growX();

                root.table(bars -> {
                    bars.defaults().height(18f).growX().pad(1f);
                    bars.add(new Bar(
                        () -> {
                            Unit u = player == null ? null : player.unit();
                            return u == null ? bundle.get("fdpanel.hp") : bundle.get("fdpanel.hp") + " " + Mathf.round(u.health) + " / " + Mathf.round(u.maxHealth);
                        },
                        () -> Pal.health,
                        () -> player == null || player.unit() == null ? 0f : Mathf.clamp(player.unit().healthf())
                    )).row();
                    bars.add(new Bar(
                        () -> {
                            Unit u = player == null ? null : player.unit();
                            return bundle.get("fdpanel.shield") + " " + (u == null ? 0 : Mathf.round(u.shield));
                        },
                        () -> Pal.accent,
                        () -> player == null || player.unit() == null ? 0f : Mathf.clamp(player.unit().shield / Math.max(player.unit().maxHealth, 1f))
                    ));
                }).padBottom(4f).row();

                buildMining(root);
                buildView(root);
                buildCombat(root);
                buildAuto(root);
                buildServer(root);
            }).left();

            full.update(() -> {
                if(!attachTimer.get(20f)) return;
                float offset = Core.scene.getHeight() - panelsBottom() + Scl.scl(settings.getInt("fdpanel-offset", 0));
                if(Math.abs(offset - attachedOffset) > 0.5f){
                    attachedOffset = offset;
                    rootCell.padTop(Math.max(offset, 0f) / Scl.scl(1f));
                    full.invalidate();
                }
            });
        });
    }

    private final Interval attachTimer = new Interval();
    private float attachedOffset = -1f;

    /**
     * Lowest bottom edge (in scene coordinates) of the panels stacked along the left edge of the HUD, including the
     * vanilla wave info and panels added by mods. The side panel is attached right under them.
     */
    private float panelsBottom(){
        float[] bottom = {Core.scene.getHeight()};
        collectPanels(ui.hudGroup, bottom);
        return bottom[0];
    }

    private void collectPanels(Group group, float[] bottom){
        for(Element e : group.getChildren()){
            if(e == fdpanel || !e.visible || e.getWidth() <= 0f || e.getHeight() <= 0f) continue;

            if(e instanceof Table table && table.getBackground() != null){
                Vec2 pos = e.localToStageCoordinates(Tmp.v1.set(0f, 0f));
                float top = pos.y + e.getHeight();
                boolean leftEdge = pos.x <= Scl.scl(10f);
                boolean small = e.getWidth() < Core.scene.getWidth() / 2f && e.getHeight() < Core.scene.getHeight() / 2f;
                if(leftEdge && small && top >= Core.scene.getHeight() / 2f){
                    bottom[0] = Math.min(bottom[0], pos.y);
                }
            }

            if(e instanceof Group g) collectPanels(g, bottom);
        }
    }

    /** Icons per row, same idea as the active modes display in the wave panel. */
    private static final int columns = 8;
    /** Tint for switched-off icons, matches the active modes display. */
    private static final Color offColor = new Color(0.4f, 0.4f, 0.4f, 0.55f);

    private static float cellSize(){
        return settings.getInt("buttonsizefdpamel", 30) + 4f;
    }

    private static float iconSize(){
        return Math.max(settings.getInt("buttonsizefdpamel", 30) * 0.8f, 12f);
    }

    private static Drawable icon(UnlockableContent content){
        return new TextureRegionDrawable(content.uiIcon);
    }

    private void buildMining(Table root){
        header(root, "fdpanel.tab.mining");
        iconGrid(root,
            itemToggle(Items.copper, "", () -> minecopper, () -> minecopper = !minecopper),
            itemToggle(Items.lead, "", () -> minelead, () -> minelead = !minelead),
            itemToggle(Items.titanium, "", () -> minetitan, () -> minetitan = !minetitan),
            itemToggle(Items.sand, "", () -> minesand, () -> minesand = !minesand),
            itemToggle(Items.coal, "", () -> minecoal, () -> minecoal = !minecoal),
            itemToggle(Items.scrap, "", () -> minescrap, () -> minescrap = !minescrap),
            itemToggle(Items.beryllium, bundle.get("fdpanel.wall"), () -> mineBerylliumwall, () -> mineBerylliumwall = !mineBerylliumwall),
            itemToggle(Items.graphite, bundle.get("fdpanel.wall"), () -> mineGraphiticwall, () -> mineGraphiticwall = !mineGraphiticwall),

            action(Icon.production, "fdpanel.mine", () -> {
                eneblemining = true;
                startmining();
            }),
            toggle(icon(UnitTypes.mono), "fdpanel.automine", () -> MinersFDAI.autoMiningActive, () -> MinersFDAI.autoMiningActive = !MinersFDAI.autoMiningActive),
            toggle(icon(UnitTypes.poly), "fdpanel.minepolys", () -> minePolys, () -> minePolys = !minePolys),
            toggle(Icon.hammer, "fdpanel.assistbuild", () -> MinersFDAI.autoAssistBuild, () -> MinersFDAI.autoAssistBuild = !MinersFDAI.autoAssistBuild),
            toggle(Icon.commandRally, "fdpanel.respectcommands", () -> MinersFDAI.respectManualCommands, () -> MinersFDAI.respectManualCommands = !MinersFDAI.respectManualCommands),
            toggle(Icon.pause, "fdpanel.afk", () -> settings.getBool("afkmode"), () -> {
                if(!settings.getBool("afkmode")){
                    eneblemining = true;
                    startmining();
                }else{
                    Navigation.stopFollowing();
                }
                settings.put("afkmode", !settings.getBool("afkmode"));
                new Toast(1).add(bundle.get("setting.afkmode.name") + ": " + bundle.get(settings.getBool("afkmode") ? "mod.enabled" : "mod.disabled"));
            }),
            action(Icon.settings, "fdpanel.minerssettings", () -> MinersSettingsDialog.get().show())
        );
    }

    private void buildView(Table root){
        header(root, "fdpanel.tab.view");
        iconGrid(root,
            toggle(icon(Blocks.illuminator), "fdpanel.light", () -> enableLight, () -> enableLight = !enableLight),
            toggle(Icon.add, "fdpanel.unitshealth", () -> viewunitshealth, () -> viewunitshealth = !viewunitshealth),
            toggle(Icon.effect, "fdpanel.unitseffects", () -> viewunitseffects, () -> viewunitseffects = !viewunitseffects),
            toggle(icon(Blocks.groundFactory), "fdpanel.unitsprogress", () -> viewprogressunit, () -> viewprogressunit = !viewprogressunit),
            toggle(Icon.crafting, "fdpanel.buildprogress", () -> viewprogresbuild, () -> viewprogresbuild = !viewprogresbuild),
            toggle(Icon.chartBar, "fdpanel.efficiency", () -> viewEfficiency, () -> viewEfficiency = !viewEfficiency),
            settingToggle(Icon.chartAlt, "fdpanel.prodanal", "prod-anal"),
            settingToggle(Icon.eyeOff, "fdpanel.smarttransparency", "smarttransparency")
        );

        header(root, "fdpanel.scan");
        iconGrid(root,
            action(Icon.units, "fdpanel.scan.units", this::checkunits),
            action(icon(Blocks.coreShard), "fdpanel.scan.cores", this::checkcores),
            action(Icon.modeAttack, "fdpanel.scan.spawns", this::checkspawns),
            action(icon(Blocks.itemVoid), "fdpanel.scan.voids", this::checkvoids),
            action(icon(Blocks.itemSource), "fdpanel.scan.sources", this::checksources),
            action(icon(Blocks.worldProcessor), "fdpanel.scan.worldproc", this::checkworldprocc),
            action(icon(UnitTypes.flare), "fdpanel.scan.wave", this::checkNextWave),
            settingToggle(Icon.chat, "fdpanel.unitatchat", "unitatchat")
        );
    }

    private void buildCombat(Table root){
        header(root, "fdpanel.tab.combat");
        iconGrid(root,
            settingToggle(Icon.commandAttack, "fdpanel.smarttargeting", "smarttargeting"),
            settingToggle(icon(Blocks.mender), "fdpanel.smartbuildings", "smartshoot-buildings"),
            settingToggle(new SlashTextureRegionDrawable(Icon.units.getRegion(), Color.white), "fdpanel.ignoreunit", "ignoreunit"),
            settingToggle(new SlashTextureRegionDrawable(Blocks.mender.uiIcon, Color.white), "fdpanel.ignoreheal", "ignoreheal"),
            toggle(Icon.zoom, "fdpanel.aim", () -> FDAutoShoot.viewUnitAim, () -> FDAutoShoot.viewUnitAim = !FDAutoShoot.viewUnitAim),
            action(icon(UnitTypes.mega), "fdpanel.mega", () ->
                ClientVars.clientCommandHandler.handleMessage("!uc " + UnitTypes.mega.localizedName, player))
        );
    }

    private void buildAuto(Table root){
        header(root, "fdpanel.tab.auto");
        iconGrid(root,
            toggle(Icon.upload, "fdpanel.autotransfer", () -> settings.getBool("autotransfer"), () -> {
                AutoTransfer.enabled = !AutoTransfer.enabled;
                settings.put("autotransfer", !settings.getBool("autotransfer"));
                new Toast(1).add(bundle.get("client.autotransfer") + ": " + bundle.get(AutoTransfer.enabled ? "mod.enabled" : "mod.disabled"));
            }),
            transferTarget(Blocks.duo, "fdpanel.target.turrets", "autotransfer-t-turrets", AutoTransfer.Settings::setTargetTurrets),
            transferTarget(Blocks.siliconSmelter, "fdpanel.target.prod", "autotransfer-t-prod", AutoTransfer.Settings::setTargetProduction),
            transferTarget(Blocks.groundFactory, "fdpanel.target.units", "autotransfer-t-units", AutoTransfer.Settings::setTargetUnitFactories),
            transferTarget(Blocks.additiveReconstructor, "fdpanel.target.recons", "autotransfer-t-recons", AutoTransfer.Settings::setTargetReconstructors),
            autoAction(Icon.power, "fdpanel.fixpower", PanelFragment::fixPower, () -> autoFixPower, () -> {
                autoFixPower = !autoFixPower;
                fixPowerTimer.reset(0, 0f); // the first automatic run waits a full minute
            }),
            withSettings(toggle(icon(UnitTypes.nova), "fdpanel.novaassist", () -> BuilderAssist.enabled, BuilderAssist::toggle), BuilderAssist::showSettings),
            action(Icon.logic, "fdpanel.fixcode", () -> ClientVars.clientCommandHandler.handleMessage("!fixcode r", player)),
            settingToggle(Icon.eraser, "fdpanel.schemcleanup", "placeSchematicWithCleanup"),
            settingToggle(icon(Blocks.itemBridge), "fdpanel.plastbridges", "plastbridges"),
            withSettings(toggle(icon(UnitTypes.poly), "fdpanel.polyai", () -> polyAiMode, () -> {
                polyAiMode = !polyAiMode;
                settings.put("polyAiMode", polyAiMode);
                if(!polyAiMode){
                    aiNotPolyAi.stopAfk();
                    if(player.unit() != null) player.unit().plans.clear();
                }
            }), () -> PolySettingsDialog.instance.show())
        );
    }

    private void buildServer(Table root){
        header(root, "fdpanel.tab.server");
        iconGrid(root,
            action(Icon.refresh, "fdpanel.sync", () -> Call.sendChatMessage("/sync")),
            action(Icon.ok, "fdpanel.vote", () -> Call.sendChatMessage("/vote y")),
            autoAction(Icon.map, "fdpanel.rtv", () -> Call.sendChatMessage("/rtv"), () -> rtvKey, () -> rtvKey = !rtvKey),
            autoAction(Icon.waves, "fdpanel.rtvwave", () -> Call.sendChatMessage("/rtv wave"), () -> rtvWaveKey, () -> rtvWaveKey = !rtvWaveKey),
            action(Icon.book, "fdpanel.history", () -> Call.sendChatMessage("/history"))
        );
    }

    private static void fixPower(){
        ClientVars.clientCommandHandler.handleMessage("!fixpower c", player);
    }

    /** The automatic run: silent when there is nothing to connect. */
    private static void fixPowerQuiet(){
        ClientVars.clientCommandHandler.handleMessage("!fixpower c q", player);
    }

    // region panel widgets

    private interface GridEntry{
        void add(Table t);
    }

    /** Group caption: small accent title followed by a line, like the section titles in the game's dialogs. */
    private static void header(Table root, String key){
        root.table(h -> {
            h.left();
            h.add(bundle.get(key)).color(Pal.accent).padRight(6f).get().setFontScale(0.8f);
            h.image().color(Pal.accent).height(2f).growX();
        }).growX().padTop(4f).padBottom(1f).row();
    }

    /** Lays icons out {@link #columns} per row. A {@code null} entry ends the current row early. */
    private static void iconGrid(Table root, GridEntry... entries){
        root.table(g -> {
            g.left().defaults().size(cellSize()).pad(1f);
            int col = 0;
            for(GridEntry entry : entries){
                if(entry == null || col == columns){
                    g.row();
                    col = 0;
                    if(entry == null) continue;
                }
                entry.add(g);
                col++;
            }
        }).left().row();
    }

    /** Name and, when the bundle has one, a longer description underneath. */
    private static String tooltip(String key){
        String tip = key + ".tooltip";
        return bundle.get(key) + (bundle.has(tip) ? "\n[lightgray]" + bundle.get(tip) : "");
    }

    /** Like {@link Styles#clearNonei}, but without image colors (the entries tint the icon) and with an accent tint while switched on. */
    private static ImageButton.ImageButtonStyle iconStyle;

    /** @param state for switches: shown in the tooltip as on/off; {@code null} for one-off actions */
    private static Cell<ImageButton> iconButton(Table g, Drawable icon, String tooltipText, @Nullable Boolp state, Runnable action){
        if(iconStyle == null){
            iconStyle = new ImageButton.ImageButtonStyle(){{
                up = Styles.none;
                over = Styles.flatOver;
                down = Styles.flatDown;
                checked = ((TextureRegionDrawable)Tex.whiteui).tint(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.3f);
            }};
        }
        return g.button(icon, iconStyle, iconSize(), action).tooltip(t -> {
            t.background(Styles.black6).margin(4f);
            // long descriptions wrap instead of stretching across the whole screen
            Label label = new Label(tooltipText, Styles.outlineLabel);
            float width = Math.min(label.getPrefWidth() / Scl.scl(1f), 340f);
            label.setWrap(true);
            t.add(label).width(width).left();
            if(state != null){
                t.row();
                t.label(() -> state.get() ? "[accent]" + bundle.get("fdpanel.on") : "[lightgray]" + bundle.get("fdpanel.off")).style(Styles.outlineLabel).left();
            }
        });
    }

    /** Keys of the switches whose saved state was already applied this launch. */
    private static final ObjectSet<String> restored = new ObjectSet<>();

    /**
     * Makes a panel switch keep its state between launches: the saved state is applied once per launch,
     * and any change (from the panel, a hotkey or a dialog) is written back to the settings.
     */
    private static void remember(String key, Boolp on, Runnable flip){
        if(!restored.add(key)) return;
        String setting = "glpanel-" + key;
        if(settings.has(setting) && settings.getBool(setting) != on.get()) flip.run();
    }

    private static void save(String key, boolean value){
        String setting = "glpanel-" + key;
        if(settings.getBool(setting, !value) != value) settings.put(setting, value);
    }

    /** Switch: highlighted and bright while on, dimmed like the active modes display while off. Remembers its state. */
    private static GridEntry toggle(Drawable icon, String key, Boolp on, Runnable flip){
        return savedToggle(icon, key, on, flip, tooltip(key));
    }

    private static GridEntry savedToggle(Drawable icon, String key, Boolp on, Runnable flip, String tooltipText){
        remember(key, on, flip);
        return g -> iconButton(g, icon, tooltipText, on, flip).update(b -> {
            save(key, on.get());
            b.setChecked(on.get());
            b.getImage().setColor(on.get() ? Color.white : offColor);
        });
    }

    private static GridEntry toggle(Drawable icon, Boolp on, Runnable flip, String tooltipText){
        return g -> iconButton(g, icon, tooltipText, on, flip).update(b -> {
            b.setChecked(on.get());
            b.getImage().setColor(on.get() ? Color.white : offColor);
        });
    }

    private static GridEntry settingToggle(Drawable icon, String key, String setting){
        return toggle(icon, () -> settings.getBool(setting, false), () -> settings.put(setting, !settings.getBool(setting, false)), tooltip(key));
    }

    /** One-off action, always drawn bright. */
    private static GridEntry action(Drawable icon, String key, Runnable action){
        return g -> iconButton(g, icon, tooltip(key), null, action).update(b -> b.setChecked(false));
    }

    /** Adds a right click action (opening the settings of the feature) to a grid entry. */
    private static GridEntry withSettings(GridEntry entry, Runnable openSettings){
        return g -> {
            entry.add(g);
            g.getChildren().peek().addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode key){
                    if(key == KeyCode.mouseRight){
                        openSettings.run();
                        return true;
                    }
                    return false;
                }
            });
        };
    }

    /** Left click runs the action once, right click toggles running it automatically (highlighted while automatic). */
    private static GridEntry autoAction(Drawable icon, String key, Runnable action, Boolp auto, Runnable toggleAuto){
        remember(key, auto, toggleAuto);
        return g -> {
            ImageButton b = iconButton(g, icon, tooltip(key) + "\n[lightgray]" + bundle.get("fdpanel.autohint"), auto, action).get();
            b.update(() -> {
                save(key, auto.get());
                b.setChecked(auto.get());
                b.getImage().setColor(auto.get() ? Pal.accent : Color.white);
            });
            b.addListener(new InputListener(){
                @Override
                public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode key){
                    if(key == KeyCode.mouseRight){
                        toggleAuto.run();
                        return true;
                    }
                    return false;
                }
            });
        };
    }

    /** Mining ore selector. */
    private GridEntry itemToggle(Item item, String suffix, Boolp on, Runnable flip){
        return savedToggle(icon(item), "mine." + item.name, on, () -> {
            flip.run();
            updatemineitems();
        }, bundle.get("fdpanel.mineitem") + ": " + item.localizedName + (suffix.isEmpty() ? "" : " (" + suffix + ")"));
    }

    private static GridEntry transferTarget(Block block, String key, String setting, Boolc apply){
        return toggle(icon(block), () -> settings.getBool(setting, false), () -> {
            boolean val = !settings.getBool(setting, false);
            settings.put(setting, val);
            apply.get(val);
        }, bundle.get("fdpanel.transfer") + ": " + bundle.get(key));
    }

    // endregion

    public void updateTriControl() {
        if (!triEnabled || !Vars.state.isGame() || Vars.player.unit() == null) return;
        if(triUnitTypeIndex < 0 || triUnitTypeIndex >= sortedUnitTypes.size) return;

        UnitType currentType = sortedUnitTypes.get(triUnitTypeIndex);

        // 1. Очистка и поиск юнитов
        followers.removeAll(u -> !u.isValid() || u.team != Vars.player.team() || u.type != currentType);

        if (followers.size < triUnitCount) {
            Groups.unit.each(u -> {
                if (followers.size < triUnitCount && u.type == currentType && u.team == Vars.player.team() && !followers.contains(u)) {
                    followers.add(u);
                }
            });
        }
        if (followers.size > triUnitCount) followers.truncate(triUnitCount);

        // 2. Команды (раз в 5 тиков для оптимизации сетевого трафика)
        syncTimer++;
        if (syncTimer % 30 != 0) return;

        float baseRotation = Time.time * triRotSpeed;

        for (int i = 0; i < followers.size; i++) {
            Unit unit = followers.get(i);

            // --- ЛОГИКА АТАКИ ---
            // Ищем ближайшего врага в радиусе обзора юнита (или фиксированном, напр. 250 пикселей)
            float range = unit.range();
            Unit target = Units.closestEnemy(unit.team, unit.x, unit.y, range, u ->
                    // Передаем два аргумента: targetAir и targetGround
                    u.checkTarget(unit.type.targetAir, unit.type.targetGround)
            );

            if (target != null) {
                // Атака цели
                Call.commandUnits(Vars.player, new int[]{unit.id}, null, target, new Vec2(target.x, target.y), false, true);
                continue;
            }

            // --- ЛОГИКА ТРЕУГОЛЬНИКА (если врагов нет) ---
            float progress = (float) i / Math.max(1, followers.size);
            float sideProgress = (progress * 3f) % 1f;
            int side = Mathf.floor(progress * 3f);

            float a1 = baseRotation + (side * 120f);
            float a2 = baseRotation + ((side + 1) * 120f);

            float x1 = Vars.player.x + Mathf.cosDeg(a1) * triSize;
            float y1 = Vars.player.y + Mathf.sinDeg(a1) * triSize;
            float x2 = Vars.player.x + Mathf.cosDeg(a2) * triSize;
            float y2 = Vars.player.y + Mathf.sinDeg(a2) * triSize;

            float tx = Mathf.lerp(x1, x2, sideProgress);
            float ty = Mathf.lerp(y1, y2, sideProgress);

            Call.commandUnits(Vars.player, new int[]{unit.id}, null, null, new Vec2(tx, ty), false, true);
        }
    }


    //Копание всех всем
    private void autoAssignMiningUnits() {
        if (player.unit() == null) return;

        // Списки ID юнитов по типам
        IntSeq t1Ids = new IntSeq();
        IntSeq t2Ids = new IntSeq();
        IntSeq t3Ids = new IntSeq();

        // 1. Собираем всех юнитов в группы по типам
        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable()) continue;

            if (u.type == UnitTypes.mono) t1Ids.add(u.id);
            else if (u.type == UnitTypes.poly || u.type == UnitTypes.pulsar) t2Ids.add(u.id);
            else if (u.type == UnitTypes.mega || u.type == UnitTypes.quasar) t3Ids.add(u.id);
        }

        // 2. Определяем наборы ресурсов для активации
        Item[] t1Targets = {Items.copper, Items.lead, Items.sand};
        Item[] t2Targets = {Items.copper, Items.lead, Items.sand, Items.coal};
        Item[] t3Targets = {Items.copper, Items.lead, Items.sand, Items.coal, Items.titanium};

        // 3. Отдаем приказы
        assignGroup(t1Ids, t1Targets);
        assignGroup(t2Ids, t2Targets);
        assignGroup(t3Ids, t3Targets);
    }

    private void assignGroup(IntSeq ids, Item[] items) {
        if (ids.isEmpty()) return;
        int[] rawIds = ids.toArray();

        // А. Переводим в режим добычи
        Call.setUnitCommand(player, rawIds, UnitCommand.mineCommand);

        // Б. Выключаем режим "Авто" , так как она мешает выбору конкретных ресурсов
        Call.setUnitStance(player, rawIds, UnitStance.mineAuto, false);

        // В. Включаем КАЖДЫЙ нужный ресурс по очереди
        for (Item item : items) {
            UnitStance stance = ItemUnitStance.getByItem(item);
            if (stance != null) {
                Call.setUnitStance(player, rawIds, stance, true);
            }
        }
    }

    private void checkspawns() {
        if(!state.hasSpawns()) return;

        StringBuilder sb = new StringBuilder();
        sb.append(": ");
        int num = 0;

        for(Tile tile: spawner.getSpawns()){
            sb.append("(").append(Mathf.ceil(tile.x)).append(",").append(Mathf.ceil(tile.y)).append(");");
            num++;
        }

        if(sb.length() > 2){
            String uspawns = "Spawns(" + num + ")" + sb.toString();

            if ((max_length != 0) && (uspawns.length() >= max_length)) {
                uspawns = uspawns.substring(0, max_length);
            }

            if(settings.getBool("unitatchat")){
                Call.sendChatMessage(uspawns);
            } else {
                ChatFragment.ChatMessage msg = ui.chatfrag.addMessage(uspawns, null, null, "", uspawns);
                NetClient.findCoords(msg);
            }
        }
    }

    public static void startmining() {
        if(player.team().data().core() == null) return;
        currentfollowmode = 1;
        Navigation.follow( new MinePath(itemtomine, player.team().data().core().storageCapacity), true);
    }

    private void checkvoids() {
        Threads.daemon(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append(":");

            for(Tile tile : world.tiles) {
                if (tile.block() instanceof PowerVoid) { sb.append("(").append(tile.x).append(",").append(tile.y).append(");"); }
            }

            Core.app.post(() -> {
                if (player == null || player.unit() == null) return;

                if(sb.length() > 1) {
                    String ucont = sb.toString();
                    String prefix = "[#fa]Power Voids:[white] ";
                    String fullMessage = prefix + ucont;

                    if ((max_length != 0) && (fullMessage.length() >= max_length)) {
                        fullMessage = fullMessage.substring(0, max_length);
                    }

                    if(Core.settings.getBool("unitatchat")){
                        if(state.rules.pvp) {
                            Call.sendChatMessage("/t " + fullMessage);
                        } else {
                            Call.sendChatMessage(fullMessage);
                        }
                    } else {
                        if (fullMessage.length() > 1000) fullMessage = fullMessage.substring(0, 1000) + "... [gray](truncated)";
                        ChatFragment.ChatMessage msg = ui.chatfrag.addMessage(fullMessage, null, null, "", fullMessage);
                        NetClient.findCoords(msg);
                    }
                }
            });
        });
    }

    private void checksources() {
        Threads.daemon(() -> {
            if (world.tiles == null) return;
            ObjectMap<Team, StringBuilder> teamBuilders = new ObjectMap<>();
            for (Team t : Team.all) teamBuilders.put(t, new StringBuilder().append(":"));

            for (Tile tile : world.tiles) {
                if (tile.build != null && tile.build.tile == tile) {
                    Block block = tile.build.block;
                    if (block instanceof ItemSource || block instanceof PowerSource || block instanceof LiquidSource) {
                        Team team = tile.build.team;
                        StringBuilder sb = teamBuilders.get(team);
                        if (sb != null) sb.append(Fonts.getUnicodeStr(block.name)).append("(").append(tile.x).append(",").append(tile.y).append(");");
                    }
                }
            }

            Core.app.post(() -> {
                if (player == null || player.unit() == null) return;
                for (ObjectMap.Entry<Team, StringBuilder> entry : teamBuilders.entries()) {
                    Team cteam = entry.key;
                    StringBuilder sb = entry.value;

                    if (sb.length() > 1) {
                        String content = sb.toString();
                        boolean atChat = Core.settings.getBool("unitatchat");
                        String fullMessage = "[#" + cteam.color + "]" + cteam.name + (atChat ? "[white]" : "[]") + content;

                        if (atChat) {
                            if (max_length != 0 && fullMessage.length() >= max_length) fullMessage = fullMessage.substring(0, max_length);
                            if (state.rules.pvp) Call.sendChatMessage("/t " + fullMessage); else Call.sendChatMessage(fullMessage);
                        } else {
                            if (fullMessage.length() > 1000) fullMessage = fullMessage.substring(0, 1000) + "... [gray](truncated)";
                            ChatFragment.ChatMessage msg = ui.chatfrag.addMessage(fullMessage, null, null, "", fullMessage);
                            NetClient.findCoords(msg);
                        }
                    }
                }
            });
        });
    }

    private void checkworldprocc() {
        Threads.daemon(() -> {
            ObjectMap<Team, StringBuilder> teamBuilders = new ObjectMap<>();
            for (Team t : Team.all) teamBuilders.put(t, new StringBuilder().append(":"));

            for(Tile tile : world.tiles) {
                if(tile.build != null && tile.build.tile == tile && tile.build.block == Blocks.worldProcessor) {
                    Team team = tile.build.team;
                    StringBuilder sb = teamBuilders.get(team);
                    if (sb != null) sb.append(Fonts.getUnicodeStr(Blocks.worldProcessor.name)).append("(").append(tile.x).append(", ").append(tile.y).append("); ");
                }
            }

            Core.app.post(() -> {
                if (player == null || player.unit() == null) return;
                for(ObjectMap.Entry<Team, StringBuilder> entry : teamBuilders.entries()) {
                    StringBuilder sb = entry.value;
                    if(sb.length() > 1) {
                        Team cteam = entry.key;
                        boolean atChat = Core.settings.getBool("unitatchat");
                        String fullMessage = "[#" + cteam.color + "]" + cteam.name + (atChat ? "[white]" : "[]") + sb.toString();

                        if (atChat) {
                            if (max_length != 0 && fullMessage.length() >= max_length) fullMessage = fullMessage.substring(0, max_length);
                            if (state.rules.pvp) Call.sendChatMessage("/t " + fullMessage); else Call.sendChatMessage(fullMessage);
                        } else {
                            if (fullMessage.length() > 1000) fullMessage = fullMessage.substring(0, 1000) + "... [gray](truncated)";
                            ChatFragment.ChatMessage msg = ui.chatfrag.addMessage(fullMessage, null, null, "", fullMessage);
                            NetClient.findCoords(msg);
                        }
                    }
                }
            });
        });
    }

    /**
     * Next wave enemy composition (side panel).
     * Same routing as Eye of Sauron: public chat only when "Unit in chat" ({@code unitatchat}) is on.
     */
    private void checkNextWave(){
        if(state.isMenu() || state.rules == null || state.rules.spawns == null) return;

        int displayWave = Math.max(state.wave, 1);
        int internalWave = displayWave - 1;
        int spawnCount = Math.max(spawner.getSpawns() != null ? spawner.getSpawns().size : 0, 1);
        boolean toChat = settings.getBool("unitatchat");

        // Aggregate unit type (+ optional status) → count for this wave
        ObjectMap<String, Integer> counts = new ObjectMap<>();
        ObjectMap<String, UnitType> types = new ObjectMap<>();
        ObjectMap<String, StatusEffect> effects = new ObjectMap<>();
        int totalUnits = 0;
        float totalHp = 0f, totalShield = 0f;

        for(SpawnGroup group : state.rules.spawns){
            if(group == null || group.type == null) continue;
            int amt = group.getSpawned(internalWave);
            if(amt <= 0) continue;

            int finalAmt = amt * (group.spawn == -1 ? spawnCount : 1);
            StatusEffect eff = (group.effect == null || group.effect == StatusEffects.none) ? null : group.effect;
            String key = group.type.name + (eff != null ? ":" + eff.name : "");

            counts.put(key, counts.get(key, 0) + finalAmt);
            types.put(key, group.type);
            if(eff != null) effects.put(key, eff);

            totalUnits += finalAmt;
            totalHp += group.type.health * finalAmt;
            totalShield += group.getShield(internalWave) * finalAmt;
        }

        if(counts.isEmpty()){
            postWaveInfo("W" + displayWave + ": —", toChat);
            return;
        }

        // Sort keys by count desc for readable summary
        Seq<String> keys = counts.keys().toSeq();
        keys.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));

        StringBuilder body = new StringBuilder();
        for(String key : keys){
            UnitType type = types.get(key);
            StatusEffect eff = effects.get(key);
            int n = counts.get(key);
            body.append(Fonts.getUnicodeStr(type.name)).append("x").append(n);
            if(eff != null){
                body.append(Fonts.getUnicodeStr(eff.name));
            }
            body.append(" ");
        }

        String hpStr = totalHp >= 1000 ? Strings.fixed(totalHp / 1000f, 1) + "k" : String.valueOf(Math.round(totalHp));
        String shStr = totalShield >= 1000 ? Strings.fixed(totalShield / 1000f, 1) + "k" : String.valueOf(Math.round(totalShield));

        String full = "W" + displayWave + " (" + totalUnits + ") HP:" + hpStr
            + (totalShield > 0 ? " Sh:" + shStr : "")
            + ": " + body.toString().trim();

        // Split into chat-sized chunks when posting publicly
        if(toChat && max_length > 0 && full.length() > max_length){
            String rest = body.toString().trim();
            int pos = 0;
            int part = 0;
            String prefix = "W" + displayWave + ": ";
            while(pos < rest.length()){
                int end = Math.min(pos + Math.max(20, max_length - prefix.length() - 4), rest.length());
                if(end < rest.length()){
                    int sp = rest.lastIndexOf(' ', end);
                    if(sp > pos) end = sp;
                }
                String chunk = (part == 0 ? prefix : "W" + displayWave + "+ ") + rest.substring(pos, end).trim();
                postWaveInfo(chunk, true);
                pos = end;
                while(pos < rest.length() && rest.charAt(pos) == ' ') pos++;
                part++;
            }
        }else{
            postWaveInfo(full, toChat);
        }
    }

    private void postWaveInfo(String message, boolean toPublicChat){
        if(message == null || message.isEmpty()) return;
        if(toPublicChat){
            String msg = message;
            if(max_length > 0 && msg.length() > max_length) msg = msg.substring(0, max_length);
            if(state.rules.pvp) Call.sendChatMessage("/t " + msg);
            else Call.sendChatMessage(msg);
        }else{
            String local = message.length() > 1000 ? message.substring(0, 1000) + "..." : message;
            ui.chatfrag.addMessage(local, null, null, "", local);
        }
    }

    private void checkcores() {
        Threads.daemon(() -> {
            ObjectMap<Team, StringBuilder> teamBuilders = new ObjectMap<>();
            ObjectMap<Team, Integer> teamCounters = new ObjectMap<>();

            for (Team t : Team.all) {
                teamBuilders.put(t, new StringBuilder().append(":"));
                teamCounters.put(t, 0);
            }

            for(Tile tile : world.tiles) {
                if(tile.build instanceof CoreBlock.CoreBuild && tile.build.tile == tile) {
                    Team team = tile.build.team;
                    StringBuilder sb = teamBuilders.get(team);

                    if (sb != null) {
                        sb.append(Fonts.getUnicodeStr(tile.build.block.name))
                                .append("(")
                                .append(tile.x)
                                .append(", ")
                                .append(tile.y)
                                .append("); ");

                        teamCounters.put(team, teamCounters.get(team) + 1);
                    }
                }
            }

            Core.app.post(() -> {
                if (player == null || player.unit() == null) return;

                for(ObjectMap.Entry<Team, StringBuilder> entry : teamBuilders.entries()) {
                    StringBuilder sb = entry.value;
                    if(sb.length() > 1) {
                        Team cteam = entry.key;
                        int count = teamCounters.get(cteam);
                        String content = sb.toString();
                        String fullMessage;

                        if(Core.settings.getBool("unitatchat")){
                            fullMessage = "[#" + cteam.color + "]" + cteam.name + "(" + count + ")[white]" + content;

                            if ((max_length != 0) && (fullMessage.length() >= max_length)) {
                                fullMessage = fullMessage.substring(0, max_length);
                            }

                            if(state.rules.pvp) Call.sendChatMessage("/t " + fullMessage);
                            else Call.sendChatMessage(fullMessage);
                        } else {
                            fullMessage = "[#" + cteam.color + "]" + cteam.name + "(" + count + ")[]" + content;
                            if (fullMessage.length() > 1000) fullMessage = fullMessage.substring(0, 1000) + "... [gray](truncated)";
                            ChatFragment.ChatMessage msg = ui.chatfrag.addMessage(fullMessage, null, null, "", fullMessage);
                            NetClient.findCoords(msg);
                        }
                    }
                }
            });
        });
    }

    private void checkunits() {
        Threads.daemon(() -> {
            ObjectMap<Team, ObjectIntMap<UnitType>> teamUnitMap = new ObjectMap<>();

            for (Unit unit : Groups.unit) {
                if (unit == null || unit.type == null) continue;

                Team team = unit.team;
                if (!teamUnitMap.containsKey(team)) {
                    teamUnitMap.put(team, new ObjectIntMap<>());
                }

                ObjectIntMap<UnitType> counts = teamUnitMap.get(team);
                counts.put(unit.type, counts.get(unit.type, 0) + 1);
            }

            Seq<String> messagesToSend = new Seq<>();
            StringBuilder currentBatch = new StringBuilder();

            for (Team team : Team.all) {
                if (!teamUnitMap.containsKey(team)) continue;

                ObjectIntMap<UnitType> counts = teamUnitMap.get(team);
                if (counts.size == 0) continue;

                StringBuilder teamSb = new StringBuilder();
                String color = Core.settings.getBool("unitatchat") ? "[#" + team.color + "]" : "[#" + team.color + "]";
                String reset = Core.settings.getBool("unitatchat") ? "[white]" : "[]";

                teamSb.append(color).append(team.name).append(reset).append(":");

                for (ObjectIntMap.Entry<UnitType> entry : counts.entries()) {
                    teamSb.append(Fonts.getUnicodeStr(entry.key.name))
                            .append(entry.value)
                            .append(";");
                }

                String teamString = teamSb.toString();

                if (currentBatch.length() + teamString.length() + 3 < max_length) {
                    if (currentBatch.length() > 0) currentBatch.append(" | ");
                    currentBatch.append(teamString);
                } else {
                    if (currentBatch.length() > 0) {
                        messagesToSend.add(currentBatch.toString());
                        currentBatch.setLength(0);
                    }

                    if (teamString.length() >= max_length) {
                        messagesToSend.add(teamString.substring(0, Math.min(teamString.length(), max_length)));
                    } else {
                        currentBatch.append(teamString);
                    }
                }
            }

            if (currentBatch.length() > 0) {
                messagesToSend.add(currentBatch.toString());
            }

            Core.app.post(() -> {
                if (player == null || messagesToSend.isEmpty()) return;

                for (int i = 0; i < messagesToSend.size; i++) {
                    String msg = messagesToSend.get(i);

                    Timer.schedule(() -> {
                        if (player == null) return;

                        if (Core.settings.getBool("unitatchat")) {
                            if (state.rules.pvp) Call.sendChatMessage("/t " + msg);
                            else Call.sendChatMessage(msg);
                        } else {
                            ChatFragment.ChatMessage m = ui.chatfrag.addMessage(msg, null, null, "", msg);
                            NetClient.findCoords(m);
                        }
                    }, i * 1.1f);
                }
            });
        });
    }
    private void oldcheckunits() {
        Threads.daemon(() -> {
            Seq<String> messagesToSend = new Seq<>();
            StringBuilder currentBatch = new StringBuilder();

            for(Team team : Team.all) {
                if (team.data().unitCount == 0) continue;

                StringBuilder teamSb = new StringBuilder();
                boolean hasUnits = false;

                if(Core.settings.getBool("unitatchat")){
                    teamSb.append("[#").append(team.color).append("]").append(team.name).append("[white]:");
                } else {
                    teamSb.append("[#").append(team.color).append("]").append(team.name).append("[]:");
                }

                for(UnitType type : content.units()) {
                    int count = team.data().countType(type);
                    if(count > 0) {
                        teamSb.append(Fonts.getUnicodeStr(type.name)).append(count).append(";");
                        hasUnits = true;
                    }
                }

                if(!hasUnits) continue;

                String teamString = teamSb.toString();

                if (currentBatch.length() + teamString.length() < max_length) {
                    if (currentBatch.length() > 0) currentBatch.append(" | ");
                    currentBatch.append(teamString);
                }
                else {
                    if (currentBatch.length() > 0) {
                        messagesToSend.add(currentBatch.toString());
                        currentBatch.setLength(0);
                    }

                    if (teamString.length() >= max_length && max_length != 0) {
                        messagesToSend.add(teamString.substring(0, max_length));
                    } else {
                        currentBatch.append(teamString);
                    }
                }
            }

            if (currentBatch.length() > 0) {
                messagesToSend.add(currentBatch.toString());
            }

            Core.app.post(() -> {
                if (player == null || player.unit() == null) return;
                if (messagesToSend.isEmpty()) return;

                for (int i = 0; i < messagesToSend.size; i++) {
                    String msg = messagesToSend.get(i);

                    Timer.schedule(() -> {
                        if (player == null) return;

                        if(Core.settings.getBool("unitatchat")){
                            if(state.rules.pvp) {
                                Call.sendChatMessage("/t " + msg);
                            } else {
                                Call.sendChatMessage(msg);
                            }
                        } else {
                            ChatFragment.ChatMessage m = ui.chatfrag.addMessage(msg, null, null, "", msg);
                            NetClient.findCoords(m);
                        }
                    }, i * 1.1f);
                }
            });
        });
    }

    private static class EffState {
        float prevValue = 0;
        float currentValue = 0;
        float startTime = 0;
        float lastUpdate = 0;
    }

    private float getSmoothEfficiency(Building build) {
        float timerSeconds = 10f; // Уменьшим окно до 10 сек для отзывчивости
        float windowTicks = timerSeconds * 60f;
        float now = Time.time;

        EffState state = effStorage.get(build.id);

        if (state == null) {
            state = new EffState();
            state.startTime = now;
            state.lastUpdate = now;
            effStorage.put(build.id, state);
            return 0;
        }

        float passedTime = now - state.lastUpdate;
        float elapsedSinceStart = now - state.startTime;

        if (elapsedSinceStart > windowTicks) {
            state.prevValue = state.currentValue / windowTicks;
            state.currentValue = 0;
            state.startTime = now;
        }

        // ГЛАВНОЕ ИЗМЕНЕНИЕ: эффективность умножаем на скорость времени (Overdrive)
        // Для ускорителей берем просто их рабочее состояние
        float points = build.efficiency * build.timeScale();

        // Если это сам ускоритель, его timeScale всегда 1, но нам важно, работает ли он
        if(build instanceof mindustry.world.blocks.defense.OverdriveProjector.OverdriveBuild) {
            points = build.efficiency;
        }

        state.currentValue += passedTime * points;
        state.lastUpdate = now;

        float measurement = Math.min(1f, elapsedSinceStart / windowTicks);
        return (state.currentValue / windowTicks) + (state.prevValue * (1f - measurement));
    }
    public static void initTypes() {
        // Используем select вместо filter
        sortedUnitTypes = Vars.content.units().copy()
                .select(u -> !u.internal)
                .select(u -> !u.hidden)
                .sort(u -> u.health);
    }


    private void updatemineitems() { //Выбор руд
        if (player == null || player.unit() == null) return;

        if (minecopper){if(!itemtomine.contains(Items.copper)) itemtomine.add(Items.copper);} else {if(itemtomine.contains(Items.copper)){itemtomine.remove(Items.copper);}}
        if (minelead){if(!itemtomine.contains(Items.lead)) itemtomine.add(Items.lead);} else {if(itemtomine.contains(Items.lead)){itemtomine.remove(Items.lead);}}
        if (minesand){if(!itemtomine.contains(Items.sand)) itemtomine.add(Items.sand);} else {if(itemtomine.contains(Items.sand)){itemtomine.remove(Items.sand);}}
        if (minecoal){if(!itemtomine.contains(Items.coal)) itemtomine.add(Items.coal);} else {if(itemtomine.contains(Items.coal)){itemtomine.remove(Items.coal);}}
        if (minescrap){if(!itemtomine.contains(Items.scrap)) itemtomine.add(Items.scrap);} else {if(itemtomine.contains(Items.scrap)){itemtomine.remove(Items.scrap);}}
        if (minetitan){if(!itemtomine.contains(Items.titanium)) itemtomine.add(Items.titanium);} else {if(itemtomine.contains(Items.titanium)){itemtomine.remove(Items.titanium);}}
        if (mineBerylliumwall){if(!itemtomine.contains(Items.beryllium)) itemtomine.add(Items.beryllium);} else {if(itemtomine.contains(Items.beryllium)){itemtomine.remove(Items.beryllium);}}
        if (mineGraphiticwall){if(!itemtomine.contains(Items.graphite)) itemtomine.add(Items.graphite);} else {if(itemtomine.contains(Items.graphite)){itemtomine.remove(Items.graphite);}}

        if((player.unit().type == UnitTypes.evoke)||(player.unit().type == UnitTypes.incite)||(player.unit().type == UnitTypes.emanate)){
            if(itemtomine.contains(Items.copper)){itemtomine.remove(Items.copper);}
            if(itemtomine.contains(Items.lead)){itemtomine.remove(Items.lead);}
            if(itemtomine.contains(Items.sand)){itemtomine.remove(Items.sand);}
            if(itemtomine.contains(Items.coal)){itemtomine.remove(Items.coal);}
            if(itemtomine.contains(Items.scrap)){itemtomine.remove(Items.scrap);}
            if(itemtomine.contains(Items.titanium)){itemtomine.remove(Items.titanium);}
        }

    }
    private void drawBuildings() {
        if (!viewprogressunit && !viewprogresbuild && !viewEfficiency) return;

        float fontScale = 0.25f / Scl.scl(1.0f);
        Font font = Fonts.outline;

        font.setUseIntegerPositions(false);
        font.getData().setScale(fontScale);
        font.setColor(Color.white);

        for(Building bui : Groups.build) {
            if(!bui.within(Core.camera.position, Core.graphics.getWidth() / 1.5f)) continue;

            if (viewprogressunit) {             // --- ЛОГИКА ФАБРИК ---
                float prog = 0;
                if (bui instanceof UnitFactory.UnitFactoryBuild build) {
                    prog = build.fraction();
                } else if (bui instanceof Reconstructor.ReconstructorBuild buildr) {
                    prog = buildr.fraction();
                }

                if (prog > 0.0001f) {
                    drawBarAndText(bui.x, bui.y, bui.block.size * 4, bui.team.color, prog, true, font);
                }
            }

            if (viewprogresbuild && bui instanceof ConstructBuild entity) {        // --- ЛОГИКА СТРОИТЕЛЬСТВА  ---
                float prog = entity.progress;
                if (prog > 0.0001f && prog < 1f) {
                    drawTextOnly(bui.x, bui.y, prog, font);
                }
            }

            if (viewEfficiency) {
                boolean isProducer = bui.block.category == Category.production ||
                        bui.block.category == Category.crafting ||
                        bui.block.category == Category.units ||
                        bui instanceof mindustry.world.blocks.distribution.MassDriver.MassDriverBuild  ||
                        bui instanceof mindustry.world.blocks.production.Pump.PumpBuild  ||
                        bui instanceof mindustry.world.blocks.defense.OverdriveProjector.OverdriveBuild  ||
                        bui instanceof mindustry.world.blocks.power.PowerGenerator.GeneratorBuild;

                if (isProducer) {
                    float smoothEff = getSmoothEfficiency(bui);

                    if (smoothEff >= 0f) {
                        // Расчет цвета:
                        if (smoothEff < 0.5f) tmpCol.set(Color.red).lerp(Color.orange, smoothEff * 2f);
                        else if (smoothEff <= 1.02f) tmpCol.set(Color.orange).lerp(Color.green, (smoothEff - 0.5f) * 2f);
                        else tmpCol.set(Color.green).lerp(Color.cyan, Math.min(1f, (smoothEff - 1f) / 1.5f)); // Голубой для > 100%

                        Draw.z(Layer.darkness + 1);
                        sb.setLength(0);

                        // Если эффективность > 100%, подсвечиваем это
                        int val = Math.round(smoothEff * 100);
                        sb.append(val).append("%");

                        layout.setText(font, sb);
                        font.setColor(tmpCol);

                        // Отрисовка в центре блока
                        font.draw(sb, bui.x - layout.width / 2, bui.y + layout.height / 2);
                    }
                }
            }
        }

        font.getData().setScale(1f);
        Draw.reset();
    }

    private void drawUnits() {
        if (!viewunitshealth && !viewunitseffects) return;

        brokenFade = Mathf.lerpDelta(brokenFade, 1f, 0.1f);

        float fontScale = 0.25f / Scl.scl(1.0f);
        Font font = Fonts.outline;

        font.setUseIntegerPositions(false);
        font.getData().setScale(fontScale);

        for(Unit unit : Groups.unit) {
            if(!unit.isAdded() || !unit.within(Core.camera.position, Core.graphics.getWidth() / 1.5f)) continue;

            if (viewunitshealth && unit.health < unit.maxHealth) {
                float prog = unit.health / unit.maxHealth;
                if (prog > 0.0001f) {
                    tmpCol.set(Color.white).lerp(Color.black, 1f - prog);

                    drawBarAndText(unit.x, unit.y - (unit.hitSize * 3) + (unit.hitSize + 2), unit.hitSize, unit.team.color, prog, false, font);
                }
            }
            if (viewunitseffects) {
                Draw.alpha(0.90f * brokenFade);
                tempBits.clear();
                Bits applied = unit.statusBits();

                if(applied != null && !applied.isEmpty()){
                    int i = 0;
                    for(StatusEffect effect : content.statusEffects()){
                        if(applied.get(effect.id) && !effect.isHidden()){
                            Draw.rect(effect.uiIcon, unit.x + i * effect.uiIcon.width / 4f, unit.y);
                            i++;
                        }
                    }
                }
            }
        }

        font.getData().setScale(1f);
        Draw.reset();
    }

    private void drawBarAndText(float x, float y, float width, Color teamColor, float prog, boolean isFactory, Font font) {
        float yOffset = isFactory ? width + 2 : width + 2; // В оригинале было (hw + 2)

        Draw.z(Layer.darkness + 1);

        if(isFactory){
            Draw.color(Pal.darkerGray);
        } else {
            Draw.color(tmpCol);
        }

        Lines.stroke(4);
        Lines.line(x - width, y + yOffset, x - width + width * 2, y + yOffset);

        Draw.color(teamColor);
        Lines.stroke(2);
        Lines.line(x - width, y + yOffset, x - width + width * 2 * prog, y + yOffset);

        sb.setLength(0);
        sb.append((int)(prog * 100)).append("%");

        layout.setText(font, sb);
        font.setColor(Color.white);
        font.draw(sb, x - layout.width / 2, y + yOffset + layout.height / 2 + 6);
        Draw.reset();
    }

    private void drawTextOnly(float x, float y, float prog, Font font) {
        Draw.z(Layer.darkness + 1);

        sb.setLength(0);
        sb.append((int)(prog * 100)).append("%");

        layout.setText(font, sb);
        font.setColor(Color.white);
        font.draw(sb, x - layout.width / 2, y + layout.height / 2);

        Draw.reset();
    }
}