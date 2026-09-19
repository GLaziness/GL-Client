package mindustry.client.fallen;

import arc.*;
import arc.graphics.*;
import arc.input.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.Vars;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.game.Schematic;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SchematicsDialog;

import static mindustry.Vars.ui;


public class QuickSchemFrag extends Table {

    private static final int DEFAULT_TABS_PER_ROW = 6;

    private Table container = new Table();
    private Seq<QuickTab> tabs = new Seq<>();
    private int currentTab = 0;
    private Table tabTable;
    private boolean visible = Core.settings.getBool("quickschems", false);
    private Json json = new Json(){{
        setIgnoreUnknownFields(true);
        // Регистрируем класс вкладки и слота со всеми возможными именами
        addClassTag("mindustry.client.fallen.QuickSchemFrag$QuickTab", QuickTab.class);
        addClassTag("mindustry.client.fallen.QuickSchemFrag.QuickTab", QuickTab.class);
        addClassTag("QuickTab", QuickTab.class);
        addClassTag("QuickSlot", QuickSlot.class);

        setElementType(QuickTab.class, "slots", QuickSlot.class);
    }};

    private float lastX = 0, lastY = 0;
    private boolean centered = false;
    private float lastWidth = 0;

    // Структура данных для кнопки
    public static class QuickSlot {
        public String schemName = "";
        public String iconName = "none";
        public boolean isContent = false;

        public QuickSlot() {}
    }

    // Расширенная структура для вкладки
    public static class QuickTab {
        public String name = "Tab";
        public String iconName = "infoSmall";
        public String defaultSlotIcon = "none";
        public boolean defaultSlotIsContent = false;
        public boolean useIcon = false;
        public boolean isContent = false;
        public Seq<QuickSlot> slots = new Seq<>();

        public QuickTab() {}
        public QuickTab(String name) { this.name = name; }

        public void validate() {
            // 1. Проверка базовых полей вкладки
            if (name == null) name = "Tab";

            // Если иконка вкладки null (старый конфиг), ставим "none" или системную
            if (iconName == null) iconName = "infoSmall";

            // Новое поле: обязательно инициализируем, чтобы не было null
            if (defaultSlotIcon == null) defaultSlotIcon = "none";

            // 2. Проверка списка слотов
            if (slots == null) {
                slots = new Seq<>();
            } else {
                // Проходимся по всем загруженным слотам и лечим их тоже
                for (QuickSlot slot : slots) {
                    if (slot == null) continue; // На всякий случай

                    // Если в старом конфиге иконка была null или не указана
                    if (slot.iconName == null) slot.iconName = "none";

                    // Чтобы не ловить ошибки при поиске схем
                    if (slot.schemName == null) slot.schemName = "";
                }
            }
        }
    }

    public void build(Group parent) {
        parent.addChild(this);
        loadData();
        for(QuickTab tab : tabs) tab.validate();

        visible(() -> ui.hudfrag.shown && visible && Core.settings.getBool("quickschems", false));

        // Основной фон окна
        background(Styles.black6);
        // Главная таблица
        table(main -> {
            // Правая часть
            main.table(content -> {
                content.table(t -> {
                    this.tabTable = t;
                }).growX().left().row();

                content.image().growX().height(2f).color(Pal.coalBlack).row();

                content.table(c -> {
                    c.add(container).top().left();
                }).grow();

                rebuild();
            }).grow();
        }).grow();

        // Позиционирование
        update(() -> {
            if(!centered && Core.graphics.getWidth() > 0){
                float sx = Core.settings.getFloat("schemfrag-x", Core.graphics.getWidth() / 2f - width / 2f);
                float sy = Core.settings.getFloat("schemfrag-y", Core.graphics.getHeight() / 2f - height / 2f);
                // Ограничиваем, чтобы окно не появилось за пределами экрана
                setPosition(Mathf.clamp(sx, 0, Core.graphics.getWidth() - width),
                        Mathf.clamp(sy, 0, Core.graphics.getHeight() - height));
                centered = true;
            }
        });
        rebuild();
    }

