package mindustry.client.fallen.assistai;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

/** Settings of the poly mode ({@link SelfBuilderAI}), opened with a right click on its side panel button. */
public class PolySettingsDialog extends BaseDialog{
    public static PolySettingsDialog instance = new PolySettingsDialog();

    private final Table all = new Table();
    private float width = 600f;

    public PolySettingsDialog(){
        super("@client.polyai.title");
        addCloseButton();
        shown(this::rebuild);
        onResize(this::rebuild);
        cont.pane(all).scrollX(false).grow();
    }

    public void rebuild(){
        all.clear();
        all.top().margin(10f).marginBottom(30f);
        width = Math.min(600f, Core.graphics.getWidth() / Scl.scl(1f) - 60f);

        section(Icon.hammer, "@client.polyai.logic", t -> {
            check(t, "@client.polyai.rebuild", SelfBuilderAI.rebuildBlocks, b -> {
                SelfBuilderAI.rebuildBlocks = b;
                Core.settings.put("poly-rebuild-blocks", b);
            });
            check(t, "@client.polyai.turrets", SelfBuilderAI.checkEnemyTurrets, b -> {
                SelfBuilderAI.checkEnemyTurrets = b;
                Core.settings.put("poly-check-turrets", b);
            });
            check(t, "@client.polyai.clearghosts", SelfBuilderAI.clearGhosts, b -> {
                SelfBuilderAI.clearGhosts = b;
                Core.settings.put("poly-clear-ghosts", b);
            });
            t.add("@client.polyai.clearghosts.hint").color(Color.lightGray).wrap().growX().left().padBottom(6f).row();
            check(t, "@client.polyai.resources", SelfBuilderAI.checkResources, b -> {
                SelfBuilderAI.checkResources = b;
                Core.settings.put("poly-check-res", b);
            });
            check(t, "@client.polyai.defense", SelfBuilderAI.prioritizeDefenses, b -> {
                SelfBuilderAI.prioritizeDefenses = b;
                Core.settings.put("poly-prio-defense", b);
            });
            check(t, "@client.polyai.heal", SelfBuilderAI.healDamaged, b -> {
                SelfBuilderAI.healDamaged = b;
                Core.settings.put("poly-heal", b);
            });
            check(t, "@client.polyai.closest", SelfBuilderAI.findClosestPlan, b -> {
                SelfBuilderAI.findClosestPlan = b;
                Core.settings.put("poly-closest", b);
            });
        });

        section(Icon.production, "@client.polyai.afk", t -> {
            check(t, "@client.polyai.afk.mine", SelfBuilderAI.afkMine, b -> {
                SelfBuilderAI.afkMine = b;
                Core.settings.put("poly-afk-mine", b);
                if(!b) mindustry.client.ui.PanelFragment.aiNotPolyAi.stopAfk();
            });

            Slider slider = new Slider(2, 60, 1, false);
            slider.setValue(SelfBuilderAI.afkMineDelay);
            Label value = new Label("", Styles.outlineLabel);
            Runnable text = () -> value.setText(SelfBuilderAI.afkMineDelay + " " + Core.bundle.get("unit.seconds"));
            text.run();
            Table content = new Table();
            content.add("@client.polyai.afk.delay", Styles.outlineLabel).left().growX().wrap();
            content.add(value).padLeft(10f).right();
            content.margin(3f, 33f, 3f, 33f);
            content.touchable = Touchable.disabled;
            slider.changed(() -> {
                SelfBuilderAI.afkMineDelay = (int)slider.getValue();
                Core.settings.put("poly-afk-delay", SelfBuilderAI.afkMineDelay);
                text.run();
            });
            t.stack(slider, content).growX().padTop(6f).row();
            t.add("@client.polyai.afk.hint").color(Color.lightGray).wrap().growX().left().padTop(4f).row();
        });

        section(Icon.players, "@client.polyai.filter", t -> {
            check(t, "@client.polyai.onlywhitelist", PolyFilter.onlyWhitelist, b -> {
                PolyFilter.onlyWhitelist = b;
                Core.settings.put("poly-only-whitelist", b);
            });
            check(t, "@client.polyai.customlvl", PolyFilter.allowCustomLvl, b -> {
                PolyFilter.allowCustomLvl = b;
                Core.settings.put("poly-allow-custom-lvl", b);
            });

            Slider slider = new Slider(0, 50, 1, false);
            slider.setValue(PolyFilter.minLevel);
            Label value = new Label(String.valueOf(PolyFilter.minLevel), Styles.outlineLabel);
            Table content = new Table();
            content.add("@client.polyai.minlevel", Styles.outlineLabel).left().growX().wrap();
            content.add(value).padLeft(10f).right();
            content.margin(3f, 33f, 3f, 33f);
            content.touchable = Touchable.disabled;
            slider.changed(() -> {
                PolyFilter.minLevel = (int)slider.getValue();
                Core.settings.put("poly-min-lvl", PolyFilter.minLevel);
                value.setText(String.valueOf(PolyFilter.minLevel));
            });
            t.stack(slider, content).growX().padTop(6f).row();
        });

        section(Icon.list, "@client.polyai.players", t -> {
            t.add("@client.polyai.players.hint").color(Color.lightGray).wrap().growX().left().padBottom(6f).row();

            boolean any = false;
            for(Player p : Groups.player){
                if(p.isLocal()) continue;
                any = true;

                String key = PolyFilter.getKey(p);
                PolyFilter.PlayerInfo info = PolyFilter.parsePlayer(p);

                t.table(Styles.black6, row -> {
                    row.left().margin(4f);
                    row.button(Icon.star, Styles.clearNoneTogglei, () -> {
                        if(!PolyFilter.whitelist.remove(key)){
                            PolyFilter.whitelist.add(key);
                            PolyFilter.blacklist.remove(key);
                        }
                    }).size(36f).tooltip("@client.polyai.whitelist")
                        .update(b -> {
                            b.setChecked(PolyFilter.whitelist.contains(key));
                            b.getImage().setColor(b.isChecked() ? Pal.accent : Color.lightGray);
                        });

                    row.button(Icon.cancel, Styles.clearNoneTogglei, () -> {
                        if(!PolyFilter.blacklist.remove(key)){
                            PolyFilter.blacklist.add(key);
                            PolyFilter.whitelist.remove(key);
                        }
                    }).size(36f).padRight(8f).tooltip("@client.polyai.blacklist")
                        .update(b -> {
                            b.setChecked(PolyFilter.blacklist.contains(key));
                            b.getImage().setColor(b.isChecked() ? Color.scarlet : Color.lightGray);
                        });

                    String level = info.isCustomLevel ? "[gold]<" + info.customTag + ">" : "[lightgray]<" + info.level + ">";
                    if(info.isAfk) level = "[gray]<AFK> " + level;
                    row.add(p.coloredName() + " " + level).left().growX().wrap();
                }).growX().padTop(3f).row();
            }

            if(!any) t.add("@client.polyai.noplayers").color(Color.lightGray).left().padTop(4f).row();
        });
    }

    /** Accent title, accent line and a dark panel, same as the other GL dialogs. */
    private void section(Drawable icon, String title, Cons<Table> content){
        all.table(head -> {
            head.left();
            head.image(icon).color(Pal.accent).size(24f).padRight(8f);
            head.add(title).color(Pal.accent).left();
        }).width(width).padTop(16f).left().row();
        all.image().color(Pal.accent).height(3f).width(width).padTop(4f).padBottom(6f).row();

        all.table(Styles.grayPanel, t -> {
            t.left().top().margin(10f);
            t.defaults().left();
            content.get(t);
        }).width(width).row();
    }

    private void check(Table t, String name, boolean current, Boolc changed){
        t.check(name, current, changed).left().padTop(4f).row();
    }
}
