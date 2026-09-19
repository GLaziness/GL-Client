package mindustry.client.ui;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.entities.bullet.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;

import static mindustry.Vars.*;

/**
 * GL: the PEW PEW cursedness level of the main menu. Armed units fly in, turn to the cursor, fire one shot at it and
 * fly away; a shot that reaches the cursor cracks the screen like glass. Then the next group comes, of a different size.
 */
public class MenuShooters extends Element{
    private final Seq<Shooter> shooters = new Seq<>();
    private final Seq<Shot> shots = new Seq<>();
    private final Seq<Crack> cracks = new Seq<>();
    private final Seq<Shard> shards = new Seq<>();
    private @Nullable Seq<UnitType> armed;
    private float nextWave = 60f;

    public MenuShooters(){
        touchable = Touchable.disabled;
        setFillParent(true);
    }

    public static boolean active(){
        return CursednessLevel.get() == CursednessLevel.PEWPEW;
    }

    @Override
    public void act(float delta){
        super.act(delta);
        if(!active() || !state.isMenu()){
            shooters.clear();
            shots.clear();
            cracks.clear();
            shards.clear();
            return;
        }

        float d = Time.delta;
        Vec2 mouse = Core.input.mouse();

        if(shooters.isEmpty() && (nextWave -= d) <= 0f) spawnWave();

        for(Shooter s : shooters) s.update(d, mouse);
        shooters.removeAll(s -> s.done);

        for(Shot s : shots){
            float step = s.speed * d;
            if(Mathf.dst(s.x, s.y, s.tx, s.ty) <= step){
                impact(s.tx, s.ty, s.color);
                s.done = true;
            }else{
                Tmp.v1.set(s.tx - s.x, s.ty - s.y).setLength(step);
                s.x += Tmp.v1.x;
                s.y += Tmp.v1.y;
            }
        }
        shots.removeAll(s -> s.done);

        for(Crack c : cracks) c.life -= d;
        cracks.removeAll(c -> c.life <= 0f);

        for(Shard s : shards){
            s.x += s.vx * d;
            s.y += s.vy * d;
            s.vy -= Scl.scl(0.25f) * d;
            s.rot += s.spin * d;
            s.life -= d;
        }
        shards.removeAll(s -> s.life <= 0f);
    }

    private void spawnWave(){
        if(armed == null){
            armed = content.units().select(u -> !u.isHidden() && u.fullIcon != null && u.fullIcon.found()
                && u.weapons.contains(w -> w.bullet != null && w.bullet.damage > 0f && !w.bullet.killShooter));
        }
        if(armed.isEmpty()) return;
        int count = Mathf.random(1, 6);
        for(int i = 0; i < count; i++) shooters.add(new Shooter(armed.random(), i * Mathf.random(10f, 40f)));
        nextWave = Mathf.random(40f, 150f);
    }

    private void impact(float x, float y, Color color){
        Sounds.explosionDull.play(0.4f);
        cracks.add(new Crack(x, y));
        for(int i = 0; i < 14; i++){
            Shard s = new Shard();
            s.x = x;
            s.y = y;
            float speed = Scl.scl(Mathf.random(1.5f, 6f)), angle = Mathf.random(360f);
            s.vx = Angles.trnsx(angle, speed);
            s.vy = Angles.trnsy(angle, speed) + Scl.scl(2f);
            s.rot = Mathf.random(360f);
            s.spin = Mathf.range(12f);
            s.size = Scl.scl(Mathf.random(3f, 8f));
            s.life = s.maxLife = Mathf.random(35f, 70f);
            s.color = Mathf.chance(0.5) ? color : Color.white;
            shards.add(s);
        }
    }

    @Override
    public void draw(){
        if(!active()) return;
        float a = parentAlpha;

        for(Crack c : cracks){
            float alpha = Mathf.clamp(c.life / 60f) * a;
            Lines.stroke(Scl.scl(1.6f));
            Draw.color(1f, 1f, 1f, 0.85f * alpha);
            for(int i = 0; i + 3 < c.points.size; i += 4){
                Lines.line(c.points.get(i), c.points.get(i + 1), c.points.get(i + 2), c.points.get(i + 3));
            }
            Draw.color(1f, 1f, 1f, 0.35f * alpha);
            Lines.circle(c.x, c.y, Scl.scl(10f + (1f - c.life / c.maxLife) * 18f));
        }

        for(Shard s : shards){
            Draw.color(s.color, Mathf.clamp(s.life / s.maxLife) * a);
            Fill.poly(s.x, s.y, 3, s.size, s.rot);
        }

        for(Shot s : shots){
            float rot = Angles.angle(s.x, s.y, s.tx, s.ty) - 90f;
            Draw.color(s.back, a);
            Draw.rect("bullet-back", s.x, s.y, s.w, s.h, rot);
            Draw.color(s.color, a);
            Draw.rect("bullet", s.x, s.y, s.w, s.h, rot);
        }

        for(Shooter s : shooters){
            if(s.wait > 0f) continue;
            TextureRegion icon = s.type.fullIcon;
            float w = s.size, h = s.size * icon.height / icon.width;
            Draw.color(0f, 0f, 0f, 0.35f * a);
            Draw.rect(icon, s.x - Scl.scl(10f), s.y - Scl.scl(12f), w, h, s.rot - 90f);
            Draw.color(1f, 1f, 1f, a);
            Draw.rect(icon, s.x, s.y, w, h, s.rot - 90f);
        }
        Draw.reset();
    }