    private void rebuildTabs() {
        if (tabTable == null) return;

        tabTable.clear();
        tabTable.left().top().defaults().pad(2).size(Core.settings.getFloat("qs-btn-size", 64f));

        int tabsPerRow = Core.settings.getInt("qs-tbs-cols", DEFAULT_TABS_PER_ROW);
        int currentInRow = 0;
        boolean dragAdded = false;

        for (int i = 0; i < tabs.size; i++) {
            int index = i;
            QuickTab tab = tabs.get(i);

            // Создаем кнопку вкладки
            Button btn = tabTable.button(b -> {
                if (tab.useIcon) b.image(getIconDrawable(tab.iconName, tab.isContent)).size(Core.settings.getFloat("qs-btn-size", 64f)*0.7f);
                else b.add(tab.name).fontScale(0.5f).ellipsis(true);
            }, Styles.flatBordert, () -> {
                currentTab = index;
                rebuild();
            }).checked(currentTab == index).get();

            btn.addListener(new InputListener() {
                @Override public boolean touchDown(InputEvent e, float x, float y, int p, KeyCode b) {
                    if (b == KeyCode.mouseRight) { showTabSettings(tab, index); return true; }
                    return false;
                }
            });

            currentInRow++;

            // ЛОГИКА ВСТАВКИ КНОПКИ ПЕРЕМЕЩЕНИЯ (В конец ПЕРВОГО ряда)
            if (!dragAdded && currentInRow == tabsPerRow - 1) {
                addDragButton(tabTable); // Вставляем кнопку в последнюю ячейку 1-го ряда
                tabTable.row();
                currentInRow = 0;
                dragAdded = true;
            }
            // Перенос для всех последующих рядов (они будут длиннее на 1 кнопку)
            else if (dragAdded && currentInRow == tabsPerRow) {
                tabTable.row();
                currentInRow = 0;
            }
        }

        // Если вкладок слишком мало и мы не дошли до конца 1-го ряда
        if (!dragAdded) {
            // Добавляем пустые ячейки, чтобы выровнять кнопку по правому краю
            while (currentInRow < tabsPerRow - 1) {
                tabTable.add().size(Core.settings.getFloat("qs-btn-size", 64f));
                currentInRow++;
            }
            addDragButton(tabTable);
        }
    }

    private void addDragButton(Table t) {
        ImageButton drag = t.button(Icon.move, Styles.cleari, () -> {}).size(Core.settings.getFloat("qs-btn-size", 64f)).get();
        drag.addListener(new InputListener() {
            boolean isRightClick = false;
            @Override public boolean touchDown(InputEvent e, float x, float y, int p, KeyCode b) {
                if (b == KeyCode.mouseRight) { isRightClick = true; showSettings(); return true; }
                isRightClick = false;
                lastX = e.stageX; lastY = e.stageY;
                return true;
            }
            @Override public void touchDragged(InputEvent e, float x, float y, int p) {
                if(!isRightClick) {
                    moveBy(e.stageX - lastX, e.stageY - lastY);
                    lastX = e.stageX; lastY = e.stageY;
                }
            }
            @Override public void touchUp(InputEvent e, float x, float y, int p, KeyCode b) {
                if(!isRightClick) {
                    Core.settings.put("schemfrag-x", QuickSchemFrag.this.x);
                    Core.settings.put("schemfrag-y", QuickSchemFrag.this.y);
                }
            }
        });
    }

