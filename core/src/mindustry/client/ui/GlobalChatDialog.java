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
 * move button like the log history window, and the game keeps running under it.
 */
public class GlobalChatDialog extends Table{
    private static GlobalChatDialog instance;
    /** Message rows: see-through, only a light highlight under the mouse. */
    private static final TextButton.TextButtonStyle lineStyle = new TextButton.TextButtonStyle(){{
        font = Fonts.def;
        fontColor = Color.white;
        over = down = ((arc.scene.style.TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.12f);
    }};

    private final Table lines = new Table();
    private ScrollPane pane;
    private TextField field;
    private boolean shown, placed;
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
                ImageButton drag = head.button(Icon.move, Styles.cleari, () -> {}).size(36f).get();
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
                head.button(Icon.power, Styles.clearNoneTogglei, () -> {
                    boolean on = !GlobalChat.enabled();
                    Core.settings.put("globalchat", on);
                    GlobalChat.setEnabled(on);
                }).size(36f).checked(b -> GlobalChat.enabled()).tooltip("@client.setting.globalchat.name");
                head.button(Icon.cancel, Styles.cleari, this::toggle).size(36f);
            }).growX().row();

            root.label(() -> GlobalChat.enabled() ? GlobalChat.status() : Core.bundle.get("client.globalchat.off.window")).fontScale(0.85f).wrap().growX().left().padTop(2f).row();
            root.add("@client.globalchat.copyhint").color(Color.gray).fontScale(0.75f).left().padTop(2f).row();
            root.image().color(Pal.accent).height(2f).growX().padTop(4f).padBottom(4f).row();

            lines.top().left();
            pane = root.pane(lines).grow().scrollX(false).get();
            root.row();

            root.table(input -> {
                field = input.field("", t -> {}).growX().height(42f).maxTextLength(200).get();
                field.setMessageText(Core.bundle.get("client.globalchat.hint"));
                field.keyDown(KeyCode.enter, this::send);
                field.keyDown(KeyCode.escape, () -> Core.scene.setKeyboardFocus(null));
                input.button(Icon.right, Styles.flati, this::send).size(42f).padLeft(4f);
            }).growX().padTop(6f);
        }).grow().touchable(Touchable.enabled);

        update(() -> {
            if(!placed && Core.scene.getWidth() > 0){
                setPosition(Core.scene.getWidth() / 2f, Core.scene.getHeight() / 2f, Align.center);
                placed = true;
            }
        });
    }

    private void toggle(){
        shown = !shown;
        if(shown){
            toFront();
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
        if(!GlobalChat.enabled()) return; // the status line above already says to press the power button
        if(GlobalChat.send(text)) field.setText("");
    }

    /** Actions for one player: moderators punish and lift punishments, the owner also appoints moderators. */
    private void playerMenu(String target, String name){
        if(popup != null) popup.remove();
        Table menu = new Table(Tex.pane);
        popup = menu;
        menu.touchable = Touchable.enabled;
        menu.margin(6f);
        menu.defaults().size(250f, 38f).left();
        menu.add("[accent]" + name.replace("[", "[[") + " [gray]#" + target).left().padBottom(4f).row();
        if(GlobalChat.moderator()){
            menuItem(menu, Icon.lock, "@client.globalchat.btn.mute", () -> confirm("client.globalchat.confirm.mute", "mute", target, name));
            menuItem(menu, Icon.lockOpen, "@client.globalchat.btn.unmute", () -> GlobalChat.moderate("unmute", target));
            menuItem(menu, Icon.hammer, "@client.globalchat.btn.ban", () -> confirm("client.globalchat.confirm.ban", "ban", target, name));
            menuItem(menu, Icon.refresh, "@client.globalchat.btn.unban", () -> GlobalChat.moderate("unban", target));
        }
        if(GlobalChat.owner()){
            menuItem(menu, Icon.admin, "@client.globalchat.btn.addmod", () -> confirm("client.globalchat.confirm.addmod", "addmod", target, name));
            menuItem(menu, Icon.cancel, "@client.globalchat.btn.delmod", () -> GlobalChat.moderate("delmod", target));
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

    /** Everyone in the global chat now, with their tags; a click on one opens the same actions as [GL]. */
    private void showOnline(){
        GlobalChat.requestWho(players -> {
            if(!shown) return;
            closePopup();
            Table menu = new Table(Tex.pane);
            popup = menu;
            menu.touchable = Touchable.enabled;
            menu.margin(6f);
            menu.add(Core.bundle.format("client.globalchat.onlinelist", players.size)).color(Pal.accent).left().padBottom(4f).row();
            menu.pane(list -> {
                list.defaults().width(280f).height(34f).left();
                for(var p : players){
                    String name = p.getString("name", "?"), tag = p.getString("tag", ""), role = p.getString("role", "");
                    String badge = role.equals("owner") ? "[gold]" + Iconc.admin + "[] " : role.equals("mod") ? "[sky]" + Iconc.admin + "[] " : "";
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
        ui.showConfirm("@confirm", Core.bundle.format(key, name.replace("[", "[["), target), () -> GlobalChat.moderate(action, target));
    }

    private void rebuild(){
        lines.clear();
        if(GlobalChat.log.isEmpty()){
            lines.add("@client.globalchat.empty").color(Color.lightGray).pad(10f);
        }
        // a click on a line copies its text
        for(int i = 0; i < GlobalChat.log.size; i++){
            String copy = GlobalChat.copies.get(i), line = GlobalChat.log.get(i);
            String from = GlobalChat.lineTags.get(i), name = GlobalChat.lineNames.get(i);
            // owner and moderators: a click (left or right) on [GL] of someone's message opens the actions for that player
            boolean menu = !from.isEmpty() && line.startsWith(GlobalChat.prefix);
            lines.table(row -> {
                row.top().left();
                String text = line;
                if(menu){
                    text = line.substring(GlobalChat.prefix.length());
                    TextButton gl = row.button("[#7fd3ff][[GL]", lineStyle, () -> playerMenu(from, name)).top().get();
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
                row.button(b -> b.add(shown).left().wrap().width(menu ? 370f : 410f), lineStyle, () -> {
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
