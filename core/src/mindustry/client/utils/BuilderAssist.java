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

    private static final UnitType[] types = {UnitTypes.nova, UnitTypes.vela};
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
                if(timer.get(120f)) update();
            }else if(previous.size > 0){
                release();
            }
        });
    }

    public static void toggle(){
        enabled = !enabled;
        timer.reset(0, 120f); // apply right away
    }

    private static boolean managed(Unit u){
        if(u.team != player.team() || !u.isCommandable()) return false;
        for(UnitType type : types){
            if(u.type == type) return true;
        }
        return false;
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

    private static void release(){
        ObjectMap<UnitCommand, IntSeq> back = new ObjectMap<>();
        for(var e : previous.entries()){
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || !(u.controller() instanceof CommandAI ai) || ai.command != UnitCommand.assistCommand) continue;
            back.get(e.value, IntSeq::new).add(e.key);
        }
        for(var e : back.entries()){
            Call.setUnitCommand(player, e.value.toArray(), e.key);
        }
        previous.clear();
        manual.clear();
    }
}
