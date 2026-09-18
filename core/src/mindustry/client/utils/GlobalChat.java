package mindustry.client.utils;

import arc.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.game.*;
import mindustry.gen.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.security.cert.*;

import static mindustry.Vars.*;

/**
 * GL: global chat between GL Client players, through the GL chat server (newline separated JSON over TLS).
 * The server certificate is pinned, so a fake server cannot pretend to be it. Nothing secret is stored here:
 * every client has a random token of its own, and the server turns it into the short tag shown after the name,
 * so nobody can write under the tag of someone else.
 * Besides the global channel there is a channel for every game server (by its IP, so all modes of one server share
 * it): only players who are on that server now see it.
 */
public class GlobalChat{
    private static final String host = "2.26.10.69";
    private static final int port = 7160;
    /** SHA-256 of the server certificate (DER). */
    private static final String pin = "5373188ec5a8d68d4f930a38f2aadb8b9606ac35819d8ddcecb8865e4d81971e";
    private static final int maxText = 200, maxLog = 150;
    /** Start of every line of the global chat. */
    public static final String prefix = "[#7fd3ff][[GL][] ";
    /** Start of every line of the chat of the server the player is on. */
    public static final String serverPrefix = "[#a3e87a][[GL-S][] ";
    /** Kinds of {@link #lineKinds}: system lines are shown in both tabs. */
    public static final int kindSystem = 0, kindGlobal = 1, kindServer = 2;

    private static volatile boolean running;
    private static volatile SSLSocket socket;
    private static volatile Writer out;
    private static volatile boolean connected;
    private static volatile int online;
    private static volatile String tag = "";
    /** "owner", "curator" or "": given by the server, it checks the rights itself on every action. */
    private static volatile String role = "";
    /** Game server the player is on now (its IP, "" in the menu) and the server channel the chat server confirmed. */
    private static volatile String serverHost = "", channel = "";
    private static volatile int serverOnline;
    /** "mod" when the player is a moderator of the server he is on. */
    private static volatile String serverRole = "";
    private static String pendingHost = "";
    private static boolean hooked;
    /** Why the chat is not connected (shown to the player), null when there is no problem. */
    private static volatile @Nullable String error;
    private static Thread thread;

    /** Lines of the global chat for its window, main thread only. */
    public static final Seq<String> log = new Seq<>();
    /** Text copied when a line of {@link #log} is clicked (the message itself, without the name). */
    public static final Seq<String> copies = new Seq<>();
    /** Tag and name of the player who wrote each line of {@link #log} ("" for system lines), for the moderation buttons. */
    public static final Seq<String> lineTags = new Seq<>(), lineNames = new Seq<>();
    /** {@link #kindSystem}, {@link #kindGlobal} or {@link #kindServer} for each line of {@link #log}. */
    public static final IntSeq lineKinds = new IntSeq();
    /** Called on the main thread when a line is added to {@link #log}. */
    public static @Nullable Runnable listener;

    public static void init(){
        if(!hooked){
            hooked = true;
            // the server channel follows the game server: set when its world is loaded, cleared in the menu
            Events.on(EventType.ClientServerConnectEvent.class, e -> pendingHost = e.ip);
            Events.on(EventType.WorldLoadEvent.class, e -> {
                if(net.client()) setServer(pendingHost);
            });
            Events.on(EventType.MenuReturnEvent.class, e -> setServer(""));
        }
        // the server chat appeared later: whoever already used the global chat gets it on too
        if(!Core.settings.has("globalchat-server")) Core.settings.put("globalchat-server", globalOn());
        if(enabled()) start();
    }

    /** The game server the player is on (address as typed, it is resolved to the IP), "" when none. */
    public static void setServer(String address){
        String a = address == null ? "" : address.trim();
        if(a.isEmpty()){
            report("");
            return;
        }
        Thread t = new Thread(() -> {
            String h;
            try{
                h = InetAddress.getByName(a).getHostAddress();
            }catch(Exception e){
                h = a.toLowerCase();
            }
            report(h);
        }, "GL-GlobalChat-resolve");
        t.setDaemon(true);
        t.start();
    }

    private static void report(String h){
        if(h.equals(serverHost)) return;
        serverHost = h;
        if(connected) sendServer();
    }

    private static void sendServer(){
        Jval msg = Jval.newObject();
        msg.put("t", "server");
        msg.put("host", serverOn() ? serverHost : "");
        write(msg);
    }

    /** Server channel the player is in, "" when he is not on a server. */
    public static String channel(){
        return connected ? channel : "";
    }

