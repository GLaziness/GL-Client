package mindustry.client.ui;

import arc.*;
import arc.graphics.*;
import arc.input.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Queue;
import arc.util.*;
import mindustry.client.Spectate;
import mindustry.client.fallen.ActionsHistory;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import java.text.SimpleDateFormat;
import java.util.Date;

import static mindustry.Vars.*;

public class HistoryFragment extends Table {
    private String searchText = "";
    private LogType currentTab = null;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
    private final Date date = new Date();
    private boolean visible = false;
    private Table listTable = new Table();

    private float lastX = 0, lastY = 0;
    private boolean centered = false;

    enum LogType {
        BLOCKS, CONFIGS, ITEMS, DEATHS, COMMANDS, STATES
    }

    public void build(Group parent) {
        parent.addChild(this);

        float w = 550f;
        float h = Core.graphics.getHeight() * 0.8f;
        setSize(w, h);

        this.touchable = Touchable.childrenOnly;

        visible(() -> visible);

        table(Tex.buttonTrans, pane -> {
            pane.table(hTable -> {
                ImageButton dragButton = hTable.button(Icon.move, Styles.cleari, () -> {}).size(40).get();
                dragButton.addListener(new InputListener() {
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        lastX = x; lastY = y;
                        return true;
                    }
                    @Override
                    public void touchDragged(InputEvent event, float x, float y, int pointer) {
                        moveBy(x - lastX, y - lastY);
                    }
                });

                hTable.add(new Label(" LOG HISTORY ")).color(Pal.accent).padLeft(5);
                hTable.add().growX();

                hTable.button(Icon.refresh, Styles.cleari, () -> {
                    setPosition(Core.graphics.getWidth() / 2f, Core.graphics.getHeight() / 2f, Align.center);
                }).size(40).tooltip("Reset position");

                hTable.button(Icon.cancel, Styles.cleari, this::toggle).size(40);
            }).growX().color(Color.valueOf("3a3a3a"));

            pane.row();

            pane.field(searchText, text -> {
                searchText = text.toLowerCase();
                rebuildContent();
            }).growX().pad(8).get().setMessageText("Search by player, block or item...");

            pane.row();

            pane.table(tabs -> {
                tabs.defaults().growX().height(45).pad(2);
                for (LogType type : LogType.values()) {
                    tabs.button(t -> {
                        t.add(type.name()).left().growX();
                        t.label(() -> "[" + getCount(type) + "]")
                                .fontScale(0.8f)
                                .color(Pal.accent)
                                .right();
                    }, Styles.cleart, () -> {
                        currentTab = type;
                        rebuildContent();
                    }).checked(b -> currentTab == type);
                }
            }).growX();

            pane.row();

            pane.pane(listTable).grow().scrollX(false).pad(4).update(p -> {
                if(Core.scene.getScrollFocus() == p && !Core.input.shift()){
                    Core.scene.setScrollFocus(null);
                }
            });
            rebuildContent();

        }).grow().touchable(Touchable.enabled);

