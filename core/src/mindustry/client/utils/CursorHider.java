package mindustry.client.utils;

import arc.*;
import arc.math.*;
import mindustry.entities.units.*;
import mindustry.gen.*;

import static mindustry.Vars.*;

/**
 * GL: "hidecursor" setting. While not shooting, other players (and mods that draw cursors) see a point just in front
 * of the unit instead of the real cursor. The real aim is still sent while shooting, since it aims the weapons.
 * As a client this changes the aim sent to the server ({@link mindustry.client.Main#floatEmbed}); as the host the cursor
 * and weapon aims written into the entity snapshots are swapped for the duration of the write.
 */
public class CursorHider{
    private static boolean swapped;
    private static float mouseX, mouseY;
    private static float[] mountAims = new float[0];

    public static boolean hiding(){
        return Core.settings.getBool("hidecursor", false) && player != null && !player.dead() && !player.shooting;
    }

    public static float hiddenX(Unit unit){
        return unit.x + Angles.trnsx(unit.rotation, unit.hitSize * 1.5f);
    }

    public static float hiddenY(Unit unit){
        return unit.y + Angles.trnsy(unit.rotation, unit.hitSize * 1.5f);
    }

    /** Called by the host right before writing entity snapshots. */
    public static void beginHostSnapshot(){
        if(swapped || headless || !hiding()) return;
        Unit unit = player.unit();
        float x = hiddenX(unit), y = hiddenY(unit);

        mouseX = player.mouseX;
        mouseY = player.mouseY;
        player.mouseX = x;
        player.mouseY = y;

        WeaponMount[] mounts = unit.mounts;
        if(mountAims.length < mounts.length * 2) mountAims = new float[mounts.length * 2];
        for(int i = 0; i < mounts.length; i++){
            mountAims[i * 2] = mounts[i].aimX;
            mountAims[i * 2 + 1] = mounts[i].aimY;
            mounts[i].aimX = x;
            mounts[i].aimY = y;
        }
        swapped = true;
    }

    /** Called by the host right after writing entity snapshots: puts the real cursor back. */
    public static void endHostSnapshot(){
        if(!swapped) return;
        swapped = false;
        player.mouseX = mouseX;
        player.mouseY = mouseY;

        if(player.dead()) return;
        WeaponMount[] mounts = player.unit().mounts;
        for(int i = 0; i < mounts.length && i * 2 + 1 < mountAims.length; i++){
            mounts[i].aimX = mountAims[i * 2];
            mounts[i].aimY = mountAims[i * 2 + 1];
        }
    }
}
