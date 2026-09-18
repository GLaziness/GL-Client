package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.scene.*;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.consumers.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.defense.*;
import mindustry.game.EventType;
import mindustry.content.*;

import static arc.Core.scene;
import static mindustry.Vars.*;

public class ProductionAnalyzerFrag extends Table {
    private boolean visible = false;
    private Table contentTable = new Table();
    private Table summaryTable = new Table();
    private final ObjectMap<Block, BlockStat> statsMap = new ObjectMap<>();
    private final IntMap<EffState> effStorage = new IntMap<>();

    // Для очистки effStorage от построек, вышедших из выделения / уничтоженных
    private final IntSet seenIdsThisPass = new IntSet();
    private final IntSeq staleIdsBuffer = new IntSeq();

    // Throttling пересборки UI: данные считаются каждый вызов updateStats,
    // но перерисовка виджетов (дорогая по scene2d layout) — не чаще, чем раз в N вызовов,
    // если набор блоков не поменялся структурно.
    private int framesSinceRebuild = 0;
    private static final int REBUILD_EVERY_N_CALLS = 6;
    private final ObjectSet<Block> lastBlockSet = new ObjectSet<>();

    private final float tableWidth = 750f;
    private final float colName = 260f;
    private final float colVal = 100f;
    private final float colReq = 260f;
    private float sumCurP_EMA = 0;
    private float sumMaxP_EMA = 0;
    private ObjectFloatMap<Item> sumCurI_EMA = new ObjectFloatMap<>(), sumMaxI_EMA = new ObjectFloatMap<>();
    private ObjectFloatMap<Liquid> sumCurL_EMA = new ObjectFloatMap<>(), sumMaxL_EMA = new ObjectFloatMap<>();

    private static class BlockStat {
        int count = 0;
        float curPower = 0, maxPower = 0;
        ObjectFloatMap<Item> curItems = new ObjectFloatMap<>(), maxItems = new ObjectFloatMap<>();
        ObjectFloatMap<Liquid> curLiquids = new ObjectFloatMap<>(), maxLiquids = new ObjectFloatMap<>();
    }

    private static class EffState {
        float averageEff = 0f;        // Сглаженная эффективность (аптайм блока)
        float averagePower = -1f;     // -1 для инициализации: сглаженное производство энергии
        float averageConsumption = -1f; // -1 для инициализации: сглаженное потребление энергии
    }

    public boolean isShown(){
        return visible;
    }
    public void build(Group parent) {
        parent.fill(t -> {
            t.name = "production-analyzer";
            t.right().center();
            t.visible(() -> visible && state.isGame());

            t.table(Styles.black6, main -> {
                main.margin(12f);
                main.add("[accent]PRODUCTION ANALYZER[]").colspan(4).padBottom(6f).row();
                main.image().growX().height(2f).color(Pal.accent).colspan(4).padBottom(8f).row();
                main.table(s -> summaryTable = s).growX().padBottom(4f).maxWidth(tableWidth+colName+colVal+colReq).row();
                main.image().growX().height(2f).color(Pal.accent).colspan(4).padBottom(8f).row();
                main.table(h -> {
                    h.defaults().pad(2).fontScale(0.85f);
                    h.add("[lightgray]Block / Resource").width(colName).left();
                    h.add("[lightgray]Realtime").width(colVal).center();
                    h.add("[lightgray]Max").width(colVal).center();
                    h.add("[lightgray]Required").width(colReq).center();
                }).growX().row();

                main.image().growX().height(1f).color(Color.darkGray).colspan(4).padBottom(6f).row();

                main.pane(p -> {
                    p.top();
                    contentTable = p;
                }).grow().maxHeight(Core.graphics.getHeight() * 0.75f).scrollX(false).update(pane -> {
                    if(scene.getScrollFocus() == pane && !Core.input.shift()) scene.setScrollFocus(null);
                });
            }).width(tableWidth).touchable(Touchable.enabled);
        });

        Events.on(EventType.WorldLoadEvent.class, e -> {
            effStorage.clear();
            lastBlockSet.clear();
            framesSinceRebuild = 0;
        });
    }
    private void resetEMA(){
        sumCurP_EMA = 0; sumMaxP_EMA = 0;
        sumCurI_EMA.clear(); sumMaxI_EMA.clear();
        sumCurL_EMA.clear(); sumMaxL_EMA.clear();
    }

