package mindustry.client.ui;

import arc.*;
import arc.graphics.*;
import arc.input.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import mindustry.client.utils.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

/** GL: a window with the global chat only, opened from the Alt + left click menu. */
public class GlobalChatDialog extends BaseDialog{
    private static GlobalChatDialog instance;

    private final Table lines = new Table();
    private ScrollPane pane;
    private TextField field;

    public static void showDialog(){
        if(instance == null) instance = new GlobalChatDialog();
        instance.show();
    }

    private GlobalChatDialog(){
        super("@client.globalchat.title");
        addCloseButton();

        cont.table(head -> {
            head.left();
            head.image(Icon.chat).color(Pal.accent).size(24f).padRight(8f);
            head.label(GlobalChat::status).growX().left().wrap();
            head.button(Icon.power, Styles.clearNoneTogglei, () -> {
                boolean on = !GlobalChat.enabled();
                Core.settings.put("globalchat", on);
                GlobalChat.setEnabled(on);
            }).size(40f).checked(b -> GlobalChat.enabled()).tooltip("@client.setting.globalchat.name");
        }).growX().maxWidth(700f).row();
        cont.image().color(Pal.accent).height(3f).growX().maxWidth(700f).padTop(4f).padBottom(6f).row();

        lines.top().left();
        pane = cont.pane(lines).grow().maxWidth(700f).scrollX(false).get();
        cont.row();

        cont.table(input -> {
            field = input.field("", t -> {}).growX().height(48f).maxTextLength(200).get();
            field.setMessageText(Core.bundle.get("client.globalchat.hint"));
            input.button(Icon.right, Styles.flati, this::send).size(48f).padLeft(6f);
        }).growX().maxWidth(700f).padTop(6f);

        keyDown(KeyCode.enter, this::send);

        shown(() -> {
            GlobalChat.listener = this::rebuild;
            rebuild();
            Core.scene.setKeyboardFocus(field);
        });
        hidden(() -> GlobalChat.listener = null);
    }

    private void send(){
        String text = field.getText().trim();
        if(text.isEmpty()) return;
        if(GlobalChat.send(text)) field.setText("");
        Core.scene.setKeyboardFocus(field);
    }

    private void rebuild(){
        lines.clear();
        if(GlobalChat.log.isEmpty()){
            lines.add("@client.globalchat.empty").color(Color.lightGray).pad(10f);
        }
        for(String line : GlobalChat.log){
            lines.add(line).left().growX().wrap().padBottom(3f).row();
        }
        lines.layout();
        pane.layout();
        Core.app.post(() -> pane.setScrollY(pane.getMaxY()));
    }
}