        update(() -> {
            if(!centered && Core.graphics.getWidth() > 0){
                setPosition(Core.graphics.getWidth() / 2f, Core.graphics.getHeight() / 2f, Align.center);
                centered = true;
            }
        });
    }

    private String getCount(LogType type) {
        int count = switch (type) {
            case BLOCKS -> ActionsHistory.blocksplayersplans.size;
            case CONFIGS -> ActionsHistory.blockconfplayersplans.size;
            case ITEMS -> ActionsHistory.playeritemsplans.size;
            case DEATHS -> ActionsHistory.deathunitsplan.size + ActionsHistory.deathunitscontrolplan.size;
            case COMMANDS -> ActionsHistory.unitcommandsplans.size;
            case STATES -> ActionsHistory.unitstatesplans.size;
        };

        if (count >= 1000) return (count / 100) / 10.0f + "k";
        return String.valueOf(count);
    }

    public void toggle() {
        visible = !visible;
        if (visible) toFront();
    }

    private void rebuildContent() {
        listTable.clear();
        listTable.top();

        if (currentTab == null) {
            listTable.add("Please select a category above").color(Color.lightGray).pad(40);
            return;
        }

        switch (currentTab) {
            case BLOCKS -> buildBlockList(listTable);
            case CONFIGS -> buildConfigList(listTable);
            case ITEMS -> buildItemList(listTable);
            case DEATHS -> buildDeathList(listTable);
            case COMMANDS -> buildUnitCommandList(listTable);
            case STATES -> buildUnitStateList(listTable);
        }

        if (listTable.getChildren().isEmpty()) {
            listTable.add("< No logs here >").color(Color.gray).pad(20);
        }
    }


    private void buildBlockList(Table t) {
        for (var p : ActionsHistory.blocksplayersplans) {
            String blockName = content.block(p.block).localizedName;
            if (!filter(p.lastacs) && !filter(blockName)) continue;

            t.table(Styles.black3, row -> {
                row.add(formatTime(p.timestamp)).color(Color.gray).fontScale(0.8f).width(75).padRight(8);
                row.image(content.block(p.block).uiIcon).size(22).padRight(4);
                row.add(p.lastacs).growX().left().color(Pal.accent).ellipsis(true);
                row.add(p.wasbreaking ? "[scarlet]B" : "[green]S").width(24);
                row.button(Icon.move, Styles.cleari, () -> Spectate.INSTANCE.spectate(new Vec2(p.x * tilesize, p.y * tilesize))).size(34);
            }).growX().margin(6).padBottom(2);
            t.row();
        }
    }

    private void buildConfigList(Table t) {
        for (var p : ActionsHistory.blockconfplayersplans) {
            String blockName = content.block(p.block).localizedName;
            if (!filter(p.lastacs) && !filter(blockName)) continue;

            t.table(Styles.black3, row -> {
                row.add(formatTime(p.timestamp)).color(Color.gray).fontScale(0.8f).width(75).padRight(8);
                row.image(content.block(p.block).uiIcon).size(22).padRight(4);
                row.add(p.lastacs).growX().left().color(Pal.accent).ellipsis(true);
                row.button(Icon.move, Styles.cleari, () -> Spectate.INSTANCE.spectate(new Vec2(p.x * tilesize, p.y * tilesize))).size(34);
            }).growX().margin(6).padBottom(2);
            t.row();
        }
    }

    private void buildItemList(Table t) {
        for (var p : ActionsHistory.playeritemsplans) {
            String name = p.player != null ? p.player.name : "Unknown";
            if (!filter(name) && (p.item == null || !filter(p.item.localizedName))) continue;
            t.table(Styles.black3, row -> {
                row.add(formatTime(p.timestamp)).color(Color.gray).fontScale(0.8f).width(75).padRight(8);
                row.add(name).growX().left().color(Pal.accent).ellipsis(true);
                row.add(p.take ? "[scarlet]-" : "[green]+").width(20);
                if(p.item != null) row.image(p.item.uiIcon).size(20);
                row.button(Icon.move, Styles.cleari, () -> Spectate.INSTANCE.spectate(new Vec2(p.tile.x * tilesize, p.tile.y * tilesize))).size(34);
            }).growX().margin(6).padBottom(2);
            t.row();
        }
    }

    private void buildDeathRows(Table t, Queue<? extends ActionsHistory.UnitsKilledByPlayers> queue) {
        for (var p : queue) {
            if (!filter(p.playerName) && !filter(p.unitType.localizedName)) continue;
            t.table(Styles.black3, row -> {
                row.add(formatTime(p.timestamp)).color(Color.gray).fontScale(0.8f).width(75).padRight(8);
                row.image(p.unitType.uiIcon).size(22).padRight(4);
                row.add(p.playerName).growX().left().color(Pal.accent).ellipsis(true);
                row.button(Icon.move, Styles.cleari, () -> Spectate.INSTANCE.spectate(new Vec2(p.x, p.y))).size(34);
            }).growX().margin(6).padBottom(2);
            t.row();
        }
    }

    private void buildDeathList(Table t) {
        buildDeathRows(t, ActionsHistory.deathunitsplan);
        buildDeathRows(t, ActionsHistory.deathunitscontrolplan);
    }

    private boolean filter(String input) {
        if (searchText.isEmpty() || input == null) return true;
        return Strings.stripColors(input).toLowerCase().contains(searchText);
    }

    private String formatTime(long ts) {
        date.setTime(ts);
        return dateFormat.format(date);
    }

    private void buildUnitCommandList(Table t) {
        for (var p : ActionsHistory.unitcommandsplans) {

            boolean unitMatches = false;
            for(var ut : p.unitTypes) {
                if(filter(ut.type.localizedName)) {
                    unitMatches = true;
                    break;
                }
            }
            if (!filter(p.playerName) && !unitMatches) continue;

            t.table(Styles.black3, row -> {
                row.add(formatTime(p.timestamp)).color(Color.gray).fontScale(0.8f).width(75).padRight(8).left();
                row.image(p.targetName.equals("None") ? Icon.move.getRegion() : Icon.commandAttack.getRegion()).size(20).padRight(4);

                row.table(col -> {
                    col.left();
                    col.defaults().left();
                    col.add(p.playerName).left().color(Pal.accent).ellipsis(true);
                    col.row();

                    col.table(unitIcons -> {
                        unitIcons.left();
                        for(var ut : p.unitTypes) {
                            unitIcons.image(ut.type.uiIcon).size(14).padRight(2);
                            unitIcons.add(ut.count + "").fontScale(0.7f).color(Color.lightGray).padRight(6);
                        }
                        unitIcons.add("[gray]Target:[] " + p.targetName).fontScale(0.75f).padLeft(4);
                    }).left();
                }).growX().left();

                row.button(Icon.move, Styles.cleari, () -> Spectate.INSTANCE.spectate(new Vec2(p.x, p.y))).size(34);
            }).growX().margin(6).padBottom(2);
            t.row();
        }
    }

    private void buildUnitStateList(Table t) {
        for (var p : ActionsHistory.unitstatesplans) {

            boolean unitMatches = false;
            for(var ut : p.unitTypes) {
                if(filter(ut.type.localizedName)) {
                    unitMatches = true;
                    break;
                }
            }
            if (!filter(p.playerName) && !unitMatches && !filter(p.commandName)) continue;

            t.table(Styles.black3, row -> {
                row.add(formatTime(p.timestamp)).color(Color.gray).fontScale(0.8f).width(75).padRight(8).left();
                row.image(Icon.settings.getRegion()).size(20).padRight(4);

                row.table(col -> {
                    col.left();
                    col.defaults().left();
                    col.add(p.playerName).left().color(Pal.accent).ellipsis(true);
                    col.row();

                    col.table(unitIcons -> {
                        unitIcons.left();
                        for(var ut : p.unitTypes) {
                            unitIcons.image(ut.type.uiIcon).size(14).padRight(2);
                            unitIcons.add(ut.count + "").fontScale(0.7f).color(Color.lightGray).padRight(6);
                        }
                        unitIcons.add("[gray]Action:[] [sky]" + p.commandName).fontScale(0.75f).padLeft(4);
                    }).left();
                }).growX().left();

                row.add().width(34);
            }).growX().margin(6).padBottom(2);
            t.row();
        }
    }
}