    public void updateStats(int x1, int y1, int x2, int y2) {
        visible = true;
        statsMap.clear();
        seenIdsThisPass.clear();
        int startX = Math.min(x1, x2), endX = Math.max(x1, x2);
        int startY = Math.min(y1, y2), endY = Math.max(y1, y2);

        for (Building b : Groups.build) {
            if (b.tileX() >= startX && b.tileX() <= endX && b.tileY() >= startY && b.tileY() <= endY && b.team == player.team()) {
                if(b.block.category == Category.distribution || b.block.category == Category.logic
                        || (b.block.category == Category.liquid && !(b instanceof Pump.PumpBuild || b.block instanceof SolidPump))) continue;
                calculateForBuild(b);
            }
        }

        cleanupStaleEffStates();

        // Определяем, поменялся ли структурно набор блоков (появился/исчез тип) —
        // в этом случае перерисовываем немедленно, иначе троттлим по частоте.
        boolean structureChanged = blockSetChanged();
        framesSinceRebuild++;

        if (structureChanged || framesSinceRebuild >= REBUILD_EVERY_N_CALLS) {
            framesSinceRebuild = 0;
            rebuildUI();
        } else {
            // Даже без пересборки виджетов дерева, summary-строку с EMA обновляем -
            // она дешёвая (просто текст), в отличие от полной пересборки contentTable.
            updateSummaryOnly();
        }
    }

    private boolean blockSetChanged() {
        boolean changed = statsMap.size != lastBlockSet.size;
        if (!changed) {
            for (Block b : statsMap.keys()) {
                if (!lastBlockSet.contains(b)) { changed = true; break; }
            }
        }
        lastBlockSet.clear();
        for (Block b : statsMap.keys()) lastBlockSet.add(b);
        return changed;
    }

    private void cleanupStaleEffStates() {
        staleIdsBuffer.clear();
        for (IntMap.Entry<EffState> e : effStorage.entries()) {
            if (!seenIdsThisPass.contains(e.key)) staleIdsBuffer.add(e.key);
        }
        for (int i = 0; i < staleIdsBuffer.size; i++) effStorage.remove(staleIdsBuffer.get(i));
    }

    public void hide() { visible = false; }

