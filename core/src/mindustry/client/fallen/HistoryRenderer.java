package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.ui.layout.Scl;
import arc.struct.Queue;
import arc.util.*;
import mindustry.client.ui.PlayerBlockListFragment;
import mindustry.game.EventType;
import mindustry.graphics.*;
import mindustry.input.Binding;
import mindustry.ui.Fonts;
import mindustry.world.*;
import java.text.SimpleDateFormat;
import java.util.Date;

import static arc.Core.scene;
import static mindustry.Vars.*;

public class HistoryRenderer {
    public static boolean showBlocks = false;
    public static boolean showDeaths = false;
    public static boolean showControlDeaths = false;


    private static final GlyphLayout layout = new GlyphLayout();
    private static final StringBuilder sb = new StringBuilder();
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
    private static final Date date = new Date();
    private static float brokenFade = 0f;

    public static void init() {
        Events.run(EventType.Trigger.draw, () -> {
            if (!state.isGame()) return;
            draw();
        });

        Events.run(EventType.Trigger.update, () -> {
            if (!state.isGame() || scene.getKeyboardFocus() != null) return;

            if (Core.input.keyTap(Binding.block_show_plans)) {
                if (PlayerBlockListFragment.name_for_plans != null) {
                    PlayerBlockListFragment.name_for_plans = null;
                } else {
                    showBlocks = !showBlocks;
                }
            }

            if (Core.input.keyTap(Binding.death_show_plans)) {
                if (Core.input.shift()) {
                    showControlDeaths = !showControlDeaths;
                } else {
                    showDeaths = !showDeaths;
                }
            }
        });
    }

    private static void draw() {
        brokenFade = Mathf.lerpDelta(brokenFade, 1f, 0.1f);

        String filterName = PlayerBlockListFragment.name_for_plans;
        String cleanFilter = filterName != null ? Strings.stripColors(filterName) : null;

        if (showBlocks || cleanFilter != null) {
            drawBlocks(cleanFilter);
            drawConfigs(cleanFilter);
        }

        if (showDeaths || cleanFilter != null) {
            drawUnitDeaths(cleanFilter);
        }
    }

    private static void drawBlocks(String filter) {
        float alphaMult = Core.settings.getInt("fadedblockallplayers", 5) / 10f;

        for (ActionsHistory.BlockPlayerPlan plan : ActionsHistory.blocksplayersplans) {
            if (plan.lastacs == null) continue;

            if (filter != null) {
                if (!Strings.stripColors(plan.lastacs).toLowerCase().contains(filter.toLowerCase())) continue;
            } else if (!showBlocks) continue;

            Block b = content.block(plan.block);
            if (b == null) continue;

            float px = plan.x * tilesize + b.offset;
            float py = plan.y * tilesize + b.offset;
            if (!Core.camera.bounds(Tmp.r1).grow(tilesize * 2f).contains(px, py)) continue;

            Draw.z(Layer.overlayUI);
            Draw.alpha(0.5f * brokenFade * alphaMult);

            Color mix = plan.wasbreaking ? Color.red : Color.green;
            Draw.mixcol(mix, 0.4f + Mathf.absin(Time.globalTime, 6f, 0.2f));

            Draw.rect(b.fullIcon, px, py, b.rotate ? plan.rotation * 90 : 0);
            Draw.reset();
        }
    }

    private static void drawConfigs(String filter) {
        float alphaMult = Core.settings.getInt("fadedblockallplayers", 5) / 10f;

        for (ActionsHistory.BlockConfigPlayerPlan plan : ActionsHistory.blockconfplayersplans) {
            if (plan.lastacs == null) continue;

            String cleanPlanName = Strings.stripColors(plan.lastacs);
            if (filter != null) {
                if (!cleanPlanName.toLowerCase().contains(filter.toLowerCase())) continue;
            } else if (!showBlocks) continue;

            Block b = content.block(plan.block);
            if (b == null) continue;

            float px = plan.x * tilesize + b.offset;
            float py = plan.y * tilesize + b.offset;

            if (!Core.camera.bounds(Tmp.r1).grow(tilesize * 2f).contains(px, py)) continue;

            Draw.z(Layer.overlayUI);
            Draw.alpha(0.45f * brokenFade * alphaMult);

            Draw.mixcol(Color.blue, 0.5f + Mathf.absin(Time.globalTime, 6f, 0.2f));

            Draw.rect(b.fullIcon, px, py);
            Draw.reset();
        }
    }

    private static void drawUnitDeaths(String filter) {
        if (filter == null && !showDeaths && !showControlDeaths) return;

        Font font = Fonts.outline;
        float oldScaleX = font.getData().scaleX;
        float oldScaleY = font.getData().scaleY;
        font.getData().setScale(0.25f / Scl.scl(1.0f));

        long timeLimit = (long)Core.settings.getInt("timeoffsetseccontr", 0) * 1000L;
        long now = System.currentTimeMillis();

        if (showDeaths || filter != null) {
            drawDeathQueue(ActionsHistory.deathunitsplan, filter, showDeaths, timeLimit, now, font);
        }

        if (showControlDeaths || filter != null) {
            drawDeathQueue(ActionsHistory.deathunitscontrolplan, filter, showControlDeaths, timeLimit, now, font);
        }

        font.getData().setScale(oldScaleX, oldScaleY);
        Draw.reset();
    }

    private static void drawDeathQueue(Queue<? extends ActionsHistory.UnitsKilledByPlayers> queue, String filter, boolean globalShow, long limit, long now, Font font) {
        for (ActionsHistory.UnitsKilledByPlayers kunit : queue) {
            if (kunit == null || kunit.unitType == null) continue;

            String cleanKName = Strings.stripColors(kunit.playerName);

            if (filter != null) {
                if (!cleanKName.contains(filter.toLowerCase())) continue;
            } else {
                if (!globalShow) continue;
            }

            if (limit > 0 && (now - kunit.timestamp) > limit) continue;
            if (!Core.camera.bounds(Tmp.r1).grow(tilesize * 2f).contains(kunit.x, kunit.y)) continue;

            Draw.z(Layer.overlayUI);
            Draw.alpha(0.8f * brokenFade);
            Draw.mixcol(Color.red, 0.3f + Mathf.absin(Time.globalTime, 6f, 0.2f));

            Draw.rect(kunit.unitType.fullIcon, kunit.x, kunit.y, kunit.unitType.hitSize * 1.5f, kunit.unitType.hitSize * 1.5f);

            date.setTime(kunit.timestamp);
            String timeStr = dateFormat.format(date);

            font.setColor(Color.white);
            layout.setText(font, kunit.playerName);
            font.draw(kunit.playerName, kunit.x - layout.width / 2f, kunit.y + (kunit.unitType.hitSize) + 10);

            layout.setText(font, timeStr);
            font.draw(timeStr, kunit.x - layout.width / 2f, kunit.y - (kunit.unitType.hitSize));
        }
        Draw.reset();
    }
}