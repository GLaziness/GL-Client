package mindustry.client.utils;

import arc.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;

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
 */
public class GlobalChat{
    private static final String host = "2.26.10.69";
    private static final int port = 7160;
    /** SHA-256 of the server certificate (DER). */
    private static final String pin = "5373188ec5a8d68d4f930a38f2aadb8b9606ac35819d8ddcecb8865e4d81971e";
    private static final int maxText = 200, maxLog = 150;

    private static volatile boolean running;
    private static volatile SSLSocket socket;
    private static volatile Writer out;
    private static volatile boolean connected;
    private static volatile int online;
    private static volatile String tag = "";
    /** Why the chat is not connected (shown to the player), null when there is no problem. */
    private static volatile @Nullable String error;
    private static Thread thread;

    /** Lines of the global chat for its window, main thread only. */
    public static final Seq<String> log = new Seq<>();
    /** Text copied when a line of {@link #log} is clicked (the message itself, without the name). */
    public static final Seq<String> copies = new Seq<>();
    /** Called on the main thread when a line is added to {@link #log}. */
    public static @Nullable Runnable listener;

    public static void init(){
        if(enabled()) start();
    }

    public static boolean enabled(){
        return Core.settings.getBool("globalchat", false);
    }

    /** Called by the setting checkbox. */
    public static void setEnabled(boolean on){
        if(on) start();
        else stop();
    }

    public static boolean connected(){
        return connected;
    }

    public static int online(){
        return online;
    }

    /** One line saying what the chat is doing: off, connected, or what went wrong. */
    public static String status(){
        if(!enabled()) return Core.bundle.get("client.globalchat.off");
        if(connected) return Core.bundle.format("client.globalchat.status", online);
        String e = error;
        return e != null ? Core.bundle.format("client.globalchat.failed", e) : Core.bundle.get("client.globalchat.connecting");
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

    /** Sends a message. When it cannot be sent, the reason is written to the chat. */
    public static boolean send(String text){
        text = text.replace('\n', ' ').trim();
        if(text.isEmpty()) return true;
        if(!enabled() || !connected){
            postRaw(status());
            return false;
        }
        if(text.length() > maxText){
            text = text.substring(0, maxText);
            postRaw(Core.bundle.format("client.globalchat.cut", maxText));
        }
        Jval msg = Jval.newObject();
        msg.put("t", "msg");
        msg.put("text", text);
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
                online = msg.getInt("online", 0);
                connected = true;
                error = null;
                postRaw(Core.bundle.format("client.globalchat.connected", online));
            }
            case "online" -> online = msg.getInt("n", online);
            case "msg" -> {
                String name = escape(msg.getString("name", "?"));
                String from = msg.getString("tag", "");
                String raw = msg.getString("text", "");
                String self = from.equals(tag) ? "[accent]" : "[white]";
                postRaw("[#7fd3ff][[GL][] " + self + name + "[] [gray]#" + escape(from) + "[]: [white]" + escape(raw), raw);
            }
            case "sys" -> {
                String code = msg.getString("code", "");
                String key = "client.globalchat.sys." + code;
                String text = Core.bundle.has(key) ? Core.bundle.get(key) : "[#7fd3ff][[GL][] [scarlet]" + escape(msg.getString("text", ""));
                if(code.equals("banned") || code.equals("kicked") || code.equals("full")){
                    error = Strings.stripColors(text.replace("[[GL]", "")).trim();
                }
                postRaw(text);
            }
            default -> {}
        }
    }

    /** Text from the server is shown as is: Mindustry color tags are escaped. */
    private static String escape(String s){
        return s.replace("[", "[[");
    }

    private static void postRaw(String text){
        postRaw(text, Strings.stripColors(text));
    }

    private static void postRaw(String text, String copy){
        Core.app.post(() -> {
            log.add(text);
            copies.add(copy);
            if(log.size > maxLog){
                log.remove(0);
                copies.remove(0);
            }
            if(ui != null && ui.chatfrag != null) ui.chatfrag.addMessage(text);
            if(listener != null) listener.run();
        });
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