    private void calculateForBuild(Building b) {
        BlockStat st = statsMap.get(b.block, BlockStat::new);
        st.count++;

        seenIdsThisPass.add(b.id);
        EffState state = effStorage.get(b.id, EffState::new);
        float alpha = 0.005f;
        float t = Math.min(1f, alpha * Time.delta);
        state.averageEff = Mathf.lerp(state.averageEff, b.efficiency * b.timeScale(), t);
        float realtimeMult = state.averageEff;

        // --- 1. ЭНЕРГИЯ ---
        float usage = (b.block.consPower != null ? b.block.consPower.usage * 60f : 0f);
        float maxProd = (b.block instanceof PowerGenerator pg ? pg.powerProduction * 60f : 0f);
        st.maxPower += (maxProd - usage);

        float rawProd = 0;
        if(b instanceof PowerGenerator.GeneratorBuild gb){
            rawProd = gb.getPowerProduction() * 60f;
        }

        if(state.averagePower < 0) state.averagePower = rawProd;
        state.averagePower = Mathf.lerp(state.averagePower, rawProd, t);
        float rawConsumption = usage * (b.power != null ? b.power.status : 0f) * b.timeScale();
        if(state.averageConsumption < 0) state.averageConsumption = rawConsumption;
        state.averageConsumption = Mathf.lerp(state.averageConsumption, rawConsumption, t);
        st.curPower += (state.averagePower - state.averageConsumption);

        // --- 2. ПОТРЕБЛЕНИЕ ---
        // Важно: используем cons.efficiency(b) как единственный источник реальной
        // просадки по конкретному ресурсу — НЕ умножаем дополнительно на realtimeMult,
        // иначе эффективность возводится в квадрат и потребление занижается.
        for (Consume cons : b.block.consumers) {
            if (cons instanceof ConsumeItems ci) {
                float duration = 60f;
                if(b.block instanceof GenericCrafter gc) duration = gc.craftTime;
                else if(b.block instanceof Reconstructor r) duration = r.constructTime;
                else if(b.block instanceof ConsumeGenerator cg) duration = cg.itemDuration;
                else if(b.block instanceof MendProjector pj) duration = pj.useTime;
                else if(b.block instanceof OverdriveProjector opj) duration = opj.useTime;
                else if(b.block instanceof NuclearReactor nr) duration = nr.itemDuration;
                else if(b.block instanceof ImpactReactor ir) duration = ir.itemDuration;
                else if(b.block instanceof UnitFactory uf) {
                    if (b instanceof UnitFactory.UnitFactoryBuild ufb && ufb.currentPlan != -1 && ufb.currentPlan < uf.plans.size) {
                        duration = uf.plans.get(ufb.currentPlan).time;
                    } else if (uf.plans.size > 0) {
                        duration = uf.plans.first().time;
                    }
                }

                float timeFactor = 60f / duration;
                float consEff = cons.efficiency(b);

                for (ItemStack stack : ci.items) {
                    float base = stack.amount * timeFactor;
                    st.maxItems.put(stack.item, st.maxItems.get(stack.item, 0) - base);
                    st.curItems.put(stack.item, st.curItems.get(stack.item, 0) - (base * consEff));
                }
            }

            if (cons instanceof ConsumeLiquid cl) {
                float base = cl.amount * 60f;
                st.maxLiquids.put(cl.liquid, st.maxLiquids.get(cl.liquid, 0) - base);
                st.curLiquids.put(cl.liquid, st.curLiquids.get(cl.liquid, 0) - (base * cons.efficiency(b)));
            }
        }

        // Специфическая логика для генераторов сжигания (Combustion / Steam)
        if (b.block == Blocks.combustionGenerator || b.block == Blocks.steamGenerator) {
            boolean isSteam = b.block == Blocks.steamGenerator;
            float baseDur = isSteam ? 90f : 120f; // 1.5s vs 2s

            Item[] fuels = {Items.coal, Items.sporePod, Items.blastCompound, Items.pyratite};
            Item activeFuel = b.items.any() ? b.items.first() : Items.coal;

            for (Item f : fuels) {
                if (b.items.has(f) || (f == Items.coal && !b.items.any())) {
                    float rate = 60f / (baseDur * (f == Items.pyratite ? 3f : 1f));
                    st.maxItems.put(f, st.maxItems.get(f, 0) - rate);
                    if (b.items.has(f)) st.curItems.put(f, st.curItems.get(f, 0) - (rate * realtimeMult));
                }
            }
        }

        // RTG Generator
        else if (b.block == Blocks.rtgGenerator) {
            st.maxItems.put(Items.thorium, st.maxItems.get(Items.thorium, 0) - 0.07f);
            if (b.items.has(Items.thorium)) st.curItems.put(Items.thorium, st.curItems.get(Items.thorium, 0) - (0.07f * realtimeMult));

            st.maxItems.put(Items.phaseFabric, st.maxItems.get(Items.phaseFabric, 0) - 0.0047f);
            if (b.items.has(Items.phaseFabric)) st.curItems.put(Items.phaseFabric, st.curItems.get(Items.phaseFabric, 0) - (0.0047f * realtimeMult));
        }


        // --- 3. ПРОИЗВОДСТВО ---
        // 1. Oil Extractor (Fracker)
        if (b.block instanceof Fracker fr) {
            float totalAttribute = b.block.sumAttribute(fr.attribute, b.tileX(), b.tileY());
            float averageAttribute = totalAttribute / (float)(b.block.size * b.block.size);

            float baseRatePerSecond = fr.pumpAmount * 60f;

            float localMax = baseRatePerSecond * (fr.baseEfficiency + averageAttribute);
            st.maxLiquids.put(fr.result, st.maxLiquids.get(fr.result, 0) + localMax);
            st.curLiquids.put(fr.result, st.curLiquids.get(fr.result, 0) + (localMax * b.efficiency));

            return;
        }
        // 2. Затем SolidPump (Water Extractor)
        else if (b.block instanceof SolidPump sp) {
            float boost = sp.baseEfficiency + b.block.sumAttribute(sp.attribute, b.tileX(), b.tileY());
            float baseMax = sp.pumpAmount * boost * 60f;
            st.maxLiquids.put(sp.result, st.maxLiquids.get(sp.result, 0) + baseMax);
            st.curLiquids.put(sp.result, st.curLiquids.get(sp.result, 0) + (baseMax * realtimeMult));
        }
        // 3. Обычные заводы (GenericCrafter)
        else if (b.block instanceof GenericCrafter gc) {
            float speedMult = 60f / gc.craftTime;
            if (gc.outputItem != null) {
                float base = gc.outputItem.amount * speedMult;
                st.maxItems.put(gc.outputItem.item, st.maxItems.get(gc.outputItem.item, 0) + base);
                st.curItems.put(gc.outputItem.item, st.curItems.get(gc.outputItem.item, 0) + (base * realtimeMult));
            }
            if (gc.outputLiquid != null) {
                float base = gc.outputLiquid.amount * speedMult;
                st.maxLiquids.put(gc.outputLiquid.liquid, st.maxLiquids.get(gc.outputLiquid.liquid, 0) + base);
                st.curLiquids.put(gc.outputLiquid.liquid, st.curLiquids.get(gc.outputLiquid.liquid, 0) + (base * realtimeMult));
            }
        }
        // Сепараторы
        if (b.block instanceof Separator sep) {
            float totalWeight = 0;
            for (ItemStack stack : sep.results) totalWeight += stack.amount;

            float craftMult = 60f / sep.craftTime;

            for (ItemStack stack : sep.results) {
                float averageRate = (stack.amount / totalWeight) * craftMult;

                st.maxItems.put(stack.item, st.maxItems.get(stack.item, 0) + averageRate);
                st.curItems.put(stack.item, st.curItems.get(stack.item, 0) + (averageRate * realtimeMult));
            }
        }

        // БУРЫ (Расчет Max независим от состояния)
        if (b instanceof Drill.DrillBuild drill && drill.dominantItem != null) {
            Drill block = (Drill)b.block;
            float maxBaseSpeed = 60f / block.getDrillTime(drill.dominantItem) * drill.dominantItems;

            st.maxItems.put(drill.dominantItem, st.maxItems.get(drill.dominantItem, 0) + maxBaseSpeed);
            st.curItems.put(drill.dominantItem, st.curItems.get(drill.dominantItem, 0) + (maxBaseSpeed * realtimeMult));
        }

        // ПОМПЫ
        if (b instanceof Pump.PumpBuild pump && pump.liquidDrop != null) {
            float totalMultiplier = 0;
            for(int dx = 0; dx < b.block.size; dx++){
                for(int dy = 0; dy < b.block.size; dy++){
                    Tile t2 = world.tile(b.tileX() + dx, b.tileY() + dy);
                    if(t2 != null && t2.floor().liquidDrop == pump.liquidDrop) totalMultiplier += t2.floor().liquidMultiplier;
                }
            }
            float baseMax = ((Pump)b.block).pumpAmount * totalMultiplier * 60f;
            st.maxLiquids.put(pump.liquidDrop, st.maxLiquids.get(pump.liquidDrop, 0) + baseMax);
            st.curLiquids.put(pump.liquidDrop, st.curLiquids.get(pump.liquidDrop, 0) + (baseMax * realtimeMult));
        }
    }

