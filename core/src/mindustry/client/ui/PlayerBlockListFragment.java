package mindustry.client.ui;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.scene.Group;
import arc.scene.event.Touchable;
import arc.scene.ui.Image;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectIntMap;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.client.ClientVars;
import mindustry.client.Spectate;
import mindustry.client.fallen.ActionsHistory;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.net.NetConnection;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;

import static mindustry.Vars.*;

public class PlayerBlockListFragment {
    public Table content = new Table().marginRight(13f).marginLeft(13f);
    private boolean visible = false;
    private final Interval timer = new Interval();
    private TextField search;
    public static String name_for_plans = null;

    private final Seq<Player> search_players = new Seq<>();
    private HistoryFragment historyDialog;


    public static final ObjectIntMap<String> builtCache = new ObjectIntMap<>();
    public static final ObjectIntMap<String> breakCache = new ObjectIntMap<>();
    public static final ObjectIntMap<String> configCache = new ObjectIntMap<>();

    public PlayerBlockListFragment(){
        Events.on(EventType.WorldLoadEvent.class, e -> {
            Groups.player.each(p -> {
                if(!ActionsHistory.playeratmap.contains(p)) {
                    ActionsHistory.playeratmap.add(p);
                }
            });
        });
    }

    public void build(Group parent){
        content.name = "players";

        parent.fill(cont -> {
            cont.name = "playerblocklist";
            cont.visible(() -> visible);
            cont.update(() -> {
                if(!state.isGame()){
                    visible = false;
                    return;
                }

                if(visible && timer.get(60)){
                    rebuild();
                }
            });

            cont.table(Tex.buttonTrans, pane -> {
                pane.label(() -> Core.bundle.format("players" + (ActionsHistory.playeratmap.size == 1 ? ".single" : ""),
                        ActionsHistory.playeratmap.size + " (" + Groups.player.count(p -> p.fooUser || p.isLocal()) + Iconc.wrench + ")"));
                pane.row();

                search = pane.field(null, text -> rebuild()).grow().pad(8).name("search").maxTextLength(maxNameLength).get();
                search.setMessageText(Core.bundle.get("players.search"));

                if(Core.settings.getBool("blocksplayersplan")){
                    pane.row();
                    pane.table(info -> {
                        info.defaults().maxHeight(25).minWidth(50).pad(2);

                        info.button("a.l.", ()->{
                            ClientVars.clientCommandHandler.handleMessage("!admin leaves", player);
                        }).height(25).minWidth(50);

                        info.button(Icon.book, () -> {
                            ui.historyFrag.toggle();
                        }).size(45).pad(4).tooltip("Toggle History Log");

                        info.button("Clear Filter", () -> {
                            name_for_plans = null;
                        }).tooltip("Сбросить");

                        info.table(stats -> {
                            String searchName = search.getText().isEmpty() ? "" : Strings.stripColors(search.getText()).toLowerCase();

                            stats.button(t -> {
                                        t.add("[green]+" + sumCache(builtCache, searchName));
                                    }, Styles.cleart, () -> name_for_plans = search.getText())
                                    .height(30).minWidth(50).pad(2).tooltip("Построено (сумма совпадений)");

                            stats.button(t -> {
                                        t.add("[red]-" + sumCache(breakCache, searchName));
                                    }, Styles.cleart, () -> name_for_plans = search.getText())
                                    .height(30).minWidth(50).pad(2).tooltip("Сломано (сумма совпадений)");

                            stats.button(t -> {
                                        t.add("[blue]~" + sumCache(configCache, searchName));
                                    }, Styles.cleart, () -> name_for_plans = search.getText())
                                    .height(30).minWidth(50).pad(2).tooltip("Конфиги (сумма совпадений)");

                            stats.button(Icon.hammer, Styles.cleari, () -> {
                                deletePlayerBuildCont(Strings.stripColors(search.getText()));
                            }).tooltip("Восстановить всё найденное по поиску");

                            stats.button(Icon.trash, Styles.cleari, () -> {
                                repairPlayerBuildCont(Strings.stripColors(search.getText()));
                            }).tooltip("Снести всё найденное по поиску");
                        });

                    });
                }
                pane.row();
                pane.pane(content).grow().scrollX(false);
                pane.row();

                pane.table(menu -> {
                    menu.defaults().pad(5).growX().height(50f).fillY();
                    menu.button("@server.bans", ui.bans::show).disabled(b -> net.client());
                    menu.button("@server.admins", ui.admins::show).disabled(b -> net.client());
                    menu.button("@close", this::toggle);
                }).margin(0f).pad(10f).growX();

            }).touchable(Touchable.enabled).margin(14f).minWidth(400f);
        });

        Events.on(EventType.PlayerJoin.class, event -> {
            readd(event.player);
        });

        Events.on(EventType.PlayerLeave.class, event -> {
            readd(event.player);
        });
    }

