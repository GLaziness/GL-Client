package mindustry.client.utils;

import arc.*;
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
    private static final int maxText = 200;

    private static volatile boolean running;
    private static volatile SSLSocket socket;
    private static volatile Writer out;
    private static volatile boolean connected;
    private static volatile int online;
    private static volatile String tag = "";
    private static Thread thread;

    public static void init(){
        if(Core.settings.getBool("globalchat", false)) start();
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

    private static String token(){
        String token = Core.settings.getString("globalchat-token", "");
        if(!token.matches("[0-9a-f]{64}")){
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            StringBuilder sb = new StringBuilder();
            for(byte b : bytes) sb.append(String.format("%02x", b));
            token = sb.toString();
            Core.settings.put("globalchat-token", token);
        }
        return token;
    }

    public static synchronized void start(){
        if(running) return;
        running = true;
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

    /** Sends a message; returns false when not connected. */
    public static boolean send(String text){
        text = text.replace('\n', ' ').trim();
        if(text.isEmpty()) return true;
        if(!connected) return false;
        if(text.length() > maxText) text = text.substring(0, maxText);
        Jval msg = Jval.newObject();
        msg.put("t", "msg");
        msg.put("text", text);
        return write(msg);
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
            try{
                connect();
                delay = 5;
                read();
            }catch(InterruptedException e){
                break;
            }catch(Exception e){
                Log.debug("[GlobalChat] @", e.toString());
            }finally{
                if(connected) post("client.globalchat.disconnected");
                connected = false;
                closeSocket();
            }
            if(!running) break;
            try{
                Thread.sleep(delay * 1000L);
            }catch(InterruptedException e){
                break;
            }
            delay = Math.min(delay * 2, 120);
        }
    }

    private static void connect() throws Exception{
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new PinnedTrust()}, new SecureRandom());
        SSLSocket s = (SSLSocket)ctx.getSocketFactory().createSocket();
        s.connect(new InetSocketAddress(host, port), 10000);
        s.setSoTimeout(60000);
        s.startHandshake();
        socket = s;
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

        Jval hello = Jval.newObject();
        hello.put("t", "hello");
        hello.put("v", 1);
        hello.put("name", player == null ? "player" : Strings.stripColors(player.name));
        hello.put("token", token());
        if(!write(hello)) throw new IOException("hello failed");
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
                post(Core.bundle.format("client.globalchat.connected", online));
            }
            case "online" -> online = msg.getInt("n", online);
            case "msg" -> {
                String name = escape(msg.getString("name", "?"));
                String from = msg.getString("tag", "");
                String text = escape(msg.getString("text", ""));
                String self = from.equals(tag) ? "[accent]" : "[white]";
                postRaw("[#7fd3ff][[GL][] " + self + name + "[] [gray]#" + escape(from) + "[]: [white]" + text);
            }
            case "sys" -> {
                String code = msg.getString("code", "");
                String key = "client.globalchat.sys." + code;
                post(Core.bundle.has(key) ? Core.bundle.get(key) : "[scarlet]" + escape(msg.getString("text", "")));
            }
            default -> {}
        }
    }

    /** Text from the server is shown as is: Mindustry color tags are escaped. */
    private static String escape(String s){
        return s.replace("[", "[[");
    }

    private static void post(String keyOrText){
        String text = Core.bundle.has(keyOrText) ? Core.bundle.get(keyOrText) : keyOrText;
        postRaw(text);
    }

    private static void postRaw(String text){
        Core.app.post(() -> {
            if(ui != null && ui.chatfrag != null) ui.chatfrag.addMessage(text);
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
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                StringBuilder sb = new StringBuilder();
                for(byte b : hash) sb.append(String.format("%02x", b));
                if(!sb.toString().equals(pin)) throw new CertificateException("certificate pin mismatch");
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