    public static int serverOnline(){
        return serverOnline;
    }

    /** Connected to the chat server: the global channel or the server one is on. */
    public static boolean enabled(){
        return globalOn() || serverOn();
    }

    /** The global channel is on (it can be off while the chat of the server stays on). */
    public static boolean globalOn(){
        return Core.settings.getBool("globalchat", false);
    }

    /** The chat of the server the player is on is on. */
    public static boolean serverOn(){
        return Core.settings.getBool("globalchat-server", false);
    }

    /** The player is on a game server now (its chat can be used when it is on). */
    public static boolean onServer(){
        return !serverHost.isEmpty();
    }

    public static void setGlobal(boolean on){
        Core.settings.put("globalchat", on);
        postRaw(Core.bundle.get(on ? "client.globalchat.local.global.on" : "client.globalchat.local.global.off"));
        apply();
    }

    public static void setServerOn(boolean on){
        Core.settings.put("globalchat-server", on);
        postRaw(Core.bundle.get(on ? "client.globalchat.local.server.on" : "client.globalchat.local.server.off"));
        apply();
    }

    /** Called by the setting checkboxes (the value is already saved). */
    public static void setEnabled(boolean on){
        apply();
    }

    /** Connects when a channel is on, disconnects when both are off, and tells the server which channels to send. */
    private static void apply(){
        if(!enabled()){
            stop();
            return;
        }
        start();
        if(connected){
            Jval msg = Jval.newObject();
            msg.put("t", "global");
            msg.put("on", globalOn());
            write(msg);
            sendServer();
        }
    }

    public static boolean connected(){
        return connected;
    }

    public static int online(){
        return online;
    }

    private static @Nullable arc.func.Cons<Seq<Jval>> whoListener;

    /** Asks the server who is online (in the whole chat or on my server); the answer comes on the main thread. */
    public static void requestWho(boolean server, arc.func.Cons<Seq<Jval>> listener){
        if(!enabled() || !connected){
            postRaw(status());
            return;
        }
        whoListener = listener;
        Jval msg = Jval.newObject();
        msg.put("t", "who");
        if(server) msg.put("ch", "server");
        write(msg);
    }

    /**
     * Can mute and ban here: the owner and curators everywhere (their punishments cover the whole chat),
     * a moderator only in the chat of the server he was appointed for.
     */
    public static boolean moderator(boolean server){
        return curator() || (server && connected && !channel.isEmpty() && serverRole.equals("mod"));
    }

    public static boolean owner(){
        return connected && role.equals("owner");
    }

    /** Owner and curators: appoint and remove moderators. */
    public static boolean curator(){
        return connected && (role.equals("owner") || role.equals("curator"));
    }

    /** Colored badge shown before the name of the owner, curators and moderators. */
    public static String badge(String role){
        return switch(role){
            case "owner" -> "[gold]" + Iconc.admin + "[] ";
            case "curator" -> "[#c28cff]" + Iconc.admin + "[] ";
            case "mod" -> "[sky]" + Iconc.admin + "[] ";
            default -> "";
        };
    }

    public static String tag(){
        return tag;
    }

    /**
     * Moderation request; the server checks the rights and answers in the chat. Besides the protocol actions:
     * ban30 and banforever (in the whole chat), sban and sbanforever (in the chat of this server only).
     */
    public static void moderate(String action, String target){
        switch(action){
            case "ban30" -> moderate("ban", target, "30d", "all");
            case "banforever" -> moderate("ban", target, "forever", "all");
            case "sban" -> moderate("ban", target, "7d", "server");
            case "sbanforever" -> moderate("ban", target, "forever", "server");
            default -> moderate(action, target, null, null);
        }
    }

    /** @param length "7d", "30d" or "forever" for bans; @param scope "server", "all" or null for the default of my rank */
    public static void moderate(String action, String target, @Nullable String length, @Nullable String scope){
        if(!enabled() || !connected){
            postRaw(status());
            return;
        }
        Jval msg = Jval.newObject();
        msg.put("t", "mod");
        msg.put("action", action);
        msg.put("target", target == null ? "" : target.trim());
        if(length != null) msg.put("len", length);
        if(scope != null) msg.put("scope", scope);
        if(!write(msg)) postRaw(Core.bundle.format("client.globalchat.failed", Core.bundle.get("client.globalchat.err.send")));
    }

