package mindustry.client.ui;

import arc.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.event.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.type.*;

import static mindustry.Vars.*;

/** GL: five quick clicks on the GL logo in the main menu: the logo spins and units rain over the menu. */
public class GlEasterEgg{
    private static final int clicksNeeded = 5, phrases = 6;
    private static final float clickWindow = 1.5f, rainTime = 6f;

    /** Counts the clicks on {@code logo} and starts the fun on the fifth one. */
    public static void attach(Element logo){
        int[] clicks = {0};
        long[] last = {0L};
        logo.touchable = Touchable.enabled;
        logo.clicked(() -> {
            if(Time.timeSinceMillis(last[0]) > clickWindow * 1000f) clicks[0] = 0;
            last[0] = Time.millis();
            if(++clicks[0] < clicksNeeded) return;
            clicks[0] = 0;
            play(logo);
        });
    }

    private static void play(Element logo){
        logo.setOrigin(Align.center);
        logo.clearActions();
        logo.setRotation(0f);
        logo.actions(Actions.parallel(
            Actions.rotateBy(-720f, 1.2f, Interp.pow3Out),
            Actions.sequence(Actions.scaleTo(1.4f, 1.4f, 0.3f, Interp.pow2Out), Actions.scaleTo(1f, 1f, 0.9f, Interp.bounceOut))
        ));
        Sounds.uiUnlock.play();
        showPhrase(Core.bundle.get("gl.ui.easter." + Mathf.random(1, phrases)));
        Core.scene.add(new UnitRain());
    }

    /** The game's toasts remove themselves in the main menu, so the phrase gets its own label. */
    private static void showPhrase(String text){
        Element old = Core.scene.find("gl-easter-phrase");
        if(old != null) old.remove();
        Table table = new Table();
        table.name = "gl-easter-phrase";
        table.touchable = Touchable.disabled;
        table.setFillParent(true);
        // at the bottom, above the menu buttons, so it does not cover the logo
        table.bottom().table(mindustry.ui.Styles.black6, t -> t.margin(8f).add(text).style(mindustry.ui.Styles.outlineLabel).fontScale(1.3f)).padBottom(90f);
        table.color.a = 0f;
        table.actions(Actions.fadeIn(0.3f), Actions.delay(4f), Actions.fadeOut(0.8f, Interp.fade), Actions.remove());
        Core.scene.add(table);
    }

    private static class UnitRain extends Element{
        private final Seq<TextureRegion> icons = new Seq<>();
        private final FloatSeq drops = new FloatSeq(); // x, y, speed, rotation, spin, size per drop
        private float time;

        UnitRain(){
            touchable = Touchable.disabled;
            for(UnitType type : content.units()){
                if(!type.isHidden() && type.uiIcon != null && type.uiIcon.found()) icons.add(type.uiIcon);
            }
            if(icons.isEmpty()) return;
            for(int i = 0; i < 70; i++){
                drops.addAll(Mathf.random(Core.graphics.getWidth()), Core.graphics.getHeight() + Mathf.random(Core.graphics.getHeight()),
                    Mathf.random(2f, 6f), Mathf.random(360f), Mathf.range(4f), Mathf.random(24f, 56f), Mathf.random(icons.size - 1));
            }
        }

        @Override
        public void act(float delta){
            super.act(delta);
            time += Time.delta / 60f;
            if(time > rainTime || icons.isEmpty()) remove();
            setBounds(0, 0, Core.graphics.getWidth(), Core.graphics.getHeight());
            toFront();
        }

        @Override
        public void draw(){
            float alpha = Mathf.clamp((rainTime - time) / 1.5f) * parentAlpha;
            for(int i = 0; i < drops.size; i += 7){
                float[] d = drops.items;
                d[i + 1] -= d[i + 2] * Time.delta;
                d[i + 3] += d[i + 4] * Time.delta;
                if(d[i + 1] < -60f) d[i + 1] = Core.graphics.getHeight() + 60f;
                float size = Scl.scl(d[i + 5]);
                Draw.alpha(alpha);
                Draw.rect(icons.get((int)d[i + 6]), d[i], d[i + 1], size, size, d[i + 3]);
            }
            Draw.reset();
        }
    }
}
