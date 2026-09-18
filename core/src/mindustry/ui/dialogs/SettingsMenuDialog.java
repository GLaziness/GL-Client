package mindustry.ui.dialogs;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.*;
import mindustry.client.*;
import mindustry.client.antigrief.*;
import mindustry.client.ui.*;
import mindustry.client.utils.*;
import mindustry.content.*;
import mindustry.content.TechTree.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.logic.*;
import mindustry.service.*;
import mindustry.type.*;
import mindustry.type.Planet;
import mindustry.ui.*;
import mindustry.ui.fragments.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.storage.*;

import java.io.*;
import java.util.zip.*;

import static arc.Core.*;
import static mindustry.Vars.*;

public class SettingsMenuDialog extends BaseDialog{
    public SettingsTable graphics;
    public SettingsTable game;
    public SettingsTable sound;
    public SettingsTable dev;
    public SettingsTable client;
    public SettingsTable moderation;
    public SettingsTable main;

    private Table prefs;
    private Table menu;
    private BaseDialog dataDialog;
    private BaseDialog planetDataDialog;
    private Planet planet = Planets.serpulo;
    private Seq<SettingsCategory> categories = new Seq<>();

    public SettingsMenuDialog(){
        super(bundle.get("settings", "Settings"));
        addCloseButton();

        cont.add(main = new SettingsTable());
        shouldPause = true;

        hidden(ConstructBlock::updateWarnBlocks); // FINISHME: Horrible

        shown(() -> {
            back();
            rebuildMenu();
        });

        int[] lastRebuildSize = {Core.graphics.getWidth(), Core.graphics.getHeight()};
        onResize(() -> {
            if(lastRebuildSize[0] != Core.graphics.getWidth() || lastRebuildSize[1] != Core.graphics.getHeight()){
                graphics.rebuild();
                sound.rebuild();
                game.rebuild();
                dev.rebuild();
                client.rebuild();
                moderation.rebuild();
                updateScrollFocus();
                lastRebuildSize[0] = Core.graphics.getWidth();
                lastRebuildSize[1] = Core.graphics.getHeight();
            }
        });

        cont.clearChildren();
        cont.remove();
        buttons.remove();

        menu = new Table(Tex.button);

        game = new SettingsTable();
        graphics = new SettingsTable();
        sound = new SettingsTable();
        dev = new SettingsTable();
        client = new SettingsTable();
        moderation = new SettingsTable();

        prefs = new Table();
        prefs.top();
        prefs.margin(14f);

        rebuildMenu();

        prefs.clearChildren();
        prefs.add(menu);

        planetDataDialog = new BaseDialog("@settings.data");
        planetDataDialog.addCloseButton();

        planetDataDialog.cont.table(Tex.button, t -> {
            t.defaults().size(280f, 60f).left();
            TextButtonStyle style = Styles.flatt;

            t.button(bundle.format("settings.planetselect", "[#" + planet.iconColor + "]" + planet.localizedName), Icon.planet, style, () -> {
                BaseDialog dialog = new BaseDialog("");
                dialog.cont.pane(p -> {
                    p.background(Tex.button);
                    int i = 0;

                    for(var plan : content.planets()){
                        if(plan.generator == null || plan.sectors.size == 0 || !plan.accessible) continue;

                        p.button(plan.localizedName, Styles.flatTogglet, () -> {
                            planet = plan;
                            dialog.hide();
                        }).size(110f, 45f).checked(planet == plan);

                        if(++i % 4 == 0){
                            p.row();
                        }
                    }
                });
                dialog.setFillParent(false);
                dialog.addCloseButton();
                dialog.show();
            }).marginLeft(4).get().getLabel().setText(() -> bundle.format("settings.planetselect", "[#" + planet.iconColor + "]" + planet.localizedName));

            t.row();

            t.button("@settings.clearplanetresearch", Icon.trash, style, () -> {
                ui.showConfirm("@confirm", bundle.format("settings.clearplanetresearch.confirm", planet.localizedName), () -> {
                    universe.clearLoadoutInfo();
                    for(TechNode node : TechTree.all){
                        if(node.rootNode == planet.techTree){
                            node.reset();
                        }
                    }
                    content.each(c -> {
                        if(c instanceof UnlockableContent u && u.databaseTabs.contains(planet)){
                            u.clearUnlock();
                        }
                    });
                    settings.remove("unlocks");
                });
            }).marginLeft(4);

            t.row();

            t.button("@settings.clearplanetcampaignsaves", Icon.trash, style, () -> {
                ui.showConfirm("@confirm", bundle.format("settings.clearplanetcampaignsaves.confirm", planet.localizedName), () -> {
                    planet.clearStats();
                    boolean any = false;
                    for(var sec : planet.sectors){
                        sec.clearInfo();
                        if(sec.save != null){
                            any = true;
                            sec.save.delete();
                            sec.save = null;
                        }
                    }
                    if(any){
                        planet.reloadMeshAsync();
                    }

                    for(var slot : control.saves.getSaveSlots().copy()){
                        if(slot.isSector() && slot.getSector().planet == planet){
                            slot.delete();
                        }
                    }
                });
            }).marginLeft(4);

            t.row();
        });

        dataDialog = new BaseDialog("@settings.data");
        dataDialog.addCloseButton();

        dataDialog.cont.table(Tex.button, t -> {
            t.defaults().size(280f, 60f).left();
            TextButtonStyle style = Styles.flatt;

            t.button("@settings.cleardata", Icon.trash, style, () -> ui.showConfirm("@confirm", "@settings.clearall.confirm", () -> {
                ObjectMap<String, Object> map = new ObjectMap<>();
                for(String value : Core.settings.keys()){
                    if(value.contains("usid") || value.contains("uuid")){
                        map.put(value, Core.settings.get(value, null));
                    }
                }
                Core.settings.clear();
                Core.settings.putAll(map);

                for(Fi file : dataDirectory.list()){
                    file.deleteDirectory();
                }

                Core.app.exit();
            })).marginLeft(4);

            t.row();

            t.button("@settings.clearplanetdata", Icon.trash, style, () -> planetDataDialog.show()).marginLeft(4).row();

            t.button("@settings.clearsaves", Icon.trash, style, () -> {
                ui.showConfirm("@confirm", "@settings.clearsaves.confirm", control.saves::deleteAll);
            }).marginLeft(4);

            t.row();

            t.button("@settings.clearresearch", Icon.trash, style, () -> {
                ui.showConfirm("@confirm", "@settings.clearresearch.confirm", () -> {
                    universe.clearLoadoutInfo();
                    for(TechNode node : TechTree.all){
                        node.reset();
                    }
                    content.each(c -> {
                        if(c instanceof UnlockableContent u){
                            u.clearUnlock();
                        }
                    });
                    settings.remove("unlocks");
                });
            }).marginLeft(4);

            t.row();

            t.button("@settings.clearcampaignsaves", Icon.trash, style, () -> {
                ui.showConfirm("@confirm", "@settings.clearcampaignsaves.confirm", () -> {
                    control.saves.load(true);
                    for(var planet : content.planets()){
                        planet.clearStats();
                        boolean any = false;
                        for(var sec : planet.sectors){
                            sec.clearInfo();
                            if(sec.save != null){
                                any = true;
                                sec.save.delete();
                                sec.save = null;
                            }
                        }
                        if(any){
                            planet.reloadMeshAsync();
                        }
                    }

                    if (control.saves.loadedSaveCount() > 0){ // If we don't explicitly check for loaded saves, getSaveSlots() will load *all* saves when there are 0 sectors loaded which we don't want as it's needlessly slow.
                        for(var slot : control.saves.getSaveSlots().copy()){
                            if(slot.isSector()){
                                slot.delete();
                            }
                        }
                    }
                    control.saves.unload();
                });
            }).marginLeft(4);

            t.row();

            t.button("@data.export", Icon.upload, style, () -> {
                FileChooser.export("mindustry-data-export", "zip", this::exportData);
            }).marginLeft(4);

            t.row();

            t.button("@data.import", Icon.download, style, () -> ui.showConfirm("@confirm", "@data.import.confirm", () -> FileChooser.open("zip").submit(file -> {
                try{
                    importData(file);
                    mapPreviewDirectory.deleteDirectory();
                    control.saves.resetSave();
                    state = new GameState();
                    Core.app.exit();
                }catch(IllegalArgumentException e){
                    ui.showErrorMessage("@data.invalid");
                }catch(Exception e){
                    Log.err(e);
                    if(e.getMessage() == null || !e.getMessage().contains("too short")){
                        ui.showException(e);
                    }else{
                        ui.showErrorMessage("@data.invalid");
                    }
                }
            }))).marginLeft(4);

            if(!mobile){
                t.row();
                t.button("@data.openfolder", Icon.folder, style, () -> Core.app.openFolder(Core.settings.getDataDirectory().absolutePath())).marginLeft(4);
            }

            t.row();

            t.button("@crash.export", Icon.upload, style, () -> {
                if(settings.getDataDirectory().child("crashes").list().length == 0 && !settings.getDataDirectory().child("last_log.txt").exists()){
                    ui.showInfo("@crash.none");
                }else{
                    FileChooser.export("logs", "txt", file -> file.writeString(getLogs()));
                }
            }).marginLeft(4);
        });

        row();
        ScrollPane pane = pane(prefs).grow().top().get();
        pane.setFadeScrollBars(true);
        pane.setCancelTouchFocus(false);
        row();
        add(buttons).fillX();

        addSettings();
    }