    /** Только пересчитывает и обновляет summary-строку без пересборки дерева блоков (дёшево). */
    private void updateSummaryOnly() {
        if (statsMap.isEmpty()) return;
        computeSummaryEMA();
        // summaryTable уже построена rebuildUI ранее; обновляем только числа через ярлыки было бы
        // сложнее без хранения ссылок на Label, поэтому здесь просто пересобираем саму summary-таблицу -
        // она значительно дешевле, чем полная contentTable с блоками.
        rebuildSummaryTableOnly();
    }

    private void computeSummaryEMA() {
        float rawSumCurP = 0, rawSumMaxP = 0;
        ObjectFloatMap<Item> rawSumCurI = new ObjectFloatMap<>(), rawSumMaxI = new ObjectFloatMap<>();
        ObjectFloatMap<Liquid> rawSumCurL = new ObjectFloatMap<>(), rawSumMaxL = new ObjectFloatMap<>();

        for (BlockStat st : statsMap.values()) {
            rawSumCurP += st.curPower; rawSumMaxP += st.maxPower;
            for (var e : st.maxItems.entries()) {
                rawSumMaxI.put(e.key, rawSumMaxI.get(e.key, 0) + e.value);
                rawSumCurI.put(e.key, rawSumCurI.get(e.key, 0) + st.curItems.get(e.key, 0));
            }
            for (var e : st.maxLiquids.entries()) {
                rawSumMaxL.put(e.key, rawSumMaxL.get(e.key, 0) + e.value);
                rawSumCurL.put(e.key, rawSumCurL.get(e.key, 0) + st.curLiquids.get(e.key, 0));
            }
        }

        float a = Math.min(1f, 0.01f * Time.delta);
        sumCurP_EMA = Mathf.lerp(sumCurP_EMA, rawSumCurP, a);
        sumMaxP_EMA = rawSumMaxP;

        for (Item item : content.items()) {
            float rawCur = rawSumCurI.get(item, 0);
            if (Math.abs(rawCur) < 0.01f && Math.abs(rawSumMaxI.get(item, 0)) < 0.01f) continue;
            sumCurI_EMA.put(item, Mathf.lerp(sumCurI_EMA.get(item, 0), rawCur, a));
        }
        for (Liquid liq : content.liquids()) {
            float rawCur = rawSumCurL.get(liq, 0);
            if (Math.abs(rawCur) < 0.01f && Math.abs(rawSumMaxL.get(liq, 0)) < 0.01f) continue;
            sumCurL_EMA.put(liq, Mathf.lerp(sumCurL_EMA.get(liq, 0), rawCur, a));
        }

        // Сохраняем raw-max суммы, они нужны rebuildSummaryTableOnly/rebuildUI
        lastRawSumMaxP = rawSumMaxP;
        lastRawSumMaxI = rawSumMaxI;
        lastRawSumMaxL = rawSumMaxL;
    }

