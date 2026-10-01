package mindustry.android;

import android.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import android.os.Build.*;
import android.telephony.*;
import arc.*;
import arc.backend.android.*;
import arc.files.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import dalvik.system.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.net.*;
import mindustry.ui.*;
import mindustry.ui.FileChooser.*;
import mindustry.ui.dialogs.*;

import java.io.*;
import java.lang.Thread.*;
import java.util.*;

import static mindustry.Vars.*;

public class AndroidLauncher extends AndroidApplication{
    public static final int PERMISSION_REQUEST_CODE = 1;
    boolean doubleScaleTablets = true;
    FileChooserDialog chooser;
    Runnable permCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState){
        UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            CrashHandler.log(error);

            //try to forward exception to system handler
            if(handler != null){
                handler.uncaughtException(thread, error);
            }else{
                Log.err(error);
                System.exit(1);
            }
        });

        super.onCreate(savedInstanceState);
        if(doubleScaleTablets && isTablet(this)){
            Scl.setAddition(0.5f);
        }

        initialize(new ClientLauncher(){
            {
                // The client itself (navigation, the Foo protocol, signing), as the desktop launcher does.
                add(mindustry.client.Main.INSTANCE);
            }

            @Override
            public void hide(){
                moveTaskToBack(true);
            }

            @Override
            public rhino.Context getScriptContext(){
                return AndroidRhinoContext.enter(getCacheDir());
            }

            @Override
            public void installUpdate(Fi apk){
                installApk(apk);
            }

            @Override
            public void shareFile(Fi file){
            }

            @Override
            public ClassLoader loadJar(Fi jar, ClassLoader parent) throws Exception{
                //Required to load jar files in Android 14: https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading
                try{
                    jar.file().setReadOnly();
                    return new DexClassLoader(jar.file().getPath(), getFilesDir().getPath(), null, parent){
                        @Override
                        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
                            //check for loaded state
                            Class<?> loadedClass = findLoadedClass(name);
                            if(loadedClass == null){
                                try{
                                    //try to load own class first
                                    loadedClass = findClass(name);
                                }catch(ClassNotFoundException | NoClassDefFoundError e){
                                    //use parent if not found
                                    return parent.loadClass(name);
                                }
                            }

                            if(resolve){
                                resolveClass(loadedClass);
                            }
                            return loadedClass;
                        }
                    };
                }catch(SecurityException e){
                    //`setReadOnly` to jar file in `/sdcard/Android/data/...` does not work on some Android 14 devices, but in `/data/...`, it does

                    if(Build.VERSION.SDK_INT < VERSION_CODES.O_MR1){
                        throw e;
                    }

                    Fi cacheDir = new Fi(getCacheDir()).child("mods");
                    cacheDir.mkdirs();

                    //long file name support
                    Fi modCacheDir = cacheDir.child(jar.nameWithoutExtension());
                    Fi modCache = modCacheDir.child(Long.toHexString(jar.lastModified()) + ".zip");

                    if(modCacheDir.equals(jar.parent())){
                        //should not reach here, just in case
                        throw e;
                    }

                    //Cache will be deleted when mod is removed
                    if(!modCache.exists() || jar.length() != modCache.length()){
                        modCacheDir.mkdirs();
                        jar.copyTo(modCache);
                    }
                    modCache.file().setReadOnly();
                    return loadJar(modCache, parent);
                }
            }

            @Override
            public void showFileChooser(FileChooserParams params){
                try{
                    String extension = params.extensions[0];

                    if(VERSION.SDK_INT >= VERSION_CODES.Q){
                        Intent intent = new Intent(params.open ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType(extension.equals("zip") && !params.open && params.extensions.length == 1 ? "application/zip" : "*/*");
                        intent.putExtra(Intent.EXTRA_TITLE, params.fileName);
                        if(params.allowMultiple){
                            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        }

                        addResultListener(i -> startActivityForResult(intent, i), (code, in) -> {
                            if(code == Activity.RESULT_OK && in != null && in.getData() != null){
                                Uri[] uris;
                                if(in.getClipData() != null){
                                    uris = new Uri[in.getClipData().getItemCount()];
                                    for(int i = 0; i < uris.length; i++){
                                        uris[i] = in.getClipData().getItemAt(i).getUri();
                                    }
                                }else{
                                    uris = new Uri[]{in.getData()};
                                }

                                if(uris.length == 0 || uris[0].getPath().contains("(invalid)")) return;

                                Fi[] files = Seq.with(uris).map(uri -> new Fi(uri.getPath()){
                                    @Override
                                    public InputStream read(){
                                        try{
                                            return getContentResolver().openInputStream(uri);
                                        }catch(IOException e){
                                            throw new ArcRuntimeException(e);
                                        }
                                    }

                                    @Override
                                    public OutputStream write(boolean append){
                                        try{
                                            return getContentResolver().openOutputStream(uri, "rwt");
                                        }catch(IOException e){
                                            throw new ArcRuntimeException(e);
                                        }
                                    }

                                    @Override
                                    public Writer writer(boolean append, String charset){
                                        try{
                                            return new OutputStreamWriter(write(append), charset);
                                        }catch(IOException ex){
                                            throw new ArcRuntimeException(ex);
                                        }
                                    }
                                }).toArray(Fi.class);

                                Core.app.post(() -> Core.app.post(() -> params.handleChooseResult(files)));
                            }
                        });
                    }else if(VERSION.SDK_INT >= VERSION_CODES.M && !(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)){
                        chooser = FileChooser.createFallbackFileChooser(params);

                        ArrayList<String> perms = new ArrayList<>();
                        if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                        }
                        if(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                        }
                        requestPermissions(perms.toArray(new String[0]), PERMISSION_REQUEST_CODE);
                    }else{
                        FileChooser.showFallbackFileChooser(params);
                    }
                }catch(Throwable error){
                    Core.app.post(() -> Vars.ui.showException(error));
                }
            }

            @Override
            public void beginForceLandscape(){
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override
            public void endForceLandscape(){
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
            }

        }, new AndroidApplicationConfiguration(){{
            useImmersiveMode = true;
            hideStatusBar = true;
            useGL30 = true;
        }});

        var intent = getIntent();
        Events.on(ClientLoadEvent.class, u -> handleIntent(intent));

        try{
            //new external folder
            Fi data = Core.files.absolute(((Context)this).getExternalFilesDir(null).getAbsolutePath());
            Core.settings.setDataDirectory(data);

            //delete unused cache folder to free up space
            try{
                Fi cache = Core.settings.getDataDirectory().child("cache");
                if(cache.exists()){
                    cache.deleteDirectory();
                }
            }catch(Throwable t){
                Log.err("Failed to delete cached folder", t);
            }

            //move to internal storage if there's no file indicating that it moved
            if(!Core.files.local("files_moved").exists()){
                Log.info("Moving files to external storage...");

                try{
                    //current local storage folder
                    Fi src = Core.files.absolute(Core.files.getLocalStoragePath());
                    for(Fi fi : src.list()){
                        fi.copyTo(data);
                    }
                    //create marker
                    Core.files.local("files_moved").writeString("files moved to " + data);
                    Core.files.local("files_moved_103").writeString("files moved again");
                    Log.info("Files moved.");
                }catch(Throwable t){
                    Log.err("Failed to move files!");
                    t.printStackTrace();
                }
            }
        }catch(Exception e){
            //print log but don't crash
            Log.err(e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
        if(requestCode == PERMISSION_REQUEST_CODE){
            for(int i : grantResults){
                if(i != PackageManager.PERMISSION_GRANTED) return;
            }
            if(chooser != null){
                Core.app.post(chooser::show);
            }
            if(permCallback != null){
                Core.app.post(permCallback);
                permCallback = null;
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);

        if(installStatus.equals(intent.getAction())){
            onInstallStatus(intent);
            return;
        }
        handleIntent(intent);
    }

    /** GL: action of the intent the system installer sends back with the result of an update. */
    static final String installStatus = "mindustry.gl.INSTALL_STATUS";

    /** GL: writes the downloaded APK into an installer session; the system then asks the player to update. */
    void installApk(Fi apk){
        try{
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(getPackageName());
            int id = installer.createSession(params);
            try(PackageInstaller.Session session = installer.openSession(id)){
                try(InputStream in = apk.read(); OutputStream out = session.openWrite("update", 0, apk.length())){
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    session.fsync(out);
                }
                Intent status = new Intent(this, AndroidLauncher.class).setAction(installStatus);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                session.commit(PendingIntent.getActivity(this, id, status, flags).getIntentSender());
            }
        }catch(Throwable e){
            Log.err(e);
            Core.app.post(() -> ui.showException("@gl.be.update.installfailed", e));
        }
    }

    private void onInstallStatus(Intent intent){
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if(status == PackageInstaller.STATUS_PENDING_USER_ACTION){
            // the confirmation (and, the first time, the "install unknown apps" switch) of the system
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if(confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }else if(status != PackageInstaller.STATUS_SUCCESS){
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.err("Update install failed: @ @", status, message);
            //cancelled by the player: nothing to say
            if(status != PackageInstaller.STATUS_FAILURE_ABORTED){
                Core.app.post(() -> ui.showErrorMessage(Core.bundle.get("gl.be.update.installfailed") + (message == null ? "" : ":\n" + message)));
            }
        }
    }

    private void handleIntent(Intent intent){
        if(intent == null) return;

        try{
            Uri uri = intent.getData();
            if(uri != null){
                String scheme = uri.getScheme();

                //clear data (not sure if necessary?)
                intent.setAction(Intent.ACTION_MAIN);
                intent.setData(null);
                setIntent(intent);

                if("mindustry".equalsIgnoreCase(scheme)){ //open a server URL

                    String host = uri.getHost();
                    int port = uri.getPort();

                    if(host != null && !host.isEmpty()){
                        Core.app.post(() -> {
                            ui.showConfirm(Core.bundle.format("servers.connect.confirm", host), () -> ui.join.connect(host, port != -1 ? port : 6567));
                        });
                    }
                }else{ //open a save file
                    Fi file = Core.files.cache("imported");
                    file.write(getContentResolver().openInputStream(uri), false);

                    ClientLauncher.handleFileImport(file);
                }
            }
        }catch(Throwable e){
            Log.err(e);
        }
    }

    private boolean isTablet(Context context){
        TelephonyManager manager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        return manager != null && manager.getPhoneType() == TelephonyManager.PHONE_TYPE_NONE;
    }
}
