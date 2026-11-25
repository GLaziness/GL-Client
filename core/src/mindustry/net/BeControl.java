package mindustry.net;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.client.utils.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.net.Administration.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.io.*;
import java.net.*;

import static mindustry.Vars.*;

/** Handles control of bleeding edge builds. */
public class BeControl{
    private static final int updateInterval = 120; // Poll every 120s (30/hr), this leaves us with 30 requests per hour to spare.
    /** GL: largest download accepted; the game jar is well under this. */
    private static final long maxDownload = 400L * 1024 * 1024;

    /** Whether or not to automatically display an update prompt on client load and every couple of minutes. */
    public boolean checkUpdates;
    private boolean updateAvailable;
    private String updateUrl;
    /** GL: the signature of {@link #updateUrl}, see {@link UpdateVerifier}: taken from the release notes, or downloaded from a .sig asset. */
    private String updateSigUrl, updateSigText;
    private String updateBuild;
    /** GL: build time (ms) of the release found, 0 when the release does not say. */
    private long updateBuildTime;

    /** @return whether this is a bleeding edge build. */
    public boolean active(){
        return Version.type.equals("bleeding-edge") && !steam;
    }

    public BeControl(){

    }

    public void init(){
        Events.on(EventType.ClientLoadEvent.class, event -> {
            checkUpdates = Core.settings.getBool("autoupdate", true);
            Timer.schedule(() -> {
                    if(checkUpdates && (!mobile || Core.app.isAndroid())){ // GL: the APK updates itself too
                        checkUpdate(result -> {
                            if (result) showUpdateDialog();
                        });
                    }
                }, 1, updateInterval
            );

            if(OS.hasProp("becopy")){
                try{
                    Fi dest = Fi.get(OS.prop("becopy"));
                    Fi self = Fi.get(BeControl.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());

                    // GL: only ever clean up our own downloads, and only copy over a jar
                    if(self.parent().equals(bebuildDirectory) && dest.extEquals("jar")){
                        for(Fi file : bebuildDirectory.list()){
                            if(!file.equals(self) && file.name().startsWith("client-be-")) file.delete();
                        }

                        self.copyTo(dest);
                    }else{
                        Log.warn("Ignoring -Dbecopy: @ is not a downloaded update", self);
                    }
                }catch(Throwable e){
                    e.printStackTrace();
                }
            }
        });
    }


    public void checkUpdate(Boolc done) {
        checkUpdate(done, Core.settings.getString("updateurl"));
    }

    /** asynchronously checks for updates. */
    public void checkUpdate(Boolc done, String repo){
        Http.get("https://api.github.com/repos/" + repo + "/releases/latest")
            .timeout(15000)
            .error(e -> Core.app.post(() -> {
                done.get(false);
                Log.err("Failed to check for updates", e);
            }))
            .submit(res -> {
                Jval val = Jval.read(res.getResultAsString());
                String newBuild = val.getString("name");
                Jval asset = findAsset(val);
                String sigText = asset == null ? null : notesSig(val, asset);
                Jval sig = asset == null || sigText != null ? null : findSig(val, asset);
                // GL: a release without a signature is never offered, whoever uploaded it
                if(asset != null && sig == null && sigText == null) Log.warn("Release @ of @ has no signature for @, ignoring it", newBuild, repo, asset.getString("name", ""));
                if(!newBuild.trim().isEmpty() && asset != null && (sig != null || sigText != null) && isNewer(val, newBuild)){
                    updateUrl = asset.getString("browser_download_url", "");
                    updateSigText = sigText;
                    updateSigUrl = sig == null ? null : sig.getString("browser_download_url", "");
                    updateAvailable = true;
                    updateBuild = newBuild;
                    updateBuildTime = releaseBuildTime(val);
                    Core.app.post(() -> done.get(true));
                }else{
                    Core.app.post(() -> done.get(false));
                }
            });
    }

    /**
     * GL Client keeps the same version between updates, so builds are compared by time instead of by name.
     * The release description holds the build time (ms) of the uploaded jar in a hidden {@code <!-- buildTime: ... -->}
     * comment, see {@link Version#buildTime}. That comment only decides whether to ask; the signed build time in the
     * .sig file is what {@link UpdateVerifier} checks before anything runs.
     */
    private static boolean isNewer(Jval release, String releaseName){
        long releaseBuild = releaseBuildTime(release);
        if(releaseBuild > 0 && Version.buildTime > 0) return releaseBuild > Version.buildTime;
        return !Version.clientVersion.equals(releaseName);
    }