    private float lastRawSumMaxP = 0;
    private ObjectFloatMap<Item> lastRawSumMaxI = new ObjectFloatMap<>();
    private ObjectFloatMap<Liquid> lastRawSumMaxL = new ObjectFloatMap<>();

    private void rebuildSummaryTableOnly() {
        summaryTable.clear();
        summaryTable.left().defaults().padRight(15f).left();

        int count = 0;
        int maxInRow = 4;

        if (Math.abs(lastRawSumMaxP) > 1) {
            addSummaryItem(summaryTable, Icon.power.getRegion(), sumCurP_EMA, lastRawSumMaxP, true);
            count++;
        }

        for (Item item : content.items()) {
            float rawMax = lastRawSumMaxI.get(item, 0);
            float cur = sumCurI_EMA.get(item, 0);
            if (Math.abs(cur) < 0.01f && Math.abs(rawMax) < 0.01f) continue;

            if (count > 0 && count % maxInRow == 0) summaryTable.row();
            addSummaryItem(summaryTable, item.uiIcon, cur, rawMax, false);
            count++;
        }

        for (Liquid liq : content.liquids()) {
            float rawMax = lastRawSumMaxL.get(liq, 0);
            float cur = sumCurL_EMA.get(liq, 0);
            if (Math.abs(cur) < 0.01f && Math.abs(rawMax) < 0.01f) continue;

            if (count > 0 && count % maxInRow == 0) summaryTable.row();
            addSummaryItem(summaryTable, liq.uiIcon, cur, rawMax, false);
            count++;
        }
    }

