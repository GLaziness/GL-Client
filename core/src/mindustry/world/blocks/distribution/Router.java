package mindustry.world.blocks.distribution;

import arc.graphics.g2d.*;
import arc.math.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

public class Router extends Block{
    public float speed = 8f;

    public Router(String name){
        super(name);
        solid = false;
        underBullets = true;
        update = true;
        hasItems = true;
        itemCapacity = 1;
        group = BlockGroup.transportation;
        unloadable = false;
        noUpdateDisabled = true;
        // ВЫКЛЮЧАЕМ КЭШИРОВАНИЕ, чтобы динамически рисовать предметы
        drawCached = false;
        drawDynamic = true;
    }

    public class RouterBuild extends Building implements ControlBlock{
        protected byte[] cycles = new byte[Vars.content.items().size];
        public Item lastItem;
        public Tile lastInput;
        public float time;
        public @Nullable BlockUnitc unit;

        // Массив для хранения времени исчезновения каждого предмета (60 тиков = 1 секунда)
        public float[] visualTimers = null;

        private float[] getVisualTimers(){
            if(visualTimers == null || visualTimers.length != content.items().size){
                visualTimers = new float[content.items().size];
            }
            return visualTimers;
        }

        @Override
        public Unit unit(){
            if(unit == null){
                unit = (BlockUnitc)UnitTypes.block.create(team);
                unit.tile(this);
            }
            return (Unit)unit;
        }

        @Override
        public boolean canControl(){
            return true;
        }

        @Override
        public boolean shouldAutoTarget(){
            return false;
        }

        @Override
        public void updateTile(){
            if(lastItem == null && items.any()){
                lastItem = items.first();
            }

            // Пока предмет внутри роутера, продлеваем его таймер
            if(ItemBridge.drawItems && lastItem != null){
                getVisualTimers()[lastItem.id] = Time.time + 60f;
            }

            if(lastItem != null){
                time += 1f / speed * delta();
                Building target = getTileTarget(lastItem, lastInput, false);

                if(target != null && (time >= 1f || !(target.block instanceof Router || target.block.instantTransfer))){
                    getTileTarget(lastItem, lastInput, true);
                    target.handleItem(this, lastItem);
                    items.remove(lastItem, 1);
                    lastItem = null;
                }
            }
        }

        @Override
        public int acceptStack(Item item, int amount, Teamc source){
            return 0;
        }

        @Override
        public boolean acceptItem(Building source, Item item){
            return team == source.team && lastItem == null && items.total() == 0;
        }

        @Override
        public void handleItem(Building source, Item item){
            items.add(item, 1);
            lastItem = item;
            time = 0f;
            lastInput = source.tile;

            // Обновляем таймер, когда предмет ВХОДИТ в роутер
            if(ItemBridge.drawItems){
                getVisualTimers()[item.id] = Time.time + 60f;
            }
        }

        @Override
        public int removeStack(Item item, int amount){
            int result = super.removeStack(item, amount);
            if(result != 0 && item == lastItem){
                lastItem = null;
            }
            return result;
        }

        public Building getTileTarget(Item item, Tile from, boolean set){
            if(unit != null && isControlled()){
                unit.health(health);
                unit.ammo((items.total() > 0 ? 1f : 0f));
                unit.team(team);
                unit.set(x, y);

                int angle = Mathf.mod((int)((angleTo(unit.aimX(), unit.aimY()) + 45) / 90), 4);

                if(unit.isShooting()){
                    Building other = nearby(angle);
                    if(other != null && other.acceptItem(this, item)){
                        return other;
                    }
                }

                return null;
            }

            //keep track of target offsets per-item to fix https://github.com/Anuken/Mindustry/issues/12471
            int id = item.id;
            int counter = cycles[id];
            for(int i = 0; i < proximity.size; i++){
                Building other = proximity.get((i + counter) % proximity.size);
                if(set) cycles[id] = ((byte)((cycles[id] + 1) % proximity.size));
                if(other.tile == from && from.block() == Blocks.overflowGate) continue;
                if(other.acceptItem(this, item)){
                    return other;
                }
            }
            return null;
        }

        @Override
        public void draw(){
            super.draw();

            if(!ItemBridge.drawItems) return;

            float[] timers = getVisualTimers();

            // Считаем количество активных (видимых) предметов
            int count = 0;
            for(int i = 0; i < timers.length; i++){
                if(timers[i] > Time.time) count++;
            }

            if(count == 0) return;

            Draw.z(Layer.blockOver);
            Draw.color();

            // Если предмет только один, рисуем его по центру (чуть меньше тайла, чтобы не перекрывать рамку)
            if(count == 1){
                for(int i = 0; i < timers.length; i++){
                    if(timers[i] > Time.time){
                        Item item = content.item(i);
                        Draw.rect(item.fullIcon, x, y, tilesize / 2f, tilesize / 2f);
                        break;
                    }
                }
            } else {
                // Если предметов несколько, располагаем их сеткой 3x3
                int index = 0;
                int maxItems = 9;
                int columns = 3;
                float space = tilesize / 3.2f;
                float size = tilesize / 3.5f;

                for(int i = 0; i < timers.length; i++){
                    if(timers[i] > Time.time){
                        Item item = content.item(i);
                        float col = index % columns - 1;
                        float row = index / columns - 1;
                        float ix = x + col * space;
                        float iy = y + row * space;

                        Draw.rect(item.fullIcon, ix, iy, size, size);
                        index++;
                        if(index >= maxItems) break;
                    }
                }
            }
            Draw.reset();
        }
    }
}