    /** 600 seconds: "10 min", 7 days: "7 d." */
    public static String duration(int seconds){
        int d = seconds / 86400, h = seconds % 86400 / 3600, m = Math.max(seconds % 3600 / 60, d == 0 && h == 0 ? 1 : 0);
        StringBuilder sb = new StringBuilder();
        if(d > 0) sb.append(Core.bundle.format("client.globalchat.time.d", d)).append(' ');
        if(h > 0) sb.append(Core.bundle.format("client.globalchat.time.h", h)).append(' ');
        if(m > 0 && d == 0) sb.append(Core.bundle.format("client.globalchat.time.m", m));
        return sb.toString().trim();
    }

    /** One line saying what the chat is doing: off, connected, or what went wrong. */
    public static String status(){
        if(!enabled()) return Core.bundle.get("client.globalchat.off");
        if(connected) return globalOn() ? Core.bundle.format("client.globalchat.status", online) : Core.bundle.get("client.globalchat.status.serveronly");
        String e = error;
        if(e == null) return Core.bundle.get("client.globalchat.connecting");
        // a banned player needs his tag to ask for an unban
        return Core.bundle.format("client.globalchat.failed", e) + (tag.isEmpty() ? "" : " " + Core.bundle.format("client.globalchat.mytag", tag));
    }

    private static String token(){
        String token = Core.settings.getString("globalchat-token", "");
        if(!token.matches("[0-9a-f]{64}")){
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            token = hex(bytes);
            Core.settings.put("globalchat-token", token);
        }
        return token;
    }

    public static synchronized void start(){
        if(running) return;
        running = true;
        error = null;
        thread = new Thread(GlobalChat::run, "GL-GlobalChat");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop(){
        running = false;
        connected = false;
        closeSocket();
        if(thread != null) thread.interrupt();
        thread = null;
    }

    /** Sends a message to the global chat. When it cannot be sent, the reason is written to the chat. */
    public static boolean send(String text){
        return send(text, false);
    }

    /** Sends a message to the global chat or to the chat of my server. */
    public static boolean send(String text, boolean server){
        text = text.replace('\n', ' ').trim();
        if(text.isEmpty()) return true;
        if(!enabled() || !connected){
            postRaw(status());
            return false;
        }
        if(server ? !serverOn() : !globalOn()){
            postRaw(Core.bundle.get(server ? "client.globalchat.serveroff" : "client.globalchat.sys.globaloff"));
            return false;
        }
        if(server && channel.isEmpty()){
            postRaw(Core.bundle.get("client.globalchat.sys.noserver"));
            return false;
        }
        if(text.length() > maxText){
            text = text.substring(0, maxText);
            postRaw(Core.bundle.format("client.globalchat.cut", maxText));
        }
        Jval msg = Jval.newObject();
        msg.put("t", "msg");
        msg.put("text", text);
        if(server) msg.put("ch", "server");
        if(!write(msg)){
            postRaw(Core.bundle.format("client.globalchat.failed", Core.bundle.get("client.globalchat.err.send")));
            return false;
        }
        return true;
    }

    private static boolean write(Jval obj){
        Writer w = out;
        if(w == null) return false;
        try{
            synchronized(GlobalChat.class){
                w.write(obj.toString(Jval.Jformat.plain));
                w.write('\n');
                w.flush();
            }
            return true;
        }catch(IOException e){
            closeSocket();
            return false;
        }
    }

    private static void run(){
        int delay = 5;
        while(running){
            String reason;
            try{
                connect();
                delay = 5;
                read();
                reason = Core.bundle.get("client.globalchat.err.closed");
            }catch(InterruptedException e){
                break;
            }catch(Exception e){
                reason = describe(e);
                Log.debug("[GlobalChat] @", e.toString());
            }

            boolean was = connected;
            connected = false;
            channel = "";
            serverRole = "";
            closeSocket();
            if(!running) break;

            // tell the player once per problem, not on every retry
            if(was) postRaw(Core.bundle.format("client.globalchat.lost", reason));
            else if(!reason.equals(error)) postRaw(Core.bundle.format("client.globalchat.failed", reason));
            error = reason;

            try{
                Thread.sleep(delay * 1000L);
            }catch(InterruptedException e){
                break;
            }
            delay = Math.min(delay * 2, 120);
        }
    }

    /** A readable reason for a connection problem. */
    private static String describe(Exception e){
        String key;
        if(e instanceof UnknownHostException || e instanceof NoRouteToHostException) key = "unreachable";
        else if(e instanceof ConnectException) key = "refused";
        else if(e instanceof SocketTimeoutException) key = "timeout";
        else if(e instanceof SSLHandshakeException && String.valueOf(Strings.getFinalCause(e).getMessage()).contains("pin")) key = "pin";
        else if(e instanceof SSLException) return Core.bundle.format("client.globalchat.err.tls", e.getMessage());
        else if(e instanceof EOFException || e instanceof SocketException) key = "closed";
        else return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return Core.bundle.get("client.globalchat.err." + key);
    }

    private static void connect() throws Exception{
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new PinnedTrust()}, new SecureRandom());
        SSLSocket s = (SSLSocket)ctx.getSocketFactory().createSocket();
        socket = s;
        s.connect(new InetSocketAddress(host, port), 10000);
        s.setSoTimeout(60000);
        s.startHandshake();
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