    public void rebuild() {
        if (container == null || tabs.isEmpty()) return;

        currentTab = Math.max(0, Math.min(currentTab, tabs.size - 1));
        float btnSize = Core.settings.getFloat("qs-btn-size", 64f);

        container.clear();
        container.top().left();

        QuickTab tab = tabs.get(currentTab);
        // syncSlots больше НЕ удаляет лишние слоты — только дополняет недостающие.
        // Возвращает актуальное кол-во ячеек текущей сетки (cols*rows),
        // чтобы отрисовать ровно столько, сколько нужно сейчас,
        // не трогая "спрятанные" слоты сверх этого количества.
        int totalSlots = syncSlots(tab);
        int cols = Core.settings.getInt("qs-cols", 5);

        int count = 0;
        for (int i = 0; i < totalSlots; i++) {
            QuickSlot slot = tab.slots.get(i);
            String iconToDraw = slot.iconName;
            boolean isContentToDraw = slot.isContent;

            if ("none".equals(iconToDraw)) {
                // Если у слота нет иконки, берем дефолт вкладки
                iconToDraw = tab.defaultSlotIcon;
                isContentToDraw = tab.defaultSlotIsContent;

                if ("none".equals(iconToDraw)) {
                    // Если и у вкладки нет, берем глобальный дефолт
                    iconToDraw = Core.settings.getString("qs-default-icon", "infoSmall");
                    isContentToDraw = Core.settings.getBool("qs-default-iscontent", false);
                }
            }

            final String finalIcon = iconToDraw;
            final boolean finalIsContent = isContentToDraw;

            Button btn = container.button(b -> {
                b.clearChildren();
                TextureRegionDrawable dr = getIconDrawable(finalIcon, finalIsContent);
                Image img = b.image(dr).size(btnSize * 0.6f).get();

                // Если в итоге всё равно вышло "none", делаем невидимым
                if ("none".equals(finalIcon)) img.color.a = 0f;
            }, Styles.flatBordert, () -> useSchematic(slot.schemName)).size(btnSize).get();


            // Делаем кнопку "призрачной" если нет схемы
            if (slot.schemName.isEmpty()) {
                btn.color.a = 0.3f;
            } else {
                btn.color.a = 1f;
            }
            btn.addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent e, float x, float y, int p, KeyCode b) {
                    if (b == KeyCode.mouseRight) {
                        showEditDialog(slot);
                        return true;
                    }
                    return false;
                }

            });
            if (!slot.schemName.isEmpty()) {
                // Ищем схему один раз при билде
                Schematic schem = Vars.schematics.all().find(s -> s.name().equals(slot.schemName));

                if (schem != null) {
                    btn.addListener(new Tooltip(t -> {
                        t.background(Styles.black8); // Темный фон как в игре
                        t.margin(10f);

                        // Заголовок: Размеры и кол-во блоков
                        t.add(schem.width + "x" + schem.height + ", " + schem.tiles.size + " блоков")
                                .style(Styles.outlineLabel).padBottom(4f).row();

                        // Само изображение схемы
                        // Ограничиваем размер превью (например, 200-250 пикселей)
                        t.add(new SchematicsDialog.SchematicImage(schem)).size(Math.min(schem.width * 16, 250f), Math.min(schem.height * 16, 250f)).pad(4f).row();

                        // Таблица с ресурсами и энергией
                        t.table(stats -> {
                            stats.left().defaults().left();

                            // Сетка ресурсов
                            stats.table(items -> {
                                int itemIdx = 0;
                                for (var stack : schem.requirements()) {
                                    items.image(stack.item.uiIcon).size(16f).padRight(4f);
                                    items.add(String.valueOf(stack.amount)).color(Color.lightGray).padRight(10f);
                                    if (++itemIdx % 4 == 0) items.row();
                                }
                            }).row();

                            // Энергия (если есть потребление или производство)
                            float power = (schem.powerProduction() - schem.powerConsumption()) * 60f;
                            if (Math.abs(power) > 0.01f) {
                                stats.table(p -> {
                                    p.image(Icon.power).color(power > 0 ? Pal.accent : Pal.remove).size(16f).padRight(4f);
                                    p.add((power > 0 ? "+" : "") + Strings.fixed(power, 2))
                                            .color(power > 0 ? Pal.accent : Pal.remove);
                                }).padTop(4f);
                            }
                        });
                    }));
                }
            }
            count++;
            if (count % cols == 0) container.row(); // Перенос строки
        }

        rebuildTabs();
        updateSize();
    }

    // Настройка КОНКРЕТНОЙ вкладки
    private void showTabSettings(QuickTab tab, int tabIndex) {
        BaseDialog dialog = new BaseDialog(arc.Core.bundle.get("gl.ui.quickschem.1"));
        dialog.cont.table(t -> {
            t.table( tn ->{
                tn.add(arc.Core.bundle.get("gl.ui.quickschem.2")).left();
                tn.field(tab.name, val -> {
                    tab.name = val;
                    saveData();
                    rebuild();
                }).growX().row();
            }).growX().row();

            t.check(arc.Core.bundle.get("gl.ui.quickschem.3"), tab.useIcon, val -> {
                tab.useIcon = val;
                saveData();
                rebuild();
            }).row();

            t.button(arc.Core.bundle.get("gl.ui.quickschem.4"), () -> {
                showIconPicker(null, tab, false, dialog);
            }).size(200, 45).row();

            t.table(di->{
                di.add(arc.Core.bundle.get("gl.ui.quickschem.5")).left().padTop(10);
                di.button(getIconDrawable(tab.defaultSlotIcon, tab.defaultSlotIsContent), () -> {
                    showIconPickerForTabDefault(tab, dialog);
                }).size(45).get();
            }).get().row();
            t.row();
            t.table( tb ->{
                tb.button(arc.Core.bundle.get("gl.ui.quickschem.6"), Icon.trash, () -> {
                    if (tabs.size > 1) {
                        tabs.remove(tabIndex);
                        currentTab = Math.min(currentTab, tabs.size - 1);
                        saveData();
                        rebuild();
                        dialog.hide();
                    }
                }).width(280f).height(50).color(Pal.remove).row();
            });



        });
        dialog.addCloseButton();
        dialog.hidden(() -> {
            saveData();
            rebuildTabs();
            rebuild();
        });
        dialog.show();
    }

    private void showIconPickerForTabDefault(QuickTab tab, BaseDialog parent) {
        // Вызываем обычный пикер, но используем "хитрость":
        // передаем null в slot и саму вкладку в tab,
        // но в rebuildIconList добавим проверку
        showIconPicker(null, tab, true, parent);
    }

    // ГЛОБАЛЬНЫЕ настройки интерфейса
    private void showSettings() {
        BaseDialog dialog = new BaseDialog(arc.Core.bundle.get("gl.ui.quickschem.7"));

        setupSettingsContent(dialog);

        dialog.addCloseButton();
        dialog.show();
    }

    private void setupSettingsContent(BaseDialog dialog) {
        dialog.cont.clear();

        dialog.cont.pane(p -> {
            p.defaults().left().growX();

            // Настройка колонок
            p.table(t -> {
                t.label(() -> "Columns: " + Core.settings.getInt("qs-cols", 5)).left().row();
                t.slider(1, 15, 1, Core.settings.getInt("qs-cols", 5), val -> {
                    Core.settings.put("qs-cols", (int)val);
                    rebuild();
                }).left().growX();
            }).row();

            // Настройка строк
            p.table(t -> {
                t.label(() -> "Rows: " + Core.settings.getInt("qs-rows", 4)).left().row();
                t.slider(1, 15, 1, Core.settings.getInt("qs-rows", 4), val -> {
                    Core.settings.put("qs-rows", (int)val);
                    rebuild();
                }).left().growX();
            }).row();

            // Настройка кол-ва вкладок в строку
            p.table(t -> {
                t.label(() -> "Tabs at row: " + Core.settings.getInt("qs-tbs-cols", DEFAULT_TABS_PER_ROW)).left().row();
                t.slider(1, 15, 1, Core.settings.getInt("qs-tbs-cols", DEFAULT_TABS_PER_ROW), val -> {
                    Core.settings.put("qs-tbs-cols", (int)val);
                    rebuildTabs();
                }).left().growX();
            }).row();

            // Настройка размера кнопок
            p.table(t -> {
                t.label(() -> "Button Size: " + (int)Core.settings.getFloat("qs-btn-size", 64f)).left().row();
                t.slider(16, 128, 4, Core.settings.getFloat("qs-btn-size", 64f), val -> {
                    Core.settings.put("qs-btn-size", val);
                    rebuild();
                }).left().growX();
            }).row();

            p.table(t -> {
                t.add((arc.Core.bundle.get("gl.ui.quickschem.8") + " ")).left();

                // Показываем текущую дефолтную иконку
                String defName = Core.settings.getString("qs-default-icon", "infoSmall");
                boolean defIsCont = Core.settings.getBool("qs-default-iscontent", false);

                t.button(getIconDrawable(defName, defIsCont), () -> {
                    showIconPicker(null, null,false, dialog);
                }).size(45);
            }).left().row();

            p.image().height(2).color(Pal.accent).row();

            p.label(() -> arc.Core.bundle.get("gl.ui.quickschem.9")).color(Pal.accent).padBottom(10).row();
            p.table(tabsTable -> {
                tabsTable.defaults().pad(2);

                for (int i = 0; i < tabs.size; i++) {
                    int index = i;
                    QuickTab tab = tabs.get(i);

                    tabsTable.table(Styles.black3, row -> {
                        // Кнопка ВВЕРХ
                        row.button(Icon.upOpen, Styles.cleari, () -> {
                            if (index > 0) {
                                tabs.swap(index, index - 1);
                                if (currentTab == index) currentTab--;
                                else if (currentTab == index - 1) currentTab++;
                                saveData();
                                rebuild();
                                setupSettingsContent(dialog); // Перерисовываем список
                            }
                        }).size(35).disabled(index == 0);

                        // Кнопка ВНИЗ
                        row.button(Icon.downOpen, Styles.cleari, () -> {
                            if (index < tabs.size - 1) {
                                tabs.swap(index, index + 1);
                                if (currentTab == index) currentTab++;
                                else if (currentTab == index + 1) currentTab--;
                                saveData();
                                rebuild();
                                setupSettingsContent(dialog); // Перерисовываем список
                            }
                        }).size(35).disabled(index == tabs.size - 1);

                        // Иконка и имя вкладки
                        row.image(getIconDrawable(tab.iconName, tab.isContent)).size(24).padRight(10);
                        row.add(tab.name).growX().ellipsis(true);
                    }).growX().row();
                }
            }).growX().row();

            p.button(arc.Core.bundle.get("gl.ui.quickschem.10"), Icon.add, () -> {
                QuickTab nt = new QuickTab("New");
                nt.iconName = Core.settings.getString("qs-default-icon", "infoSmall");
                nt.isContent = Core.settings.getBool("qs-default-iscontent", false);
                tabs.add(nt);
                saveData();
                rebuild();
            }).height(50).row();

            p.button(arc.Core.bundle.get("gl.ui.quickschem.11"), Icon.trash, () -> {
                if (tabs.size > 1) {
                    tabs.remove(currentTab);
                    currentTab = 0;
                    saveData();
                    rebuild();
                    dialog.hide();
                }
            }).width(200f).height(50f).color(Pal.remove);
        }).grow();

    }

    // Вспомогательный метод получения иконки
    private TextureRegionDrawable getIconDrawable(String name, boolean isContent) {
        // 1. Проверка на "пустую" иконку
        if (name == null || name.equals("none")) return (TextureRegionDrawable) Icon.none;

        // 2. Системные иконки.
        if (!isContent) return Icon.icons.get(name, (TextureRegionDrawable) Icon.none);

        // 3. Контент игры
        for (ContentType type : ContentType.all) {
            var content = Vars.content.getByName(type, name);
            if (content instanceof UnlockableContent uc)
                return new TextureRegionDrawable(uc.uiIcon);
        }

        // 4. Если вообще ничего не нашли
        return (TextureRegionDrawable) Icon.none;
    }

    // Универсальный выбор иконки (для слота или для вкладки)
    private void showIconPicker(QuickSlot slot, QuickTab tab, boolean editDefault, BaseDialog parent) {
        BaseDialog picker = new BaseDialog(arc.Core.bundle.get("gl.ui.quickschem.12"));
        picker.setSize(Core.graphics.getWidth() * 0.8f, Core.graphics.getHeight() * 0.8f);

        // Контейнер для списка иконок, который мы будем перерисовывать
        Table listTable = new Table();

        // Поле поиска
        picker.cont.table(t -> {
            t.add((arc.Core.bundle.get("gl.ui.quickschem.13") + " ")).padRight(8f);
            t.field("", text -> {
                // При каждом изменении текста очищаем и пересобираем список
                rebuildIconList(listTable, text.toLowerCase(), slot, tab, editDefault, picker, parent);
            }).growX().get();
        }).growX().pad(10).row();

        // Панель прокрутки, внутри которой лежит наш список
        picker.cont.pane(listTable).grow().scrollX(false).scrollY(true);

        // Первичная сборка списка (пустой поиск = показать всё)
        rebuildIconList(listTable, "", slot, tab, editDefault, picker, parent);

        picker.addCloseButton();
        picker.show();
    }

    // Вспомогательный метод для пересборки списка иконок
    private void rebuildIconList(Table t, String query, QuickSlot slot, QuickTab tab, boolean editDefault, BaseDialog picker, BaseDialog parent) {
        t.clear();
        t.top().left();
        t.defaults().size(48f).pad(2f);
        int count = 0;
        // Рассчитываем колонки (примерно)
        int columns = Math.max(1, (int)((Core.graphics.getWidth() * 0.8f) / 54f) - 1);


        // Вспомогательный метод для обработки клика (чтобы не дублировать код)
        Runnable handleResult = () -> {
            saveData();
            picker.hide();
            rebuild();
            if (tab != null) rebuildTabs();
            // Если мы меняли глобальную настройку (slot и tab = null), обновляем само окно настроек
            if (slot == null && tab == null && parent != null) setupSettingsContent(parent);
        };

        // КНОПА "НЕТ ИКОНКИ" В НАЧАЛО
        if (query.isEmpty() || "none".contains(query)) {
            t.button(Icon.none, () -> {
                if (slot != null) { slot.iconName = "none"; slot.isContent = false; }
                else if (tab != null) {
                    if (editDefault) { // Меням дефолт для слотов
                        tab.defaultSlotIcon = "none"; tab.defaultSlotIsContent = false;
                    } else { // Меняем иконку самой вкладки
                        tab.iconName = "none"; tab.isContent = false;
                    }
                }
                else {
                    Core.settings.put("qs-default-icon", "none");
                    Core.settings.put("qs-default-iscontent", false);
                }
                handleResult.run();
            }).tooltip(arc.Core.bundle.get("gl.ui.quickschem.14"));
            if (++count % columns == 0) t.row();
        }

        // 1. Системные иконки (фильтр только по названию поля)
        for (java.lang.reflect.Field field : Icon.class.getFields()) {
            if (field.getType() == TextureRegionDrawable.class) {
                String name = field.getName();

                // Если запрос не пустой и имя иконки его не содержит - пропускаем
                if (!query.isEmpty() && !name.toLowerCase().contains(query)) continue;

                t.button((TextureRegionDrawable)getIconDrawable(name, false), () -> {
                    if(slot != null) { slot.iconName = name; slot.isContent = false; }
                    else if(tab != null) {
                        if (editDefault) {
                            tab.defaultSlotIcon = name; tab.defaultSlotIsContent = false;
                        } else {
                            tab.iconName = name; tab.isContent = false;
                        }
                    }
                    else {
                        Core.settings.put("qs-default-icon", name);
                        Core.settings.put("qs-default-iscontent", false);
                    }
                    handleResult.run();
                });

                if (++count % columns == 0) t.row();
            }
        }

        // Если нашли что-то из системных и будем искать дальше - добавим разделитель
        if (count > 0) {
            t.row();
            t.image().height(4).color(Pal.accent).fillX().colspan(columns).pad(10).row();
            count = 0; // Сбрасываем счетчик для новой секции
        }

        // 2. Иконки контента (блоки, юниты, предметы)
        for (ContentType type : new ContentType[]{ContentType.block, ContentType.unit, ContentType.item, ContentType.liquid, ContentType.status, ContentType.planet, ContentType.weather}) {
            for (var content : Vars.content.getBy(type)) {
                if (content instanceof UnlockableContent uc) {
                    String internalName = uc.name.toLowerCase();
                    String localizedName = uc.localizedName.toLowerCase();

                    // Фильтр по внутреннему ИЛИ локализованному имени
                    if (!query.isEmpty() && !internalName.contains(query) && !localizedName.contains(query)) continue;

                    t.button(new TextureRegionDrawable(uc.uiIcon), () -> {
                        if(slot != null) { slot.iconName = uc.name; slot.isContent = true; }
                        else if(tab != null) {
                            if(editDefault) {
                                tab.defaultSlotIcon = uc.name; tab.defaultSlotIsContent = true;
                            } else {
                                tab.iconName = uc.name; tab.isContent = true;
                            }
                        }
                        else {
                            Core.settings.put("qs-default-icon", uc.name);
                            Core.settings.put("qs-default-iscontent", true);
                        }
                        handleResult.run();
                    });

                    if (++count % columns == 0) t.row();
                }
            }
        }
    }

    private void showEditDialog(QuickSlot slot) {
        BaseDialog dialog = new BaseDialog(arc.Core.bundle.get("gl.ui.quickschem.15"));
        dialog.cont.add(arc.Core.bundle.get("gl.ui.quickschem.16")).left().row();
        dialog.cont.field(slot.schemName, val -> {
            slot.schemName = val;
            saveData();
        }).growX().row();
        dialog.cont.button(arc.Core.bundle.get("gl.ui.quickschem.17"), () -> showIconPicker(slot, null, false, dialog)).size(200, 50);
        dialog.addCloseButton();
        dialog.hidden(this::rebuild);
        dialog.show();
    }

    private void useSchematic(String name) {
        if (name == null || name.isEmpty()) return;
        var schem = Vars.schematics.all().find(s -> s.name().equals(name));
        if (schem != null) {
            Vars.control.input.useSchematic(schem);
        } else {
            Vars.ui.showInfoFade((arc.Core.bundle.get("gl.ui.quickschem.18") + " ") + name);
        }
    }

    private void loadData() {
        var file = Vars.dataDirectory.child("quickschems.json");
        Log.info("Попытка загрузить quickschems из: @ (существует: @)", file.absolutePath(), file.exists());

        if (file.exists()) {
            try {
                String content = file.readString();
                Log.info("Прочитано символов из файла: @", content.length());

                Seq<QuickTab> loaded = json.fromJson(Seq.class, QuickTab.class, content);
                tabs = (loaded != null && loaded.any()) ? loaded : new Seq<>();

                for(QuickTab tab : tabs) {
                    if(tab != null) tab.validate();
                }
                Log.info("Успешно загружено вкладок: @", tabs.size);
            } catch (Throwable e) {
                // Выведет точную причину в терминал IDEA
                Log.err("КРИТИЧЕСКАЯ ОШИБКА ПАРСИНГА quickschems.json", e);
                tabs = new Seq<>();
            }
        } else {
            tabs = new Seq<>();
        }

        if (tabs.isEmpty()) {
            Log.warn("Список вкладок пуст, создаем General");
            tabs.add(new QuickTab("General"));
        }
    }

    private void saveData() {
        var file = Vars.dataDirectory.child("quickschems.json");
        file.writeString(json.prettyPrint(tabs));
    }

    public void toggle() {
        visible = !visible;
        if (visible) { rebuild(); toFront(); }
    }

    private void updateSize() {
        invalidateHierarchy();
        pack();
    }

    private int syncSlots(QuickTab tab) {
        int cols = Core.settings.getInt("qs-cols", 5);
        int rows = Core.settings.getInt("qs-rows", 4);
        int totalSlots = cols * rows;

        while (tab.slots.size < totalSlots) {
            QuickSlot ns = new QuickSlot();
            ns.iconName = "none";
            ns.isContent = false;
            tab.slots.add(ns);
        }
        return totalSlots;
    }
}