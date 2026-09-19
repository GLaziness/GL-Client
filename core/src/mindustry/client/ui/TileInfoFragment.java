package mindustry.client.ui;

import arc.*;
import arc.graphics.g2d.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.*;
import mindustry.client.antigrief.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.world.*;

import java.util.concurrent.atomic.*;

public class TileInfoFragment extends Table {
    public static int[] lastPos = new int[]{-1};

    private static final int displayLogs = 7; // Number of logs to display

    public TileInfoFragment() { // FINISHME: Rewrite this in a non horrible way in kotlin, this is horrendous
        setBackground(Tex.wavepane);
        marginRight(6);
        Image img = new Image();
        add(img).size(63).padRight(6);
        Label label = new Label("");
        add(label).height(126);
        visible(() -> Core.settings.getBool("tilehud"));
        var builder = new StringBuilder();
        update(() -> {
            Tile hovered = Vars.control.input.cursorTile();
            if (hovered == null) {
                img.setDrawable(Icon.none);
                label.setText("");
                return;
            } else if (hovered.block() == null) {
                img.setDrawable(hovered.floor().uiIcon);
                label.setText("");
                return;
            } else if (lastPos[0] == hovered.pos()) {
                return;
            }
            lastPos[0] = hovered.pos();

            TextureRegion icon = hovered.block().uiIcon;
            img.setDrawable(icon.found() ? icon : hovered.floor().uiIcon);
            var record = TileRecords.INSTANCE.get(hovered);
            if (record == null) return;
            var pending = NetworkTileLogs.INSTANCE.logsPending(hovered);
            var logs = record.lastLogs(displayLogs);

            builder.setLength(0);
            for (var item : logs) builder.append(compact(item, hovered.block())).append(" [lightgray]").append(UI.formatMinutesFromMillis(Time.timeSinceMillis(item.getTime().toEpochMilli()))).append("[]\n");
            if (pending && logs.size() < displayLogs) builder.append((arc.Core.bundle.get("gl.ui.tileinfo.1") + "\n")); // Append if the logs aren't already full since these are always older than client logs FINISHME: Bundle
            label.setText(builder.length() == 0 ? "" : builder.substring(0, builder.length() - 1)); // This is awful
        });
    }

    /**
     * GL: the same log line with the block (or unit) name replaced by its icon, so the panel stays narrow.
     * The block under the cursor is already shown big on the left, so its name is dropped from the line.
     */
    private static String compact(TileLog item, Block hoveredBlock){
        String s = item.toShortString();
        if (item instanceof AbstractTileLog log) {
            Block block = log.getBlock();
            if (block == hoveredBlock) {
                String icon = Fonts.getUnicodeStr(block.name);
                if (!icon.isEmpty()) s = s.replace(icon, "");
                return s.replace(block.localizedName, "").replaceAll(" {2,}", " ").trim();
            }
            return iconFor(s, block.localizedName, block.name);
        }
        if (item instanceof UnitDestroyedLog log) return iconFor(s, log.getUnitType().localizedName, log.getUnitType().name);
        return s;
    }

    private static String iconFor(String s, String localized, String name) {
        String icon = Fonts.getUnicodeStr(name);
        return icon == null || icon.isEmpty() ? s : s.replace(localized, icon);
    }
}