    private class Shooter{
        final UnitType type;
        final Weapon weapon;
        final float size, speed;
        float x, y, rot, wait, timer;
        final Vec2 stop = new Vec2(), exit = new Vec2();
        /** 0 fly in, 1 aim, 2 after the shot, 3 fly away */
        int phase;
        boolean done;

        Shooter(UnitType type, float wait){
            this.type = type;
            this.wait = wait;
            weapon = type.weapons.find(w -> w.bullet != null && w.bullet.damage > 0f && !w.bullet.killShooter);
            float sw = Core.scene.getWidth(), sh = Core.scene.getHeight();
            size = Scl.scl(Mathf.clamp(type.hitSize * 3.5f, 36f, 200f));
            speed = Scl.scl(Mathf.random(4f, 8f));

            float margin = size + Scl.scl(40f);
            float angle = Mathf.random(360f);
            Tmp.v1.trns(angle, Math.max(sw, sh));
            x = Mathf.clamp(sw / 2f + Tmp.v1.x, -margin, sw + margin);
            y = Mathf.clamp(sh / 2f + Tmp.v1.y, -margin, sh + margin);
            stop.set(Mathf.random(sw * 0.1f, sw * 0.9f), Mathf.random(sh * 0.15f, sh * 0.85f));
            rot = Angles.angle(x, y, stop.x, stop.y);
        }

        void update(float d, Vec2 mouse){
            if(wait > 0f){
                wait -= d;
                return;
            }
            switch(phase){
                case 0 -> {
                    rot = Angles.moveToward(rot, Angles.angle(x, y, stop.x, stop.y), 10f * d);
                    if(moveTo(stop, d, speed)) phase = 1;
                }
                case 1 -> {
                    float target = Angles.angle(x, y, mouse.x, mouse.y);
                    rot = Angles.moveToward(rot, target, 6f * d);
                    if(Angles.within(rot, target, 2f)){
                        fire(mouse);
                        phase = 2;
                        timer = Mathf.random(15f, 35f);
                    }
                }
                case 2 -> {
                    if((timer -= d) <= 0f){
                        float angle = rot + 180f + Mathf.range(70f);
                        float far = Math.max(Core.scene.getWidth(), Core.scene.getHeight()) + size * 2f;
                        exit.set(x, y).add(Tmp.v1.trns(angle, far));
                        phase = 3;
                    }
                }
                case 3 -> {
                    rot = Angles.moveToward(rot, Angles.angle(x, y, exit.x, exit.y), 8f * d);
                    x += Angles.trnsx(rot, speed * 1.4f * d);
                    y += Angles.trnsy(rot, speed * 1.4f * d);
                    float m = size * 2f;
                    if(x < -m || y < -m || x > Core.scene.getWidth() + m || y > Core.scene.getHeight() + m) done = true;
                }
            }
        }

        boolean moveTo(Vec2 to, float d, float speed){
            float step = speed * d, dst = Mathf.dst(x, y, to.x, to.y);
            if(dst <= step){
                x = to.x;
                y = to.y;
                return true;
            }
            // slow down near the stop point
            step *= Mathf.clamp(dst / Scl.scl(120f), 0.25f, 1f);
            Tmp.v1.set(to.x - x, to.y - y).setLength(step);
            x += Tmp.v1.x;
            y += Tmp.v1.y;
            return false;
        }

        void fire(Vec2 mouse){
            Shot s = new Shot();
            s.x = x + Angles.trnsx(rot, size * 0.45f);
            s.y = y + Angles.trnsy(rot, size * 0.45f);
            s.tx = mouse.x;
            s.ty = mouse.y;
            s.speed = Scl.scl(14f);
            float scale = Mathf.clamp(size / Scl.scl(60f), 0.8f, 2.2f);
            s.w = Scl.scl(9f) * scale;
            s.h = Scl.scl(14f) * scale;
            if(weapon != null && weapon.bullet instanceof BasicBulletType b){
                s.color = b.frontColor;
                s.back = b.backColor;
            }else{
                s.color = Pal.bulletYellow;
                s.back = Pal.bulletYellowBack;
            }
            shots.add(s);
            if(weapon != null && weapon.shootSound != null && weapon.shootSound != Sounds.none) weapon.shootSound.play(0.5f);
            else Sounds.shoot.play(0.5f);
        }
    }

    private static class Shot{
        float x, y, tx, ty, speed, w, h;
        Color color = Color.white, back = Color.white;
        boolean done;
    }

    private static class Crack{
        final float x, y, maxLife = 110f;
        float life = maxLife;
        final FloatSeq points = new FloatSeq();

        Crack(float x, float y){
            this.x = x;
            this.y = y;
            int rays = Mathf.random(6, 10);
            for(int i = 0; i < rays; i++){
                float angle = i * 360f / rays + Mathf.range(15f), px = x, py = y;
                int segments = Mathf.random(2, 4);
                for(int j = 0; j < segments; j++){
                    angle += Mathf.range(25f);
                    float len = Scl.scl(Mathf.random(10f, 26f));
                    float nx = px + Angles.trnsx(angle, len), ny = py + Angles.trnsy(angle, len);
                    points.addAll(px, py, nx, ny);
                    // small side branch
                    if(Mathf.chance(0.35)){
                        float side = angle + Mathf.sign(Mathf.chance(0.5)) * Mathf.random(30f, 60f);
                        points.addAll(nx, ny, nx + Angles.trnsx(side, len * 0.5f), ny + Angles.trnsy(side, len * 0.5f));
                    }
                    px = nx;
                    py = ny;
                }
            }
        }
    }

    private static class Shard{
        float x, y, vx, vy, rot, spin, size, life, maxLife;
        Color color;
    }
}
