package mindustry.client.fallen;

import arc.*;
import arc.func.Cons;
import arc.graphics.*;
import arc.input.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.client.ClientVars;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.net.*;
import mindustry.net.Host;
import mindustry.ui.*;
import mindustry.ui.dialogs.BaseDialog;

import static mindustry.Vars.*;

public class FavoriteServersFrag extends Table {
    private Table container = new Table();
    private Seq<FavoriteServer> favorites = new Seq<>();
    private boolean visible = false;

    private float lastX = 0, lastY = 0;
    private boolean centered = false;
    private Timer.Task refreshTask;

    public static class FavoriteServer {
        public String name;
        public String ip;
        public int port;
        public transient Host lastHost;

        public FavoriteServer() {}

        public FavoriteServer(String ip, int port) {
            this.ip = ip;
            this.port = port;
            this.name = "";
        }

        public String displayIP() {
            if (Strings.count(ip, ':') > 1) {
                return port != Vars.port ? "[" + ip + "]:" + port : ip;
            }
            return ip + (port != Vars.port ? ":" + port : "");
        }

        public String displayName() {
            if (lastHost != null && !lastHost.name.isEmpty()) {
                return lastHost.name;
            }
            return !name.isEmpty() ? name : displayIP();
        }
    }

    public void build(Group parent) {
        parent.addChild(this);
        updateSize();

        loadFavorites();
        scheduleRefresh();

        visible(() -> visible && Core.settings.getBool("favoriteservers", true));

        table(Styles.black6, main -> {
            main.table(ctrl -> {
                ImageButton drag = ctrl.button(Icon.move, Styles.cleari, () -> {}).size(35f).get();
                drag.addListener(new InputListener() {
                    @Override public boolean touchDown(InputEvent e, float x, float y, int p, KeyCode b) { lastX = x; lastY = y; return true; }
                    @Override public void touchDragged(InputEvent e, float x, float y, int p) { moveBy(x - lastX, y - lastY); }
                    @Override public void touchUp(InputEvent e, float x, float y, int p, KeyCode b) {
                        Core.settings.put("favfrag-x", FavoriteServersFrag.this.x);
                        Core.settings.put("favfrag-y", FavoriteServersFrag.this.y);
                    }
                });
                ctrl.row();
                ctrl.button(Icon.settings, Styles.cleari, this::showSettings).size(35f).tooltip("@settings");
                ctrl.row();
                ctrl.button(Icon.cancel, Styles.cleari, this::toggle).size(35f);
                ctrl.add().growY();
            }).width(45f).growY().top();

            main.image().growY().width(2f).color(Pal.coalBlack);

            main.pane(p -> {
                p.top();
                p.add(container).growX().top().pad(0);
                rebuild();
            }).growX().top().update(pane -> {
                if(Core.scene.getScrollFocus() == pane && !Core.input.shift()) Core.scene.setScrollFocus(null);
            });
        }).growX().top();

        update(() -> {
            if(!centered && Core.graphics.getWidth() > 0){
                float sx = Core.settings.has("favfrag-x") ? Core.settings.getFloat("favfrag-x") : Core.graphics.getWidth() - width - 20f;
                float sy = Core.settings.has("favfrag-y") ? Core.settings.getFloat("favfrag-y") : Core.graphics.getHeight() / 2f;
                setPosition(Mathf.clamp(sx, 0, Core.graphics.getWidth() - width), Mathf.clamp(sy, 0, Core.graphics.getHeight() - height));
                centered = true;
            }
        });
    }

    private void scheduleRefresh() {
        if (refreshTask != null) refreshTask.cancel();
        float interval = Core.settings.getInt("fav-refresh-interval", 30);
        refreshTask = Timer.schedule(this::refreshAll, 0f, interval);
    }

    private void updateSize() {
        int cols = Core.settings.getInt("fav-columns", 2);
        float cardWidth = 280f;
        float totalWidth = cardWidth * cols + (cols - 1) * 4f + 20f;
        setSize(Mathf.clamp(totalWidth, 400f, Core.graphics.getWidth() * 0.9f), 250f);
    }

    private void loadFavorites() {
        favorites = Core.settings.getJson("favorite-servers", Seq.class, FavoriteServer.class, Seq::new);
    }

    private void saveFavorites() {
        Core.settings.putJson("favorite-servers", FavoriteServer.class, favorites);
    }

