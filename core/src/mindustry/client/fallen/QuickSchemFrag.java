package mindustry.client.fallen;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;

import static arc.Core.*;
import static mindustry.Vars.*;

/**
 * GL: quick schematics panel. Categories on top, a grid of schematic slots under them, the hovered schematic is
 * previewed in the middle of the screen with its cost. Everything is set up right in the panel:
 * <ul>
 * <li>left click on a slot places its schematic, on an empty slot it opens the schematic list;</li>
 * <li>right click on a slot opens its editor (schematic, icon, clearing);</li>
 * <li>dragging a slot onto another swaps them;</li>
 * <li>right click on a category (or its name) edits it, the gear opens the panel settings.</li>
 * </ul>
 * A slot without its own icon shows the biggest block of its schematic. Kept in quickschems.json, the old format loads as is.
 */
public class QuickSchemFrag extends Table{
    private static final String file = "quickschems.json";

    // region data

    public static class QuickSlot{
        public String schemName = "";
        /** "none" means automatic: the biggest block of the schematic. */
        public String iconName = "none";
        public boolean isContent = false;

        public QuickSlot(){}
    }

    public static class QuickTab{
        public String name = "Tab";
        public String iconName = "none";
        public boolean isContent = false;
        public boolean useIcon = false;
        /** Old format fields, kept so older files still load. */
        public String defaultSlotIcon = "none";
        public boolean defaultSlotIsContent = false;
        public Seq<QuickSlot> slots = new Seq<>();

        public QuickTab(){}

        public QuickTab(String name){
            this.name = name;
        }

        void validate(){
            if(name == null) name = "Tab";
            if(iconName == null) iconName = "none";
            if(defaultSlotIcon == null) defaultSlotIcon = "none";
            if(slots == null) slots = new Seq<>();
            slots.removeAll(s -> s == null);
            for(QuickSlot slot : slots){
                if(slot.iconName == null) slot.iconName = "none";
                if(slot.schemName == null) slot.schemName = "";
            }
        }
    }

    private final Json json = new Json(){{
        setIgnoreUnknownFields(true);
        addClassTag("mindustry.client.fallen.QuickSchemFrag$QuickTab", QuickTab.class);
        addClassTag("mindustry.client.fallen.QuickSchemFrag.QuickTab", QuickTab.class);
        addClassTag("QuickTab", QuickTab.class);
        addClassTag("QuickSlot", QuickSlot.class);
        setElementType(QuickTab.class, "slots", QuickSlot.class);
    }};

    private Seq<QuickTab> tabs = new Seq<>();
    private int currentTab;

    // endregion
    // region state

    private boolean shown = settings.getBool("quickschems", false);
    private final Table body = new Table();
    private final Table preview = new Table();
    private @Nullable Schematic hovered;
    private final ObjectMap<Schematic, TextureRegion> autoIcons = new ObjectMap<>();
    private ImageButton.ImageButtonStyle slotStyle, tabStyle;
    /** Slot being dragged onto another one, -1 when nothing is dragged. */
    private int dragFrom = -1;
    private final Vec2 dragStart = new Vec2();
    private boolean placed;

    private static int cols(){
        return settings.getInt("qs2-cols", 6);
    }

    private static int rows(){
        return settings.getInt("qs2-rows", 8);
    }

    private static float size(){
        return settings.getInt("qs2-size", 34);
    }

    // endregion

    public void build(Group parent){
        loadData();

        slotStyle = new ImageButton.ImageButtonStyle(){{
            up = ((TextureRegionDrawable)Tex.whiteui).tint(0f, 0f, 0f, 0.35f);
            over = Styles.flatOver;
            down = Styles.flatDown;
        }};
        tabStyle = new ImageButton.ImageButtonStyle(){{
            up = Styles.none;
            over = Styles.flatOver;
            down = Styles.flatDown;
            checked = ((TextureRegionDrawable)Tex.whiteui).tint(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.3f);
        }};

        parent.addChild(this);
        touchable = Touchable.childrenOnly;
        visible(() -> ui.hudfrag.shown && shown && settings.getBool("quickschems", false));
        add(body);
        rebuild();

        // keeps the top left corner in place when the panel changes size, and inside the screen
        update(() -> {
            float sw = scene.getWidth(), sh = scene.getHeight();
            if(!placed && sw > 0){
                placed = true;
                x = settings.getFloat("qs2-x", sw - getPrefWidth() - Scl.scl(10f));
                top = settings.getFloat("qs2-top", sh * 0.75f);
            }
            setSize(getPrefWidth(), getPrefHeight());
            top = Mathf.clamp(top, getHeight(), sh);
            x = Mathf.clamp(x, 0f, Math.max(sw - getWidth(), 0f));
            y = top - getHeight();
            if(hovered != null && !hasMouse()) hovered = null;
        });

        parent.fill(t -> {
            t.touchable = Touchable.disabled;
            t.add(preview);
            t.visible(() -> visible && hovered != null && settings.getBool("qs2-preview", true) && dragFrom == -1);
            t.update(() -> buildPreview(hovered));
        });
    }