    String getLogs(){
        Fi log = settings.getDataDirectory().child("last_log.txt");

        StringBuilder out = new StringBuilder();
        for(Fi fi : settings.getDataDirectory().child("crashes").list()){
            out.append(fi.name()).append("\n\n").append(fi.readString()).append("\n");
        }

        if(log.exists()){
            out.append("\nlast log:\n").append(log.readString());
        }

        return out.toString();
    }

    /** Adds a custom settings category, with the icon being the specified region. */
    public void addCategory(String name, @Nullable String region, Cons<SettingsTable> builder){
        categories.add(new SettingsCategory(name, region == null ? null : new TextureRegionDrawable(atlas.find(region)), builder));
    }

    /** Adds a custom settings category, for use in mods. The specified consumer should add all relevant mod settings to the table. */
    public void addCategory(String name, @Nullable Drawable icon, Cons<SettingsTable> builder){
        categories.add(new SettingsCategory(name, icon, builder));
    }

    /** Adds a custom settings category, for use in mods. The specified consumer should add all relevant mod settings to the table. */
    public void addCategory(String name, Cons<SettingsTable> builder){
        addCategory(name, (Drawable)null, builder);
    }

    public Seq<SettingsCategory> getCategories(){
        return categories;
    }

    void rebuildMenu(){
        menu.clearChildren();

        TextButtonStyle style = Styles.flatt;

        float marg = 8f, isize = iconMed;

        menu.defaults().size(300f, 60f);
        menu.button("@settings.game", Icon.settings, style, isize, () -> visible(0)).marginLeft(marg).row();
        menu.button("@settings.graphics", Icon.image, style, isize, () -> visible(1)).marginLeft(marg).row();
        menu.button("@settings.sound", Icon.filters, style, isize, () -> visible(2)).marginLeft(marg).row();
        menu.button("@settings.dev", Icon.fileCode, style, isize, () -> visible(3)).marginLeft(marg).row();
        menu.button("@settings.client", Icon.wrench, style, isize, () -> visible(4)).marginLeft(marg).row();
        menu.button("@settings.language", Icon.chat, style, isize, ui.language::show).marginLeft(marg).row();
        if(!mobile || Core.settings.getBool("keyboard")){
            menu.button("@settings.controls", Icon.move, style, isize, ui.controls::show).marginLeft(marg).row();
        }

        menu.button("@settings.data", Icon.save, style, isize, () -> dataDialog.show()).marginLeft(marg).row();

        int i = 6;
        for(var cat : categories){
            int index = i;
            if(cat.icon == null){
                menu.button(cat.name, style, () -> visible(index)).marginLeft(marg).row();
            }else{
                menu.button(cat.name, cat.icon, style, isize, () -> visible(index)).with(b -> ((Image)b.getChildren().get(1)).setScaling(Scaling.fit)).marginLeft(marg).row();
            }
            i++;
        }
    }