    public void rebuild() {
        if (container == null) return;
        container.clear();

        int cols = Core.settings.getInt("fav-columns", 2);
        int col = 0;

        if (favorites.isEmpty()) {
            container.add("[gray]No favorite servers[]").color(Color.gray).fontScale(0.8f).center().growX().padTop(10f);
            return;
        }

        Table rowTable = null;

        for (FavoriteServer server : favorites) {
            if (col % cols == 0) {
                if (rowTable != null) {
                    container.add(rowTable).growX();
                    container.row();
                }
                rowTable = new Table();
                rowTable.defaults().pad(0f);
            }

            rowTable.table(t -> addServerCard(t, server)).growX().pad(0f);
            col++;
        }

        if (rowTable != null) {
            container.add(rowTable).growX();
        }
    }

    private void addServerCard(Table card, FavoriteServer server) {
        card.button(b -> {
            b.left().margin(10f).defaults().left();

            b.table(info -> {
                info.left().defaults().left();

                info.add(new Label(() -> server.displayName() + "[white]"))
                        .color(Pal.accent).fontScale(0.75f).growX().ellipsis(true);
                info.row();

                // Динамическая инфа (Карта | Игроки | Пинг)
                info.add(new Label(() -> {
                    if (server.lastHost == null) return "[gray]Pinging...[]";
                    Host h = server.lastHost;
                    String players = "[white]"+ h.players + (h.playerLimit > 0 ? "/" + h.playerLimit : "");
                    return "[lightgray]" + h.mapname + " [white]|[] " + players + " [white]|[] " + h.ping + "ms";
                })).fontScale(0.6f).growX().ellipsis(true);

            }).grow();

        }, Styles.flatBordert, () -> connectToServer(server)).width(270f).height(60f).pad(0f);
    }

    private void showSettings() {
        BaseDialog dialog = new BaseDialog("@favorites");
        dialog.setWidth(400f);
        dialog.setFillParent(false);

        dialog.cont.table(t -> {
            t.defaults().growX().pad(3f);

            // Интервал авто-обновления
            t.table(refreshInfo -> {
                refreshInfo.add("[lightgray]Auto-refresh interval:[]").fontScale(0.75f).right();
                int currentInterval = Core.settings.getInt("fav-refresh-interval", 30);
                refreshInfo.add(" " + currentInterval + "s[]").color(Pal.accent).fontScale(0.85f);
                refreshInfo.button(Icon.refresh, Styles.cleari, () -> {
                    refreshAll();
                    Log.info("Refreshing servers...");
                }).size(28f).tooltip("Refresh now");
            }).growX().right();
            t.row();

            t.table(sliderTable -> {
                Slider slider = sliderTable.slider(10, 120, 10, Core.settings.getInt("fav-refresh-interval", 30), val -> {
                    int newVal = (int)val;
                    Core.settings.put("fav-refresh-interval", newVal);
                    scheduleRefresh();
                }).growX().get();

                Label intervalLabel = new Label("", Styles.outlineLabel);
                intervalLabel.update(() -> {
                    intervalLabel.setText(String.valueOf((int)slider.getValue()) + "s");
                });
                sliderTable.add(intervalLabel).width(40f).padLeft(5f);

            }).growX();
            t.row();

            // Количество колонок
            t.add("Columns:").left().padTop(8f).padBottom(3f).color(Pal.accent);
            t.row();

            t.table(colsTable -> {
                Slider slider = colsTable.slider(1, 4, 1, Core.settings.getInt("fav-columns", 2), val -> {
                    Core.settings.put("fav-columns", (int)val);
                    updateSize();
                    rebuild();
                }).growX().get();

                Label intervalLabel = new Label("", Styles.outlineLabel);
                intervalLabel.update(() -> {
                    intervalLabel.setText(String.valueOf((int)slider.getValue()) + "s");
                });
                colsTable.add(intervalLabel).width(40f).padLeft(5f);
            }).growX();
            t.row();

            t.add("@server.add").left().padTop(8f).padBottom(5f).color(Pal.accent);
            t.row();

            t.table(input -> {
                TextField ipField = input.field("", text -> {}).maxTextLength(100).growX().get();
                ipField.setMessageText("127.0.0.1:6567");
                input.button(Icon.add, Styles.cleari, () -> addFavoriteServer(ipField, dialog)).size(40f);
            }).row();

            t.add("@favorites.list").left().padTop(8f).padBottom(4f).color(Pal.accent);
            t.row();

            ScrollPane scroll = new ScrollPane(new Table(list -> {
                list.defaults().growX().pad(2f);
                if (favorites.isEmpty()) {
                    list.add("[gray]No servers[]").center().padTop(10f).fontScale(0.9f);
                    return;
                }

                for (int i = 0; i < favorites.size; i++) {
                    FavoriteServer s = favorites.get(i);
                    final int index = i;
                    list.table(row -> {
                        row.left().defaults().pad(1f);

                        row.button(Icon.upOpen, Styles.cleari, () -> {
                            if (index > 0) {
                                favorites.insert(index - 1, favorites.remove(index));
                                saveFavorites();
                                rebuild();
                                dialog.hide();
                                showSettings();
                            }
                        }).size(26f).disabled(b -> index == 0).tooltip("Move up");

                        row.button(Icon.copy, Styles.cleari, () -> {
                            Core.app.setClipboardText(s.displayIP());
                            Vars.ui.showInfoFade("@copied");
                        }).size(26f).tooltip("Copy IP");

                        row.button(Icon.downOpen, Styles.cleari, () -> {
                            if (index < favorites.size - 1) {
                                favorites.insert(index + 1, favorites.remove(index));
                                saveFavorites();
                                rebuild();
                                dialog.hide();
                                showSettings();
                            }
                        }).size(26f).disabled(b -> index == favorites.size - 1).tooltip("Move down");

                        row.add(new Label(() -> s.displayName()))
                                .growX()
                                .fontScale(0.9f)
                                .left()
                                .ellipsis(true)
                                .padLeft(8f)
                                .color(Color.white);

                        row.button(Icon.trash, Styles.cleari, () -> {
                            favorites.remove(s);
                            saveFavorites();
                            rebuild();
                            dialog.hide();
                            showSettings();
                        }).size(26f).tooltip("@delete");

                    }).growX().margin(2f);
                    list.row();
                }
            }));

            t.add(scroll);
        }).growX();

        dialog.addCloseButton();
        dialog.show();
    }