    private float top;

    public void toggle(){
        shown = !shown;
        if(shown){
            if(!settings.getBool("quickschems", false)) settings.put("quickschems", true);
            rebuild();
            toFront();
        }
    }

    // region panel

    public void rebuild(){
        if(tabs.isEmpty()) tabs.add(new QuickTab(bundle.get("gl.ui.qs.defaulttab")));
        currentTab = Mathf.clamp(currentTab, 0, tabs.size - 1);
        QuickTab tab = tabs.get(currentTab);
        int cols = cols(), total = cols * rows();
        while(tab.slots.size < total) tab.slots.add(new QuickSlot());
        autoIcons.clear();

        float size = size();
        body.clear();
        body.background(Tex.buttonEdge4);
        body.margin(6f, 6f, 8f, 8f);

        // title row: drag handle, category name, new category, settings
        body.table(h -> {
            h.left();
            ImageButton move = h.button(Icon.move, Styles.clearNonei, size * 0.55f, () -> {}).size(size * 0.8f).get();
            move.addListener(new InputListener(){
                float lx, ly;

                @Override
                public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode key){
                    lx = e.stageX;
                    ly = e.stageY;
                    return true;
                }

                @Override
                public void touchDragged(InputEvent e, float x, float y, int pointer){
                    QuickSchemFrag.this.x += e.stageX - lx;
                    top += e.stageY - ly;
                    lx = e.stageX;
                    ly = e.stageY;
                }

                @Override
                public void touchUp(InputEvent e, float x, float y, int pointer, KeyCode key){
                    settings.put("qs2-x", QuickSchemFrag.this.x);
                    settings.put("qs2-top", top);
                }
            });
            tooltip(move, "gl.ui.qs.move");

            Label name = h.add(tab.name, Styles.outlineLabel).color(Pal.accent).width(Math.max(cols * (size + 2f) - 3f * size * 0.8f - 8f, 20f)).padLeft(4f).padRight(4f).get(); // the room left by the buttons, a long name ends with "..."
            name.setEllipsis(true);
            name.setFontScale(0.85f);
            name.clicked(KeyCode.mouseRight, () -> editTab(currentTab));
            tooltip(name, "gl.ui.qs.tabname");

            tooltip(h.button(Icon.add, Styles.clearNonei, size * 0.55f, () -> {
                tabs.add(new QuickTab(bundle.format("gl.ui.qs.newtab", tabs.size + 1)));
                currentTab = tabs.size - 1;
                saveData();
                rebuild();
            }).size(size * 0.8f).get(), "gl.ui.qs.addtab");
            tooltip(h.button(Icon.settings, Styles.clearNonei, size * 0.55f, this::showSettings).size(size * 0.8f).get(), "gl.ui.qs.settings");
        }).growX().row();

        // categories
        body.table(g -> {
            g.left().defaults().size(size).pad(1f);
            for(int i = 0; i < tabs.size; i++){
                int index = i;
                QuickTab t = tabs.get(i);
                boolean withIcon = t.useIcon && !"none".equals(t.iconName);
                ImageButton b = g.button(withIcon ? icon(t.iconName, t.isContent) : Styles.none, tabStyle, size * 0.7f, () -> {
                    currentTab = index;
                    rebuild();
                }).get();
                if(!withIcon){
                    b.clearChildren();
                    b.add(t.name.isEmpty() ? String.valueOf(index + 1) : t.name.substring(0, Math.min(2, t.name.length())), Styles.outlineLabel).fontScale(0.75f);
                }
                b.setChecked(index == currentTab);
                b.clicked(KeyCode.mouseRight, () -> editTab(index));
                b.addListener(new Tooltip(tt -> tt.background(Styles.black6).margin(4f).add(t.name, Styles.outlineLabel)));
                if((i + 1) % cols == 0) g.row();
            }
        }).left().padTop(2f).row();

