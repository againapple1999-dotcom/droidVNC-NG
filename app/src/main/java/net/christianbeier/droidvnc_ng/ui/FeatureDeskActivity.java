package net.christianbeier.droidvnc_ng.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;

import net.christianbeier.droidvnc_ng.InputService;
import net.christianbeier.droidvnc_ng.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Owner-visible, short-lived Feature Desk. No boot startup or unattended access.
 * Every Kali operation requires a separate on-phone confirmation. Shared files are
 * confined to a single user-chosen SAF folder. No remote shell or secret extraction.
 */
public final class FeatureDeskActivity extends AppCompatActivity {
    private static final long MAX_FILE = 50L * 1024 * 1024;
    private static final int CHUNK = 24576;
    private static final String PREFS = "remote_feature_pack";
    private static final String FOLDER = "selected_folder_uri";
    private final OkHttpClient client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable expiryStop = () -> stop("Approved session timed out. Request again.");
    private final ArrayBlockingQueue<Integer> sendSignal = new ArrayBlockingQueue<>(1);
    private volatile WebSocket socket;
    private TextView status;
    private Button startButton;
    private volatile boolean active;
    private volatile boolean screenshotGrace;
    private long expiresMs;
    private volatile String pendingId;
    private volatile String pendingOp;
    private volatile JSONObject pendingArgs;
    private Uri incomingUri;
    private OutputStream incomingOut;
    private int incomingSeq;
    private long incomingSize, incomingBytes;
    private MediaRecorder recorder;
    private File recordingFile;
    private boolean recording;

