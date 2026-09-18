package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.world.blocks.logic.LogicBlock.*;
import mindustry.client.Spectate;

import static arc.Core.scene;

public class LogicSearchFrag extends Table {
    private Table listTable = new Table();
    private boolean visible = false;
    private TextField searchField;
    private final float tableWidth = 400f;

    public void build(Group parent) {
        parent.fill(cont -> {
            cont.name = "logic-search-root";
            cont.center();
            cont.visible(() -> visible);

            cont.table(Tex.buttonTrans, main -> {
                main.margin(12f);
                main.add("[accent]FIND LOGIC CODE[]").padBottom(8f).row();

                searchField = main.field("", text -> {//автопоиск мб да
                }).growX().pad(8).get();
                searchField.setMessageText("Enter code snippet...");
                searchField.setMaxLength(100);

                main.row();

                main.button("Find", Icon.zoomSmall, this::runSearch).growX().height(40f).pad(8).row();

                main.pane(listTable).grow().maxHeight(400f).scrollX(false).row();

                main.button("@close", this::toggle).growX().height(45f).padTop(10f);

            }).width(tableWidth).touchable(Touchable.enabled);
        });
    }

    private void runSearch() {
        listTable.clear();
        String query = searchField.getText().toLowerCase();
        if (query.isEmpty()) return;

        int foundCount = 0;

        for (Building bui : Groups.build) {
            if (bui instanceof LogicBuild lb) {
                if (lb.code != null && lb.code.toLowerCase().contains(query)) {
                    foundCount++;
                    addResultRow(lb);
                }
            }
        }

        if (foundCount == 0) {
            listTable.add("[gray]No matches found").pad(20);
        }
    }

    private void addResultRow(LogicBuild lb) {
        listTable.table(Styles.black3, row -> {
            row.left().margin(6f);

            row.image(lb.block.uiIcon).size(24).padRight(8);

            String coords = "(" + lb.tileX() + ", " + lb.tileY() + ")";
            row.add("[white]Processor at [accent]" + coords).growX().left();

            row.button(Icon.moveSmall, Styles.cleari, () -> {
                Spectate.INSTANCE.spectate(lb);
            }).size(36f);

        }).growX().padBottom(4).row();
    }

    public void toggle() {
        visible = !visible;
        if (visible) {
            toFront();
            scene.setKeyboardFocus(searchField);
            listTable.clear();
        } else {
            scene.setKeyboardFocus(null);
        }
    }

    public boolean shown() {
        return visible;
    }
}