        Jval hello = Jval.newObject();
        hello.put("t", "hello");
        hello.put("v", 1);
        hello.put("name", player == null ? "player" : Strings.stripColors(player.name));
        hello.put("token", token());
        hello.put("global", globalOn());
        if(!write(hello)) throw new EOFException();
    }

    private static void read() throws Exception{
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        long lastPing = Time.millis();
        while(running){
            String line;
            try{
                line = in.readLine();
            }catch(SocketTimeoutException e){
                line = "";
            }
            if(line == null) return;
            if(Time.timeSinceMillis(lastPing) > 30000){
                lastPing = Time.millis();
                Jval ping = Jval.newObject();
                ping.put("t", "ping");
                write(ping);
            }
            if(line.isEmpty() || line.length() > 4096) continue;
            handle(Jval.read(line));
        }
    }

    private static void handle(Jval msg){
        switch(msg.getString("t", "")){
            case "welcome" -> {
                tag = msg.getString("tag", "");
                role = msg.getString("role", "");
                online = msg.getInt("online", 0);
                channel = "";
                serverRole = "";
                connected = true;
                error = null;
                postRaw(globalOn() ? Core.bundle.format("client.globalchat.connected", online) : Core.bundle.get("client.globalchat.connected.serveronly"));
                if(!serverHost.isEmpty() && serverOn()) sendServer();
            }
            case "online" -> online = msg.getInt("n", online);
            case "server" -> {
                String h = msg.getString("host", "");
                serverOnline = msg.getInt("online", 0);
                serverRole = msg.getString("role", "");
                if(!h.equals(channel)){
                    String old = channel;
                    channel = h;
                    // a local line: joined the chat of a server or left it
                    if(!h.isEmpty()) postRaw(Core.bundle.format("client.globalchat.local.joined", escape(h), serverOnline));
                    else if(!old.isEmpty()) postRaw(Core.bundle.format("client.globalchat.local.left", escape(old)));
                    // lines of the previous server go away, the chat server sends the history of the new one
                    Core.app.post(() -> {
                        for(int i = log.size - 1; i >= 0; i--){
                            if(lineKinds.get(i) == kindServer) removeLine(i);
                        }
                        if(listener != null) listener.run();
                    });
                }
            }
            case "role" -> {
                role = msg.getString("role", "");
                serverRole = msg.getString("server", "");
            }
            case "msg" -> {
                String name = escape(msg.getString("name", "?"));
                String from = msg.getString("tag", "");
                String raw = msg.getString("text", "");
                String self = from.equals(tag) ? "[accent]" : "[white]";
                String badge = badge(msg.getString("role", ""));
                boolean server = msg.getString("ch", "").equals("server");
                postRaw((server ? serverPrefix : prefix) + badge + self + name + "[] [gray]#" + escape(from) + "[]: [white]" + escape(raw), raw, from,
                    msg.getString("name", "?"), server ? kindServer : kindGlobal);
            }
            case "sys" -> {
                String code = msg.getString("code", "");
                boolean server = msg.getString("ch", "").equals("server");
                String key = "client.globalchat.sys." + (server && code.equals("banned") ? "serverbanned" : code);
                if(msg.getBool("forever", false) && Core.bundle.has(key + ".forever")) key += ".forever";
                int left = msg.getInt("left", 0);
                String text = left > 0 && Core.bundle.has(key + ".left") ? Core.bundle.format(key + ".left", duration(left)) :
                    Core.bundle.has(key) ? Core.bundle.get(key) : "[#7fd3ff][[GL][] [scarlet]" + escape(msg.getString("text", ""));
                if(!server && (code.equals("banned") || code.equals("kicked") || code.equals("full"))){
                    error = Strings.stripColors(text.replace("[[GL]", "")).trim();
                }
                // the server tells a banned player his tag: he can copy it (a click on this line) and send it to a moderator
                String own = msg.getString("tag", "");
                if(code.equals("banned") && own.matches("[0-9a-f]{6}")){
                    tag = own;
                    postRaw(text + "\n[lightgray]" + Core.bundle.format("client.globalchat.bannedtag", own), own, "", "", kindSystem);
                }else{
                    postRaw(text);
                }
            }
            case "modevent" -> {
                String action = msg.getString("action", "");
                String key = "client.globalchat.mod." + action + (msg.getBool("forever", false) ? ".forever" : "");
                if(Core.bundle.has(key)){
                    String name = msg.getString("name", "");
                    String who = escape(name.isEmpty() ? "?" : name) + " [gray]#" + escape(msg.getString("tag", "")) + "[]";
                    String text = Core.bundle.format(key, escape(msg.getString("by", "?")), who, duration(msg.getInt("minutes", 0) * 60),
                        escape(msg.getString("host", "")));
                    // actions of server moderators are about the chat of one server
                    boolean server = msg.getString("ch", "").equals("server");
                    if(server && text.startsWith(prefix)) text = serverPrefix + text.substring(prefix.length());
                    postRaw(text, Strings.stripColors(text), "", "", server ? kindServer : kindGlobal);
                }
            }
            case "who" -> {
                Seq<Jval> players = new Seq<>();
                Jval arr = msg.get("players");
                if(arr != null && arr.isArray()) players.addAll(arr.asArray());
                Core.app.post(() -> {
                    if(whoListener != null) whoListener.get(players);
                });
            }
            case "modinfo" -> {
                StringBuilder sb = new StringBuilder(Core.bundle.get("client.globalchat.list.title"));
                for(String kind : new String[]{"curators", "mods", "mutes", "bans"}){
                    sb.append("\n[accent]").append(Core.bundle.get("client.globalchat.list." + kind)).append("[] ");
                    Jval.JsonArray arr = msg.get(kind) == null ? new Jval.JsonArray() : msg.get(kind).asArray();
                    if(arr.isEmpty()) sb.append("[gray]-[]");
                    for(int i = 0; i < arr.size; i++){
                        Jval e = arr.get(i);
                        sb.append(i == 0 ? "" : ", ").append(escape(e.getString("name", ""))).append(" [gray]#").append(escape(e.getString("tag", ""))).append("[]");
                        if(e.getBool("forever", false)) sb.append(" (").append(Core.bundle.get("client.globalchat.forever")).append(")");
                        else if(e.getInt("left", 0) > 0) sb.append(" (").append(duration(e.getInt("left", 0))).append(")");
                        // the server a moderator or a punishment belongs to
                        Jval hosts = e.get("hosts");
                        String where = hosts != null && hosts.isArray() ? hosts.asArray().toString(", ") : e.getString("host", "");
                        if(!where.isEmpty()) sb.append(" [lightgray][[").append(escape(where)).append("][]");
                    }
                }
                postRaw(sb.toString());
            }
            default -> {}
        }
    }

    /** Text from the server is shown as is: Mindustry color tags are escaped. */
    private static String escape(String s){
        return s.replace("[", "[[");
    }

    private static void postRaw(String text){
        postRaw(text, Strings.stripColors(text), "", "", kindSystem);
    }

    private static void postRaw(String text, String copy, String from, String name, int kind){
        Core.app.post(() -> {
            log.add(text);
            copies.add(copy);
            lineTags.add(from);
            lineNames.add(name);
            lineKinds.add(kind);
            if(log.size > maxLog) removeLine(0);
            if(ui != null && ui.chatfrag != null) ui.chatfrag.addMessage(text);
            if(listener != null) listener.run();
        });
    }

    private static void removeLine(int i){
        log.remove(i);
        copies.remove(i);
        lineTags.remove(i);
        lineNames.remove(i);
        lineKinds.removeIndex(i);
    }

    private static void closeSocket(){
        SSLSocket s = socket;
        socket = null;
        out = null;
        if(s != null){
            try{
                s.close();
            }catch(IOException ignored){
            }
        }
    }

    private static String hex(byte[] bytes){
        StringBuilder sb = new StringBuilder();
        for(byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Accepts only the GL chat server certificate. */
    private static class PinnedTrust implements X509TrustManager{
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException{
            throw new CertificateException("not a server");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException{
            if(chain == null || chain.length == 0) throw new CertificateException("no certificate");
            try{
                if(!hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())).equals(pin)){
                    throw new CertificateException("certificate pin mismatch");
                }
            }catch(NoSuchAlgorithmException e){
                throw new CertificateException(e);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers(){
            return new X509Certificate[0];
        }
    }
}
