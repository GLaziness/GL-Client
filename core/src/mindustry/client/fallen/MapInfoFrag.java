package mindustry.client.fallen;

import arc.*;
import arc.func.Prov;
import arc.graphics.*;
import arc.input.KeyCode;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.Blocks;
import mindustry.core.UI;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.type.*;
import mindustry.client.Spectate;

import static arc.Core.graphics;
import static arc.Core.scene;
import static mindustry.Vars.*;

public class MapInfoFrag extends Table {
    private boolean visible = false;
    private float lastX = 0, lastY = 0;
    private boolean centered = false;

    private ObjectMap<Item, IntSeq> orePositions = new ObjectMap<>();
    private ObjectMap<Block, IntSeq> floorPositions = new ObjectMap<>();
    private ObjectMap<Block, IntSeq> wallPositions = new ObjectMap<>();
    private ObjectIntMap<Object> cycleIndices = new ObjectIntMap<>();

    private Table resTable = new Table();
    private Table floorTable = new Table();
    private Table teamTable = new Table();
    private Table wallTable = new Table();
    private Table bannedTable = new Table();



    public void build(Group parent) {
        parent.addChild(this);
        //setSize(500f, 750f);

        float w = Core.settings.getFloat("mapfragwidth", 500f);
        float h = Core.settings.getFloat("mapfragheight", 700f);
        if(w < 50f) w = 50f;
        if(h < 50f) h = 50f;
        setSize(w, h);

        this.touchable = Touchable.childrenOnly;
        visible(() -> ui.hudfrag.shown && visible && state.isGame());

        table(Styles.black6, main -> {
            // ВЕРХНЯЯ ПАНЕЛЬ
            main.table(hp -> {
                ImageButton drag = hp.button(Icon.move, Styles.cleari, () -> {}).size(35f).get();
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
                        Core.settings.put("mapfrag-x", MapInfoFrag.this.x);
                        Core.settings.put("mapfrag-y", MapInfoFrag.this.y);
                    }

                });

                hp.add("[accent]MAP ANALYZER[]").padLeft(10).growX().left();
                hp.button(Icon.refresh, Styles.cleari, () -> {setPosition(graphics.getWidth() / 2f, graphics.getHeight() / 2f, Align.center); scanWorld();}).size(35f);
                hp.button(Icon.edit, Styles.cleari, () -> {ui.mapInfoDial.show();}).size(40f);
                hp.button(Icon.cancel, Styles.cleari, this::toggle).size(35f);
            }).growX().pad(4).color(Color.valueOf("3a3a3a"));

            main.row();