    private void addFavoriteServer(TextField ipField, BaseDialog dialog) {
        String ip = ipField.getText().trim();
        if (ip.isEmpty()) {
            ui.showInfo("Enter server address");
            return;
        }

        String address = ip;
        int port = Vars.port;

        try {
            if (Strings.count(ip, ':') > 1 && ip.lastIndexOf("]:") != -1) {
                int idx = ip.indexOf("]:");
                address = ip.substring(1, idx);
                port = Strings.parseInt(ip.substring(idx + 2), Vars.port);
            } else if (!ip.contains(" ") && ip.lastIndexOf(':') != -1 && ip.lastIndexOf(':') != ip.length() - 1) {
                int idx = ip.lastIndexOf(':');
                address = ip.substring(0, idx);
                port = Strings.parseInt(ip.substring(idx + 1), Vars.port);
            }

            String finalAddress = address;
            int finalPort = port;
            if (favorites.contains(s -> s.ip.equals(finalAddress) && s.port == finalPort)) {
                ui.showInfo("Server already in favorites");
                return;
            }

            FavoriteServer newServer = new FavoriteServer(address, port);
            favorites.add(newServer);
            saveFavorites();
            rebuild();
            refreshServer(newServer);

            dialog.hide();
            showSettings();
        } catch (Exception e) {
            ui.showInfo("Invalid format\nUse: IP:Port\nExample: 127.0.0.1:6567");
        }
    }

    private void connectToServer(FavoriteServer server) {
        if (player.name.trim().isEmpty()) {
            ui.showInfo("@noname");
            return;
        }
        Vars.netClient.connect(server.ip, server.port);

    }



    public void refreshAll() {
        if(!visible || !Core.settings.getBool("favoriteservers", true)) return;
        for (FavoriteServer s : favorites) {
            net.pingHost(s.ip, s.port, host -> s.lastHost = host, e -> s.lastHost = null);
        }
    }

    public void refreshServer(FavoriteServer server) {
        net.pingHost(server.ip, server.port, host -> {
            server.lastHost = host;
            Core.app.post(this::rebuild);
        }, e -> {
            server.lastHost = null;
            Core.app.post(this::rebuild);
        });
    }

    public void toggle() {
        visible = !visible;
        if (visible) { rebuild(); toFront(); }
    }

    public void dispose() {
        if (refreshTask != null) refreshTask.cancel();
    }
}