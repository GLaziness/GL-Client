package mindustry.client.ui;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.client.antigrief.*;
import mindustry.client.navigation.*;
import mindustry.client.utils.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.ui.*;
import mindustry.world.*;

import static arc.Core.*;
import static mindustry.Vars.*;

/** GL: the Alt + left click menu on a tile: tile actions, client windows and toggles. */
public class TileMenu{
    private static final float width = 290f, rowHeight = 38f;
    private static Table current;

    public static void show(Tile tile){
        hide();

        Table menu = new Table(Tex.pane);
        menu.touchable = Touchable.enabled;
        menu.margin(8f);
        current = menu;
        main(menu, tile);

        menu.update(() -> {
            boolean clickedOutside = input.keyTap(Binding.select) && !menu.hasMouse();
            if(clickedOutside || input.keyTap(arc.input.KeyCode.escape) || state.isMenu()) hide();
        });
        scene.add(menu);
        place(menu, input.mouseX() - 1, input.mouseY() + 1);
    }

    public static void hide(){
        if(current != null) current.remove();
        current = null;
    }

    private static void main(Table menu, Tile tile){
        menu.clearChildren();
        header(menu, tile);

        group(menu, "@client.tilemenu.tile");
        item(menu, Icon.chat, "@client.tilemenu.coords", () -> {
            Call.sendChatMessage(tile.x + ", " + tile.y);
            Call.pingLocation(player, tile.worldx(), tile.worldy(), null);
        });
        item(menu, Icon.fileText, "@client.tilemenu.log", () -> TileRecords.INSTANCE.show(tile));
        item(menu, Icon.move, "@client.tilemenu.teleport", () -> NetClient.setPosition(World.unconv(tile.x), World.unconv(tile.y)));

        group(menu, "@client.tilemenu.windows");
        item(menu, Icon.book, "@client.tilemenu.history", () -> ui.historyFrag.toggle());
        item(menu, Icon.star, "@client.tilemenu.favorites", () -> ui.favFrag.toggle());
        item(menu, Icon.paste, "@client.tilemenu.schems", () -> ui.quickSchemFrag.toggle());
        item(menu, Icon.units, "@client.tilemenu.unitpicker", () -> ui.unitPicker.show());
        item(menu, Icon.chat, "@client.tilemenu.globalchat", GlobalChatDialog::showDialog);
        Button waypoints = item(menu, Icon.commandRally, "@client.tilemenu.waypoints", null);
        waypoints.clicked(() -> {
            waypoints(menu, tile);
            place(menu, menu.x, menu.y + menu.getHeight());
        });

        group(menu, "@client.tilemenu.toggles");
        toggle(menu, Icon.settings, "@client.tilemenu.unitcontrols",
            () -> settings.getBool("unitcontrolfragment", false),
            () -> settings.put("unitcontrolfragment", !settings.getBool("unitcontrolfragment", false)), () -> false);
        toggle(menu, Icon.distribution, "@client.tilemenu.autotransfer", () -> AutoTransfer.enabled, () -> {
            AutoTransfer.enabled ^= true;
            settings.put("autotransfer", AutoTransfer.enabled);
        }, () -> state.rules.pvp && Server.io.b());
        menu.pack();
    }

    private static void waypoints(Table menu, Tile tile){
        menu.clearChildren();
        header(menu, tile);

        Button back = item(menu, Icon.left, "@back", null);
        back.clicked(() -> {
            main(menu, tile);
            place(menu, menu.x, menu.y + menu.getHeight());
        });

        group(menu, "@client.tilemenu.waypoints");
        item(menu, Icon.add, "@client.path.record", Navigation::startRecording);
        item(menu, Icon.cancel, "@client.path.stoprecording", Navigation::stopRecording);
        item(menu, Icon.play, "@client.path.follow", () -> follow(false));
        item(menu, Icon.refresh, "@client.path.followrepeat", () -> follow(true));
        item(menu, Icon.pause, "@client.path.stopfollowing", Navigation::stopFollowing);
        menu.pack();
    }

    private static void follow(boolean repeat){
        if(Navigation.recordedPath == null) return;
        Navigation.recordedPath.reset();
        Navigation.follow(Navigation.recordedPath, repeat);
        Navigation.recordedPath.setShow(true);
    }

    /** Block (or floor and ore) icon, its name and the tile coordinates. */
    private static void header(Table menu, Tile tile){
        Block shown = tile.block() != Blocks.air ? tile.block() : tile.overlay() != Blocks.air ? tile.overlay() : tile.floor();
        menu.table(h -> {
            h.left();
            h.image(shown.uiIcon).size(26f).padRight(8f);
            h.add(shown.localizedName).color(Pal.accent).ellipsis(true).left().growX().minWidth(0f);
            h.add(tile.x + ", " + tile.y).color(Color.lightGray).padLeft(8f);
        }).width(width).padBottom(4f).row();
        menu.image().color(Pal.accent).height(2f).width(width).row();
    }

    private static void group(Table menu, String title){
        menu.add(title).color(Color.gray).fontScale(0.8f).left().padTop(6f).padLeft(4f).row();
    }

    /** Action row: closes the menu after running, unless action is null (the caller adds its own listener). */
    private static Button item(Table menu, Drawable icon, String text, @Nullable Runnable action){
        Button button = new Button(Styles.flatt);
        button.left().margin(0f, 8f, 0f, 8f);
        button.image(icon).size(20f).padRight(10f);
        button.add(text).left().growX();
        if(action != null){
            button.clicked(() -> {
                action.run();
                hide();
            });
        }
        menu.add(button).size(width, rowHeight).row();
        return button;
    }

    /** Switch row that stays open and shows the current state on the right. */
    private static void toggle(Table menu, Drawable icon, String text, Boolp on, Runnable flip, Boolp disabled){
        Button button = new Button(Styles.flatt);
        button.left().margin(0f, 8f, 0f, 8f);
        button.image(icon).size(20f).padRight(10f);
        button.add(text).left().growX();
        button.label(() -> bundle.get(on.get() ? "mod.enabled" : "mod.disabled"))
            .update(l -> l.setColor(on.get() ? Pal.accent : Color.gray)).padLeft(8f);
        button.clicked(() -> {
            if(!disabled.get()) flip.run();
        });
        button.setDisabled(disabled);
        menu.add(button).size(width, rowHeight).row();
    }

    /** Puts the top left corner at x, y, pushed back inside the screen. */
    private static void place(Table menu, float x, float top){
        menu.pack();
        float w = menu.getWidth(), h = menu.getHeight();
        x = Mathf.clamp(x, 0f, Math.max(0f, scene.getWidth() - w));
        top = Mathf.clamp(top, Math.min(h, scene.getHeight()), scene.getHeight());
        menu.setPosition(x, top - h);
    }
}
