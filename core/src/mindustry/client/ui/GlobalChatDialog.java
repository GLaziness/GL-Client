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

    private final Table lines = new Table();
    private ScrollPane pane;
    private TextField field;
    private boolean shown, placed;
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
                head.image(Icon.chat).color(Pal.accent).size(22f).padLeft(4f).padRight(6f);
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

    private void rebuild(){
        lines.clear();
        if(GlobalChat.log.isEmpty()){
            lines.add("@client.globalchat.empty").color(Color.lightGray).pad(10f);
        }
        // a click on a line copies its text
        for(int i = 0; i < GlobalChat.log.size; i++){
            String copy = GlobalChat.copies.get(i), line = GlobalChat.log.get(i);
            lines.button(b -> b.add(line).left().growX().wrap(), Styles.flatt, () -> {
                Core.app.setClipboardText(copy);
                ui.showInfoFade("@client.globalchat.copied");
            }).left().growX().padBottom(2f).get().left().margin(2f, 4f, 2f, 4f);
            lines.row();
        }
        Core.app.post(() -> {
            pane.layout();
            pane.setScrollY(pane.getMaxY());
        });
    }
}
