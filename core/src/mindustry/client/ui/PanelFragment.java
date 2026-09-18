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
import mindustry.client.fallen.miners.MinersFDAI;
import mindustry.client.fallen.miners.MinersSettingsDialog;
import mindustry.client.navigation.BuildPath;
import mindustry.client.navigation.MinePath;
import mindustry.client.navigation.Navigation;
import mindustry.client.navigation.RepairPath;
import mindustry.client.utils.AutoTransfer;
import mindustry.content.*;
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
    public static boolean minecopper = false,minelead = false, minetitan = false, minesand = false, minecoal = false, minescrap = false;
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

    String temp_name = Core.settings.getString("mynickshifter", "nani");
    public static boolean polyAiMode = Core.settings.getBool("polyAiMode", false);
    public static final BuilderAI aiNotPolyAi = new BuilderAI();



    public PanelFragment(){ //Основной класс


        Events.run(Trigger.update, () -> {
            if(!Vars.state.isMenu()) {
//                FDAutoFill.update();
                CustomBuildLogic.update();
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
//            autoMiningActive = false;
            minecopper = true; minelead = true; minetitan = true;
            mineBerylliumwall = false; mineGraphiticwall = false;
            //minesand = false; minecoal = false;
            minescrap = false;
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

    public static String shiftColorsRight(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        List<String> colors = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();

        int i = 0;
        int len = input.length();
        while (i < len) {
            // Ищем начало тега цвета: "[#"
            if (input.charAt(i) == '[' && i + 2 < len && input.charAt(i + 1) == '#') {
                int endBracket = input.indexOf(']', i + 2);
                if (endBracket != -1) {
                    // Нашли валидный тег [#...]
                    colors.add(input.substring(i, endBracket + 1));
                    texts.add(currentText.toString());
                    currentText.setLength(0); // Очищаем буфер для следующего сегмента текста
                    i = endBracket + 1;
                    continue;
                }
            }
            // Обычный символ
            currentText.append(input.charAt(i));
            i++;
        }
        // Добавляем остаток текста после последнего цвета
        texts.add(currentText.toString());

        // Если цветов 0 или 1, сдвигать нечего
        if (colors.size() <= 1) {
            return input;
        }

        // Циклический сдвиг коллекции вправо на 1 позицию
        Collections.rotate(colors, 1);

        // Собираем строку обратно, чередуя сдвинутые цвета и исходные тексты
        StringBuilder result = new StringBuilder(input.length());
        result.append(texts.get(0)); // Текст до первого цвета (обычно пустой)
        for (int k = 0; k < colors.size(); k++) {
            result.append(colors.get(k));
            result.append(texts.get(k + 1));
        }

        return result.toString();
    }

    public static void startInit() {
        mindustry.client.fallen.ActivityLogger.init();
        MinersFDAI.init();
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
        loadStyles();
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

                Table body = new Table();

                root.table(tabs -> {
                    tabs.defaults().size(boxSize()).padRight(4f);
                    for(int i = 0; i < sections.length; i++){
                        int idx = i;
                        tabs.button(sections[i].icon, boxStyle, iconSize(), () -> {
                            tab = tab == idx ? -1 : idx;
                            settings.put("fdpanel-tab", tab);
                            buildSection(body);
                        }).update(b -> {
                            b.setChecked(tab == idx);
                            b.getImage().setColor(tab == idx ? Pal.accent : Color.white);
                        }).tooltip(bundle.get(sections[idx].name));
                    }
                }).left().row();

                root.add(body).width(contentWidth()).padTop(2f);
                buildSection(body);
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

    /** Game-style octagon buttons: light border normally, accent border when hovered or selected. */
    private static ImageButton.ImageButtonStyle boxStyle, boxActionStyle;
    /** Text rows: transparent until hovered, accent octagon border when switched on. */
    private static ImageButton.ImageButtonStyle rowStyle, rowActionStyle;

    private void loadStyles(){
        if(boxStyle != null) return;
        boxStyle = new ImageButton.ImageButtonStyle(){{
            up = Tex.buttonDown;
            over = Tex.buttonOver;
            down = Tex.buttonOver;
            checked = Tex.buttonOver;
        }};
        boxActionStyle = new ImageButton.ImageButtonStyle(){{
            up = Tex.buttonDown;
            over = Tex.buttonOver;
            down = Tex.button;
        }};
        rowStyle = new ImageButton.ImageButtonStyle(){{
            up = Styles.none;
            over = Tex.button;
            down = Tex.buttonOver;
            checked = Tex.buttonOver;
        }};
        rowActionStyle = new ImageButton.ImageButtonStyle(){{
            up = Styles.none;
            over = Tex.button;
            down = Tex.buttonOver;
        }};
    }

    private static float boxSize(){
        return Math.max(rowSize() + 10f, 36f);
    }

    private static float gridIconSize(){
        return boxSize() * 0.6f;
    }

    /** Width of the widest section, so the panel keeps one size across tabs and never gets clipped by long labels. */
    private float contentWidth(){
        float width = 0f;
        for(int i = 0; i < sections.length; i++){
            Table probe = new Table();
            fillSection(probe, i);
            width = Math.max(width, probe.getPrefWidth());
        }
        return width;
    }

    private final Section[] sections = {
        new Section("fdpanel.tab.mining", Icon.production, this::buildMining),
        new Section("fdpanel.tab.view", Icon.eye, this::buildView),
        new Section("fdpanel.tab.combat", Icon.commandAttack, this::buildCombat),
        new Section("fdpanel.tab.auto", Icon.distribution, this::buildAuto),
        new Section("fdpanel.tab.server", Icon.chat, this::buildServer),
    };

    /** Selected tab index, -1 when the panel is collapsed. */
    private int tab = settings.getInt("fdpanel-tab", 0);

    private static class Section{
        final String name;
        final Drawable icon;
        final Cons<Table> builder;

        Section(String name, Drawable icon, Cons<Table> builder){
            this.name = name;
            this.icon = icon;
            this.builder = builder;
        }
    }

    private static float rowSize(){
        return settings.getInt("buttonsizefdpamel", 30);
    }

    private static float iconSize(){
        return Math.max(rowSize() * 0.6f, 12f);
    }

    private void buildSection(Table body){
        body.clear();
        if(tab < 0 || tab >= sections.length) return;
        fillSection(body, tab);
    }

    private void fillSection(Table t, int index){
        t.defaults().growX();
        Section section = sections[index];
        header(t, section.name);
        section.builder.get(t);
    }

    private void buildMining(Table t){
        itemGrid(t, 4,
            itemToggle(Items.copper, "", () -> minecopper, () -> minecopper = !minecopper),
            itemToggle(Items.lead, "", () -> minelead, () -> minelead = !minelead),
            itemToggle(Items.titanium, "", () -> minetitan, () -> minetitan = !minetitan),
            itemToggle(Items.sand, "", () -> minesand, () -> minesand = !minesand),
            itemToggle(Items.coal, "", () -> minecoal, () -> minecoal = !minecoal),
            itemToggle(Items.scrap, "", () -> minescrap, () -> minescrap = !minescrap),
            itemToggle(Items.beryllium, bundle.get("fdpanel.wall"), () -> mineBerylliumwall, () -> mineBerylliumwall = !mineBerylliumwall),
            itemToggle(Items.graphite, bundle.get("fdpanel.wall"), () -> mineGraphiticwall, () -> mineGraphiticwall = !mineGraphiticwall)
        );

        action(t, Icon.production, "fdpanel.mine", () -> {
            eneblemining = true;
            startmining();
        });

        subheader(t, "fdpanel.miners");
        toggle(t, Icon.units, "fdpanel.automine", () -> MinersFDAI.autoMiningActive, () -> MinersFDAI.autoMiningActive = !MinersFDAI.autoMiningActive);
        toggle(t, new TextureRegionDrawable(UnitTypes.poly.uiIcon), "fdpanel.minepolys", () -> minePolys, () -> minePolys = !minePolys);
        toggle(t, Icon.hammer, "fdpanel.assistbuild", () -> MinersFDAI.autoAssistBuild, () -> MinersFDAI.autoAssistBuild = !MinersFDAI.autoAssistBuild);
        toggle(t, Icon.commandRally, "fdpanel.respectcommands", () -> MinersFDAI.respectManualCommands, () -> MinersFDAI.respectManualCommands = !MinersFDAI.respectManualCommands);
        action(t, Icon.settings, "fdpanel.minerssettings", () -> MinersSettingsDialog.get().show());
    }

    private void buildView(Table t){
        toggle(t, Icon.eye, "fdpanel.light", () -> enableLight, () -> enableLight = !enableLight);
        toggle(t, Icon.defense, "fdpanel.unitshealth", () -> viewunitshealth, () -> viewunitshealth = !viewunitshealth);
        toggle(t, Icon.effect, "fdpanel.unitseffects", () -> viewunitseffects, () -> viewunitseffects = !viewunitseffects);
        toggle(t, Icon.units, "fdpanel.unitsprogress", () -> viewprogressunit, () -> viewprogressunit = !viewprogressunit);
        toggle(t, Icon.hammer, "fdpanel.buildprogress", () -> viewprogresbuild, () -> viewprogresbuild = !viewprogresbuild);
        toggle(t, Icon.chartBar, "fdpanel.efficiency", () -> viewEfficiency, () -> viewEfficiency = !viewEfficiency);
        settingToggle(t, Icon.grid, "fdpanel.prodanal", "prod-anal");

        subheader(t, "fdpanel.scan");
        itemGrid(t, 6,
            gridAction(Icon.units, "fdpanel.scan.units", this::checkunits),
            gridAction(new TextureRegionDrawable(Blocks.coreShard.uiIcon), "fdpanel.scan.cores", this::checkcores),
            gridAction(Icon.waves, "fdpanel.scan.spawns", this::checkspawns),
            gridAction(new TextureRegionDrawable(Blocks.itemVoid.uiIcon), "fdpanel.scan.voids", this::checkvoids),
            gridAction(new TextureRegionDrawable(Blocks.itemSource.uiIcon), "fdpanel.scan.sources", this::checksources),
            gridAction(new TextureRegionDrawable(Blocks.worldProcessor.uiIcon), "fdpanel.scan.worldproc", this::checkworldprocc)
        );
        settingToggle(t, Icon.chat, "fdpanel.unitatchat", "unitatchat");
    }

    private void buildCombat(Table t){
        settingToggle(t, Icon.commandAttack, "fdpanel.smarttargeting", "smarttargeting");
        settingToggle(t, Icon.units, "fdpanel.ignoreunit", "ignoreunit");
        settingToggle(t, Icon.defense, "fdpanel.ignoreheal", "ignoreheal");
        toggle(t, Icon.zoom, "fdpanel.aim", () -> FDAutoShoot.viewUnitAim, () -> FDAutoShoot.viewUnitAim = !FDAutoShoot.viewUnitAim);
        action(t, new TextureRegionDrawable(UnitTypes.mega.uiIcon), "fdpanel.mega", () ->
            ClientVars.clientCommandHandler.handleMessage("!uc " + UnitTypes.mega.localizedName, player));
    }

    private void buildAuto(Table t){
        toggle(t, Icon.diagonal, "fdpanel.afk", () -> settings.getBool("afkmode"), () -> {
            if(!settings.getBool("afkmode")){
                eneblemining = true;
                startmining();
            }else{
                Navigation.stopFollowing();
            }
            settings.put("afkmode", !settings.getBool("afkmode"));
            new Toast(1).add(bundle.get("setting.afkmode.name") + ": " + bundle.get(settings.getBool("afkmode") ? "mod.enabled" : "mod.disabled"));
        });

        subheader(t, "fdpanel.transfer");
        toggle(t, Icon.upload, "fdpanel.autotransfer", () -> settings.getBool("autotransfer"), () -> {
            AutoTransfer.enabled = !AutoTransfer.enabled;
            settings.put("autotransfer", !settings.getBool("autotransfer"));
            new Toast(1).add(bundle.get("client.autotransfer") + ": " + bundle.get(AutoTransfer.enabled ? "mod.enabled" : "mod.disabled"));
        });
        itemGrid(t, 4,
            transferTarget(Blocks.duo, "fdpanel.target.turrets", "autotransfer-t-turrets", AutoTransfer.Settings::setTargetTurrets),
            transferTarget(Blocks.siliconSmelter, "fdpanel.target.prod", "autotransfer-t-prod", AutoTransfer.Settings::setTargetProduction),
            transferTarget(Blocks.groundFactory, "fdpanel.target.units", "autotransfer-t-units", AutoTransfer.Settings::setTargetUnitFactories),
            transferTarget(Blocks.additiveReconstructor, "fdpanel.target.recons", "autotransfer-t-recons", AutoTransfer.Settings::setTargetReconstructors)
        );

        subheader(t, "fdpanel.fixes");
        action(t, Icon.power, "fdpanel.fixpower", () -> ClientVars.clientCommandHandler.handleMessage("!fixpower c", player));
        action(t, Icon.logic, "fdpanel.fixcode", () -> ClientVars.clientCommandHandler.handleMessage("!fixcode r", player));
        settingToggle(t, Icon.trash, "fdpanel.schemcleanup", "placeSchematicWithCleanup");
    }

    private void buildServer(Table t){
        action(t, Icon.refresh, "fdpanel.sync", () -> Call.sendChatMessage("/sync"));
        action(t, Icon.ok, "fdpanel.vote", () -> Call.sendChatMessage("/vote y"));
        autoAction(t, Icon.map, "fdpanel.rtv", () -> Call.sendChatMessage("/rtv"), () -> rtvKey, () -> rtvKey = !rtvKey);
        autoAction(t, Icon.waves, "fdpanel.rtvwave", () -> Call.sendChatMessage("/rtv wave"), () -> rtvWaveKey, () -> rtvWaveKey = !rtvWaveKey);
        action(t, Icon.book, "fdpanel.history", () -> Call.sendChatMessage("/history"));

        if(settings.getBool("OneLoliToRuleThemAll", false)){
            toggle(t, Icon.warning, "fdpanel.shiftnick", () -> settings.getBool("shift_nick", false), () -> {
                temp_name = shiftColorsRight(settings.getString("mynickshifter", "nani"));
                settings.put("shift_nick", !settings.getBool("shift_nick"));
            });
        }
    }

    // region panel widgets

    private static void header(Table t, String key){
        t.add(bundle.get(key)).color(Pal.accent).left().padLeft(4f).padTop(2f).row();
        t.image().color(Pal.accent).height(3f).growX().padBottom(4f).row();
    }

    private static void subheader(Table t, String key){
        t.table(h -> {
            h.add(bundle.get(key)).color(Color.lightGray).padRight(4f).get().setFontScale(0.85f);
            h.image().color(Pal.gray).height(2f).growX();
        }).padTop(6f).padBottom(2f).padLeft(4f).row();
    }

    /** Icon + label. With {@code active == null} the row is a plain action and is always drawn bright. */
    private static void rowContent(Button b, Drawable icon, String key, Boolp active){
        b.left().margin(0f, 6f, 0f, 6f);
        b.image(icon).size(iconSize()).padRight(8f).update(i -> i.setColor(active == null ? Color.white : active.get() ? Pal.accent : Color.lightGray));
        b.add(bundle.get(key)).left().growX().update(l -> l.setColor(active == null || active.get() ? Color.white : Color.lightGray));
    }

    /** Row that switches a flag; highlighted while the flag is on. */
    private static void toggle(Table t, Drawable icon, String key, Boolp checked, Runnable action){
        t.button(b -> rowContent(b, icon, key, checked), rowStyle, action)
            .update(b -> b.setChecked(checked.get()))
            .minHeight(rowSize()).tooltip(tooltip(key)).row();
    }

    private static void settingToggle(Table t, Drawable icon, String key, String setting){
        toggle(t, icon, key, () -> settings.getBool(setting, false), () -> settings.put(setting, !settings.getBool(setting, false)));
    }

    /** Row that runs a one-off action. */
    private static void action(Table t, Drawable icon, String key, Runnable action){
        t.button(b -> rowContent(b, icon, key, null), rowActionStyle, action).minHeight(rowSize()).tooltip(tooltip(key)).row();
    }

    /** Action row: left click runs it once, right click toggles running it automatically. */
    private static void autoAction(Table t, Drawable icon, String key, Runnable action, Boolp auto, Runnable toggleAuto){
        Button button = t.button(b -> {
            rowContent(b, icon, key, null);
            Label tag = b.add(bundle.get("fdpanel.auto")).color(Pal.accent).padLeft(4f).get();
            tag.setFontScale(0.75f);
            tag.visible(auto);
        }, rowStyle, action).update(b -> b.setChecked(auto.get()))
            .minHeight(rowSize()).tooltip(tooltip(key) + "\n[lightgray]" + bundle.get("fdpanel.autohint")).get();

        button.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode key){
                if(key == KeyCode.mouseRight){
                    toggleAuto.run();
                    return true;
                }
                return false;
            }
        });
        t.row();
    }

    private static String tooltip(String key){
        String tip = key + ".tooltip";
        return bundle.has(tip) ? bundle.get(tip) : bundle.get(key);
    }

    private interface GridEntry{
        void add(Table t);
    }

    private static void itemGrid(Table t, int columns, GridEntry... entries){
        t.table(g -> {
            g.defaults().size(boxSize()).pad(2f);
            for(int i = 0; i < entries.length; i++){
                entries[i].add(g);
                if((i + 1) % columns == 0) g.row();
            }
        }).padTop(2f).padBottom(2f).row();
    }

    /** Mining ore selector: item icon, dimmed while not selected. */
    private GridEntry itemToggle(Item item, String suffix, Boolp checked, Runnable flip){
        return g -> g.button(new TextureRegionDrawable(item.uiIcon), boxStyle, gridIconSize(), () -> {
            flip.run();
            updatemineitems();
        }).update(b -> {
            b.setChecked(checked.get());
            b.getImage().setColor(checked.get() ? Color.white : Color.gray);
        }).tooltip(item.localizedName + (suffix.isEmpty() ? "" : " (" + suffix + ")"));
    }

    private static GridEntry gridAction(Drawable icon, String key, Runnable action){
        return g -> g.button(icon, boxActionStyle, gridIconSize(), action).tooltip(bundle.get(key));
    }

    private static GridEntry transferTarget(Block block, String key, String setting, Boolc apply){
        return g -> g.button(new TextureRegionDrawable(block.uiIcon), boxStyle, gridIconSize(), () -> {
            boolean val = !settings.getBool(setting, false);
            settings.put(setting, val);
            apply.get(val);
        }).update(b -> {
            boolean on = settings.getBool(setting, false);
            b.setChecked(on);
            b.getImage().setColor(on ? Color.white : Color.gray);
        }).tooltip(bundle.get(key));
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