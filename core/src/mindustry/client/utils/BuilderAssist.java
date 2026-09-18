package mindustry.client.utils;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.*;

import static mindustry.Vars.*;

/**
 * GL: side panel switch that sends the team's novas and velas to help players build (the vanilla "assist" command).
 * New units get the command too; a unit whose command someone changes by hand is left alone.
 * Switching off gives the units back the command they had before.
 */
public class BuilderAssist{
    public static boolean enabled = false;
    /** Which of the two types help (right click on the panel button). */
    public static boolean nova = Core.settings.getBool("builderassist-nova", true);
    public static boolean vela = Core.settings.getBool("builderassist-vela", true);
    /** Units we switched to assist, with the command they had before. */
    private static final IntMap<UnitCommand> previous = new IntMap<>();
    /** Units someone switched away from assist by hand. */
    private static final IntSet manual = new IntSet();
    private static final Interval timer = new Interval();

    public static void init(){
        Events.on(WorldLoadEvent.class, e -> {
            previous.clear();
            manual.clear();
        });
        Events.on(UnitDestroyEvent.class, e -> {
            if(e.unit == null) return;
            previous.remove(e.unit.id);
            manual.remove(e.unit.id);
        });
        Events.run(Trigger.update, () -> {
            if(!state.isGame() || player == null) return;
            if(enabled){
                if(timer.get(120f)){
                    release(false); // types switched off in the settings
                    update();
                }
            }else if(previous.size > 0){
                release(true);
            }
        });
    }

    public static void toggle(){
        enabled = !enabled;
        timer.reset(0, 120f); // apply right away
    }

    public static void setType(UnitType type, boolean value){
        if(type == UnitTypes.nova) nova = value;
        else vela = value;
        Core.settings.put("builderassist-" + type.name, value);
        timer.reset(0, 120f);
    }

    private static boolean allowed(UnitType type){
        return type == UnitTypes.nova ? nova : type == UnitTypes.vela && vela;
    }

    private static boolean managed(Unit u){
        return u.team == player.team() && u.isCommandable() && allowed(u.type);
    }

    private static void update(){
        IntSeq send = new IntSeq();
        for(Unit u : Groups.unit){
            if(!managed(u) || manual.contains(u.id) || !(u.controller() instanceof CommandAI ai)) continue;

            if(previous.containsKey(u.id)){
                if(ai.command != UnitCommand.assistCommand){
                    // changed by hand: stop managing it
                    previous.remove(u.id);
                    manual.add(u.id);
                }
                continue;
            }

            if(ai.command != UnitCommand.assistCommand && u.type.allowCommand(u, UnitCommand.assistCommand)){
                previous.put(u.id, ai.command == null ? UnitCommand.moveCommand : ai.command);
                send.add(u.id);
            }
        }
        if(send.size > 0) Call.setUnitCommand(player, send.toArray(), UnitCommand.assistCommand);
    }

    /** Gives units their previous command back: all of them, or only those of types switched off. */
    private static void release(boolean all){
        ObjectMap<UnitCommand, IntSeq> back = new ObjectMap<>();
        IntSeq done = new IntSeq();
        for(var e : previous.entries()){
            Unit u = Groups.unit.getByID(e.key);
            if(u != null && !all && allowed(u.type)) continue;
            done.add(e.key);
            if(u == null || !(u.controller() instanceof CommandAI ai) || ai.command != UnitCommand.assistCommand) continue;
            back.get(e.value, IntSeq::new).add(e.key);
        }
        for(var e : back.entries()){
            Call.setUnitCommand(player, e.value.toArray(), e.key);
        }
        for(int i = 0; i < done.size; i++) previous.remove(done.get(i));
        if(all) manual.clear();
    }

    /** Right click on the panel button: which types help. */
    public static void showSettings(){
        var dialog = new mindustry.ui.dialogs.BaseDialog("@fdpanel.novaassist");
        dialog.addCloseButton();
        dialog.cont.defaults().left().pad(6f);
        for(UnitType type : new UnitType[]{UnitTypes.nova, UnitTypes.vela}){
            dialog.cont.check("", allowed(type), b -> setType(type, b)).get().add(new arc.scene.ui.Image(type.uiIcon)).size(40f).padLeft(8f);
            dialog.cont.add(type.localizedName).padLeft(8f).row();
        }
        dialog.show();
    }
}