    private int sumCache(ObjectIntMap<String> cache, String search) {
        if(search.isEmpty()) return 0;
        int sum = 0;
        for(var entry : cache.entries()){
            if(entry.key.toLowerCase().contains(search)){
                sum += entry.value;
            }
        }
        return sum;
    }

    public void readd(Player p){
        if (!ActionsHistory.playeratmap.contains(p)) {
            ActionsHistory.playeratmap.add(p);
        }
    }

    public static void calculateStats() {
        builtCache.clear();
        breakCache.clear();
        configCache.clear();

        if(!Core.settings.getBool("blocksplayersplan")) return;

        for(ActionsHistory.BlockPlayerPlan plan : ActionsHistory.blocksplayersplans) {
            if(plan.lastacs == null) continue;
            String cleanName = Strings.stripColors(plan.lastacs);

            if(plan.wasbreaking) {
                breakCache.put(cleanName, breakCache.get(cleanName, 0) + 1);
            } else {
                builtCache.put(cleanName, builtCache.get(cleanName, 0) + 1);
            }
        }

        for(ActionsHistory.BlockConfigPlayerPlan plan : ActionsHistory.blockconfplayersplans) {
            if(plan.lastacs == null) continue;
            String cleanName = Strings.stripColors(plan.lastacs);

            configCache.put(cleanName, configCache.get(cleanName, 0) + 1);
        }
    }

    public void rebuild(){

        Groups.player.each(p -> { if (!ActionsHistory.playeratmap.contains(p)) {ActionsHistory.playeratmap.add(p);}});

        calculateStats();

        content.clear();
        float h = 74f;
        boolean found = false;

        search_players.clear();
        search_players.addAll(ActionsHistory.playeratmap);

        var target = Spectate.INSTANCE.getPos() instanceof Player p ? p : null;

        search_players.removeAll(p -> p == null);

        search_players.sort(Structs.comps(
                Structs.comparingBool((Player p) -> p != target),
                Structs.comps(
                        Structs.comparing(p -> p.team() == null ? Team.derelict : p.team()),
                        Structs.comparingBool((Player p) -> p == null || !p.admin)
                )
        ));
        String searchText = search.getText().toLowerCase();
        boolean hasSearch = !searchText.isEmpty();

        for(var user : search_players){
            if(user == null) continue;

            if(hasSearch && !Strings.stripColors(user.name().toLowerCase()).contains(searchText)) {
                continue;
            }

            found = true;

            Table button = new Table();
            button.left().margin(5).marginBottom(10);


            Table avatar = new Table(t -> {
                t.add(new Image(user.icon()).setScaling(Scaling.bounded)).grow();
            });
            avatar.margin(8);

            Table avatarContainer = new Table(){
                @Override
                public void draw(){
                    super.draw();
                    Draw.color(Pal.gray);
                    Draw.alpha(parentAlpha);
                    Lines.stroke(Scl.scl(4f));
                    Lines.rect(x, y, width, height);
                    Draw.reset();
                }
            };
            avatarContainer.add(avatar).size(h - 16);
            button.add(avatarContainer).size(h);

            button.button(
                    Core.input.shift() ? String.valueOf(user.id) :
                            Core.input.ctrl() ? "Groups.player.getByID(" + user.id + ")" :
                                    "[#" + user.color().toString().toUpperCase() + "]" + user.name(),
                    Styles.nonetdef,
                    () -> Core.app.setClipboardText(user.name)
            ).wrap().width(250).growY().pad(10);

            if (user.admin && !(!user.isLocal() && net.server())) button.image(Icon.admin).padRight(5);
            if (user.fooUser) button.image(Icon.wrench).padRight(5);

            var ustyle = new ImageButtonStyle(){{
                down = Styles.none;
                up = Styles.none;
                imageDownColor = Pal.accent;
                imageUpColor = Color.white;
                imageOverColor = Color.lightGray;
            }};

            if (Core.settings.getBool("blocksplayersplan")) {
                String cleanUserName = Strings.stripColors(user.name());
                int built = builtCache.get(cleanUserName, 0);
                int broken = breakCache.get(cleanUserName, 0);
                int config = configCache.get(cleanUserName, 0);

                button.table(stats -> {
                    stats.button("[green]+" + built, () -> name_for_plans = user.name)
                            .height(30).minWidth(50).pad(2).tooltip("Построено");

                    stats.button("[red]-" + broken, () -> name_for_plans = user.name)
                            .height(30).minWidth(50).pad(2).tooltip("Сломано");

                    stats.button("[blue]~" + config, () -> name_for_plans = user.name)
                            .height(30).minWidth(50).pad(2).tooltip("Потрогано");

                    stats.button(Icon.hammer, ustyle, () -> {
                        String targetName = Strings.stripColors(user.name());
                        deletePlayerBuild(targetName);
                    }).tooltip("Восстановить всё, что сломал этот инвалид");

                    stats.button(Icon.trash, ustyle, () -> {
                        String targetName = Strings.stripColors(user.name());
                        repairPlayerBuild(targetName);
                    }).tooltip("Снести всё, что построил этот инвалид");
                });
            }

            if(user != player) {
                button.button(Icon.zoom, ustyle,
                        () -> Spectate.INSTANCE.spectate(user, Core.input.shift())
                ).tooltip("@client.spectate");
            }

            content.add(button).padBottom(-6).growX().maxHeight(h + 14);
            content.row();
            content.image().height(4f).color(state.rules.pvp ? user.team().color : Pal.gray).growX();
            content.row();
        }

        if(!found){
            content.add(Core.bundle.format("players.notfound")).padBottom(6).width(600f);
        }

        content.marginBottom(5);
    }