    private void rebuildUI() {
        contentTable.clear();
        if (statsMap.isEmpty()) { summaryTable.clear(); return; }

        computeSummaryEMA();
        rebuildSummaryTableOnly();

        for (var entry : statsMap.entries()) {
            Block block = entry.key;
            BlockStat st = entry.value;

            contentTable.table(Styles.black3, t -> {
                t.left().defaults().left();
                t.table(titleRow -> {
                    titleRow.image(block.uiIcon).size(20).padRight(4);
                    titleRow.add(block.localizedName + " [lightgray]x" + st.count).growX().left();
                }).width(tableWidth - 20).row();

                if(st.maxPower > 0.1f) drawStatRow(t, null, "Power", st.curPower, st.maxPower, true);
                for(var e : st.maxItems.entries()) if(e.value > 0.0001f) drawStatRow(t, e.key, e.key.localizedName, st.curItems.get(e.key, 0), e.value, false);
                for(var e : st.maxLiquids.entries()) if(e.value > 0.0001f) drawStatRow(t, e.key, e.key.localizedName, st.curLiquids.get(e.key, 0), e.value, false);

                t.image().growX().height(1f).color(Color.darkGray).pad(2).row();

                for(var e : st.maxItems.entries()) if(e.value < -0.0001f) drawStatRow(t, e.key, e.key.localizedName, st.curItems.get(e.key, 0), e.value, false);
                for(var e : st.maxLiquids.entries()) if(e.value < -0.0001f) drawStatRow(t, e.key, e.key.localizedName, st.curLiquids.get(e.key, 0), e.value, false);
                if(st.maxPower < -0.1f) drawStatRow(t, null, "Power", st.curPower, st.maxPower, true);

            }).growX().padBottom(8).row();
        }
    }

    private void drawStatRow(Table t, UnlockableContent icon, String name, float cur, float max, boolean isPower) {
        if(Math.abs(cur) < 0.01f && Math.abs(max) < 0.01f) return;
        t.table(r -> {
            if(icon != null) r.image(icon.uiIcon).size(16).padLeft(12).padRight(4);
            else r.add("").width(20).padLeft(12);

            r.add(name).width(colName - 36).fontScale(0.85f).ellipsis(true).left();

            r.add(isPower ? formatPower(cur) : format(cur)).width(colVal).center();
            r.add(isPower ? formatPower(max) : format(max)).width(colVal).center();

            r.table(req -> addRequirementIcons(req, icon, max)).width(colReq).center();
        }).height(27f).row();
    }
    private void addSummaryItem(Table table, TextureRegion icon, float cur, float max, boolean isPower) {
        table.table(t -> {
            t.image(icon).size(16).padRight(2);

            float w = 65f;
            t.add(isPower ? formatPower(cur) : format(cur)).width(w).right();
            t.add("[gray]/").width(20f).left();
            t.add(isPower ? formatPower(max) : format(max)).width(w).left();
        }).padRight(10f).padBottom(2f);
    }

