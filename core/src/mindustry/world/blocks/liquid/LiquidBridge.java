package mindustry.world.blocks.liquid;

import arc.graphics.g2d.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.meta.*;

import static mindustry.Vars.tilesize;
public class LiquidBridge extends ItemBridge{

    public static boolean drawLiquids = false;

    public static void setDrawLiquids(boolean draw){
        drawLiquids = draw;
    }

    public LiquidBridge(String name){
        super(name);
        hasItems = false;
        hasLiquids = true;
        outputsLiquid = true;
        canOverdrive = false;
        group = BlockGroup.liquids;
        envEnabled = Env.any;
    }

    public class LiquidBridgeBuild extends ItemBridgeBuild{
        // Таймер для визуального отображения жидкости (1 секунда = 60 тиков)
        public float visualLiquidTimer = 0f;

        @Override
        public void updateTile(){
            super.updateTile();

            // Если жидкость физически есть в мосту, постоянно продлеваем её таймер
            if(drawLiquids && liquids != null && liquids.currentAmount() > 0){
                visualLiquidTimer = Time.time + 60f;
            }
        }

        @Override
        public void updateTransport(Building other){
            if(warmup >= 0.25f){

                float transferred = moveLiquid(other, liquids.current()); // moveLiquid возвращает количество переданной жидкости
                if(transferred > 0.05f){
                    moved = true;

                    // Если жидкость передалась, продлеваем таймер, чтобы не моргало
                    if(drawLiquids){
                        visualLiquidTimer = Time.time + 60f;
                    }
                }
            }
        }

        @Override
        public void doDump(){
            dumpLiquid(liquids.current(), 1f);
        }

        // Переопределяем метод отрисовки, чтобы рисовать жидкость вместо предметов
        @Override
        public void drawStoredItems(){
            if(!drawLiquids) return;

            // Если время таймера еще не вышло, рисуем иконку жидкости
            if(visualLiquidTimer > Time.time && liquids.current() != null){
                Draw.z(Layer.blockOver);
                Draw.color();
                // Рисуем иконку жидкости по центру блока
                Draw.rect(liquids.current().fullIcon, x, y, tilesize / 1.5f, tilesize / 1.5f);
                Draw.reset();
            }
        }
    }
}