    void addSettings(){
        sound.checkPref("alwaysmusic", false);
        sound.sliderPref("musicvol", 100, 0, 100, 1, i -> { mainExecutor.execute(() -> Musics.load(false)); return i + "%"; });
        sound.sliderPref("sfxvol", 100, 0, 100, 1, i -> { mainExecutor.execute(() -> Sounds.load(false)); return i + "%"; });
        sound.sliderPref("ambientvol", 100, 0, 100, 1, i -> { mainExecutor.execute(() -> Sounds.load(false)); return i + "%"; });

        // Client Settings, organized exactly the same as Bundle.properties: text first, sliders second, checked boxes third, unchecked boxes last
        client.category("antigrief");
        client.sliderPref("reactorwarningdistance", 40, 0, 101, s -> s == 101 ? "@client.slider.always" : s == 0 ? "@client.slider.never" : Integer.toString(s));
        client.sliderPref("reactorsounddistance", 25, 0, 101, s -> s == 101 ? "@client.slider.always" : s == 0 ? "@client.slider.never" : Integer.toString(s));
        client.sliderPref("incineratorwarningdistance", 5, 0, 101, s -> s == 101 ? "@client.slider.always" : s == 0 ? "@client.slider.never" : Integer.toString(s));
        client.sliderPref("incineratorsounddistance", 3, 0, 101, s -> s == 101 ? "@client.slider.always" : s == 0 ? "@client.slider.never" : Integer.toString(s));
        client.sliderPref("slagwarningdistance", 10, 0, 101, s -> s == 101 ? "@client.slider.always" : s == 0 ? "@client.slider.never" : Integer.toString(s));
        client.sliderPref("slagsounddistance", 5, 0, 101, s -> s == 101 ? "@client.slider.always" : s == 0 ? "@client.slider.never" : Integer.toString(s));
        client.checkPref("breakwarnings", true); // Warnings for removal of certain sandbox stuff (mostly sources)
        client.checkPref("powersplitwarnings", true); // FINISHME: Add a minimum building requirement and a setting for it
        client.checkPref("viruswarnings", true, b -> LExecutor.virusWarnings = b);
        client.checkPref("removecorenukes", false);
        client.checkPref("seer-enabled", false);

        client.category("chat");
        client.checkPref("globalchat", false, mindustry.client.utils.GlobalChat::setEnabled);
        client.checkPref("globalchat-server", true, mindustry.client.utils.GlobalChat::setEnabled);
        client.checkPref("clearchatonleave", true);
        client.checkPref("logmsgstoconsole", true);
        client.checkPref("clientjoinleave", true);
        client.checkPref("showidinjoinleave", false);
        client.checkPref("highlightcryptomsg", true);
        client.checkPref("highlightclientmsg", false);
        client.checkPref("showclientmsgsendername", true);
        client.checkPref("displayasuser", true);
        client.checkPref("showuserid", false);
        client.checkPref("hideserversbydefault", false); // Inverts behavior of server hiding
        client.checkPref("enablechatlimit", false);
        client.sliderPref("shownmessagescount", 10, 1, 25, 1, s -> {
            ChatFragment.setShownMessages();
            return String.valueOf(s);
        });

        client.category("controls");
        client.checkPref("blockreplace", true);
        client.checkPref("instantturn", true);
        client.checkPref("autoboost", false);
        client.checkPref("assumeunstrict", false);
        client.checkPref("returnonmove", false);
        client.checkPref("nostrafepenalty", false);
        client.checkPref("decreasedrift", false);
        client.checkPref("zerodrift", false);
        client.checkPref("fastrespawn", false);
        client.checkPref("sendbuildplans", true, b -> ClientVars.dispatchingBuildPlans = b);

        client.category("graphics");
        client.sliderPref("minzoom", 0, 0, 100, s -> Strings.fixed(Mathf.pow(10, 0.0217f * s) / 100f, 2) + "x");
        client.sliderPref("weatheropacity", 50, 0, 100, s -> s + "%");
        client.sliderPref("beamdrillopacity", 100, 0, 100, 1, s -> s + "%");
        client.sliderPref("junctionview", 0, -1, 1, 1, s -> { Junction.setBaseOffset(s); return s == -1 ? "@client.left" : s == 1 ? "@client.right" : "@client.slider.hide"; });
        client.sliderPref("itembridgeview", 0, 0, 1, 1, s -> {ItemBridge.setDrawItems(s == 1);return s == 1 ? "@client.slider.show" : "@client.slider.hide";});
        client.sliderPref("liquidbridgeview", 0, 0, 1, 1, s -> {mindustry.world.blocks.liquid.LiquidBridge.setDrawLiquids(s == 1);return s == 1 ? "@client.slider.show" : "@client.slider.hide";});
        client.sliderPref("spawntime", 5, -1, 60, s -> { ClientVars.spawnTime = 60 * s; if (Vars.pathfinder.thread == null) Vars.pathfinder.start(); return s == -1 ? "@client.slider.solidline" : s == 0 ? "@off" : String.valueOf(s); });
        client.sliderPref("traveltime", 10, 0, 60, s -> { ClientVars.travelTime = 60f / s; return s == 0 ? "@off" : String.valueOf(s); });
        client.sliderPref("formationopacity", 30, 10, 100, 5, s -> { UnitType.formationAlpha = s / 100f; return s + "%"; });
        client.sliderPref("hitboxopacity", 0, 0, 100, 5, s -> { UnitType.hitboxAlpha = s / 100f; return s == 0 ? "@off" : s + "%"; });
        client.sliderPref("transferrangeopacity", 0, 0, 100, 5, s -> s == 0 ? "@off" : s + "%");
        client.checkPref("tilehud", true);
        client.checkPref("lighting", true);
        client.checkPref("placementfragmentsearch", true);
        client.checkPref("junctionflowratedirection", false, s -> Junction.flowRateByDirection = s);
        client.checkPref("drawwrecks", true);
        client.checkPref("drawallitems", true, i -> UnitType.drawAllItems = i);
        client.checkPref("drawpath", true);
        client.checkPref("selectionsizeoncursor", true);
        client.checkPref("drawselectionvanilla", false);
        client.checkPref("drawcursors", false);
        client.checkPref("drawdisplayborder", false);
        client.checkPref("tracelogicunits", false);
        client.checkPref("enemyunitranges", false);
        client.checkPref("allyunitranges", false);
        client.checkPref("highlightselectedgraph", true, i -> content.blocks().<BeamNode>each(b -> b instanceof BeamNode, b -> b.configurable = i));
        client.checkPref("highlighthoveredgraph", false);
        client.checkPref("mobileui", false, i -> mobile = !mobile);
        client.checkPref("showreactors", false);
        client.checkPref("showdomes", false);
        client.checkPref("allowinvturrets", true);
        client.checkPref("showtoasts", true);
        client.checkPref("unloaderview", false, i -> Unloader.drawUnloaderItems = i);
        client.checkPref("customnullunloader", false, i -> Unloader.customNullLoader = i);
        int[] lastCursednessLevelI = {Core.settings.getInt("cursednesslevel", 0)};
        client.sliderPref("cursednesslevel", 1, 0, 4, s -> CursednessLevel.fromInteger(s).name(), s -> {
            if(Vars.ui.menufrag.renderer != null && Vars.state.isMenu() && s != lastCursednessLevelI[0]){
                Vars.ui.menufrag.renderer.refresh();
                lastCursednessLevelI[0] = s;
            }
        });
        client.checkPref("logiclinkorder", false);
        client.checkPref("showcutscenes", true);
        client.checkPref("powerinfo", true);
        client.checkPref("activemodesdisplay", true);
        client.checkPref("useiconslogs", false);
        client.checkPref("colorizelogs", false);
        client.checkPref("showmassdriverdistance", false);
        client.checkPref("alwaysfullnumbers", false);
        client.checkPref("enableunderwaterenv", true);
        client.checkPref("alwaysshowteams", false);
        client.checkPref("playerliststyle", true);

        client.category("misc");
        client.updatePref();
        client.textPref("keybind1shiftcommand", "");
        client.textPref("keybind1ctrlcommand", "");
        client.textPref("keybind1altcommand", "");
        client.textPref("keybind1command", "");
        client.sliderPref("minepathcap", 5000, -100, 5000, 100, s -> s == 0 ? "@client.slider.unlimited" : s == -100 ? "@client.slider.never" : String.valueOf(s));
        client.sliderPref("defaultbuildpathradius", 0, 0, 250, 5, s -> s == 0 ? "@client.slider.unlimited" : String.valueOf(s));
        client.sliderPref("modautoupdate", 1, 0, 2, s -> s == 0 ? "@off" : s == 1 ? "@client.slider.background" : "@client.slider.restart");
        client.sliderPref("processorstatementscale", 80, 10, 100, 1, s -> String.format("%.2fx", s/100f)); // This is the most scuffed setting you have ever seen
        client.sliderPref("automapvote", 0, 0, 4, s -> s == 0 ? "@client.slider.never" : s == 4 ? "@client.slider.randomvote" : new String[]{"@client.slider.alwaysdownvote", "@client.slider.alwaysnovote", "@client.slider.alwaysupvote"}[--s]);
        client.sliderPref("pingexecutorthreads", OS.isWindows && !OS.is64Bit ? 5 : 65, 5, 105, 5, s -> s > 100 ? "@client.slider.unlimited" : String.valueOf(s));
        client.sliderPref("maxschematicslisted", 300, 0, 3000, 150, s -> s == 0 ? "@client.slider.unlimited" : String.valueOf(s));
        client.textPref("defaultbuildpathargs", "self"); // Keep it to just self. Skill issue players going afk make this too problematic otherwise. FINISHME: Add an afk detection system and revert this once we can reliably detect afk players and allow others to stop their pathing
        client.textPref("defaultminepathargs", "all");
        client.textPref("gamejointext", "");
        client.textPref("gamewintext", "");
        client.textPref("gamelosetext", "");
        client.checkPref("autoupdate", true, i -> becontrol.checkUpdates = i);
        client.checkPref("discordrpc", true, i -> platform.toggleDiscord(i));
        client.checkPref("confirmexit", true, i -> Vars.confirmExit = i);
        client.checkPref("pathnav", true);
        client.checkPref("nyduspadpatch", true);
        client.checkPref("forceallowschematics", true);
        client.checkPref("blockfishannoyances", true, i -> Fish.blockAnnoyances = i);
        client.checkPref("autorestart", true);
        client.checkPref("realautorestart", true);
        client.checkPref("onjoinfixcode", true);
        client.checkPref("downloadmusic", true);
        client.checkPref("downloadsound", true);
        client.checkPref("schematicmenuexporttags", true);
        client.checkPref("schematicbrowserimporttags", true);
        client.checkPref("schematicuicarryover", false);
        client.checkPref("uselocalizedname", true);
        client.checkPref("hidebannedblocks", false);
        client.checkPref("allowjoinany", false);
        client.checkPref("debug", false, i -> Log.level = i ? Log.LogLevel.debug : Log.LogLevel.info); // Sets the log level to debug
        if (steam) client.checkPref("unlockallachievements", false, i -> { Structs.each(Achievement::complete, Achievement.all); Core.settings.remove("unlockallachievements"); });
        client.checkPref("automega", false, i -> ui.unitPicker.type = i ? UnitTypes.mega : ui.unitPicker.type);
        client.checkPref("processorconfigs", false);
        client.checkPref("attemwarfare", false);
        client.checkPref("removeatteminsteadoffixing", false);
        client.checkPref("circleassist", false);
        client.checkPref("ignoremodminversion", false);
        client.checkPref("betterenemyblocktapping", false);
        client.checkPref("autoohno", false);
        client.checkPref("client-experimentals", false);

        client.category("fallen");
        client.checkPref("hidecursor", false);
        client.checkPref("blocksplayersplan", true);
        client.checkPref("itemslog", true);
        client.checkPref("unitlog", true);
        client.sliderPref("placefragwidth", 7,  3, 10, 1, String::valueOf);
        client.checkPref("tilefragment", true);
        client.checkPref("historyfragment", false);
        client.checkPref("quickschems", false);
        client.checkPref("wavefragment", false);
        client.checkPref("mapinfofrag", false);
        client.checkPref("unitcontrolfragment", false);
        client.checkPref("hidejoinleave", false);
        client.checkPref("placeSchematicWithCleanup", false);
        client.checkPref("no_collisions", false);
        client.checkPref("unitcontrolalarm", false);
        client.checkPref("unitcontrolselfalarm", false);
        client.sliderPref("unitcontrolalarmcount", 100, 1, 1000, 1, String::valueOf);
        client.checkPref("coredeathalarm", true);
        client.checkPref("coredeathalarmrecap", true);
        client.checkPref("playerunitdeathalarm", false);
        client.sliderPref("playerunitdeathalarmhp", 15000, 0, 24000, 50, String::valueOf);
        client.sliderPref("fdpanel-offset", 0, -300, 300, 5, String::valueOf);
        client.sliderPref("buttonsizefdpamel", 30, 10, 70, 5, String::valueOf);
        client.sliderPref("fadedblockallplayers", 10, 0, 100, 1, String::valueOf);
        client.checkPref("resetschetags", false);
        //client.checkPref("steal_map", false);
        //client.checkPref("fd_autofill", false);
        client.checkPref("forcechat", false);
        client.checkPref("ihateattems", true);
        client.checkPref("assistfixfd", false);
        client.checkPref("alarmgriefblocks", false);
        client.sliderPref("alarmgriefblocksbuild", 10, 0, 500, 1, String::valueOf);
        client.sliderPref("alarmgriefblocksbreake", 100, 0, 500, 1, String::valueOf);

        client.updateUuid();
        client.sliderPref("uchatmode", 0, 0, 4, i -> "@client.slider.uchatmode." + i);
        client.colorPref("uchatcolor", "ffd37f");
        client.colorPref("uchatgradientstart", "ffd37f");
        client.colorPref("uchatgradientend", "ffffff");
        client.addGradientNicknameGenerator();

        if (settings.getBool("client-experimentals") || OS.hasProp("policone")) {
            client.category("experimental");
            client.checkPref("trackcoreitems", false, i -> CoreItemsDisplay.trackItems = i && !net.server());
            client.checkPref("modiconloadingoptimization", false);

            client.checkPref("seer-warnings", false);
            client.checkPref("seer-scoring", false);
            client.checkPref("seer-autokick", false);
            client.sliderPref("seer-warnthreshold", 10, 0, 50, String::valueOf);
            client.sliderPref("seer-autokickthreshold", 20, 0, 50, String::valueOf);
            client.sliderPref("seer-scoredecayinterval", 1, 0, 10, i -> i * 30 + "s");
            client.sliderPref("seer-scoredecay", 5, 0, 20, String::valueOf);
            client.sliderPref("seer-reactorscore", 8, 0, 10, String::valueOf);
            client.sliderPref("seer-reactordistance", 5, 0, 20, String::valueOf);
            client.sliderPref("seer-configscore", 3, 0, 50, i -> String.valueOf(i / 5f)); // 0.60
            client.sliderPref("seer-configdistance", 20, 0, 100, String::valueOf);
            client.sliderPref("seer-proclinkthreshold", 20, 0, 80, String::valueOf);
            client.sliderPref("seer-proclinkscore", 10, 0, 50, String::valueOf);
        }
        // End Client Settings

        game.sliderPref("saveinterval", 60, 10, 5 * 120, 10, i -> Core.bundle.format("setting.seconds", i));
        game.checkPref("autotarget", false);
        if(mobile){
            game.checkPref("keyboard", false, val -> {
                control.setInput(val ? new DesktopInput() : new MobileInput());
                input.setUseKeyboard(val);
            });
            if(Core.settings.getBool("keyboard")){
                control.setInput(new DesktopInput());
                input.setUseKeyboard(true);
            }

        }
        //the issue with touchscreen support on desktop is that:
        //1) I can't test it
        //2) the SDL backend doesn't support multitouch
        /*else{
            game.checkPref("touchscreen", false, val -> control.setInput(!val ? new DesktopInput() : new MobileInput()));
            if(Core.settings.getBool("touchscreen")){
                control.setInput(new MobileInput());
            }
        }*/

        game.checkPref("communityservers", true, val -> {
            defaultServers.clear();
            if(val){
                // FINISHME: JoinDialog methods are not static in foos client because of extra error handling flags
                // -BalaM314, merging v8, Apr 13 2025
                // JoinDialog.fetchServers();
            }
        });

        game.checkPref("savecreate", true);
//        game.checkPref("blockreplace", true); We have this as a client setting in foo's
        game.checkPref("conveyorpathfinding", true);
        game.checkPref("hints", true);

        if(!mobile){
            game.checkPref("backgroundpause", true);
            game.checkPref("buildautopause", false);
            game.checkPref("distinctcontrolgroups", true);
        }

        game.checkPref("doubletapmine", false);
        game.checkPref("commandmodehold", true);
        game.checkPref("unitboosthold", true);

        if(steam){
            game.sliderPref("playerlimit", 16, 2, 250, i -> {
                platform.updateLobby();
                return i + "";
            });
        }

        graphics.sliderPref("uiEdgePadding", 0, 0, 100, s -> s + "px", s -> {
            if(ui != null){
                ui.updateMargins();
                Core.scene.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
            }
        });

        int[] lastUiScale = {settings.getInt("uiscale", 100)};

        graphics.sliderPref("uiscale", 100, 25, 300, 5, s -> {
            //if the user changed their UI scale, but then put it back, don't consider it 'changed'
            Core.settings.put("uiscalechanged", s != lastUiScale[0]);
            return s + "%";
        });

        graphics.sliderPref("screenshake", 4, 0, 8, i -> (i / 4f) + "x");

        graphics.sliderPref("bloomintensity", 6, 0, 16, i -> (int)(i / 4f * 100f) + "%");
        graphics.sliderPref("bloomblur", 2, 1, 16, i -> i + "x");

        graphics.sliderPref("fpscap", 240, 10, 245, 5, s -> {
            if(ios){
                Core.graphics.setPreferredFPS(s > 240 ? 0 : s);
            }
            return (s > 240 ? Core.bundle.get("setting.fpscap.none") : Core.bundle.format("setting.fpscap.text", s));
        });

        if(ios){
            int value = Core.settings.getInt("fpscap", 240);
            Core.graphics.setPreferredFPS(value > 240 ? 0 : value);
        }

        graphics.sliderPref("chatopacity", 100, 0, 100, 5, s -> s + "%");
        graphics.sliderPref("lasersopacity", 100, 0, 100, 5, s -> {
            if(ui.settings != null){
                Core.settings.put("preferredlaseropacity", s);
            }
            return s + "%";
        });

        graphics.sliderPref("unitlaseropacity", 100, 0, 100, 5, s -> s + "%");
        graphics.sliderPref("bridgeopacity", 100, 0, 100, 5, s -> s + "%");

        graphics.sliderPref("maxmagnificationmultiplierpercent", 100, 100, 200, 25, s -> {
            if(ui.settings != null){
                Core.settings.put("maxzoomingamemultiplier", (float)s / 100.0f);
            }
            return s + "%";
        });

        graphics.sliderPref("minmagnificationmultiplierpercent", 100, 100, 300, 25, s -> {
            if(ui.settings != null){
                Core.settings.put("minzoomingamemultiplier", (float)s / 100.0f);
            }
            return s + "%";
        });

        if(!mobile){
            graphics.checkPref("vsync", true, b -> Core.graphics.setVSync(b));
            graphics.checkPref("fullscreen", false, b -> Core.graphics.setFullscreen(b));

            Core.graphics.setVSync(Core.settings.getBool("vsync"));

            if(Core.settings.getBool("fullscreen")){
                Core.app.post(() -> Core.graphics.setFullscreen(true));
            }
        }else if(!ios){
            graphics.checkPref("landscape", false, b -> {
                if(b){
                    platform.beginForceLandscape();
                }else{
                    platform.endForceLandscape();
                }
            });

            if(Core.settings.getBool("landscape")){
                platform.beginForceLandscape();
            }
        }

        graphics.checkPref("effects", true);
        graphics.checkPref("lodfade", true);
        graphics.checkPref("atmosphere", true);
        graphics.checkPref("drawlight", true);
        graphics.checkPref("destroyedblocks", true);
        graphics.checkPref("blockstatus", false);
        graphics.checkPref("playerchat", true);
        graphics.checkPref("coreitems", !mobile);
        graphics.checkPref("minimap", !mobile);
        graphics.checkPref("smoothcamera", true);
        if(!mobile){
            graphics.checkPref("detach-camera", false);
        }
        graphics.checkPref("position", false);
        graphics.checkPref("fps", false);
        graphics.checkPref("playerindicators", true);
        graphics.checkPref("showpings", true);
        graphics.checkPref("showotherbuildplans", true);
        graphics.checkPref("indicators", true);
        // graphics.checkPref("showweather", true); FINISHME: Move client weather alpha to this
        graphics.checkPref("animatedwater", true);

        graphics.checkPref("bloom", true, val -> renderer.toggleBloom(val));

        graphics.checkPref("pixelate", false, val -> {
            if(val){
                Events.fire(Trigger.enablePixelation);
            }
        });

        Cons2<Boolean, Boolean> setFilters = (setNonText, setText) -> {
            ObjectSet<Texture> atlas = new ObjectSet<>(Core.atlas.getTextures());
            final boolean lText = Core.settings.getBool("lineartext");
            var fontFilter = Fonts.getTextFilter(lText);
            for(Font f : new Font[]{Fonts.def, Fonts.outline}){
                f.getRegions().each(t -> {
                    if(setText) {
                        t.texture.setFilter(fontFilter);
                    }
                    atlas.remove(t.texture);
                });
            }
            if(setNonText){
                final var filter = Core.settings.getBool("linear") ? TextureFilter.linear : TextureFilter.nearest;
                atlas.each(t -> t.setFilter(filter));
            }
        };

        graphics.checkPref("linear", !mobile, b -> {
            setFilters.get(true, false);
        });
        graphics.checkPref("lineartext", Core.settings.getBool("linear"), b -> {
            setFilters.get(false, true);
        });

        setFilters.get(true, true);

        graphics.checkPref("skipcoreanimation", false);
        graphics.checkPref("hidedisplays", false);
        graphics.checkPref("logiclocalization", true);

        if(OS.isMac){
            graphics.checkPref("macnotch", false);
        }

        if(!mobile){
            if (Core.settings.getBool("swapdiagonal", false)) Core.settings.put("swapdiagonal", false);
        }


        // Start Moderation Settings
        moderation.checkPref("modenabled", true, b -> Client.INSTANCE.setLeaves(b ? new Moderation() : null));
        moderation.sliderPref("leavecount", 100, 5, 1000, 10, String::valueOf);
        // End Moderation Settings

        dev.checkPref("console", false);
        dev.checkPref("drawhitboxes", false);
        dev.checkPref("showperformance", false);

        if(!ios){
            dev.checkPref("modcrashdisable", true);
        }
    }