    private void addRequirementIcons(Table t, Object content, float rate) {
        if (rate >= -0.01f) return;
        float abs = Math.abs(rate);
        t.left().defaults().left().padRight(3);

        if (content instanceof Item item) {
            if (item == Items.copper || item == Items.lead || item == Items.sand || item == Items.coal || item == Items.scrap) {
                addReq(t, Blocks.mechanicalDrill, abs / getDrillSpeed(Blocks.mechanicalDrill, item));
                addReq(t, Blocks.pneumaticDrill,  abs / getDrillSpeed(Blocks.pneumaticDrill, item));
                addReq(t, Blocks.laserDrill,      abs / getDrillSpeed(Blocks.laserDrill, item));
                addReq(t, Blocks.blastDrill,      abs / getDrillSpeed(Blocks.blastDrill, item));
            }
            if (item == Items.titanium) {
                addReq(t, Blocks.pneumaticDrill,  abs / getDrillSpeed(Blocks.pneumaticDrill, item));
                addReq(t, Blocks.laserDrill,      abs / getDrillSpeed(Blocks.laserDrill, item));
                addReq(t, Blocks.blastDrill,      abs / getDrillSpeed(Blocks.blastDrill, item));
                addReq(t, Blocks.separator, abs / 0.285f);
                addReq(t, Blocks.disassembler, abs / 0.8f);
            }
            if (item == Items.thorium) {
                addReq(t, Blocks.laserDrill,      abs / getDrillSpeed(Blocks.laserDrill, item));
                addReq(t, Blocks.blastDrill,      abs / getDrillSpeed(Blocks.blastDrill, item));
                addReq(t, Blocks.disassembler, abs / 0.8f);
            }
            if (item == Items.copper) {
                addReq(t, Blocks.separator, abs / 0.714f);
            }
            if (item == Items.lead) {
                addReq(t, Blocks.separator, abs / 0.428f);
            }
            if (item == Items.coal) {
                addReq(t, Blocks.coalCentrifuge, abs / 2.0f);
            }
            if (item == Items.sand) {
                addReq(t, Blocks.pulverizer, abs / 1.5f);
                addReq(t, Blocks.disassembler, abs / 1.6f);
            }
            if (item == Items.sporePod) {
                addReq(t, Blocks.cultivator, abs / 0.6f);
            }
            if (item == Items.silicon){
                addReq(t, Blocks.siliconSmelter, abs / 1.5f);
                addReq(t, Blocks.siliconCrucible, abs / 5.333f);
            }
            if (item == Items.graphite){
                addReq(t, Blocks.graphitePress, abs / 0.666f);
                addReq(t, Blocks.multiPress, abs / 4f);
                addReq(t, Blocks.separator, abs / 0.285f);
                addReq(t, Blocks.disassembler, abs / 0.8f);
            }
            if (item == Items.metaglass) {
                addReq(t, Blocks.kiln, abs / 2f);
            }
            if (item == Items.plastanium) {
                addReq(t, Blocks.plastaniumCompressor, abs / 1f);
            }
            if (item == Items.phaseFabric) {
                addReq(t, Blocks.phaseWeaver, abs / 0.5f);
            }
            if (item == Items.surgeAlloy) {
                addReq(t, Blocks.surgeSmelter, abs / 0.8f);
            }
            if (item == Items.pyratite) {
                addReq(t, Blocks.pyratiteMixer, abs / 0.75f);
            }
            if (item == Items.blastCompound) {
                addReq(t, Blocks.blastMixer, abs / 0.75f);
            }

        } else if (content instanceof Liquid liq) {
            if (liq == Liquids.water || liq == Liquids.oil || liq == Liquids.cryofluid || liq == Liquids.slag) {
                addReq(t, Blocks.mechanicalPump, abs / 7f);
                addReq(t, Blocks.rotaryPump, abs / 48f);
                addReq(t, Blocks.impulsePump, abs / 118.799f);
            }
            if (liq == Liquids.cryofluid) {
                addReq(t, Blocks.cryofluidMixer, abs / 12.0f);
            }
            if (liq == Liquids.oil) {
                addReq(t, Blocks.oilExtractor, abs / 15.0f);
                addReq(t, Blocks.sporePress, abs / 18.0f);
            }
            if (liq == Liquids.water) {
                addReq(t, Blocks.waterExtractor, abs / 7f);
            }
        }
    }
    private float getDrillSpeed(Block block, Item item) {
        if (!(block instanceof Drill drill)) return 0.0001f;
        float timePerItem = drill.getDrillTime(item);
        return (60f / timePerItem) * (drill.size * drill.size);
    }

    private void addReq(Table t, Block b, float val) {
        if(val < 0.0001f) return;
        t.table(ttt->{
            ttt.image(b.uiIcon).size(14);
            ttt.add(Strings.fixed(val, 1)).fontScale(0.7f).color(Color.lightGray).padRight(3);
            if (b instanceof Drill drill && drill.liquidBoostIntensity > 1f) {
                ttt.row();
                ttt.image(Liquids.water.uiIcon).size(12).padRight(2);
                float boostedVal = val / drill.liquidBoostIntensity;
                ttt.add(Strings.fixed(boostedVal, 1)).fontScale(0.7f).color(Pal.accent);
            }
        });

    }

    private String format(float val) {
        String color = val > 0.05f ? "[green]+" : (val < -0.05f ? "[scarlet]" : "[gray]");
        float absV = Math.abs(val);
        if (absV >= 1000) return color + Strings.fixed(val / 1000f, 1) + "k";
        return color + Strings.fixed(val, 1);
    }

    private String formatPower(float val) {
        String color = val > 0.1f ? "[#f3e979]+" : (val < -0.1f ? "[scarlet]" : "[gray]");
        float absV = Math.abs(val);
        if (absV >= 1000) return color + Strings.fixed(val / 1000f, 1) + "k";
        return color + Strings.fixed(val, 0);
    }
}