    /**
     * GL: the Steam build is uploaded as its own asset, named so that it holds neither "desktop" nor "mindustry" -
     * older clients look for those two words only and must never be handed the Steam build, which asks Steam to
     * install the game on startup. A copy running under Steam takes the "steam" asset and nothing else.
     */
    private static @Nullable Jval findAsset(Jval release){
        Seq<Jval> assets = release.get("assets").asArray().select(v -> !v.getString("name", "").toLowerCase().endsWith(".sig"));
        // GL: a phone takes the APK, named without "desktop" and "mindustry" too
        if(Core.app.isAndroid()) return assets.find(v -> v.getString("name", "").toLowerCase().endsWith(".apk"));
        if(steam || Version.isSteam) return assets.find(v -> v.getString("name", "").toLowerCase().contains("steam"));

        Jval plain = assets.find(v -> matchesPlain(v, "desktop"));
        return plain != null ? plain : assets.find(v -> matchesPlain(v, "mindustry"));
    }

    /** GL: the signature as a hidden {@code <!-- gl-sig <asset name> <base64> -->} comment in the release notes, made by release/SignRelease.java. */
    private static @Nullable String notesSig(Jval release, Jval asset){
        String name = asset.getString("name", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<!--\\s*gl-sig\\s+(\\S+)\\s+([A-Za-z0-9+/=]{1,8000})\\s*-->").matcher(release.getString("body", ""));
        while(m.find()){
            if(!m.group(1).equalsIgnoreCase(name)) continue;
            try{
                return new String(java.util.Base64.getDecoder().decode(m.group(2)), java.nio.charset.StandardCharsets.UTF_8);
            }catch(IllegalArgumentException e){
                return null;
            }
        }
        return null;
    }

    /** GL: older form - the signature uploaded next to the asset as {@code <asset name>.sig}. */
    private static @Nullable Jval findSig(Jval release, Jval asset){
        String name = asset.getString("name", "") + ".sig";
        return release.get("assets").asArray().find(v -> v.getString("name", "").equalsIgnoreCase(name));
    }

    private static boolean matchesPlain(Jval asset, String key){
        String name = asset.getString("name", "").toLowerCase();
        return name.contains(key) && !name.contains("steam");
    }

    private static long releaseBuildTime(Jval release){
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("buildTime:\\s*(\\d+)").matcher(release.getString("body", ""));
        return m.find() ? Strings.parseLong(m.group(1), 0L) : 0L;
    }

    /** GL: a build time as a date in the game language, like "19 сентября 2026"; empty when unknown. */
    public static String buildDate(long time){
        if(time <= 0) return "";
        return Version.formatDate(time);
    }

    private static String buildText(long time, String fallback){
        String date = buildDate(time);
        return date.isEmpty() ? fallback : Core.bundle.format("gl.be.update.date", date);
    }

    /** @return whether a new update is available */
    public boolean isUpdateAvailable(){
        return updateAvailable;
    }

    /** Sets updateAvailable to the specified value */
    public void setUpdateAvailable(boolean available){
        updateAvailable = available;
    }

    /** shows the dialog for updating the game on desktop, or a prompt for doing so on the server */
    public void showUpdateDialog(){
        if(!updateAvailable) return;

        if(!headless){
            checkUpdates = false;
            // GL: both builds shown by date, like under the menu logo
            String text = Core.bundle.format("gl.be.update.current", buildText(Version.buildTime, Version.clientVersion)) + "\n"
                + Core.bundle.format("gl.be.update.new", buildText(updateBuildTime, updateBuild)) + "\n\n" + Core.bundle.get("be.update.confirm");
            ui.showCustomConfirm(Core.bundle.get("gl.be.update.title"), text, "@ok", "@be.ignore",
                this::actuallyDownload, () -> checkUpdates = false);
        }else{
            Log.info("&lcCurrent: " + Version.clientVersion + " A new update is available: &lyBleeding Edge build @", updateBuild);
            // GL: GL publishes no signed server builds, so a server never downloads and runs one by itself
            if(Config.autoUpdate.bool()) Log.warn("Auto-update is off: there are no signed GL server builds.");
            checkUpdates = false;
        }
    }

    /** Convenience method to download a jar so that this doesn't need to get copied multiple times */
    public void downloadJar(String url, Fi dest, Runnable done, Cons<Throwable> error){
        download(url, dest, maxDownload, l -> {}, p -> {}, () -> false, done, error);
    }

    private void download(String furl, Fi dest, long limit, Intc length, Floatc progressor, Boolp canceled, Runnable done, Cons<Throwable> error){
        mainExecutor.submit(() -> {
            try{
                // GL: https only, with timeouts and a size limit, so a slow or endless server can neither hang nor fill the disk
                URL url = new URL(furl);
                if(!url.getProtocol().equals("https")) throw new IOException("Refusing a non-https download: " + furl);
                HttpURLConnection con = (HttpURLConnection)url.openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                long size = con.getContentLengthLong();
                if(!con.getURL().getProtocol().equals("https")) throw new IOException("Refusing a non-https redirect");
                if(size > limit) throw new IOException("Download too large: " + size + " bytes");

                long counter = 0;
                try(InputStream in = new BufferedInputStream(con.getInputStream()); OutputStream out = dest.write(false, 4096)){
                    byte[] data = new byte[4096];
                    length.get((int)size);
                    int x;
                    while((x = in.read(data, 0, data.length)) >= 0 && !canceled.get()){
                        counter += x;
                        if(counter > limit) throw new IOException("Download too large");
                        progressor.get(size > 0 ? (float)counter / (float)size : 0f);
                        out.write(data, 0, x);
                    }
                }
                if(!canceled.get()) done.run();
                else dest.delete();
            }catch(Throwable e){
                dest.delete();
                error.get(e);
            }
        });
    }

    /**
     * GL: downloads the update and its signature, and runs the update only when {@link UpdateVerifier} accepts it.
     * Only ever started by the player; nothing from the network can start it.
     */
    public void actuallyDownload() {
        if(!updateAvailable || updateUrl == null || (updateSigUrl == null && updateSigText == null)) return;
        try{
            boolean[] cancel = {false};
            float[] progress = {0};
            int[] length = {0};
            boolean android = Core.app.isAndroid();
            // GL: a fixed name - the release name comes from the network and must not pick the path
            Fi file = bebuildDirectory.child(android ? "update.apk" : "client-be-update.jar");
            Fi sigFile = file.sibling(file.name() + ".sig");
            Fi fileDest = android ? null : OS.hasProp("becopy") ?
                Fi.get(OS.prop("becopy")) :
                Fi.get(BeControl.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());

            BaseDialog dialog = new BaseDialog("@be.updating");
            Cons<Throwable> failed = e -> {
                file.delete();
                sigFile.delete();
                Core.app.post(() -> {
                    dialog.hide();
                    ui.showException(e);
                });
            };

            String notesSig = updateSigText;
            Cons<Runnable> fetchSig = then -> {
                if(notesSig != null){
                    sigFile.writeString(notesSig);
                    then.run();
                }else{
                    download(updateSigUrl, sigFile, UpdateVerifier.maxSigSize, i -> {}, v -> {}, () -> cancel[0], then, failed);
                }
            };

            download(updateUrl, file, maxDownload, i -> length[0] = i, v -> progress[0] = v, () -> cancel[0], () ->
            fetchSig.get(() -> {
                try{
                    UpdateVerifier.verify(file, sigFile.readString(), UpdateVerifier.expectedKind());
                }catch(Throwable e){
                    file.delete();
                    sigFile.delete();
                    Log.err("Update rejected", e);
                    Core.app.post(() -> {
                        dialog.hide();
                        ui.showErrorMessage(Core.bundle.format("gl.be.update.unsigned", Strings.getFinalMessage(e)));
                    });
                    return;
                }
                sigFile.delete();
                Log.info("Verified update @", file.absolutePath());

                if(android){
                    // GL: the system installer takes over; the game restarts updated
                    Core.app.post(() -> {
                        dialog.hide();
                        try{
                            platform.installUpdate(file);
                        }catch(Throwable e){
                            ui.showException("@gl.be.update.installfailed", e);
                        }
                    });
                }else{
                    ClientUtils.openJar("-Dberestart", "-Dbecopy=" + fileDest.absolutePath(), "-jar", file.absolutePath());
                }
            }), failed);

            dialog.cont.add(new Bar(() -> length[0] == 0 ? Core.bundle.get("be.updating") : (int)(progress[0] * length[0])/1024/1024 + "/" + length[0]/1024/1024 + " MB", () -> Pal.accent, () -> progress[0])).width(400f).height(70f);
            dialog.buttons.button("@cancel", Icon.cancel, () -> {
                cancel[0] = true;
                dialog.hide();
            }).size(210f, 64f);
            dialog.buttons.button("@close", Icon.menu, dialog::hide).size(210f, 64f);
            dialog.setFillParent(false);
            dialog.show();
        }catch(Exception e){
            ui.showException(e);
        }
    }
}