    private final ActivityResultLauncher<Intent> folderPicker = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), outcome -> {
            if (outcome.getResultCode() != RESULT_OK || outcome.getData() == null) return;
            Uri uri = outcome.getData().getData();
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(FOLDER, uri.toString()).apply();
                note("Shared folder saved for this app. You may revoke it in Feature Pack.");
            } catch (Exception e) {note("Folder permission not saved: " + e.getClass().getSimpleName());}
        });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(24, 24, 24, 24);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        setContentView(scroll);
        TextView heading = new TextView(this);
        heading.setText("Remote Feature Desk • NO VNC"); heading.setTextSize(23);
        root.addView(heading);
        TextView warning = new TextView(this);
        warning.setPadding(0, 20, 0, 20);
        warning.setText("Kali can request Status, Folder Files/Photos/Videos, Transfer, Rename/Delete, " +
            "Screenshot, Backup/Daily Backup, Contacts CSV, Bluetooth, Test Notification, 10-second Voice Note and History. " +
            "Every action requires your on-phone approval. Screen and microphone operations can " +
            "require Android-specific consent. Leaving this screen DISCONNECTS (except brief approved screenshot). " +
            "No automatic startup, hidden capture, or remote shell.");
        root.addView(warning);
        status = new TextView(this); status.setPadding(0, 14, 0, 14);
        root.addView(status);
        Button folder = new Button(this);folder.setText("CHOOSE SHARED FOLDER (REMEMBER ACCESS)");
        folder.setOnClickListener(v -> pickFolder());root.addView(folder);
        startButton = new Button(this);startButton.setText("START REMOTE FEATURE DESK");
        startButton.setOnClickListener(v -> checkApproval());root.addView(startButton);
        Button stop = new Button(this);stop.setText("STOP / DISCONNECT");
        stop.setOnClickListener(v -> stop("Stopped by phone owner"));root.addView(stop);
        note("Ready. Choose folder, then approve a fresh request from Kali.");
    }

    private void note(String text) {runOnUiThread(() -> {if (status != null) status.setText(text);});}
    private void event(String event) {FeaturePackLog.append(this, "Remote (approved): " + event);}
    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPicker.launch(intent);
    }
    private Uri tree() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(FOLDER, null);
        if (saved == null) return null;
        Uri uri = Uri.parse(saved);
        for (android.content.UriPermission perm : getContentResolver().getPersistedUriPermissions()) {
            if (perm.getUri().equals(uri) && perm.isReadPermission() && perm.isWritePermission()) return uri;
        }
        return null;
    }
    private static boolean safeName(String s) {
        if (s == null || s.isEmpty() || s.length() > 160 || s.equals(".") || s.equals("..")) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '/' || s.charAt(i) == '\\' || s.charAt(i) < 32) return false;
        }
        return true;
    }
    private static boolean idOK(String s) {return s != null && s.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");}
    private void checkApproval() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String did = getSharedPreferences("remote_assistance", MODE_PRIVATE).getString("device_id", null);
        String server = getSharedPreferences("remote_assistance", MODE_PRIVATE).getString("server_url", "");
        if (user == null || !user.isEmailVerified() || did == null || !server.startsWith("https://")) {
            note("Sign in and Register This Device first.");return;
        }
        startButton.setEnabled(false);
        note("Checking fresh host approval...");
        FirebaseFirestore.getInstance().collection("users").document(user.getUid())
            .collection("devices").document(did).get(Source.SERVER)
            .addOnSuccessListener(doc -> {
                String rid=doc.getString("remoteRequest.id");
                String state=doc.getString("remoteRequest.status");
                String approvedBy=doc.getString("remoteRequest.approvalDeviceId");
                String signature=doc.getString("remoteRequest.approvalSignature");
                String approvedServer=doc.getString("remoteRequest.serverUrl");
                Timestamp exp=doc.getTimestamp("remoteRequest.expiresAt");
                if (!idOK(rid) || !"approved".equals(state) || !did.equals(approvedBy) ||
                    signature == null || signature.isEmpty() || !server.equals(approvedServer) ||
                    exp == null || exp.toDate().getTime() <= System.currentTimeMillis()) {
                    startButton.setEnabled(true);note("No fresh approval. Ask Kali to REQUEST, then ALLOW here.");return;
                }
                long until=exp.toDate().getTime();
                new AlertDialog.Builder(this).setTitle("Allow visible Remote Feature Desk session?")
                    .setMessage("For up to 1 hour while this screen is open. Every operation asks again for your " +
                        "approval. You can STOP at any time. Your folder permission only covers the chosen folder.")
                    .setNegativeButton("CANCEL", (d,w) -> startButton.setEnabled(true))
                    .setOnCancelListener(d -> startButton.setEnabled(true))
                    .setPositiveButton("START", (d,w) -> connect(user, server, did, rid, until)).show();
            }).addOnFailureListener(e -> {startButton.setEnabled(true);note("Approval check failed");});
    }
    private void connect(FirebaseUser user, String server, String did, String rid, long until) {
        if (System.currentTimeMillis() >= until) {startButton.setEnabled(true);note("Approval expired");return;}
        active=true;expiresMs=System.currentTimeMillis()+60L*60*1000;
        ui.removeCallbacks(expiryStop);ui.postDelayed(expiryStop,60L*60*1000);
        user.getIdToken(false).addOnSuccessListener(token -> {
            if (!active || token.getToken() == null) {stop("Firebase token missing");return;}
            String url="wss://"+server.substring(8)+"/features?role=host&deviceId="+did+"&requestId="+rid;
            Request request=new Request.Builder().url(url)
                .header("Authorization","Bearer "+token.getToken()).build();
            WebSocket ws=client.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket w, Response response) {
                    note("Feature Desk READY. Run python3 tools/kali_features.py on Kali.");
                }
                @Override public void onMessage(WebSocket w, String raw) {processMessage(w,raw);}
                @Override public void onClosed(WebSocket w,int code,String reason) {runOnUiThread(() -> stop("Remote desk disconnected"));}
                @Override public void onFailure(WebSocket w,Throwable error,Response response) {
                    runOnUiThread(() -> stop("Feature connection failed. Check approval/server."));
                }
            });
            if (active) socket=ws; else ws.close(1000,"Owner left");
        }).addOnFailureListener(e -> stop("Firebase token unavailable"));
    }
    private synchronized void processMessage(WebSocket ws,String raw) {
        if (!active || System.currentTimeMillis() >= expiresMs) {ws.close(1000,"Expired");return;}
        try {
            JSONObject msg=new JSONObject(raw);
            String type=msg.optString("type");
            if ("peer".equals(type)) {note("joined".equals(msg.optString("state")) ?
                "Kali connected. Awaiting your approval for each action." : "Kali left.");return;}
            if ("end".equals(type)) {runOnUiThread(() -> stop("Kali disconnected"));return;}
            if ("command".equals(type)) {
                String id=msg.optString("id"),op=msg.optString("op");
                JSONObject args=msg.optJSONObject("args");
                if (!idOK(id) || args==null || !supported(op) || pendingId != null) {
                    sendError(id,"Busy or invalid command");return;
                }
                pendingId=id;pendingOp=op;pendingArgs=args;
                runOnUiThread(() -> confirmCommand(id,op,args));return;
            }
            if (pendingId == null || !pendingId.equals(msg.optString("id"))) return;
            if ("file_start".equals(type) && "upload".equals(pendingOp)) {receiveStart(msg);return;}
            if ("file_chunk".equals(type) && "upload".equals(pendingOp)) {receiveChunk(msg);return;}
            if ("file_end".equals(type) && "upload".equals(pendingOp)) {receiveEnd();return;}
            if ("file_ready".equals(type) && !"upload".equals(pendingOp)) {sendSignal.offer(-1);return;}
            if ("file_ack".equals(type) && !"upload".equals(pendingOp)) {sendSignal.offer(msg.optInt("seq",-2));return;}
            if ("result".equals(type) && !"upload".equals(pendingOp)) {
                note("Kali saved the approved file.");endPending();return;
            }
            if ("error".equals(type)) {note("Kali transfer failed: "+msg.optString("message"));abortUpload();endPending();}
        } catch (Exception e) {note("Feature protocol error: "+e.getClass().getSimpleName());}
    }
    private static boolean supported(String op) {
        switch(op) {
            case "status":case "list":case "download":case "upload":case "rename":case "delete":
            case "screenshot":case "backup":case "backup_download":case "backup_daily_enable":
            case "backup_daily_disable":case "contacts_export":
            case "bluetooth":case "notify":case "voice_note":case "history":return true;
            default:return false;
        }
    }
    private void confirmCommand(String id,String op,JSONObject args) {
        if (!active || !id.equals(pendingId) || isFinishing()) return;
        String detail=op.toUpperCase(Locale.ROOT).replace('_',' ');
        if (args.has("name")) detail+="\nFile: "+args.optString("name");
        if (args.has("newName")) detail+="\nNew name: "+args.optString("newName");
        if (args.has("size")) detail+="\nSize: "+args.optLong("size")+" bytes";
        if ("delete".equals(op)) detail+="\nPermanent deletion may not be reversible!";
        if ("contacts_export".equals(op)) detail+="\nExports contact names/numbers to your selected folder (not automatically to Kali).";
        if ("voice_note".equals(op)) detail+="\nRecords only for 10 seconds ON THIS SCREEN, saves locally. No automatic audio upload.";
        if ("screenshot".equals(op)) detail+="\nYou'll have 3 seconds to open the screen you want. It will be sent to Kali.";
        new AlertDialog.Builder(this).setTitle("Kali requests permission")
            .setMessage(detail+"\n\nAllow this ONE operation?")
            .setNegativeButton("DENY",(d,w)->{sendDecision(id,false);endPending();})
            .setPositiveButton("ALLOW ONCE",(d,w)->{sendDecision(id,true);executeCommand(id,op,args);})
            .setOnCancelListener(d->{sendDecision(id,false);endPending();}).show();
    }
    private void sendDecision(String id,boolean allow) {
        try {send(new JSONObject().put("type","decision").put("id",id).put("allow",allow));}
        catch(Exception ignored) {}
    }
    private void executeCommand(String id,String op,JSONObject args) {
        if (!active || !id.equals(pendingId)) return;
        if ("upload".equals(op)) {
            if (!safeName(args.optString("name")) || args.optLong("size",-1)<0 ||
                args.optLong("size",-1)>MAX_FILE || tree()==null) {
                sendError(id,"Choose a writable folder, file size max 50MiB");endPending();return;
            }
            note("Approved Kali upload. Waiting for file...");return;
        }
        if ("screenshot".equals(op)) {takeRemoteScreenshot(id);return;}
        if ("voice_note".equals(op)) {recordVoiceNote(id);return;}
        new Thread(() -> {
            try {
                switch(op) {
                    case "status": sendResult(id,true,deviceInfo()); break;
                    case "list": sendResult(id,true,remoteFileList());break;
                    case "download": sendChosenFile(id,args.optString("name"));break;
                    case "rename": rename(id,args);break;
                    case "delete": delete(id,args);break;
                    case "backup": {
                        String r=LocalFolderBackup.backup(this);
                        sendResult(id,r.startsWith("Backup saved"),new JSONObject().put("message",r));break;
                    }
                    case "backup_daily_enable": {
                        if(tree()==null)throw new IllegalStateException("Choose shared folder first");
                        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                            "consented-local-folder-backup", ExistingPeriodicWorkPolicy.UPDATE,
                            new PeriodicWorkRequest.Builder(LocalFolderBackup.class,24,TimeUnit.HOURS).build());
                        sendResult(id,true,new JSONObject().put("message","Daily LOCAL backup enabled. No cloud upload."));break;
                    }
                    case "backup_daily_disable": {
                        WorkManager.getInstance(this).cancelUniqueWork("consented-local-folder-backup");
                        sendResult(id,true,new JSONObject().put("message","Daily local backup disabled."));break;
                    }
                    case "backup_download": {
                        File f=LocalFolderBackup.latestBackup(this);
                        if(f==null) throw new IllegalStateException("No backup. Run backup first.");
                        sendFile(id,f.getName(),"application/zip",new FileInputStream(f));break;
                    }
                    case "contacts_export": exportContacts(id);break;
                    case "bluetooth": sendResult(id,true,bluetoothInfo());break;
                    case "notify": localNotification();sendResult(id,true,new JSONObject().put("message","Phone notification posted"));break;
                    case "history":{String history=FeaturePackLog.read(this);
                        sendResult(id,true,new JSONObject().put("history",history.substring(0,Math.min(3500,history.length()))));break;}
                    default:throw new IllegalArgumentException("Unrecognized operation");
                }
                event(op);
            } catch(Exception e) {sendResultError(id,e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));}
        },"remote-feature-"+op).start();
    }
    private JSONObject deviceInfo() throws Exception {
        BatteryManager bm=(BatteryManager)getSystemService(BATTERY_SERVICE);
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities caps=cm==null?null:cm.getNetworkCapabilities(cm.getActiveNetwork());
        String net=caps==null?"Offline/unknown":caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"Cellular":"Other";
        return new JSONObject().put("model",Build.MODEL).put("android",Build.VERSION.RELEASE)
            .put("batteryPercent",bm==null?-1:bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("network",net).put("freeStorageMiB",getFilesDir().getFreeSpace()/1048576);
    }
    private Uri newDocument(String mime,String name) throws Exception {
        Uri folder=tree();if(folder==null) throw new IllegalStateException("Choose shared folder first");
        if (!safeName(name)) throw new IllegalArgumentException("Unsafe name");
        Uri root=DocumentsContract.buildDocumentUriUsingTree(folder,DocumentsContract.getTreeDocumentId(folder));
        Uri created=DocumentsContract.createDocument(getContentResolver(),root,mime,name);
        if (created==null) throw new IllegalStateException("Cannot create file");
        return created;
    }
    private Uri findFile(String name) throws Exception {
        if (!safeName(name)) throw new IllegalArgumentException("Unsafe file name");
        Uri folder=tree();if(folder==null) throw new IllegalStateException("Choose shared folder first");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(folder,DocumentsContract.getTreeDocumentId(folder));
        try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)) {
            if(c!=null) while(c.moveToNext()) {
                if(name.equals(c.getString(1)) && !DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)))
                    return DocumentsContract.buildDocumentUriUsingTree(folder,c.getString(0));
            }
        }
        return null;
    }
    private JSONObject remoteFileList() throws Exception {
        Uri folder=tree();if(folder==null) throw new IllegalStateException("Choose shared folder first");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(folder,DocumentsContract.getTreeDocumentId(folder));
        JSONArray items=new JSONArray();
        try(Cursor c=getContentResolver().query(children,new String[]{
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE},null,null,null)) {
            if(c!=null) while(c.moveToNext() && items.length()<100) {
                String name=c.getString(0),mime=c.getString(1);
                if(!safeName(name) || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))continue;
                items.put(new JSONObject().put("name",name).put("mime",mime)
                    .put("bytes",c.isNull(2)?-1:c.getLong(2)));
            }
        }
        return new JSONObject().put("files",items).put("limit","Top 100 files in chosen folder");
    }
    private void rename(String id,JSONObject args) throws Exception {
        String old=args.optString("name"),name=args.optString("newName");
        if(!safeName(name) || findFile(name)!=null) throw new IllegalArgumentException("Invalid or existing destination name");
        Uri file=findFile(old);if(file==null) throw new IllegalStateException("File not found");
        if(DocumentsContract.renameDocument(getContentResolver(),file,name)==null)throw new IllegalStateException("Provider refused rename");
        sendResult(id,true,new JSONObject().put("renamed",name));
    }
    private void delete(String id,JSONObject args) throws Exception {
        Uri file=findFile(args.optString("name"));if(file==null)throw new IllegalStateException("File not found");
        if(!DocumentsContract.deleteDocument(getContentResolver(),file))throw new IllegalStateException("Provider refused deletion");
        sendResult(id,true,new JSONObject().put("deleted",args.optString("name")));
    }
    private void sendChosenFile(String id,String name) throws Exception {
        Uri file=findFile(name);if(file==null)throw new IllegalStateException("File not found");
        String mime=getContentResolver().getType(file);
        InputStream stream=getContentResolver().openInputStream(file);
        if(stream==null)throw new IllegalStateException("Cannot read file");
        sendFile(id,name,mime==null?"application/octet-stream":mime,stream);
    }
    private void sendFile(String id,String name,String mime,InputStream src) throws Exception {
        File temp=File.createTempFile("feature-transfer-",".tmp",getCacheDir());
        try {
            long size=0;
            try(InputStream in=src;FileOutputStream out=new FileOutputStream(temp)) {
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1) {
                    size+=n;if(size>MAX_FILE)throw new IllegalStateException("File over 50MiB limit");
                    out.write(buffer,0,n);
                }
            }
            JSONObject head=new JSONObject().put("type","file_start").put("id",id)
                .put("name",name).put("size",size).put("mime",mime);
            sendSignal.clear();send(head);
            if(!waitSignal(-1,60))throw new IllegalStateException("Kali did not accept file");
            try(InputStream in=new FileInputStream(temp)) {
                byte[] buffer=new byte[CHUNK];int n,seq=0;
                while((n=in.read(buffer))!=-1) {
                    if(!active)throw new IllegalStateException("Session ended");
                    byte[] chunk=n==buffer.length?buffer:java.util.Arrays.copyOf(buffer,n);
                    send(new JSONObject().put("type","file_chunk").put("id",id).put("seq",seq)
                        .put("data",Base64.encodeToString(chunk,Base64.NO_WRAP)));
                    if(!waitSignal(seq,60))throw new IllegalStateException("File transfer acknowledgement timeout");
                    seq++;
                }
            }
            send(new JSONObject().put("type","file_end").put("id",id));
            note("Sent approved file. Waiting for Kali to save it...");
        } finally {temp.delete();}
    }
    private boolean waitSignal(int expected,int seconds) throws InterruptedException {
        Integer ack=sendSignal.poll(seconds,TimeUnit.SECONDS);
        return ack!=null && ack==expected && active;
    }
    private void receiveStart(JSONObject m) {
        try {
            if(incomingOut != null || tree()==null || !safeName(m.optString("name")) ||
                !pendingArgs.optString("name").equals(m.optString("name")) ||
                pendingArgs.optLong("size",-1)!=m.optLong("size",-2) ||
                pendingArgs.optLong("size",-1)>MAX_FILE ||
                !pendingArgs.optString("mime").equals(m.optString("mime")))
                throw new IllegalStateException("Unexpected upload metadata");
            String name=m.optString("name");
            if(findFile(name)!=null)throw new IllegalStateException("File already exists (no overwrite)");
            incomingUri=newDocument(m.optString("mime"),name);
            incomingOut=getContentResolver().openOutputStream(incomingUri,"w");
            if(incomingOut==null)throw new IllegalStateException("Provider denied upload");
            incomingSeq=0;incomingBytes=0;incomingSize=m.optLong("size");
            send(new JSONObject().put("type","file_ready").put("id",pendingId));
        } catch(Exception e) {abortUpload();sendError(pendingId,"Upload setup: "+e.getClass().getSimpleName());endPending();}
    }
    private void receiveChunk(JSONObject m) {
        try {
            if(incomingOut==null || incomingSeq!=m.optInt("seq",-1))throw new IllegalStateException("Out of order file chunk");
            byte[] bytes=Base64.decode(m.getString("data"),Base64.DEFAULT);
            if(bytes.length<1 || bytes.length>CHUNK || incomingBytes+bytes.length>incomingSize)
                throw new IllegalStateException("Bad file chunk size");
            incomingOut.write(bytes);incomingBytes+=bytes.length;
            send(new JSONObject().put("type","file_ack").put("id",pendingId).put("seq",incomingSeq));
            incomingSeq++;
        } catch(Exception e) {abortUpload();sendError(pendingId,"Upload write failed");endPending();}
    }
    private void receiveEnd() {
        try {
            if(incomingOut==null || incomingBytes!=incomingSize)throw new IllegalStateException("Incomplete upload");
            incomingOut.close();incomingOut=null;incomingUri=null;
            sendResult(pendingId,true,new JSONObject().put("uploadedBytes",incomingBytes));
        } catch(Exception e) {abortUpload();sendResultError(pendingId,"Incomplete upload");}
    }
    private void abortUpload() {
        if(incomingOut!=null)try{incomingOut.close();}catch(Exception ignored){}
        incomingOut=null;
        if(incomingUri!=null)try{DocumentsContract.deleteDocument(getContentResolver(),incomingUri);}catch(Exception ignored){}
        incomingUri=null;incomingSeq=0;incomingBytes=0;
    }
    private void takeRemoteScreenshot(String id) {
        if(tree()==null || Build.VERSION.SDK_INT<30) {sendResultError(id,"Choose folder and enable Android 11+ Accessibility screenshot");return;}
        note("Approved screenshot. Open target screen within 3 seconds...");
        screenshotGrace=true;
        ui.postDelayed(() -> {
            try {
                String name="Screenshot-"+System.currentTimeMillis()+".png";
                Uri dst=newDocument("image/png",name);
                boolean queued=InputService.captureOneScreenshot(dst,(success,message)-> {
                    if(!success) {
                        try{DocumentsContract.deleteDocument(getContentResolver(),dst);}catch(Exception ignored){}
                        screenshotGrace=false;sendResultError(id,"Screenshot failed: "+message);return;
                    }
                    new Thread(() -> {
                        try {
                            InputStream in=getContentResolver().openInputStream(dst);
                            if(in==null)throw new IllegalStateException("Cannot open saved screenshot");
                            sendFile(id,name,"image/png",in);event("Screenshot transferred after phone-owner approval");
                        } catch(Exception e) {sendError(id,"Screenshot transfer failed");endPending();}
                        // The screenshot can be delivered after the user switches screens;
                        // once transferred, only this operation gets a short-lived grace.
                    },"approved-screenshot-transfer").start();
                });
                if(!queued) {screenshotGrace=false;sendResultError(id,"Enable droidVNC-NG Accessibility in Android Settings");}
            } catch(Exception e) {screenshotGrace=false;sendResultError(id,"Cannot capture screenshot");}
        },3000);
    }
    private void exportContacts(String id) throws Exception {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("Grant Contacts permission on phone first");
        Uri dst=newDocument("text/csv","contacts-"+System.currentTimeMillis()+".csv");
        long count=0;
        try(OutputStream out=getContentResolver().openOutputStream(dst,"w")) {
            if(out==null)throw new IllegalStateException("Cannot write CSV");
            out.write("Name,Phone\n".getBytes(StandardCharsets.UTF_8));
            try(Cursor c=getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER},null,null,null)) {
                if(c!=null)while(c.moveToNext()) {
                    String line=csv(c.getString(0))+","+csv(c.getString(1))+"\n";
                    out.write(line.getBytes(StandardCharsets.UTF_8));count++;
                    if(count>10000)break;
                }
            }
        }
        sendResult(id,true,new JSONObject().put("message","Contacts saved locally to selected folder")
            .put("rows",count).put("next","Use List then Download with separate phone approval"));
    }
    private static String csv(String s) {return "\""+(s==null?"":s.replace("\"","\"\""))+"\"";}
    @SuppressWarnings("MissingPermission") private JSONObject bluetoothInfo() throws Exception {
        if(Build.VERSION.SDK_INT>=31 && ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("Grant Bluetooth Nearby Devices permission first");
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        JSONArray devices=new JSONArray();
        if(adapter!=null) {
            Set<BluetoothDevice> paired=adapter.getBondedDevices();
            if(paired!=null)for(BluetoothDevice d:paired) if(devices.length()<80)
                devices.put(d.getName()==null?"Unknown":d.getName());
        }
        return new JSONObject().put("pairedNames",devices);
    }
    private void localNotification() {
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager==null)return;
        String channel="feature_alerts";
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(
            channel,"Remote Feature Desk alerts",NotificationManager.IMPORTANCE_DEFAULT));
        manager.notify(843,new NotificationCompat.Builder(this,channel)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Remote Feature Desk")
            .setContentText("Your approved Kali test notification").setAutoCancel(true).build());
    }
    private void recordVoiceNote(String id) {
        if(tree()==null || ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            sendResultError(id,"Choose folder and grant microphone permission first");return;
        }
        try {
            recordingFile=File.createTempFile("approved-voice-",".m4a",getCacheDir());
            recorder=Build.VERSION.SDK_INT>=31?new MediaRecorder(this):new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(recordingFile.getAbsolutePath());
            recorder.prepare();recorder.start();recording=true;
            note("● Mic active on THIS PHONE for 10 seconds. STOP / DISCONNECT to cancel.");
            ui.postDelayed(() -> completeVoiceNote(id),10000);
        } catch(Exception e) {cancelVoice();sendResultError(id,"Microphone could not start");}
    }
    private void completeVoiceNote(String id) {
        if(!recording || !active || !id.equals(pendingId)) {cancelVoice();return;}
        try {recorder.stop();recording=false;releaseRecorder();}
        catch(Exception e) {cancelVoice();sendResultError(id,"Audio recording failed");return;}
        File src=recordingFile;recordingFile=null;
        new Thread(() -> {
            try {
                String name="VoiceNote-"+System.currentTimeMillis()+".m4a";
                Uri dest=newDocument("audio/mp4",name);
                try(InputStream in=new FileInputStream(src);OutputStream out=getContentResolver().openOutputStream(dest,"w")) {
                    if(out==null)throw new IllegalStateException("Cannot save voice note");
                    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
                }
                sendResult(id,true,new JSONObject().put("savedOnPhone",name)
                    .put("next","Download this file separately with phone-owner approval"));
                event("Owner recorded 10-second local voice note");
            } catch(Exception e) {sendResultError(id,"Could not save voice note");}
            finally {src.delete();}
        },"visible-voice-note-save").start();
    }
    private void releaseRecorder() {
        if(recorder!=null) {try{recorder.release();}catch(Exception ignored){} recorder=null;}
    }
    private void cancelVoice() {
        if(recorder!=null) {try{recorder.stop();}catch(Exception ignored){}releaseRecorder();}
        recording=false;
        if(recordingFile!=null) {recordingFile.delete();recordingFile=null;}
    }
    private void sendResultError(String id,String error) {
        try{sendResult(id,false,new JSONObject().put("error",error.substring(0,Math.min(150,error.length()))));}
        catch(Exception ignored){}
    }
    private void sendError(String id,String message) {
        try{send(new JSONObject().put("type","error").put("id",id).put("message",message));}
        catch(Exception ignored){}
    }
    private void sendResult(String id,boolean ok,JSONObject data) {
        try{send(new JSONObject().put("type","result").put("id",id).put("ok",ok).put("data",data));}
        catch(Exception ignored){}
        endPending();
    }
    private synchronized void endPending() {
        pendingId=null;pendingOp=null;pendingArgs=null;
        if(screenshotGrace) {
            screenshotGrace=false;
            runOnUiThread(() -> {if(!hasWindowFocus())stop("Screenshot finished; screen is no longer visible.");});
        }
    }
    private void send(JSONObject obj) {
        WebSocket ws=socket;
        if(active && ws!=null)ws.send(obj.toString());
    }
    private synchronized void stop(String reason) {
        active=false;screenshotGrace=false;sendSignal.offer(-500);
        ui.removeCallbacks(expiryStop);
        cancelVoice();abortUpload();endPending();
        WebSocket old=socket;socket=null;
        if(old!=null)old.close(1000,"Phone owner ended session");
        runOnUiThread(() -> {if(startButton!=null)startButton.setEnabled(true);note(reason);});
    }
    @Override protected void onStop() {
        if(!screenshotGrace) stop("Disconnected because phone owner left the Feature Desk screen.");
        super.onStop();
    }
    @Override protected void onDestroy() {
        stop("Feature Desk closed");
        client.dispatcher().executorService().shutdown();
        super.onDestroy();
    }
}