        body.image().color(Pal.accent).height(2f).growX().padTop(3f).padBottom(3f).row();

        // slots
        body.table(g -> {
            g.left().defaults().size(size).pad(1f);
            for(int i = 0; i < total; i++){
                addSlot(g, tab, i, size);
                if((i + 1) % cols == 0) g.row();
            }
        }).left();

        pack();
    }

    private void addSlot(Table g, QuickTab tab, int index, float size){
        QuickSlot slot = tab.slots.get(index);
        Schematic schem = findSchem(slot.schemName);
        Drawable drawable = schem == null ? Icon.pencil : "none".equals(slot.iconName) ? new TextureRegionDrawable(autoIcon(schem)) : icon(slot.iconName, slot.isContent);

        ImageButton b = g.button(drawable, slotStyle, size * 0.7f, () -> {
            if(schem != null) control.input.useSchematic(schem);
            else pickSchematic(slot);
        }).get();
        b.userObject = index;
        b.getImage().setScaling(Scaling.fit);
        if(schem == null){
            b.getImage().setColor(slot.schemName.isEmpty() ? Color.gray.cpy().a(0.5f) : Pal.remove); // red: the schematic was renamed or deleted
            b.resizeImage(size * 0.45f);
        }

        b.hovered(() -> hovered = schem);
        b.exited(() -> {
            if(hovered == schem) hovered = null;
        });
        b.clicked(KeyCode.mouseRight, () -> editSlot(slot));
        if(schem == null && !slot.schemName.isEmpty()) tooltipText(b, bundle.format("gl.ui.qs.missing", slot.schemName));

        // drag a slot onto another one to swap them
        b.addCaptureListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode key){
                if(key != KeyCode.mouseLeft) return false;
                dragFrom = -1;
                dragStart.set(e.stageX, e.stageY);
                return true;
            }

            @Override
            public void touchDragged(InputEvent e, float x, float y, int pointer){
                if(dragFrom == -1 && dragStart.dst(e.stageX, e.stageY) > Scl.scl(8f)) dragFrom = index;
            }

            @Override
            public void touchUp(InputEvent e, float x, float y, int pointer, KeyCode key){
                if(dragFrom != index) return;
                dragFrom = -1;
                Element over = scene.hit(e.stageX, e.stageY, true);
                while(over != null && !(over.userObject instanceof Integer)) over = over.parent;
                if(over != null && over.userObject instanceof Integer to && to != index && isAscendantOf(over)){
                    tab.slots.swap(index, to);
                    saveData();
                    app.post(QuickSchemFrag.this::rebuild);
                }
                e.cancel(); // no click after a drag
            }
        });
    }

    private void buildPreview(@Nullable Schematic schem){
        if(schem == null || preview.userObject == schem) return;
        preview.userObject = schem;
        preview.clear();
        preview.background(Styles.black6);
        preview.margin(8f);
        preview.add(schem.name(), Styles.outlineLabel).color(Pal.accent).padBottom(4f).row();
        float scale = Math.min(Math.min(360f / (schem.width * 8f), 360f / (schem.height * 8f)), 4f);
        preview.add(new SchematicsDialog.SchematicImage(schem)).size(schem.width * 8f * scale, schem.height * 8f * scale).row();
        preview.add(bundle.format("gl.ui.qs.info", schem.width, schem.height, schem.tiles.size), Styles.outlineLabel).color(Color.lightGray).padTop(4f).row();

        preview.table(req -> {
            int i = 0;
            for(ItemStack stack : schem.requirements()){
                Item item = stack.item;
                int amount = stack.amount;
                req.image(item.uiIcon).size(iconSmall).left();
                req.label(() -> {
                    var core = player.core();
                    if(core == null || state.rules.infiniteResources || core.items.has(item, amount)) return "[lightgray]" + amount;
                    return "[scarlet]" + core.items.get(item) + "[lightgray]/" + amount;
                }).padLeft(2f).padRight(8f).left();
                if(++i % 4 == 0) req.row();
            }
        }).padTop(4f).row();

        float produce = schem.powerProduction() * 60f, consume = schem.powerConsumption() * 60f;
        if(produce > 0.001f || consume > 0.001f){
            preview.table(p -> {
                if(produce > 0.001f){
                    p.image(Icon.powerSmall).color(Pal.powerLight).padRight(3f);
                    p.add("+" + Strings.autoFixed(produce, 2)).color(Pal.powerLight).padRight(12f);
                }
                if(consume > 0.001f){
                    p.image(Icon.powerSmall).color(Pal.remove).padRight(3f);
                    p.add("-" + Strings.autoFixed(consume, 2)).color(Pal.remove);
                }
            }).padTop(4f);
        }
    }

    // endregion
    // region editing

    private void editSlot(QuickSlot slot){
        BaseDialog dialog = new BaseDialog(bundle.get("gl.ui.qs.slot"));
        Runnable[] fill = {null};
        fill[0] = () -> {
            dialog.cont.clear();
            Schematic schem = findSchem(slot.schemName);
            dialog.cont.table(Styles.black3, t -> {
                t.margin(10f);
                if(schem != null){
                    t.add(new SchematicsDialog.SchematicImage(schem)).size(120f).padRight(10f);
                }
                t.table(info -> {
                    info.left().defaults().left();
                    info.add(schem != null ? schem.name() : slot.schemName.isEmpty() ? bundle.get("gl.ui.qs.empty") : bundle.format("gl.ui.qs.missing", slot.schemName))
                        .color(schem != null ? Pal.accent : Color.lightGray).wrap().width(300f).row();
                    info.button(bundle.get("gl.ui.qs.pickschem"), Icon.paste, () -> pickSchematic(slot, () -> fill[0].run())).size(260f, 50f).padTop(8f).row();
                });
            }).row();

            dialog.cont.table(t -> {
                t.add(bundle.get("gl.ui.qs.icon")).padRight(10f);
                t.button(schem != null && "none".equals(slot.iconName) ? new TextureRegionDrawable(autoIcon(schem)) : icon(slot.iconName, slot.isContent), Styles.cleari, 40f,
                    () -> pickIcon(true, (name, isContent) -> {
                        slot.iconName = name;
                        slot.isContent = isContent;
                        saveData();
                        fill[0].run();
                    })).size(56f);
                t.add("none".equals(slot.iconName) ? bundle.get("gl.ui.qs.iconauto") : "").color(Color.lightGray).padLeft(10f);
            }).padTop(12f).row();

            dialog.cont.button(bundle.get("gl.ui.qs.clear"), Icon.trash, () -> {
                slot.schemName = "";
                slot.iconName = "none";
                slot.isContent = false;
                saveData();
                dialog.hide();
            }).size(260f, 50f).padTop(12f).disabled(b -> slot.schemName.isEmpty() && "none".equals(slot.iconName));
        };
        fill[0].run();
        dialog.addCloseButton();
        dialog.hidden(this::rebuild);
        dialog.show();
    }

    private void pickSchematic(QuickSlot slot){
        pickSchematic(slot, null);
    }

    /** The game's schematics list (tags, search) in choosing mode, a click puts the schematic into the slot. */
    private void pickSchematic(QuickSlot slot, @Nullable Runnable done){
        ui.schematics.pick(schem -> {
            slot.schemName = schem.name();
            saveData();
            if(done != null) done.run();
            rebuild();
        });
    }

    private void editTab(int index){
        QuickTab tab = tabs.get(index);
        BaseDialog dialog = new BaseDialog(bundle.get("gl.ui.qs.tab"));
        Runnable[] fill = {null};
        fill[0] = () -> {
            dialog.cont.clear();
            dialog.cont.defaults().left().padTop(6f);
            dialog.cont.table(t -> {
                t.add(bundle.get("gl.ui.qs.name")).padRight(8f);
                t.field(tab.name, text -> {
                    tab.name = text;
                    saveData();
                }).width(260f);
            }).row();
            dialog.cont.table(t -> {
                t.add(bundle.get("gl.ui.qs.icon")).padRight(8f);
                t.button(icon(tab.iconName, tab.isContent), Styles.cleari, 40f, () -> pickIcon(false, (name, isContent) -> {
                    tab.iconName = name;
                    tab.isContent = isContent;
                    tab.useIcon = !"none".equals(name);
                    saveData();
                    fill[0].run();
                })).size(56f);
                t.add("none".equals(tab.iconName) ? bundle.get("gl.ui.qs.iconname") : "").color(Color.lightGray).padLeft(10f);
            }).row();
            dialog.cont.table(t -> {
                t.defaults().size(56f).padRight(6f);
                tooltipText(t.button(Icon.left, Styles.cleari, () -> {
                    move(index, -1);
                    dialog.hide();
                }).disabled(b -> index == 0).get(), bundle.get("gl.ui.qs.moveleft"));
                tooltipText(t.button(Icon.right, Styles.cleari, () -> {
                    move(index, 1);
                    dialog.hide();
                }).disabled(b -> index >= tabs.size - 1).get(), bundle.get("gl.ui.qs.moveright"));
                t.button(bundle.get("gl.ui.qs.deletetab"), Icon.trash, () -> ui.showConfirm(bundle.get("gl.ui.qs.deletetab"), bundle.format("gl.ui.qs.deleteconfirm", tab.name), () -> {
                    tabs.remove(index);
                    if(tabs.isEmpty()) tabs.add(new QuickTab(bundle.get("gl.ui.qs.defaulttab")));
                    currentTab = Math.min(currentTab, tabs.size - 1);
                    saveData();
                    dialog.hide();
                })).size(240f, 56f);
            }).padTop(12f);
        };
        fill[0].run();
        dialog.addCloseButton();
        dialog.hidden(this::rebuild);
        dialog.show();
    }

    private void move(int index, int by){
        int to = index + by;
        if(to < 0 || to >= tabs.size) return;
        tabs.swap(index, to);
        if(currentTab == index) currentTab = to;
        else if(currentTab == to) currentTab = index;
        saveData();
    }

    private void showSettings(){
        BaseDialog dialog = new BaseDialog(bundle.get("gl.ui.qs.settings"));
        Runnable[] fill = {null};
        fill[0] = () -> {
            dialog.cont.clear();
            dialog.cont.defaults().width(Math.min(scene.getWidth() / Scl.scl(1f) - 40f, 460f)).padTop(4f);
            slider(dialog.cont, "gl.ui.qs.cols", "qs2-cols", 2, 12, cols());
            slider(dialog.cont, "gl.ui.qs.rows", "qs2-rows", 1, 16, rows());
            slider(dialog.cont, "gl.ui.qs.size", "qs2-size", 24, 72, (int)size());
            dialog.cont.check(bundle.get("gl.ui.qs.preview"), settings.getBool("qs2-preview", true), v -> settings.put("qs2-preview", v)).left().padTop(10f).row();
            dialog.cont.add(bundle.get("gl.ui.qs.help")).color(Color.lightGray).wrap().padTop(14f).row();
            dialog.cont.table(t -> {
                t.left().defaults().size(250f, 50f).padRight(8f);
                t.button(bundle.get("gl.ui.qs.resetpos"), Icon.move, () -> {
                    settings.remove("qs2-x");
                    settings.remove("qs2-top");
                    placed = false;
                });
                // the panel settings only, the categories and slots stay
                t.button(bundle.get("gl.ui.qs.defaults"), Icon.refresh, () -> {
                    for(String key : new String[]{"qs2-cols", "qs2-rows", "qs2-size", "qs2-preview", "qs2-x", "qs2-top"}) settings.remove(key);
                    placed = false;
                    rebuild();
                    fill[0].run();
                });
            }).padTop(10f).left();
        };
        fill[0].run();
        dialog.addCloseButton();
        dialog.show();
    }

    private void slider(Table t, String key, String setting, int min, int max, int value){
        Label label = new Label("", Styles.outlineLabel);
        Slider slider = new Slider(min, max, 1, false);
        slider.setValue(value);
        Runnable text = () -> label.setText(bundle.get(key) + ": [accent]" + (int)slider.getValue());
        text.run();
        slider.changed(() -> {
            settings.put(setting, (int)slider.getValue());
            text.run();
            rebuild();
        });
        t.table(r -> {
            r.add(label).left().growX().row();
            r.add(slider).growX();
        }).row();
    }

    private interface IconPicked{
        void get(String name, boolean isContent);
    }

    /** Icons of the game and of all content, with a search by name. */
    private void pickIcon(boolean forSlot, IconPicked picked){
        BaseDialog dialog = new BaseDialog(bundle.get("gl.ui.qs.pickicon"));
        Table list = new Table();
        String[] query = {""};
        Runnable[] fill = {null};
        fill[0] = () -> {
            list.clear();
            list.top().left();
            int columns = Math.max((int)(scene.getWidth() * 0.85f / Scl.scl(52f)), 4);
            String q = query[0].toLowerCase();

            Table[] section = {null};
            int[] count = {0};
            Cons<String> header = title -> {
                list.table(h -> {
                    h.left();
                    h.add(title).color(Pal.accent).padRight(6f);
                    h.image().color(Pal.accent).height(2f).growX();
                }).growX().padTop(8f).padBottom(4f).row();
                list.table(s -> {
                    s.left().defaults().size(48f).pad(2f);
                    section[0] = s;
                }).left().row();
                count[0] = 0;
            };
            Cons3<Drawable, String, Boolean> add = (drawable, name, isContent) -> {
                section[0].button(drawable, Styles.clearNonei, 36f, () -> {
                    picked.get(name, isContent);
                    dialog.hide();
                }).tooltip(name);
                if(++count[0] % columns == 0) section[0].row();
            };

            if(q.isEmpty()){
                header.get(bundle.get(forSlot ? "gl.ui.qs.iconauto" : "gl.ui.qs.iconname"));
                add.get(forSlot ? Icon.refresh : Icon.edit, "none", false);
            }

            Seq<ContentType> types = Seq.with(ContentType.block, ContentType.unit, ContentType.item, ContentType.liquid, ContentType.status);
            for(ContentType type : types){
                boolean first = true;
                for(var c : content.getBy(type)){
                    if(!(c instanceof UnlockableContent uc) || uc.isHidden() || uc.uiIcon == null || !uc.uiIcon.found()) continue;
                    if(!q.isEmpty() && !uc.name.toLowerCase().contains(q) && !uc.localizedName.toLowerCase().contains(q)) continue;
                    if(first){
                        header.get(bundle.get("gl.ui.qs.type." + type.name()));
                        first = false;
                    }
                    add.get(new TextureRegionDrawable(uc.uiIcon), uc.name, true);
                }
            }

            boolean first = true;
            for(String name : Icon.icons.keys().toSeq().sort()){
                if(!q.isEmpty() && !name.toLowerCase().contains(q)) continue;
                if(first){
                    header.get(bundle.get("gl.ui.qs.type.icons"));
                    first = false;
                }
                add.get(Icon.icons.get(name), name, false);
            }
        };
        dialog.cont.table(t -> {
            t.image(Icon.zoom).padRight(8f);
            t.field("", text -> {
                query[0] = text;
                fill[0].run();
            }).growX().get().setMessageText(bundle.get("gl.ui.qs.search"));
        }).growX().pad(6f).row();
        dialog.cont.pane(list).grow().scrollX(false);
        fill[0].run();
        dialog.addCloseButton();
        dialog.show();
    }

    // endregion
    // region helpers

    private static void tooltip(Element e, String key){
        tooltipText(e, bundle.get(key));
    }

    private static void tooltipText(Element e, String text){
        if(!mobile) e.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add(text, Styles.outlineLabel)));
    }

    private static @Nullable Schematic findSchem(String name){
        if(name == null || name.isEmpty()) return null;
        return schematics.all().find(s -> s.name().equals(name));
    }

    private static Drawable icon(String name, boolean isContent){
        if(name == null || "none".equals(name)) return Icon.none;
        if(!isContent) return Icon.icons.get(name, Icon.none);
        for(ContentType type : ContentType.all){
            if(content.getByName(type, name) instanceof UnlockableContent uc) return new TextureRegionDrawable(uc.uiIcon);
        }
        return Icon.none;
    }

    /** The block that takes the most room in the schematic. */
    private TextureRegion autoIcon(Schematic schem){
        return autoIcons.get(schem, () -> {
            ObjectIntMap<Block> area = new ObjectIntMap<>();
            for(Schematic.Stile tile : schem.tiles) area.increment(tile.block, tile.block.size * tile.block.size);
            Block best = null;
            int max = 0;
            for(var e : area){
                if(e.value > max){
                    max = e.value;
                    best = e.key;
                }
            }
            return best == null ? Icon.paste.getRegion() : best.uiIcon;
        });
    }

    private void loadData(){
        Fi f = dataDirectory.child(file);
        tabs = new Seq<>();
        if(f.exists()){
            try{
                Seq<QuickTab> loaded = json.fromJson(Seq.class, QuickTab.class, f.readString());
                if(loaded != null) tabs = loaded;
            }catch(Throwable e){
                Log.err("Could not read " + file, e);
                f.copyTo(dataDirectory.child(file + ".broken"));
            }
        }
        tabs.removeAll(t -> t == null);
        for(QuickTab tab : tabs) tab.validate();
    }

    private void saveData(){
        dataDirectory.child(file).writeString(json.prettyPrint(tabs));
    }

    // endregion
}