    public static void deletePlayerBuild(String targetName) {
        if (player.unit() == null) return;

        for (ActionsHistory.BlockPlayerPlan plan : ActionsHistory.blocksplayersplans) {
            if (plan.wasbreaking && plan.lastacs != null && Strings.stripColors(plan.lastacs).equals(targetName)) {
                Block block = Vars.content.block(plan.block);
                if (block == null || block == Blocks.air) continue;

                player.unit().addBuild(new BuildPlan(plan.x, plan.y, plan.rotation, block, plan.config), true);
            }
        }
    }

    public static void deletePlayerBuildCont(String targetName) {
        if (player.unit() == null) return;

        for (ActionsHistory.BlockPlayerPlan plan : ActionsHistory.blocksplayersplans) {
            if (plan.wasbreaking && plan.lastacs != null && Strings.stripColors(plan.lastacs).contains(targetName)) {
                Block block = Vars.content.block(plan.block);
                if (block == null || block == Blocks.air) continue;

                player.unit().addBuild(new BuildPlan(plan.x, plan.y, plan.rotation, block, plan.config), true);
            }
        }
    }

    public static void repairPlayerBuild(String targetName) {
        if (player.unit() == null) return;

        for (ActionsHistory.BlockPlayerPlan plan : ActionsHistory.blocksplayersplans) {
            if (!plan.wasbreaking && plan.lastacs != null && Strings.stripColors(plan.lastacs).equals(targetName)) {

                Tile tile = world.tile(plan.x, plan.y);
                if(tile != null && tile.build != null && tile.team() == player.team()){

                    BuildPlan breakPlan = new BuildPlan(tile.build.tileX(), tile.build.tileY());
                    breakPlan.breaking = true;

                    boolean alreadyQueued = false;
                    for(BuildPlan p : player.unit().plans){
                        if(p.breaking && p.x == breakPlan.x && p.y == breakPlan.y){
                            alreadyQueued = true;
                            break;
                        }
                    }

                    if(!alreadyQueued){
                        player.unit().addBuild(breakPlan, true);
                    }
                }
            }
        }
    }

    public static void repairPlayerBuildCont(String targetName) {
        if (player.unit() == null) return;

        for (ActionsHistory.BlockPlayerPlan plan : ActionsHistory.blocksplayersplans) {
            if (!plan.wasbreaking && plan.lastacs != null && Strings.stripColors(plan.lastacs).contains(targetName)) {

                Tile tile = world.tile(plan.x, plan.y);
                if(tile != null && tile.build != null && tile.team() == player.team()){

                    BuildPlan breakPlan = new BuildPlan(tile.build.tileX(), tile.build.tileY());
                    breakPlan.breaking = true;

                    boolean alreadyQueued = false;
                    for(BuildPlan p : player.unit().plans){
                        if(p.breaking && p.x == breakPlan.x && p.y == breakPlan.y){
                            alreadyQueued = true;
                            break;
                        }
                    }

                    if(!alreadyQueued){
                        player.unit().addBuild(breakPlan, true);
                    }
                }
            }
        }
    }

    public void toggle(){
        visible = !visible;
        if(visible){
            Core.scene.setKeyboardFocus(search);
            rebuild();
        }else{
            Core.scene.setKeyboardFocus(null);
            search.clearText();
        }
    }

    public boolean shown(){
        return visible;
    }
}