            main.pane(p -> {
                p.top().defaults().growX().pad(4);

                // 1. Attributes & Multipliers (Две колонки)
                p.table(top -> {
                    top.defaults().growX().top();

                    // Левая колонка: Attributes
                    top.table(Styles.black3, t -> {
                        t.add("[accent]Attributes[]").colspan(2).padBottom(4).row();
                        addProp(t, "Size:", () -> world.width() + "x" + world.height(), null);
                        addProp(t, "Waves:", () -> state.rules.waves ? "Yes" : "No", true);
                        addProp(t, "Win Wave:", () -> state.rules.winWave > 0 ? "" + state.rules.winWave : "Inf", 0);
                        addProp(t, "Initial Sp:", () -> (state.rules.initialWaveSpacing / 60f) + "s", 300f);
                        addProp(t, "Wave Sp:", () -> (state.rules.waveSpacing / 60f) + "s", 120f);
                        addProp(t, "Solar Power:", () -> state.rules.solarMultiplier + "x", 1.0f);
                        addProp(t, "Unit Cap Var:", () -> state.rules.unitCapVariable ? "Yes" : "No", true);
                    }).padRight(4);

                    // Правая колонка: Multipliers
                    top.table(Styles.black3, t -> {
                        t.add("[accent]Multipliers[]").colspan(2).padBottom(4).row();
                        float d = 1.0f;
                        addProp(t, "Build Cost:", () -> state.rules.buildCostMultiplier + "x", d);
                        addProp(t, "Build Spd:", () -> state.rules.buildSpeedMultiplier + "x", d);
                        addProp(t, "Block HP:", () -> state.rules.blockHealthMultiplier + "x", d);
                        addProp(t, "Unit HP:", () -> state.rules.unitHealthMultiplier + "x", d);
                        addProp(t, "Unit Dmg:", () -> state.rules.unitDamageMultiplier + "x", d);
                        addProp(t, "Unit Cost:", () -> (state.rules.unitCostMultiplier) + "x", d);
                        addProp(t, "Base Unit Cap:", () -> (state.rules.unitCap) + "", d);

                    });
                }).row();

                // 2. Rules (Широкая таблица, Booleans)
                p.table(Styles.black3, t -> {
                    t.add("[accent]Rules Settings[]").colspan(4).padBottom(4).row();
                    t.defaults().growX().left().fontScale(0.8f);


                    addBoolProp(t, Core.bundle.get("map_inf.fire"), () -> state.rules.fire, false);
                    addBoolProp(t,  Core.bundle.get("map_inf.dExplosions"), () -> state.rules.damageExplosions, true);
                    t.row();
                    addBoolProp(t, Core.bundle.get("map_inf.rExplosions"), () -> state.rules.reactorExplosions, false);
                    addBoolProp(t, Core.bundle.get("map_inf.luBuild"), () -> state.rules.logicUnitBuild, false);
                    t.row();
                    addBoolProp(t, Core.bundle.get("map_inf.sAllowed"), () -> state.rules.schematicsAllowed, true);
                    addBoolProp(t, Core.bundle.get("map_inf.cCapture"), () -> state.rules.coreCapture, false);
                    t.row();
                    addBoolProp(t, Core.bundle.get("map_inf.fogWar"), () -> state.rules.fog, false);
                    t.row();
                    addBoolProp(t, Core.bundle.get("map_inf.coreInc"), () -> state.rules.coreIncinerates, true);
                    addBoolProp(t, Core.bundle.get("map_inf.oDCore"), () -> state.rules.onlyDepositCore, false);
                    t.row();
                    addBoolProp(t, Core.bundle.get("map_inf.randWave"), () -> state.rules.randomWaveAI, false);
                    addBoolProp(t, Core.bundle.get("map_inf.airSpawns"), () -> state.rules.airUseSpawns, false);

                }).padTop(4).row();

                p.table(Styles.black3, res -> {
                    res.button(b -> b.add("[accent]Resources (Ores)").fontScale(0.8f), Styles.cleart, () -> {
                        copySection("Resources", orePositions);
                    }).growX().pad(4).row();
                    res.add(resTable).growX();
                }).padTop(4).row();
                p.table(Styles.black3, res -> {
                    res.button(b -> b.add("[accent]Floors").fontScale(0.8f), Styles.cleart, () -> {
                        copySection("Floors", floorPositions);
                    }).growX().pad(4).row();
                    res.add(floorTable).growX();
                }).padTop(4).row();

                p.table(Styles.black3, walls -> {
                    walls.button(b -> b.add("[accent]Environment Walls").fontScale(0.8f), Styles.cleart, () -> {
                        copySection("Walls", wallPositions);
                    }).growX().pad(4).row();
                    walls.add(wallTable).growX();
                }).padTop(4).row();

                p.table(Styles.black3, teams -> {
                    teams.add("[accent]Active Teams[]").pad(4).row();
                    teams.add(teamTable).growX();
                }).padTop(4).row();

                p.table(Styles.black3, banned -> {
                    banned.add("[accent]Banned Content[]").pad(4).row();
                    banned.add(bannedTable).growX();
                }).padTop(4).row();

            }).grow().scrollX(false).update(pane -> {
                if(scene.getScrollFocus() == pane && !Core.input.shift()){
                    scene.setScrollFocus(null);
                }
            });

        }).grow();

        // Позиционирование
        update(() -> {
            if(!centered && graphics.getWidth() > 0){
                if(Core.settings.has("mapfrag-x") && Core.settings.has("mapfrag-y")){
                    float sx = Core.settings.getFloat("mapfrag-x");
                    float sy = Core.settings.getFloat("mapfrag-y");

                    sx = Mathf.clamp(sx, 0, Core.graphics.getWidth() - width);
                    sy = Mathf.clamp(sy, 0, Core.graphics.getHeight() - height);
                    setPosition(sx, sy);
                } else {
                    setPosition(graphics.getWidth() / 2f, graphics.getHeight() / 2f, Align.center);
                }

                centered = true;
            }
        });

        Events.on(WorldLoadEvent.class, e -> scanWorld());
    }

    private void addProp(Table t, String name, Prov<? extends CharSequence> val, Object def) {
        t.add(name).left().color(Color.lightGray).fontScale(0.8f);
        t.label(() -> {
            CharSequence current = val.get();
            boolean isDefault = def != null && String.valueOf(current).equals(String.valueOf(def));
            return (isDefault ? "[gray]" : "[white]") + current;
        }).right().padLeft(10).row();
    }

    private void addBoolProp(Table t, String name, Prov<Boolean> val, boolean def) {
        t.label(() -> {
            boolean current = val.get();
            String icon = current ? "[green]" + Iconc.ok : "[scarlet]" + Iconc.cancel;
            Color c = (current == def) ? Color.gray : Color.white;
            return icon + " [#" + c.toString() + "]" + name;
        }).padRight(10);
    }

    private void scanWorld() {
        if(world == null || world.tiles == null) return;

        Threads.daemon(() -> {
            ObjectMap<Item, IntSeq> tmpOres = new ObjectMap<>();
            ObjectMap<Block, IntSeq> tmpFloors = new ObjectMap<>();
            ObjectMap<Block, IntSeq> tmpWalls = new ObjectMap<>();

            for(Tile tile : world.tiles){
                // 1. Ресурсы
                Item drop = tile.drop();
                if(drop != null){
                    if(!tmpOres.containsKey(drop)) tmpOres.put(drop, new IntSeq());
                    tmpOres.get(drop).add(tile.pos());
                }

                // 2. Стены
                Block b = tile.block();
                if(b != Blocks.air && b.isStatic()){
                    if(!tmpWalls.containsKey(b)) tmpWalls.put(b, new IntSeq());
                    tmpWalls.get(b).add(tile.pos());
                }

                // 3. Полы
                Block floor = tile.floor();
                if(floor != null && floor != Blocks.air){
                    if(!tmpFloors.containsKey(floor)) tmpFloors.put(floor, new IntSeq());
                    tmpFloors.get(floor).add(tile.pos());
                }
            }

            Core.app.post(() -> {
                orePositions = tmpOres;
                floorPositions = tmpFloors;
                wallPositions = tmpWalls;
                rebuildDynamicContent();
            });
        });
    }

    private void rebuildDynamicContent() {
        resTable.clear();
        floorTable.clear();
        teamTable.clear();
        wallTable.clear();
        bannedTable.clear();


        fillIconButtonTable(resTable, orePositions);
        fillIconButtonTable(floorTable, floorPositions);
        fillIconButtonTable(wallTable, wallPositions);

        buildTeamSection();

        buildBannedSection();
    }

    private void buildTeamSection() {
        class View {
            boolean bh = false, bd = false, bs = false, us = false,
                    uc = false, uh = false, ud = false, ucr = false;
            boolean ir = false, ia = false;
        }
        View v = new View();

        Seq<Team> activeTeams = new Seq<>();
        for(Team team : Team.all){
            var data = team.data();
            if(data != null && (!data.cores.isEmpty() || data.unitCount > 0)) activeTeams.add(team);
        }

        for(Team team : activeTeams){
            var tr = state.rules.teams.get(team);
            if((tr != null ? tr.blockHealthMultiplier : state.rules.blockHealthMultiplier) != 1f) v.bh = true;
            if((tr != null ? tr.blockDamageMultiplier : state.rules.blockDamageMultiplier) != 1f) v.bd = true;
            if((tr != null ? tr.buildSpeedMultiplier : state.rules.buildSpeedMultiplier) != 1f) v.bs = true;
            if((tr != null ? tr.unitBuildSpeedMultiplier : state.rules.unitBuildSpeedMultiplier) != 1f) v.us = true;
            if((tr != null ? tr.unitCostMultiplier : state.rules.unitCostMultiplier) != 1f) v.uc = true;
            if((tr != null ? tr.unitHealthMultiplier : state.rules.unitHealthMultiplier) != 1f) v.uh = true;
            if((tr != null ? tr.unitDamageMultiplier : state.rules.unitDamageMultiplier) != 1f) v.ud = true;
            if((tr != null ? tr.unitCrashDamageMultiplier : state.rules.unitCrashDamageMultiplier) != 1f) v.ucr = true;
            if(state.rules.infiniteResources || (tr != null && tr.infiniteResources)) v.ir = true;
        }

        float nameW = 100f; // Ширина колонки Team
        float mulW = 45f;  // Ширина колонок с множителями
        float iconW = 40f; // Ширина колонок Cores/Units
        float ruleW = 55f; // Ширина колонок InfRes/InfAmmo

        teamTable.table(header -> {
            header.defaults().pad(2).fontScale(0.8f);
            header.add("[gray]Team").width(nameW).left();

            if (v.bh) { addVLine(header); header.add("[gray]BHp").width(mulW).center(); }
            if (v.bd) { addVLine(header); header.add("[gray]BDmg").width(mulW).center(); }
            if (v.bs) { addVLine(header); header.add("[gray]BSpd").width(mulW).center(); }
            if (v.us) { addVLine(header); header.add("[gray]USpd").width(mulW).center(); }
            if (v.uc) { addVLine(header); header.add("[gray]UCost").width(mulW).center(); }
            if (v.uh) { addVLine(header); header.add("[gray]UHp").width(mulW).center(); }
            if (v.ud) { addVLine(header); header.add("[gray]UDmg").width(mulW).center(); }
            if (v.ucr) { addVLine(header); header.add("[gray]UCras").width(mulW).center(); }

            addVLine(header); header.add("[gray]" + Iconc.host).width(iconW).center();
            addVLine(header); header.add("[gray]" + Iconc.units).width(iconW).center();
            if (v.ir) { addVLine(header); header.add("[gray]InfRes").width(ruleW).center(); }
            if (v.ia) { addVLine(header); header.add("[gray]InfAmmo").width(ruleW).center(); }
        }).growX().padBottom(2).row();

        for(Team team : activeTeams){
            var data = team.data();
            var tr = state.rules.teams.get(team);

            teamTable.table(rt -> {
                rt.defaults().fontScale(0.85f).center();
                rt.add(team.name).color(team.color).width(nameW).left().ellipsis(true);

                if (v.bh) { addVLine(rt); rt.add(formatMul(tr != null ? tr.blockHealthMultiplier : state.rules.blockHealthMultiplier)).width(mulW); }
                if (v.bd) { addVLine(rt); rt.add(formatMul(tr != null ? tr.blockDamageMultiplier : state.rules.blockDamageMultiplier)).width(mulW); }
                if (v.bs) { addVLine(rt); rt.add(formatMul(tr != null ? tr.buildSpeedMultiplier : state.rules.buildSpeedMultiplier)).width(mulW); }
                if (v.us) { addVLine(rt); rt.add(formatMul(tr != null ? tr.unitBuildSpeedMultiplier : state.rules.unitBuildSpeedMultiplier)).width(mulW); }
                if (v.uc) { addVLine(rt); rt.add(formatMul(tr != null ? tr.unitCostMultiplier : state.rules.unitCostMultiplier)).width(mulW); }
                if (v.uh) { addVLine(rt); rt.add(formatMul(tr != null ? tr.unitHealthMultiplier : state.rules.unitHealthMultiplier)).width(mulW); }
                if (v.ud) { addVLine(rt); rt.add(formatMul(tr != null ? tr.unitDamageMultiplier : state.rules.unitDamageMultiplier)).width(mulW); }
                if (v.ucr) { addVLine(rt); rt.add(formatMul(tr != null ? tr.unitCrashDamageMultiplier : state.rules.unitCrashDamageMultiplier)).width(mulW); }

                addVLine(rt); rt.add("" + data.cores.size).width(iconW).color(Color.white);
                addVLine(rt); rt.add("" + data.unitCount).width(iconW).color(Color.white);

                if (v.ir) {
                    boolean ir = state.rules.infiniteResources || (tr != null && tr.infiniteResources);
                    addVLine(rt); rt.add(ir ? "[green]" + Iconc.ok : "[gray]" + Iconc.cancel).width(ruleW);
                }
            }).growX().row();
        }
    }


    private void addVLine(Table t) {
        t.image(Tex.whiteui).color(Color.gray).width(1f).fillY().padLeft(2).padRight(2);
    }

    private String formatMul(float val) {
        if (val == 1f) return "[gray]1.0";
        return (val > 1f ? "[green]" : "[scarlet]") + Strings.fixed(val, 1);
    }

    private void buildTeamSection_old() {

        // --- КОМАНДЫ (С множителями) ---
        teamTable.table(header -> {
            header.defaults().pad(2).fontScale(0.8f);
            header.add("[gray]Team").width(90).left();
            header.image(Tex.whiteui).color(Color.gray).width(1f).fillY().padLeft(4).padRight(4);
            header.add("[gray]" + Iconc.host).width(30);
            header.image(Tex.whiteui).color(Color.gray).width(1f).fillY().padLeft(4).padRight(4);
            header.add("[gray]InfRes").width(50);
            header.image(Tex.whiteui).color(Color.gray).width(1f).fillY().padLeft(4).padRight(4);
            header.add("[gray]InfAmmo").width(55);
        }).growX().row();

        for(Team team : Team.all){
            Teams.TeamData data = team.data();
            if(data == null || (data.cores.isEmpty() && data.unitCount == 0)) continue;
            if(data.cores.isEmpty() && data.unitCount == 0) continue;

            teamTable.table(rt -> {
                rt.defaults().fontScale(0.85f);
                rt.add(team.name).color(team.color).width(90).left().ellipsis(true);
                rt.add("" + data.cores.size).width(30).color(Color.white);
                rt.image(Tex.whiteui).color(Color.gray).width(1f).fillY().padLeft(4).padRight(4);

                var teamRules = state.rules.teams.get(team);
                boolean infRes = state.rules.infiniteResources || (teamRules != null && teamRules.infiniteResources);
                rt.add(infRes ? "[green]Yes" : "[gray]No").width(50);
                rt.image(Tex.whiteui).color(Color.gray).width(1f).fillY().padLeft(4).padRight(4);
            }).growX().pad(1).row();
        }
    }

    private void buildBannedSection() {
        bannedTable.top().left();

        if(!state.rules.bannedBlocks.isEmpty()){
            bannedTable.add("[lightgray]Blocks: ").padRight(4);
            int i = 0;
            for(Block b : state.rules.bannedBlocks){
                if(b == null) continue;
                bannedTable.image(b.uiIcon).size(20).pad(1);
                if(++i % 12 == 0) bannedTable.row().add();
            }
            bannedTable.row();
        }

        if(!state.rules.bannedUnits.isEmpty()){
            float padTop = state.rules.bannedBlocks.isEmpty() ? 0 : 4f;
            bannedTable.add("[lightgray]Units: ").padRight(4).padTop(padTop);
            int i = 0;
            for(UnitType u : state.rules.bannedUnits){
                if(u == null) continue;
                bannedTable.image(u.uiIcon).size(20).pad(1);
                if(++i % 12 == 0) bannedTable.row().add();
            }
        }

        if(state.rules.bannedBlocks.isEmpty() && state.rules.bannedUnits.isEmpty()){
            bannedTable.add("No bans on this map").color(Color.gray).fontScale(0.8f);
        }
    }

    private <T extends UnlockableContent> void fillIconButtonTable(Table table, ObjectMap<T, IntSeq> data) {
        int count = 0;
        for(var entry : data.entries()){
            T content = entry.key;
            IntSeq posSeq = entry.value;
            table.button(b -> {
                b.image(content.uiIcon).size(22);
                b.add(UI.formatAmount(posSeq.size)).fontScale(0.8f).padLeft(2);
            }, Styles.cleart, () -> cycle(content, posSeq)).pad(2);
            if(++count % 6 == 0) table.row();
        }
    }

    private void cycle(Object key, IntSeq positions) {
        if(positions == null || positions.size == 0) return;

        int index = cycleIndices.get(key, 0);
        int pos = positions.get(index % positions.size);

        Spectate.INSTANCE.spectate(new Vec2(Point2.x(pos) * tilesize, Point2.y(pos) * tilesize));
        cycleIndices.put(key, index + 1);
    }

    public void toggle() {
        visible = !visible;
        if(visible) {
            toFront();
            if(orePositions.isEmpty()) scanWorld();
            else rebuildDynamicContent();
        }
    }

    public void resetPos(){
       setPosition(Core.graphics.getWidth() / 2f, 150f, Align.bottom);
    }
    public void updateSize(){
        float w = Core.settings.getFloat("mapfragwidth");
        float h = Core.settings.getFloat("mapfragheigh");
        if(w < 50f) w = 500f;
        if(h < 50f) h = 700f;
        setSize(w, h);
    }


    private void addProp(Table t, String name, Prov<?> val) {
        t.add(name).left().color(Color.lightGray).fontScale(0.8f);
        t.label(() -> String.valueOf(val.get())).right().padLeft(10).row();
    }

    private <T extends UnlockableContent> void copySection(String title, ObjectMap<T, IntSeq> data) {
        if (data.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[accent]-- ").append(title).append(" --[white]\n");

        Seq<T> keys = data.keys().toSeq();
        keys.sort(k -> -data.get(k).size);
        for (T content : keys) {
            int count = data.get(content).size;

            sb.append(Fonts.getUnicodeStr(content.name))
                    //.append(" ")
                    //.append(content.localizedName)
                    .append("x")
                    .append(count)
                    .append("; ");
        }

        Core.app.setClipboardText(sb.toString());
    }
}