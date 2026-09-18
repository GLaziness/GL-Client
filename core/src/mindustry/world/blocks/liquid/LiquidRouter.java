package mindustry.world.blocks.liquid;

import arc.graphics.g2d.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import static mindustry.Vars.tilesize;

public class LiquidRouter extends LiquidBlock{
    public float liquidPadding = 0f;

    public LiquidRouter(String name){
        super(name);
        solid = true;
        noUpdateDisabled = true;
        canOverdrive = false;
        floating = true;
    }

    @Override
    public TextureRegion[] icons(){
        return new TextureRegion[]{bottomRegion, region};
    }

    public class LiquidRouterBuild extends LiquidBuild{
        // Таймер для визуального отображения жидкости (1 секунда = 60 тиков)
        public float visualLiquidTimer = 0f;

        @Override
        public void updateTile(){
            super.updateTile();
            dumpLiquid(liquids.current());

            // Если жидкость физически есть, постоянно продлеваем её таймер
            if(LiquidBridge.drawLiquids && liquids != null && liquids.currentAmount() > 0.001f){
                visualLiquidTimer = Time.time + 60f;
            }
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount){
            super.handleLiquid(source, liquid, amount);

            // Если жидкость входит, продлеваем таймер, чтобы не моргало при транзите
            if(LiquidBridge.drawLiquids){
                visualLiquidTimer = Time.time + 60f;
            }
        }

        @Override
        public void draw(){
            Draw.rect(bottomRegion, x, y);

            if(liquids.currentAmount() > 0.001f){
                drawTiledFrames(size, x, y, liquidPadding, liquids.current(), liquids.currentAmount() / liquidCapacity);
            }

            Draw.rect(region, x, y);

            // Отрисовка иконки жидкости с задержкой 1 секунду
            if(LiquidBridge.drawLiquids && visualLiquidTimer > Time.time && liquids.current() != null){
                Draw.z(Layer.blockOver);
                Draw.color();
                // Рисуем иконку жидкости по центру блока (немного меньше тайла, чтобы не перекрывать спрайт)
                Draw.rect(liquids.current().fullIcon, x, y, tilesize / 2.5f, tilesize / 2.5f);
                Draw.reset();
            }
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid){
            return (liquids.current() == liquid || liquids.currentAmount() < 0.2f);
        }
    }
}