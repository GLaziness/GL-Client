package mindustry.net;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.client.utils.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.io.*;
import mindustry.net.Administration.*;
import mindustry.net.Packets.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.io.*;
import java.net.*;

import static mindustry.Vars.*;

/** Handles control of bleeding edge builds. */
public class BeControl{
    private static final int updateInterval = 120; // Poll every 120s (30/hr), this leaves us with 30 requests per hour to spare.

    /** Whether or not to automatically display an update prompt on client load and every couple of minutes. */
    public boolean checkUpdates;
    private boolean updateAvailable;
    private String updateUrl;
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
                    if(checkUpdates && !mobile){ // Don't auto update on manually cloned copies of the repo
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

                    for(Fi file : self.parent().findAll(f -> !f.equals(self))) file.delete();

                    self.copyTo(dest);
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
            .error(e -> Core.app.post(() -> {
                done.get(false);
                Log.err("Failed to check for updates", e);
            }))
            .submit(res -> {
                Jval val = Jval.read(res.getResultAsString());
                String newBuild = val.getString("name");
                Jval asset = val.get("assets").asArray().find(v -> v.getString("name", "").toLowerCase().contains("desktop"));
                if (asset == null) asset = val.get("assets").asArray().find(v -> v.getString("name", "").toLowerCase().contains("mindustry"));
                if(!newBuild.trim().isEmpty() && asset != null && isNewer(val, newBuild)){
                    updateUrl = asset.getString("browser_download_url", "");
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
     * comment, see {@link Version#buildTime}.
     */
    private static boolean isNewer(Jval release, String releaseName){
        long releaseBuild = releaseBuildTime(release);
        if(releaseBuild > 0 && Version.buildTime > 0) return releaseBuild > Version.buildTime;
        return !Version.clientVersion.equals(releaseName);
    }

    private static long releaseBuildTime(Jval release){
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("buildTime:\\s*(\\d+)").matcher(release.getString("body", ""));
        return m.find() ? Strings.parseLong(m.group(1), 0L) : 0L;
    }

    /** GL: a build time as a date in the game language, like "19 сентября 2026"; empty when unknown. */
    public static String buildDate(long time){
        if(time <= 0) return "";
        return java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", Core.bundle.getLocale())
            .format(java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()));
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
            if(Config.autoUpdate.bool()){
                Log.info("&lcAuto-downloading next version...");

                try{
                    //download new file from github
                    Fi source = Fi.get(BeControl.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
                    Fi dest = source.sibling("server-be-" + updateBuild + ".jar");

                    download(updateUrl, dest,
                    len -> Core.app.post(() -> Log.info("&ly| Size: @ MB.", Strings.fixed((float)len / 1024 / 1024, 2))),
                    progress -> {},
                    () -> false,
                    () -> Core.app.post(() -> {
                        Log.info("&lcSaving...");
                        SaveIO.save(saveDirectory.child("autosavebe." + saveExtension));
                        Log.info("&lcAutosaved.");

                        netServer.kickAll(KickReason.serverRestarting);
                        Threads.sleep(500);

                        Log.info("&lcVersion downloaded, exiting. Note that if you are not using a auto-restart script, the server will not restart automatically.");
                        //replace old file with new
                        dest.copyTo(source);
                        dest.delete();
                        System.exit(2); //this will cause a restart if using the script
                    }),
                    Throwable::printStackTrace);
                }catch(Exception e){
                    e.printStackTrace();
                }
            }
            checkUpdates = false;
        }
    }

    /** Convenience method to download a jar so that this doesn't need to get copied multiple times */
    public void downloadJar(String url, Fi dest, Runnable done, Cons<Throwable> error){
        download(url, dest, l -> {}, p -> {}, () -> false, done, error);
    }

    private void download(String furl, Fi dest, Intc length, Floatc progressor, Boolp canceled, Runnable done, Cons<Throwable> error){
        mainExecutor.submit(() -> {
            try{
                HttpURLConnection con = (HttpURLConnection)new URL(furl).openConnection();
                BufferedInputStream in = new BufferedInputStream(con.getInputStream());
                OutputStream out = dest.write(false, 4096);

                byte[] data = new byte[4096];
                long size = con.getContentLength();
                long counter = 0;
                length.get((int)size);
                int x;
                while((x = in.read(data, 0, data.length)) >= 0 && !canceled.get()){
                    counter += x;
                    progressor.get((float)counter / (float)size);
                    out.write(data, 0, x);
                }
                out.close();
                in.close();
                if(!canceled.get()) done.run();
            }catch(Throwable e){
                error.get(e);
            }
        });
    }

    public void actuallyDownload() {
        actuallyDownload(null);
    }

    public void actuallyDownload(@Nullable String sender) {
        if(!updateAvailable) return;
        try{
            boolean[] cancel = {false};
            float[] progress = {0};
            int[] length = {0};
            Fi file = bebuildDirectory.child("client-be-" + updateBuild + ".jar");
            Fi fileDest = OS.hasProp("becopy") ?
                Fi.get(OS.prop("becopy")) :
                Fi.get(BeControl.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());

            BaseDialog dialog = new BaseDialog("@be.updating");
            download(updateUrl, file, i -> length[0] = i, v -> progress[0] = v, () -> cancel[0], () -> {
                Log.info(file.absolutePath());
                ClientUtils.openJar("-Dberestart", "-Dbecopy=" + fileDest.absolutePath(), "-jar", file.absolutePath());
            }, e -> {
                dialog.hide();
                ui.showException(e);
            });

            dialog.cont.add(new Bar(() -> length[0] == 0 ? Core.bundle.get("be.updating") : (int)(progress[0] * length[0])/1024/1024 + "/" + length[0]/1024/1024 + " MB", () -> Pal.accent, () -> progress[0])).width(400f).height(70f);
            if (sender == null) {
                dialog.buttons.button("@cancel", Icon.cancel, () -> {
                    cancel[0] = true;
                    dialog.hide();
                }).size(210f, 64f);
            } else {
                dialog.cont.row();
                dialog.cont.add("By royal decree of emperor [accent]" + sender + "[white] your client is being updated.");
            }
            dialog.buttons.button("@close", Icon.menu, dialog::hide).size(210f, 64f);
            dialog.setFillParent(false);
            dialog.show();
        }catch(Exception e){
            ui.showException(e);
        }
    }
}
