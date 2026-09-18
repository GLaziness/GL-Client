package mindustry.client.ui;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.scene.style.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.client.utils.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.mod.Mods.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/** GL: main menu, GL Client, "Recommended add-ons": the GL Admin Mode mod and the global chat, with install and on/off buttons. */
public class RecommendedDialog extends BaseDialog{
    public static final RecommendedDialog instance = new RecommendedDialog();

    private static final String adminRepo = "GLaziness/GL-AdminMode", adminName = "gl-admin-mode";
    /** Latest GL Admin Mode release on GitHub, "" until known. */
    private String adminLatest = "";

    public RecommendedDialog(){
        super("@client.recommended");
        addCloseButton();
        shown(() -> {
            rebuild();
            checkAdminVersion();
        });
        onResize(this::rebuild);
    }

    private void rebuild(){
        cont.clear();
        float width = Math.min(620f, Core.graphics.getWidth() / Scl.scl(1f) - 60f);

        cont.pane(all -> {
            all.top().margin(10f);
            all.add("@client.recommended.hint").color(Color.lightGray).wrap().width(width).left().padBottom(6f).row();

            // GL Admin Mode
            section(all, width, Icon.admin, "GL Admin Mode", t -> {
                t.add("@client.recommended.admin.text").wrap().growX().left().row();
                t.add("@client.recommended.admin.list").color(Color.lightGray).wrap().growX().left().padTop(6f).row();
                t.label(this::adminState).wrap().growX().left().padTop(8f).row();
                t.table(b -> {
                    b.defaults().height(48f).growX().pad(2f);
                    b.button("", Icon.download, Styles.flatt, () -> ui.mods.githubImportMod(adminRepo, true, null, true))
                        .update(x -> x.setText(adminButtonText())).disabled(x -> adminInstalled() && !adminHasUpdate());
                    b.button("@client.recommended.github", Icon.github, Styles.flatt, () -> {
                        String url = "https://github.com/" + adminRepo;
                        if(!Core.app.openURI(url)){
                            ui.showErrorMessage("@linkfail");
                            Core.app.setClipboardText(url);
                        }
                    });
                }).growX().padTop(6f).row();
            });

            // global chat
            section(all, width, Icon.chat, "@client.globalchat.title", t -> {
                t.add("@client.recommended.chat.text").wrap().growX().left().row();
                t.add("@client.recommended.chat.list").color(Color.lightGray).wrap().growX().left().padTop(6f).row();
                t.label(() -> Strings.stripColors(GlobalChat.status()).replace("<" + mindustry.gen.Iconc.planet + "> ", "").replace("<" + mindustry.gen.Iconc.host + "> ", "")).wrap().growX().left().padTop(8f)
                    .update(l -> l.setColor(GlobalChat.connected() ? Pal.accent : Color.lightGray)).row();
                t.button(b -> b.label(() -> Core.bundle.get(GlobalChat.enabled() ? "client.recommended.chat.off" : "client.recommended.chat.on")),
                    Styles.flatt, () -> {
                        // both channels: the global one and the chat of the server
                        boolean on = !GlobalChat.enabled();
                        Core.settings.put("globalchat-server", on);
                        GlobalChat.setGlobal(on);
                    }).height(48f).growX().padTop(8f).row();
            });
        }).grow().scrollX(false);
    }

    private void section(Table all, float width, Drawable icon, String title, Cons<Table> content){
        all.table(head -> {
            head.left();
            head.image(icon).color(Pal.accent).size(28f).padRight(8f);
            head.add(title).color(Pal.accent).left();
        }).width(width).padTop(16f).left().row();
        all.image().color(Pal.accent).height(3f).width(width).padTop(4f).padBottom(6f).row();
        all.table(Styles.grayPanel, t -> {
            t.left().top().margin(12f);
            t.defaults().left();
            content.get(t);
        }).width(width).row();
    }

    private @Nullable LoadedMod adminMod(){
        return mods.getMod(adminName);
    }

    private boolean adminInstalled(){
        return adminMod() != null;
    }

    private boolean adminHasUpdate(){
        LoadedMod mod = adminMod();
        return mod != null && !adminLatest.isEmpty() && mod.meta.version != null && Strings.checkNewerSemver(adminLatest, mod.meta.version);
    }

    private String adminState(){
        LoadedMod mod = adminMod();
        String latest = adminLatest.isEmpty() ? "" : " " + Core.bundle.format("client.recommended.latest", adminLatest);
        if(mod == null) return Core.bundle.get("client.recommended.notinstalled") + latest;
        if(!mod.enabled()) return Core.bundle.format("client.recommended.disabled", mod.meta.version) + latest;
        return Core.bundle.format(adminHasUpdate() ? "client.recommended.update" : "client.recommended.installed", mod.meta.version) + latest;
    }

    private String adminButtonText(){
        return Core.bundle.get(!adminInstalled() ? "client.recommended.install" : adminHasUpdate() ? "client.recommended.doupdate" : "client.recommended.uptodate");
    }

    private void checkAdminVersion(){
        Http.get(ghApi + "/repos/" + adminRepo + "/releases/latest", res -> {
            String tag = Jval.read(res.getResultAsString()).getString("tag_name", "");
            Core.app.post(() -> adminLatest = tag.startsWith("v") ? tag.substring(1) : tag);
        }, e -> {});
    }
}
