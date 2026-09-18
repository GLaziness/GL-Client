package mindustry.client.fallen;

import arc.Core;
import arc.Events;
import arc.util.Timer;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

import java.util.ArrayDeque;
import java.util.Queue;

public class AntiAttemPatcher {

    // Сообщение, которое будет вшито в процессор
    private static final String WARNING_CODE =
            "print \"Stop build this, or you will get BANNED\"\n" +
                    "print \"https://mindustry.dev/attem\"\n";

    // Паттерны "плохого" кода для поиска
    private static final String[] CONFIRMED_ATTEM = new String[] {
            "read index cell1 1\n" +
                    "jump 43 greaterThan i 0\n" +
                    "ulocate building core false @copper outx outy found core\n" +
                    "ucontrol move outx outy 0 0 0\n" +
                    "ucontrol itemTake core itemNeed itemCap 0 0\n" +
                    "write outx cell1 5\n" +
                    "write outy cell1 6\n" +
                    "end\n" +
                    "jump 51 equal item @lead\n" +
                    "jump 51 equal item @coal\n" +
                    "jump 51 equal item @lead\n" +
                    "jump 51 equal item @graphite\n" +
                    "jump 51 equal item @metaglass\n" +
                    "jump 53 equal item @phase-fabric\n" +
                    "jump 53 equal item @surge-alloy\n" +
                    "jump 53 greaterThan index 7\n" +
                    "set container1 vault1",
            "write 8 cell1 1\n" +
                    "end\n" +
                    "jump 125 greaterThan plast5 600\n" +
                    "sensor cPlast5 core @plastanium\n" +
                    "jump 125 equal cPlast5 0\n" +
                    "control config sorter1 @plastanium 0 0 0\n" +
                    "write 8 cell1 1\n" +
                    "end\n" +
                    "jump 131 greaterThan surge5 500\n" +
                    "sensor cSurge5 core @surge-alloy\n" +
                    "jump 131 equal cSurge5 0\n" +
                    "control config sorter1 @surge-alloy 0 0 0\n" +
                    "write 8 cell1 1\n" +
                    "end\n" +
                    "jump 137 greaterThan phase5 350\n" +
                    "sensor cPhase core @phase-fabric\n" +
                    "jump 137 equal cPhase 0\n" +
                    "control config sorter1 @phase-fabric 0 0 0\n" +
                    "write 8 cell1 1\n" +
                    "end\n" +
                    "jump 143 greaterThan titanium5 100\n" +
                    "sensor cTitanium core @titanium\n" +
                    "jump 143 equal cTitanium 0\n" +
                    "control config sorter1 @titanium 0 0 0\n" +
                    "write 8 cell1 1\n" +
                    "end\n" +
                    "control config sorter1 null 0 0 0\n" +
                    "end\n" +
                    "control config sorter1 @titanium 0 0 0\n",
            "read max cell1 4\n" +
                    "jump 25 notEqual max 0\n" +
                    "print \"SET UNIT CAP HERE\"\n" +
                    "set max 32\n" +
                    "op mul fx @thisx -10000\n" +
                    "op add flag @thisy fx\n" +
                    "op ceil flag flag fx\n" +
                    "write flag cell1 0\n" +
                    "ubind UnitType\n" +
                    "jump 33 notEqual first null\n" +
                    "set first @unit\n" +
                    "jump 34 always first @unit\n" +
                    "jump 46 strictEqual first @unit\n" +
                    "op add i i 1\n" +
                    "sensor f @unit @flag",
            "jump 72 notEqual min-item null\n" +
                    "set Wait 1\n" +
                    "jump 0 always min-item null\n" +
                    "control config sorter1 min-item 0 0 0\n" +
                    "set Wait 0\n" +
                    "write index cell1 1\n" +
                    "write total cell1 7\n" +
                    "op div fullness total 14000\n" +
                    "write fullness cell1 8\n" +
                    "write c cell1 10\n" +
                    "write min cell1 11\n" +
                    "jump 0 always 0 false",
            "read amount1 cell1 index1\n" +
                    "read amount1 cell1 index1\n" +
                    "op add amount1 amount1 amount\n" +
                    "op add amount2 amount2 amount\n" +
                    "write amount1 cell1 index1\n" +
                    "write amount2 cell1 index2\n" +
                    "jump 17 always f 0\n" +
                    "set i 0\n" +
                    "jump 73 greaterThanEq j 13\n" +
                    "write 0 cell1 j\n" +
                    "op add j j 1\n" +
                    "jump 69 always j 13\n" +
                    "set j 0\n" +
                    "set first null\n" +
                    "end"};

