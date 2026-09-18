package mindustry.client.ui;

import arc.*;
import arc.graphics.*;
import arc.input.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.client.utils.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

/**
 * GL: a floating window with the global chat only, opened from the Alt + left click menu. It can be dragged by the
 * move button like the log history window, and the game keeps running under it. Two tabs: the global chat and the
 * chat of the server the player is on (all modes of that server, available only while playing there).
 */
public class GlobalChatDialog extends Table{
    private static GlobalChatDialog instance;
    /** Message rows: see-through, only a light highlight under the mouse. */
    private static final TextButton.TextButtonStyle lineStyle = new TextButton.TextButtonStyle(){{
        font = Fonts.def;
        fontColor = Color.white;
        over = down = ((arc.scene.style.TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.12f);
    }};
    /** Tabs: see-through too, the selected one is tinted with the accent color. */
    private static final TextButton.TextButtonStyle tabStyle = new TextButton.TextButtonStyle(){{
        font = Fonts.def;
        fontColor = Color.white;
        checkedFontColor = Pal.accent;
        disabledFontColor = Color.gray;
        over = down = ((arc.scene.style.TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.12f);
        checked = ((arc.scene.style.TextureRegionDrawable)Tex.whiteui).tint(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.2f);
    }};

    private final Table lines = new Table();
    private ScrollPane pane;
    private TextField field;
    private boolean shown, placed, serverTab;
    private @Nullable Table popup;
    private float lastX, lastY;

    public static void showDialog(){
        if(instance == null){
            instance = new GlobalChatDialog();
            ui.hudGroup.addChild(instance);
        }
        instance.toggle();
    }

    private GlobalChatDialog(){
        setSize(460f, 380f);
        touchable = Touchable.childrenOnly;
        visible(() -> shown && ui.hudfrag.shown);

        table(Tex.buttonTrans, root -> {
            root.margin(8f);
            root.table(head -> {
                ImageButton drag = head.button(Icon.move, Styles.clearNonei, () -> {}).size(36f).get();
                drag.addListener(new InputListener(){
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                        lastX = x;
                        lastY = y;
                        return true;
                    }

                    @Override
                    public void touchDragged(InputEvent event, float x, float y, int pointer){
                        moveBy(x - lastX, y - lastY);
                        keepInside();
                    }
                });
                head.button(Icon.chat, Styles.clearNonei, this::showOnline).size(36f).padLeft(2f).padRight(2f)
                    .tooltip("@client.globalchat.onlinehint").get().getImage().setColor(Pal.accent);
                head.add("@client.globalchat.title").color(Pal.accent);
                head.add().growX();
                head.button(Icon.copy, Styles.clearNonei, () -> {
                    // my tag, also when banned: it is what a moderator needs to lift a punishment
                    if(GlobalChat.tag().isEmpty()){
                        ui.showInfoFade("@client.globalchat.mytag.none");
                    }else{
                        Core.app.setClipboardText(GlobalChat.tag());
                        ui.showInfoFade(Core.bundle.format("client.globalchat.mytag.copied", GlobalChat.tag()));
                    }
                }).size(36f).tooltip(t -> t.background(Styles.black8).margin(4f).label(() ->
                    GlobalChat.tag().isEmpty() ? Core.bundle.get("client.globalchat.mytag.none") : Core.bundle.format("client.globalchat.mytag.hint", GlobalChat.tag())));
                // the two channels are turned on and off separately: the chat of this server and the global one
                head.button(Icon.host, Styles.clearNoneTogglei, () -> GlobalChat.setServerOn(!GlobalChat.serverOn()))
                    .size(36f).checked(b -> GlobalChat.serverOn()).tooltip(t -> t.background(Styles.black8).margin(4f).label(() ->
                        Core.bundle.get(GlobalChat.serverOn() ? "client.globalchat.btn.server.off" : "client.globalchat.btn.server.on")));
                head.button(Icon.planet, Styles.clearNoneTogglei, () -> GlobalChat.setGlobal(!GlobalChat.globalOn()))
                    .size(36f).checked(b -> GlobalChat.globalOn()).tooltip(t -> t.background(Styles.black8).margin(4f).label(() ->
                        Core.bundle.get(GlobalChat.globalOn() ? "client.globalchat.btn.global.off" : "client.globalchat.btn.global.on")));
                head.button(Icon.cancel, Styles.clearNonei, this::toggle).size(36f);
            }).growX().row();

            root.table(tabs -> {
                tabs.defaults().height(34f).growX();
                // the server first: it is the one opened by default when the player is on a server
                // one update for the text and the selection: a second update() would replace the one of checked()
                tabs.button("", tabStyle, () -> setTab(true))
                    .update(b -> tab(b, serverTab, !GlobalChat.onServer(), !GlobalChat.onServer() ? Core.bundle.get("client.globalchat.tab.server.off") :
                        !GlobalChat.serverOn() ? Core.bundle.get("client.globalchat.tab.server.disabled") :
                        Core.bundle.format("client.globalchat.tab.server", GlobalChat.channel().isEmpty() ? 0 : GlobalChat.serverOnline())))
                    .tooltip("@client.globalchat.tab.server.hint");
                tabs.button("", tabStyle, () -> setTab(false)).padLeft(4f)
                    .update(b -> tab(b, !serverTab, false, !GlobalChat.globalOn() ? Core.bundle.get("client.globalchat.tab.global.disabled") :
                        Core.bundle.format("client.globalchat.tab.global", GlobalChat.connected() ? GlobalChat.online() : 0)));
            }).growX().padTop(4f).row();

            root.label(() -> serverTab ?
                (!GlobalChat.serverOn() ? Core.bundle.get("client.globalchat.serveroff.window") :
                GlobalChat.channel().isEmpty() ? GlobalChat.status() :
                Core.bundle.format("client.globalchat.serverstatus", GlobalChat.channel(), GlobalChat.serverOnline())) :
                (!GlobalChat.globalOn() ? Core.bundle.get("client.globalchat.off.window") : GlobalChat.status()))
                .fontScale(0.85f).wrap().growX().left().padTop(2f).row();
            root.add("@client.globalchat.copyhint").color(Color.gray).fontScale(0.75f).left().padTop(2f).row();
            root.image().color(Pal.accent).height(2f).growX().padTop(4f).padBottom(4f).row();

            lines.top().left();
            // the wheel scrolls the chat only under the mouse, elsewhere it zooms the camera as usual
            pane = root.pane(lines).grow().scrollX(false).update(p -> {
                if(Core.scene.getScrollFocus() == p && !p.hasMouse()) Core.scene.setScrollFocus(null);
            }).get();
            root.row();

            root.table(input -> {
                field = input.field("", t -> {}).growX().height(42f).maxTextLength(200).get();
                field.setMessageText(Core.bundle.get("client.globalchat.hint"));
                field.update(() -> field.setMessageText(Core.bundle.get(serverTab ? "client.globalchat.hint.server" : "client.globalchat.hint")));
                field.keyDown(KeyCode.enter, this::send);
                field.keyDown(KeyCode.escape, () -> Core.scene.setKeyboardFocus(null));
                input.button(Icon.right, Styles.clearNonei, this::send).size(42f).padLeft(4f);
            }).growX().padTop(6f);
        }).grow().touchable(Touchable.enabled);

        update(() -> {
            if(!placed && Core.scene.getWidth() > 0){
                setPosition(Core.scene.getWidth() / 2f, Core.scene.getHeight() / 2f, Align.center);
                placed = true;
            }
            // left the server: back to the global chat
            if(serverTab && !GlobalChat.onServer()) setTab(false);
        });
    }

    private void tab(TextButton b, boolean selected, boolean disabled, String text){
        b.setChecked(selected);
        b.setDisabled(disabled);
        b.setText(text);
    }

    private void setTab(boolean server){
        if(server && !GlobalChat.onServer()) return;
        if(serverTab == server) return;
        serverTab = server;
        closePopup();
        if(shown) rebuild();
    }

    private void toggle(){
        shown = !shown;
        if(shown){
            toFront();
            // on a server its chat is opened first
            serverTab = GlobalChat.onServer();
            GlobalChat.listener = this::rebuild;
            rebuild();
            Core.scene.setKeyboardFocus(field);
        }else{
            GlobalChat.listener = null;
            if(Core.scene.getKeyboardFocus() == field) Core.scene.setKeyboardFocus(null);
        }
    }

    private void keepInside(){
        float w = Core.scene.getWidth(), h = Core.scene.getHeight();
        setPosition(Math.max(0f, Math.min(x, w - width)), Math.max(0f, Math.min(y, h - height)));
    }

    private void send(){
        String text = field.getText().trim();
        if(text.isEmpty()) return;
        if(serverTab ? !GlobalChat.serverOn() : !GlobalChat.globalOn()) return; // the status line above says which button turns it on
        if(GlobalChat.send(text, serverTab)) field.setText("");
    }

    /**
     * Actions for one player: moderators punish and lift punishments (in the chat of their server), curators punish
     * in the whole chat and appoint moderators for the server they are on, the owner also appoints curators.
     */
    private void playerMenu(String target, String name){
        if(popup != null) popup.remove();
        Table menu = new Table(Tex.pane);
        popup = menu;
        menu.touchable = Touchable.enabled;
        menu.margin(6f);
        menu.defaults().size(250f, 38f).left();
        menu.add("[accent]" + name.replace("[", "[[") + " [gray]#" + target).left().padBottom(4f).row();
        if(GlobalChat.moderator(serverTab)){
            menuItem(menu, Icon.lock, "@client.globalchat.btn.mute", () -> confirm("client.globalchat.confirm.mute", "mute", target, name));
            menuItem(menu, Icon.lockOpen, "@client.globalchat.btn.unmute", () -> GlobalChat.moderate("unmute", target));
            // bans in the chat of this server: moderators, curators and the owner, for 7 days or forever
            if(serverTab){
                menuItem(menu, Icon.hammer, "@client.globalchat.btn.sban", () -> confirm("client.globalchat.confirm.sban", "sban", target, name));
                menuItem(menu, Icon.hammer, "@client.globalchat.btn.sbanforever", () -> confirm("client.globalchat.confirm.sbanforever", "sbanforever", target, name));
            }
            // bans in the whole chat: curators for 7 or 30 days, the owner also forever
            if(GlobalChat.curator()){
                menuItem(menu, Icon.hammer, "@client.globalchat.btn.ban", () -> confirm("client.globalchat.confirm.ban", "ban", target, name));
                menuItem(menu, Icon.hammer, "@client.globalchat.btn.ban30", () -> confirm("client.globalchat.confirm.ban30", "ban30", target, name));
                if(GlobalChat.owner()){
                    menuItem(menu, Icon.hammer, "@client.globalchat.btn.banforever", () -> confirm("client.globalchat.confirm.banforever", "banforever", target, name));
                }
            }
            menuItem(menu, Icon.refresh, "@client.globalchat.btn.unban", () -> GlobalChat.moderate("unban", target));
        }
        if(GlobalChat.curator()){
            if(!GlobalChat.channel().isEmpty()){
                menuItem(menu, Icon.admin, "@client.globalchat.btn.addmod", () -> confirm("client.globalchat.confirm.addmod", "addmod", target, name));
            }
            menuItem(menu, Icon.cancel, "@client.globalchat.btn.delmod", () -> GlobalChat.moderate("delmod", target));
        }
        if(GlobalChat.owner()){
            menuItem(menu, Icon.star, "@client.globalchat.btn.addcur", () -> confirm("client.globalchat.confirm.addcur", "addcur", target, name));
            menuItem(menu, Icon.cancel, "@client.globalchat.btn.delcur", () -> GlobalChat.moderate("delcur", target));
        }
        menuItem(menu, Icon.copy, "@client.globalchat.btn.copytag", () -> {
            Core.app.setClipboardText(target);
            ui.showInfoFade("@client.globalchat.copied");
        });
        menu.update(() -> {
            boolean outside = (Core.input.keyTap(KeyCode.mouseLeft) || Core.input.keyTap(KeyCode.mouseRight)) && !menu.hasMouse();
            if(outside || Core.input.keyTap(KeyCode.escape) || !shown) closePopup();
        });
        Core.scene.add(menu);
        menu.pack();
        float mx = Core.input.mouseX(), my = Core.input.mouseY();
        menu.setPosition(Math.min(mx, Core.scene.getWidth() - menu.getWidth()), Math.max(0f, my - menu.getHeight()));
    }

    /** Everyone in the global chat (or on this server) now, with their tags; a click on one opens the same actions as [GL]. */
    private void showOnline(){
        boolean server = serverTab;
        GlobalChat.requestWho(server, players -> {
            if(!shown) return;
            closePopup();
            Table menu = new Table(Tex.pane);
            popup = menu;
            menu.touchable = Touchable.enabled;
            menu.margin(6f);
            menu.add(Core.bundle.format(server ? "client.globalchat.onlinelist.server" : "client.globalchat.onlinelist", players.size)).color(Pal.accent).left().padBottom(4f).row();
            menu.pane(list -> {
                list.defaults().width(280f).height(34f).left();
                for(var p : players){
                    String name = p.getString("name", "?"), tag = p.getString("tag", ""), role = p.getString("role", "");
                    String badge = GlobalChat.badge(role);
                    String self = tag.equals(GlobalChat.tag()) ? "[accent]" : "[white]";
                    TextButton b = list.button(badge + self + name.replace("[", "[[") + "[] [gray]#" + tag, lineStyle, () -> playerMenu(tag, name)).get();
                    b.left();
                    b.getLabel().setEllipsis(true);
                    b.addListener(new ClickListener(KeyCode.mouseRight){
                        @Override
                        public void clicked(InputEvent event, float x, float y){
                            playerMenu(tag, name);
                        }
                    });
                    list.row();
                }
            }).maxHeight(320f).scrollX(false);
            menu.update(() -> {
                boolean outside = (Core.input.keyTap(KeyCode.mouseLeft) || Core.input.keyTap(KeyCode.mouseRight)) && !menu.hasMouse();
                if((outside && popup == menu) || Core.input.keyTap(KeyCode.escape) || !shown) closePopup();
            });
            Core.scene.add(menu);
            menu.pack();
            float mx = Core.input.mouseX(), my = Core.input.mouseY();
            menu.setPosition(Math.min(mx, Core.scene.getWidth() - menu.getWidth()), Math.max(0f, my - menu.getHeight()));
        });
    }

    private void menuItem(Table menu, arc.scene.style.Drawable icon, String text, Runnable action){
        menu.button(text, icon, Styles.flatt, () -> {
            closePopup();
            action.run();
        }).get().left();
        menu.row();
    }

    private void closePopup(){
        if(popup != null) popup.remove();
        popup = null;
    }

    private void confirm(String key, String action, String target, String name){
        String text = Core.bundle.format(key, name.replace("[", "[["), target, GlobalChat.channel());
        // where the punishment works: curators and the owner punish in the whole chat, moderators on their server
        String scope = switch(action){
            case "mute" -> GlobalChat.curator() ? "all" : "server";
            case "ban", "ban30", "banforever" -> "all";
            case "sban", "sbanforever" -> "server";
            default -> null;
        };
        if(scope != null) text += "\n\n[lightgray]" + Core.bundle.get("client.globalchat.scope." + scope);
        ui.showConfirm("@confirm", text, () -> GlobalChat.moderate(action, target));
    }

    private void rebuild(){
        lines.clear();
        int hide = serverTab ? GlobalChat.kindGlobal : GlobalChat.kindServer;
        if(!GlobalChat.lineKinds.contains(serverTab ? GlobalChat.kindServer : GlobalChat.kindGlobal)){
            lines.add(serverTab ? "@client.globalchat.empty.server" : "@client.globalchat.empty").color(Color.lightGray).wrap().width(400f).pad(10f).row();
        }
        // a click on a line copies its text
        for(int i = 0; i < GlobalChat.log.size; i++){
            if(GlobalChat.lineKinds.get(i) == hide) continue;
            String copy = GlobalChat.copies.get(i), line = GlobalChat.log.get(i);
            String from = GlobalChat.lineTags.get(i), name = GlobalChat.lineNames.get(i);
            // owner and moderators: a click (left or right) on [GL] of someone's message opens the actions for that player
            String start = line.startsWith(GlobalChat.serverPrefix) ? GlobalChat.serverPrefix : GlobalChat.prefix;
            boolean menu = !from.isEmpty() && line.startsWith(start);
            lines.table(row -> {
                row.top().left();
                String text = line;
                if(menu){
                    text = line.substring(start.length());
                    TextButton gl = row.button(start.substring(0, start.length() - 3), lineStyle, () -> playerMenu(from, name)).top().get();
                    gl.margin(2f, 4f, 2f, 2f);
                    gl.addListener(new ClickListener(KeyCode.mouseRight){
                        @Override
                        public void clicked(InputEvent event, float x, float y){
                            playerMenu(from, name);
                        }
                    });
                    gl.addListener(new Tooltip(t -> t.background(Styles.black8).margin(4f).add("@client.globalchat.menuhint")));
                }
                String shown = text;
                row.button(b -> b.add(shown).left().wrap().width(menu ? (start == GlobalChat.prefix ? 370f : 355f) : 410f), lineStyle, () -> {
                    Core.app.setClipboardText(copy);
                    ui.showInfoFade("@client.globalchat.copied");
                }).left().growX().get().left().margin(2f, 4f, 2f, 4f);
            }).left().growX().padBottom(2f);
            lines.row();
        }
        Core.app.post(() -> {
            pane.layout();
            pane.setScrollY(pane.getMaxY());
        });
    }
}