    public void exportData(Fi file) throws IOException{
        Seq<Fi> files = new Seq<>();
        files.add(Core.settings.getSettingsFile());
        files.addAll(customMapDirectory.list());
        files.addAll(saveDirectory.list());
        files.addAll(modDirectory.list());
        files.addAll(schematicDirectory.list());
        files.addAll(assetCacheDirectory.list()); //important for saves
        String base = Core.settings.getDataDirectory().path();

        //add directories
        for(Fi other : files.copy()){
            Fi parent = other.parent();
            while(!files.contains(parent) && !parent.equals(settings.getDataDirectory())){
                files.add(parent);
            }
        }

        try(OutputStream fos = file.write(false, 2048); ZipOutputStream zos = new ZipOutputStream(fos)){
            for(Fi add : files){
                String path = add.path().substring(base.length());
                if(add.isDirectory()) path += "/";
                //fix trailing / in path
                path = path.startsWith("/") ? path.substring(1) : path;
                zos.putNextEntry(new ZipEntry(path));
                if(!add.isDirectory()){
                    try(var stream = add.read()){
                        Streams.copy(stream, zos);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    public void importData(Fi file){
        Fi zipped = new ZipFi(file);

        Fi base = Core.settings.getDataDirectory();
        if(!zipped.child("settings.bin").exists()){
            throw new IllegalArgumentException("Not valid save data.");
        }

        //delete old saves so they don't interfere
        saveDirectory.deleteDirectory();

        //clear old assets cache
        assetCacheDirectory.deleteDirectory();

        //purge existing tmp data, keep everything else
        tmpDirectory.deleteDirectory();

        zipped.walk(f -> f.copyTo(base.child(f.path())));


        //clear old data
        settings.clear();
        //load data so it's saved on exit
        settings.load();
    }

    private void back(){
        rebuildMenu();
        prefs.clearChildren();
        prefs.add(menu);
    }

    public void visible(int index){
        prefs.clearChildren();

        Seq<Table> tables = Seq.with(game, graphics, sound, dev, client, moderation);
        categories.each(c -> tables.add(c.table));

        prefs.add(tables.get(index));
    }

    @Override
    public void addCloseButton(){
        buttons.button("@back", Icon.left, () -> {
            if(prefs.getChildren().first() != menu){
                back();
            }else{
                hide();
            }
        }).size(210f, 64f);

        keyDown(key -> {
            if(key == KeyCode.escape || key == KeyCode.back){
                if(prefs.getChildren().first() != menu){
                    back();
                }else{
                    hide();
                }
            }
        });
    }

    public interface StringProcessor{
        String get(int i);
    }

    public static class SettingsCategory{
        public String name;
        public @Nullable Drawable icon;
        public Cons<SettingsTable> builder;
        public SettingsTable table;

        public SettingsCategory(String name, Drawable icon, Cons<SettingsTable> builder){
            this.name = name;
            this.icon = icon;
            this.builder = builder;

            table = new SettingsTable();
            builder.get(table);
        }
    }

    public static class SettingsTable extends Table{
        protected Seq<Setting> list = new Seq<>();
        private static Seq<Setting> listSorted = new Seq<>();
        private String search = "";
        private final Table searchBarTable = makeSearchBar();
        private TextField searchBar;

        private static final SettingsTable tempTable = new SettingsTable();

        static {
            tempTable.canRebuild = false; // Disable the hack that detects mods adding unsupported settings
        }

        public SettingsTable(){
            left();
        }

        private Table makeSearchBar(){
            return new Table(s -> {
                s.image(Icon.zoom);
                searchBar = s.field(search, res -> {
                    search = res;
                    rebuild();
                }).growX().get();
            });
        }

        public Seq<Setting> getSettings(){
            return list;
        }

        public void pref(Setting setting){
            list.add(setting);
            rebuild();
        }

        public SliderSetting sliderPref(String name, int def, int min, int max, StringProcessor s){
            return sliderPref(name, def, min, max, 1, s);
        }

        public SliderSetting sliderPref(String name, int def, int min, int max, int step, StringProcessor s){
            return sliderPref(name, def, min, max, step, s, null);
        }

        public SliderSetting sliderPref(String name, int def, int min, int max, StringProcessor s, Intc changed){
            return sliderPref(name, def, min, max, 1, s, changed);
        }

        public SliderSetting sliderPref(String name, int def, int min, int max, int step, StringProcessor s, Intc changed){
            SliderSetting res;
            list.add(res = new SliderSetting(name, def, min, max, step, s, changed));
            settings.defaults(name, def);
            rebuild();
            return res;
        }

        public void checkPref(String name, boolean def){
            list.add(new CheckSetting(name, def, null));
            settings.defaults(name, def);
            rebuild();
        }

        public void checkPref(String name, boolean def, Boolc changed){
            list.add(new CheckSetting(name, def, changed));
            settings.defaults(name, def);
            rebuild();
        }

        public void colorPref(String name, String defHex){
            settings.defaults(name, defHex);
            pref(new Setting(name){
                @Override
                public void add(SettingsTable table){
                    Color col = new Color();
                    try{
                        String raw = settings.getString(name, defHex).trim();
                        if(raw.startsWith("#")) raw = raw.substring(1);
                        Color named = Colors.get(raw);
                        col.set(named != null ? named : Color.valueOf(raw.isEmpty() ? defHex : raw));
                    }catch(Exception e){
                        col.set(Color.valueOf(defHex));
                    }
                    col.a = 1f;
                    Image swatch = new Image(Tex.whiteui);
                    swatch.setColor(col);
                    table.table(t -> {
                        t.left();
                        t.add(title).padRight(10).growX();
                        t.add(swatch).size(36).padRight(8);
                        t.button(Icon.pencil, Styles.cleari, () -> ui.picker.show(col.cpy(), false, c -> {
                            c.a = 1f;
                            col.set(c);
                            swatch.setColor(c);
                            int r = Mathf.clamp((int)(c.r * 255f + 0.5f), 0, 255);
                            int g = Mathf.clamp((int)(c.g * 255f + 0.5f), 0, 255);
                            int b = Mathf.clamp((int)(c.b * 255f + 0.5f), 0, 255);
                            settings.put(name, String.format("%02x%02x%02x", r, g, b));
                        })).size(40);
                    }).left().growX().padTop(4).padBottom(4);
                    table.row();
                }
            });
        }

        public void textPref(String name, String def){
            list.add(new TextSetting(name, def, null));
            settings.defaults(name, def);
            rebuild();
        }

        public void textPref(String name, String def, Cons<String> changed){
            list.add(new TextSetting(name, def, changed));
            settings.defaults(name, def);
            rebuild();
        }

        public void areaTextPref(String name, String def){
            list.add(new AreaTextSetting(name, def, null));
            settings.defaults(name, def);
            rebuild();
        }

        public void areaTextPref(String name, String def, Cons<String> changed){
            list.add(new AreaTextSetting(name, def, changed));
            settings.defaults(name, def);
            rebuild();
        }

        private long lastRebuild;
        /** True while in rebuild(). If the add() method is called on the settings table while this is false, we know that a mod is doing something unsupported and will disable the search bar */
        private boolean isRebuilding;
        /** Whether this table is safe to rebuild. A table is safe unless a mod has added a non Setting element. When this is false, rebuild() will always return immediately, even when called from the mod this table was created by */
        private boolean canRebuild = true;
        /** Horrible way to force a rebuild right before a mod adds a non Setting */
        private boolean forceRebuild = false;

        /** Scuffed way of detecting mods adding non Setting elements to tables */
        @Override
        public <T extends Element> Cell<T> add(T element){
            // FINISHME: Do we also want to maybe get the stacktrace and check if this is being called from Setting.add()? It would be slow but some mod probably does something weird like this somewhere. This suggestion applies to row() as well
            if(isRebuilding || !canRebuild) return super.add(element); // Normal behavior as long as we're adding stuff using the rebuild() method or once we've already disabled our optimizations
            // Force the table to be rebuilt *before* adding the first non setting as this is how vanilla would behave (there's a good chance that the last rebuild was delayed to the next frame for performance reasons)
            Log.warn(bundle.format("client.settings.search.disabled.log.add", Threads.getTrace(1)));
            forceRebuild = true;
            rebuild();
            return super.add(element);
        }

        /** Scuffed way of detecting mods trying to add rows before we have rebuilt() at least once (this happens since foo's normally delays the first rebuild to the frame after setting creation) */
        @Override
        public Table row(){
            if (hasChildren() || !canRebuild || isRebuilding) return super.row();
            // If we haven't yet seen a non setting, but they're trying to add a row, they're probably just adding it after the Reset to Defaults button which would be here normally. Trigger a rebuild so its there properly
            Log.warn(bundle.format("client.settings.search.disabled.log.row", Threads.getTrace(1)));
            forceRebuild = true;
            rebuild();
            return super.row();
        }

        public void rebuild(){ // FINISHME: Ideally, we should still be able to search for settings even if a mod has added non settings to the table. Perhaps we could have a Setting type that just wraps a runnable that adds the element being added in add()?
            if(!forceRebuild){
                if(!canRebuild) return;
                // This optimization breaks some mods that add content to the table as non Settings, it is automatically bypassed rebuilding() is blocked entirely when non Settings are detected
                if (lastRebuild == -1) return; // Can't run more than twice per frame
                if (lastRebuild == 0 || lastRebuild == Core.graphics.getFrameId()) { // First ever run and second run per frame
                    lastRebuild = -1;
                    Core.app.post(() -> {
                        lastRebuild = -2; // Allows rebuild to run
                        rebuild(); // This will set lastRebuild to the current frame
                    });
                    return;
                }
            }else{ // A rebuild was forced, this means that we can no longer rebuild this due to a mod adding a non Setting
                forceRebuild = canRebuild = false;
                if(!searchBar.isDisabled()) searchBar.addListener(Tooltip.Tooltips.getInstance().create("@client.settings.search.disabled.tooltip")); // Only on first run
                searchBar.setDisabled(true);
            }

            isRebuilding = true;
            lastRebuild = Core.graphics.getFrameId();
            boolean hasFocus = searchBar.hasKeyboard();
            clearChildren();
            // FINISHME: Inefficient
            add(searchBarTable).fillX().padBottom(4);
            row();

            if(search.trim().isEmpty()){ // No search value: Contains hacky code for client setting categories
                Cons<Category> pushCategory = cat -> { // Adds all the elements of the current category to the settings table
                    if(cat != null){
                        cat.children.clearChildren(); // Clear the settings otherwise we'll end up with multiple sets of settings
                        tempTable.getCells().each(cell -> {
                            cat.children.add(cell.get()).set(cell); // Hack to get the element of a cell, move it to another table and then copy over the properties to its new cell.
                            if(cell.isEndRow()) cat.children.row(); // Extra hack because setting the cell to the old one doesn't account for rows
                        });
                    }
                    tempTable.clearChildren();
                };
                Category lastCategory = null;
                for(Setting setting : list){
                    if(setting instanceof Category c){
                        pushCategory.get(lastCategory);
                        lastCategory = c;
                        setting.add(this);
                    }else setting.add(lastCategory == null ? this : tempTable);
                }
                pushCategory.get(lastCategory); // Handle final category for table
            }else{
                listSorted.selectFrom(list, s -> !(s instanceof Category));
                var searchLower = search.toLowerCase();
                var searchSplit = ObjectSet.with(searchLower.split(" "));
                listSorted.sort(Structs.comparingFloat(u -> { // FINISHME: Maybe if distance == length, do not include? But troublesome
                    var title = u.title == null ? "" : Strings.stripColors(u.title).toLowerCase();
//                    var desc = u.description == null ? "" : Strings.stripColors(u.description).toLowerCase();
                    var weight = title.isEmpty() /*&& desc.isEmpty()*/ ? Float.POSITIVE_INFINITY : 0f;

                    if (!title.isEmpty()) weight += ClientUtils.biasedLevenshtein(searchLower, title, true, true) / (Structs.count(title.split(" "), searchSplit::contains) + 1); // Case-sensitive as a minor optimization so that search query is only lowercased once
//                    Line below doesn't work great since a lot of the settings don't have descriptions
//                    if (!desc.isEmpty()) weight += .5f * ClientUtils.biasedLevenshtein(searchLower, desc, false, true) / (Structs.count(desc.split(" "), searchSplit::contains) + 1);

                    return weight;
                }));

                for(Setting setting : listSorted){
                    setting.add(this);
                }
            }

            button(bundle.get("settings.reset", "Reset to Defaults"), () -> {
                for(Setting setting : list){
                    if(setting.name == null || setting.title == null) continue;
                    settings.remove(setting.name);
                }
                forceRebuild = !canRebuild; // If we can't rebuild normally, we force a new rebuild through: vanilla would do the same, and it would break bad mods as it will here.
                rebuild();
            }).margin(14).width(240f).pad(6);

            if(hasFocus){
                searchBar.requestKeyboard();
            }
            isRebuilding = false;
        }


        private void addGradientNicknameGenerator() {
            Core.settings.defaults("grad_nick_raw", "Test Nick");
            Core.settings.defaults("grad_start_hex", "ff0000");
            Core.settings.defaults("grad_end_hex", "ffffff");
            Core.settings.defaults("grad_step", 1);

            pref(new Setting("grad_nickname_gen") {
                Color startC = Color.valueOf(Core.settings.getString("grad_start_hex"));
                Color endC = Color.valueOf(Core.settings.getString("grad_end_hex"));
                final String[] result = {""};

                @Override
                public void add(SettingsTable table) {
                    table.row();
                    // Основная панель еще шире для удобства
                    table.table(Styles.grayPanel, t -> {
                        t.margin(14);
                        t.add("@client.gradgen.title").color(Pal.accent).padBottom(10).row();

                        // 1. Поле ввода
                        t.table(it -> {
                            it.add("@client.gradgen.text").left().expandX().row();
                            it.field(Core.settings.getString("grad_nick_raw"), text -> {
                                Core.settings.put("grad_nick_raw", text);
                            }).width(400).height(45);
                        }).row();

                        // 2. Выбор цветов
                        t.table(ct -> {
                            ImageButton bStart = ct.button(Icon.fill, () -> {
                                Vars.ui.picker.show(startC, res -> {
                                    startC.set(res);
                                    Core.settings.put("grad_start_hex", res.toString());
                                });
                            }).size(55).get();
                            bStart.update(() -> bStart.getStyle().imageUpColor = startC);

                            ct.add("@client.gradgen.gradient").pad(0, 15, 0, 15);

                            ImageButton bEnd = ct.button(Icon.fill, () -> {
                                Vars.ui.picker.show(endC, res -> {
                                    endC.set(res);
                                    Core.settings.put("grad_end_hex", res.toString());
                                });
                            }).size(55).get();
                            bEnd.update(() -> bEnd.getStyle().imageUpColor = endC);
                        }).padTop(10).row();

                        // 3. Слайдер
                        t.table(st -> {
                            st.add("@client.gradgen.step").left();
                            Label stepLabel = st.add("").color(Pal.accent).width(35).get();
                            stepLabel.update(() -> stepLabel.setText(String.valueOf(Core.settings.getInt("grad_step"))));

                            st.slider(1, 10, 1, Core.settings.getInt("grad_step"), val -> {
                                Core.settings.put("grad_step", (int)val);
                            }).width(200).padLeft(10);
                        }).width(400).padTop(10).row();

                        // 5. Кнопка Генерации
                        t.button("@client.gradgen.generate", Icon.refresh, () -> {
                            result[0] = generateGradient(
                                    Core.settings.getString("grad_nick_raw"),
                                    startC,
                                    endC,
                                    Core.settings.getInt("grad_step")
                            );
                        }).width(400).height(50).padTop(10).color(Pal.accent).row();

                        // 4. Поле ПРЕДВЫБОРА / КОПИРОВАНИЯ
                        t.add("@client.gradgen.copyhint").padTop(20).left().row();

                        TextButton resBtn = t.button("", Styles.flatBordert, () -> {
                            if(!result[0].isEmpty()){
                                Core.app.setClipboardText(result[0]);
                                Vars.ui.showInfoFade("@client.gradgen.copied");
                            }
                        }).width(400).height(55).get();

                        // Кнопка всегда видна, но меняет текст
                        resBtn.update(() -> {
                            if(result[0].isEmpty()){
                                resBtn.setText("[gray]" + Core.bundle.get("client.gradgen.placeholder"));
                                resBtn.setDisabled(true);
                            } else {
                                resBtn.setText(result[0]);
                                resBtn.setDisabled(false);
                            }
                        });
                        t.row();

                        // Счетчик символов
                        t.table(lt -> {
                            lt.add("").update(l -> {
                                int len = result[0].length();
                                if(len == 0) l.setText("");
                                else {
                                    l.setText(Core.bundle.format("client.gradgen.length", len));
                                    l.setColor(len > 70 ? Color.scarlet : Color.lightGray);
                                }
                            });
                        }).height(20).row();



                    }).width(440).pad(10).expandX();
                    table.row();
                }
            });
        }

        private String generateGradient(String text, Color start, Color end, int step) {
            if (text == null || text.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            String cleanText = text.replace(" ", "");

            // Если в нике всего 1 буква или шаг больше длины
            if (cleanText.length() <= 1 || cleanText.length() <= step) {
                return "[#" + start.toString().substring(0, 6) + "]" + text;
            }

            int totalBlocks = (int) Math.ceil((double) cleanText.length() / step);
            int cleanCharIdx = 0;

            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);

                if (c == ' ') {
                    sb.append(c);
                    continue;
                }

                if (cleanCharIdx % step == 0) {
                    // Рассчитываем t от 0.0 до 1.0
                    float t = (float) (cleanCharIdx / step) / (totalBlocks - 1);

                    // Самый надежный способ lerp для Mindustry
                    int r = (int) ((start.r + (end.r - start.r) * t) * 255);
                    int g = (int) ((start.g + (end.g - start.g) * t) * 255);
                    int b = (int) ((start.b + (end.b - start.b) * t) * 255);

                    sb.append(String.format("[#%02x%02x%02x]",
                            Math.max(0, Math.min(255, r)),
                            Math.max(0, Math.min(255, g)),
                            Math.max(0, Math.min(255, b))));
                }

                sb.append(c);
                cleanCharIdx++;
            }

            return sb.toString();
        }

        private void updateUuid() {
            settings.defaults("updateuuid", settings.getString("uuid"));

            pref(new Setting("updateuuid") {
                @Override
                public void add(SettingsTable table) {
                    name = "updateuuid";
                    title = bundle.get("setting." + name + ".name");

                    table.table(t -> {
                        t.left();
                        t.button(Icon.refresh, Styles.settingTogglei, 32, () -> {
                            String val = settings.getString("updateuuid");
                            if(!val.isEmpty()) {
                                Core.settings.put("uuid", val);
                                ui.showInfo("UUID применен! Перезайди на сервер.");
                            }
                        }).padRight(4);

                        t.add(title).padRight(10);

                        TextField field = t.field(settings.getString(name), text -> {
                            settings.put(name, text);
                        }).width(400).get();

                        field.setMessageText("UUID...");

                        t.button(Icon.box, Styles.cleari, () -> {
                            byte[] bytes = new byte[8];
                            new java.util.Random().nextBytes(bytes);
                            String newGen = new String(arc.util.serialization.Base64Coder.encode(bytes));

                            settings.put("updateuuid", newGen);
                            field.setText(newGen);
                        }).size(32).padLeft(8).tooltip("Random UUID");

                    }).left().expandX();
                    table.row();
                }
            });
        }

        public abstract static class Setting{
            public String name;
            public String title;
            public @Nullable String description;

            public Setting(String name){
                this.name = name;
                String fooKey = "client.setting." + name + ".name";
                if(bundle.has(fooKey)){ // Client keys are in the format client.setting.name.__ as we name all client settings this way and its cleaner. We also don't support winkey as we don't need it.
                    title = bundle.get(fooKey);
                    description = bundle.getOrNull("client.setting." + name + ".description");
                    return;
                }
                String winkey = "setting." + name + ".name.windows";
                title = OS.isWindows && bundle.has(winkey) ? bundle.get(winkey) : bundle.get("setting." + name + ".name", name);
                description = bundle.getOrNull("setting." + name + ".description");
            }

            public abstract void add(SettingsTable table);

            public void addDesc(Element elem){
                ui.addDescTooltip(elem, description);
            }
        }

        public static class CheckSetting extends Setting{
            boolean def;
            Boolc changed;

            public CheckSetting(String name, boolean def, Boolc changed){
                super(name);
                this.def = def;
                this.changed = changed;
            }

            @Override
            public void add(SettingsTable table){
                Table box = Elems.check(title, () -> settings.getBool(name), value -> {
                    settings.put(name, value);
                    if(changed != null){
                        changed.get(value);
                    }
                });

                addDesc(table.add(box).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f))).fillX().height(45f).left().padTop(7f).get());
                table.row();
            }
        }

        public static class SliderSetting extends Setting{
            int def, min, max, step;
            StringProcessor sp;
            Intc changed;

            public SliderSetting(String name, int def, int min, int max, int step, StringProcessor s, Intc changed){
                super(name);
                this.def = def;
                this.min = min;
                this.max = max;
                this.step = step;
                this.sp = s;
                this.changed = changed;
            }

            @Override
            public void add(SettingsTable table){
                Slider slider = new Slider(min, max, step, false);

                slider.setValue(settings.getInt(name));

                Label value = new Label("", Styles.outlineLabel);
                Table content = new Table();
                content.add(title, Styles.outlineLabel).left().growX().wrap();
                content.add(value).padLeft(10f).right();
                content.margin(3f, 33f, 3f, 33f);
                content.touchable = Touchable.disabled;

                slider.changed(() -> {
                    settings.put(name, (int)slider.getValue());
                    value.setText(sp.get((int)slider.getValue()));
                    if(changed != null) changed.get((int)slider.getValue());
                });

                slider.change();

                addDesc(table.stack(slider, content).width(Math.min(Core.graphics.getWidth() / 1.2f / Scl.scl(1f), 500f)).left().padTop(4f).get());
                table.row();
            }
        }

        /** Add a section/subcategory. */
        public void category(String name){ // FINISHME: Rename this to header or something, it doesn't do the same thing as SettingsCategory
            pref(new Category(name));
        }

        // Elements are actually added below
        public static class Category extends Setting{
            protected final static ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(){{
                imageDownColor = Pal.accent;
                imageOverColor = Pal.accent;
                imageUpColor = Pal.accent;
            }};
            protected Table children = new Table();
            private final Collapser collapser = new Collapser(children, settings.getBool("settingscategory-" + name + "-enabled", true));

            Category(String name){
                super(name);
                title = bundle.get("client.setting." + name + ".category");
            }

            @Override
            public void add(SettingsTable table){
                ImageButton[] arrowButton = {null};
                table.add("").row(); // Add a cell first as .row doesn't work if there are no cells in the current row.
                final Runnable onClicked = () -> {
                    collapser.toggle();
                    settings.put("settingscategory-" + name + "-enabled", collapser.isCollapsed());
                    arrowButton[0].getStyle().imageUp = collapser.isCollapsed() ? Icon.downOpen : Icon.upOpen;
                };
                table.table(t -> {
                    t.add(title).center().growX().color(Pal.accent).get().clicked(onClicked);
                    (arrowButton[0] = t.button(Icon.downOpen, style, onClicked).size(10f).right().padRight(10f).get())
                        .getStyle().imageUp = collapser.isCollapsed() ? Icon.downOpen : Icon.upOpen;
                }).growX();
                table.row();
                table.image(Tex.whiteui, Pal.accent).growX().height(3f).padTop(4f).padBottom(4f);
                table.row();
                table.add(collapser).left();
                table.row();
            }
        }

        private void updatePref(){
            settings.defaults("updateurl", "GLaziness/GL-Client");
            if (!Version.updateUrl.isEmpty()) settings.put("updateurl", Version.updateUrl); // overwrites updateurl on every boot, shouldn't be a real issue
            pref(new Setting("updateurl") {
                boolean urlChanged;

                @Override
                public void add(SettingsTable table) { // Update URL with update button FINISHME: Move this to TextPref when i decide im willing to spend 6 hours doing so

                    table.table(t -> {
                        t.button(Icon.refresh, Styles.settingTogglei, 32, () -> {
                            ui.loadfrag.show();
                            becontrol.checkUpdate(result -> {
                                ui.loadfrag.hide();
                                urlChanged = false;
                                if(!result){
                                    ui.showInfo("@be.noupdates");
                                } else {
                                    becontrol.showUpdateDialog();
                                }
                            });
                        }).update(u -> u.setChecked(becontrol.isUpdateAvailable() || urlChanged)).padRight(4);
                        Label label = new Label(title);
                        t.add(label).minWidth(label.getPrefWidth() / Scl.scl(1.0F) + 25.0F);
                        t.field(settings.getString(name), text -> {
                            becontrol.setUpdateAvailable(false); // Set this to false as we don't know if this is even a valid URL.
                            urlChanged = true;
                            settings.put(name, text);
                            if(text.isEmpty()) settings.remove(name);
                        }).width(450).get().setMessageText("mindustry-antigrief/mindustry-client-v8-builds");
                    }).left().expandX().padTop(3).height(32).padBottom(3);
                    table.row();
                }
            });
        }

        public static class TextSetting extends Setting{
            String def;
            Cons<String> changed;

            public TextSetting(String name, String def, Cons<String> changed){
                super(name);
                this.def = def;
                this.changed = changed;
            }

            @Override
            public void add(SettingsTable table){
                TextField field = new TextField(settings.getString(name));
                field.setMessageText(def);
                field.changed(() -> {
                    settings.put(name, field.getText());
                    if(changed != null){
                        changed.get(field.getText());
                    }
                });

                Table prefTable = table.table().left().padTop(3f).get();
                prefTable.label(() -> title);
                prefTable.add(field).width(450);
                addDesc(prefTable);
                table.row();
            }
        }

        public static class AreaTextSetting extends TextSetting{
            public AreaTextSetting(String name, String def, Cons<String> changed){
                super(name, def, changed);
            }

            @Override
            public void add(SettingsTable table){
                TextArea area = new TextArea(settings.getString(name));
                area.setPrefRows(5);

                area.changed(() -> {
                    settings.put(name, area.getText());
                    if(changed != null){
                        changed.get(area.getText());
                    }
                });

                addDesc(table.label(() -> title).left().padTop(3f).get());
                table.row().add(area).left();
                table.row();
            }
        }
    }
}