    // Настройки анти-спама: задержка между Call.tileConfig в секундах
    private static final float CONFIG_DELAY_SEC = 1f;

    // Очередь процессоров для пакетной обработки с задержками
    private static final Queue<LogicBuild> patchQueue = new ArrayDeque<>();
    private static boolean processingQueue = false;
    private static boolean loaded = false;


    public static void load() {
        if (loaded) return; // Защита от повторной инициализации
        loaded = true;
        // 1. Загрузка мира: сканируем УЖЕ СУЩЕСТВУЮЩИЕ процессоры с задержкой 5 сек

        Events.on(EventType.WorldLoadEvent.class, e -> {
            if (Core.settings.getBool("ihateattems", true)) {
                Timer.schedule(() -> {
                    if (Vars.net.client() && Vars.player != null && Vars.player.unit() != null) {
                        scanExistingProcessors();
                    }
                }, 5f);
            }
        });

        // 2. Постройка блока: проверяем НОВЫЕ процессоры
        Events.on(EventType.BlockBuildEndEvent.class, event -> {
            if (Core.settings.getBool("ihateattems", true)) {
                checkNewProcessor(event);
            }
        });

        // 3. Изменение блока: проверяем дописывание в процессоры
        Events.on(EventType.ConfigEvent.class, event -> {
            if (Core.settings.getBool("ihateattems", true)) {
                checkConfProcessor(event);
            }
        });
    }

    // Проверка вновь построенного процессора
    private static void checkNewProcessor(EventType.BlockBuildEndEvent event) {
        if (event.breaking) return;
        if (!Vars.net.client()) return;
        Building build = event.tile.build;
        if (build instanceof LogicBuild processor) {
            if(Vars.player.team() == build.team){
                if (containsBadCode(processor.code)) {
                    queuePatch(processor);
                }
            }
        }
    }

    // Проверка изменённого процессора
    private static void checkConfProcessor(EventType.ConfigEvent event) {
        if (!Vars.net.client()) return;
        Building build = event.tile;
        if (build instanceof LogicBuild processor) {
            if(Vars.player.team() == build.team){
                if (containsBadCode(processor.code)) {
                    queuePatch(processor);
                }
            }
        }
    }

    // Сканирование всех существующих процессоров
    private static void scanExistingProcessors() {
        if (Vars.player == null || Vars.player.team() == null) return;

        var builds = Vars.player.team().data().buildings;
        int patched = 0;

        for (Building build : builds) {
            if (build instanceof LogicBuild processor) {
                if(Vars.player.team() == build.team){
                    if (containsBadCode(processor.code)) {
                        queuePatch(processor);
                        patched++;
                    }
                }
            }
        }
        if (patched > 0) {
            Vars.player.sendMessage("AntiAttemPatcher: в очередь на исправление добавлено " + patched + " процессоров.");
        } else {
            Vars.player.sendMessage("AntiAttemPatcher: идиотов не обнаружено.");
        }
    }

    // Добавление процессора в очередь на исправление
    private static void queuePatch(LogicBuild processor) {
        patchQueue.add(processor);
        processQueue();
    }

    // Обработка очереди с задержками между запросами
    private static void processQueue() {
        if (patchQueue.isEmpty() || processingQueue) return;
        processingQueue = true;

        LogicBuild processor = patchQueue.poll();
        patchProcessorImmediate(processor);

        // Планируем следующую итерацию с задержкой
        Timer.schedule(() -> {
            processingQueue = false;
            processQueue();
        }, CONFIG_DELAY_SEC);
    }

    // Непосредственная замена кода и отправка на сервер
    private static void patchProcessorImmediate(LogicBuild processor) {
        if (processor == null) return;

        //Генерируем сжатый массив байтов (код + связи процессора)
        byte[] compressedCode = LogicBlock.compress(WARNING_CODE, processor.relativeConnections());
        processor.updateCode(WARNING_CODE);
        Call.tileConfig(Vars.player, processor, compressedCode);

        Log.info("AntiAttemPatcher: Код заменен на координатах (" + processor.tileX() + ", " + processor.tileY() + ")");
    }

    // Проверка: содержит ли код один из запрещённых паттернов
    private static boolean containsBadCode(String code) {
        if (code == null) return false;
        for (String pattern : CONFIRMED_ATTEM) {
            if (code.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}