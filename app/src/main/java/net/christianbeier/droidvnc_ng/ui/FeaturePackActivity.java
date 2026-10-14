package net.christianbeier.droidvnc_ng.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ContentResolver;
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
import android.os.Environment;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;
import android.provider.Settings;
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

import net.christianbeier.droidvnc_ng.InputService;
import net.christianbeier.droidvnc_ng.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * On-device, user-driven feature hub.
 * No functionality here accepts background/remote screenshot, contact, or microphone requests.
 * All sensitive operations require visible local interaction, user-selected documents and
 * the operating system's standard permission flows.
 */
public final class FeaturePackActivity extends AppCompatActivity {
    private static final String PREFS = "remote_feature_pack";
    private static final String FOLDER = "selected_folder_uri";
    private TextView message;
    private Button recordingButton;
    private MediaRecorder recorder;
    private File recordingFile;
    private boolean recording;

    private final ActivityResultLauncher<Intent> chooseFolder =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            Uri uri = result.getData().getData();
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(FOLDER, uri.toString()).apply();
                event("Folder selected on this device");
                show("Folder access saved. You can revoke it in Android Settings.");
            } catch (SecurityException e) {
                show("Folder permission was not granted: " + e.getMessage());
            }
        });
    private final ActivityResultLauncher<Intent> importDocument =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            Uri incoming = result.getData().getData();
            if (incoming != null) copyIntoSelectedFolder(incoming);
        });
    private final ActivityResultLauncher<Intent> exportBackupFile =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            Uri output = result.getData().getData();
            if (output == null) return;
            new Thread(() -> {
                try {
                    File latest = LocalFolderBackup.latestBackup(this);
                    if (latest == null) throw new java.io.IOException("No local backups exist");
                    try (InputStream in = new java.io.FileInputStream(latest);
                         OutputStream out = getContentResolver().openOutputStream(output, "w")) {
                        if (out == null) throw new java.io.IOException("Destination cannot be opened");
                        byte[] bytes = new byte[8192]; int n;
                        while ((n=in.read(bytes))!=-1) out.write(bytes,0,n);
                    }
                    event("User exported a saved local backup");
                    runOnUiThread(() -> show("Backup exported to your chosen location."));
                } catch (Exception e) {
                    runOnUiThread(() -> show("Export failed: " + e.getMessage()));
                }
            }).start();
        });
    private final ActivityResultLauncher<String> contactsPermission =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) confirmContactsBackup(); else show("Contacts permission denied");
        });
    private final ActivityResultLauncher<String> microphonePermission =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) startRecording(); else show("Microphone permission denied");
        });
    private final ActivityResultLauncher<String> bluetoothPermission =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) showPairedBluetooth(); else show("Bluetooth access denied");
        });
    private final ActivityResultLauncher<String> notificationsPermission =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) sendTestNotification(); else show("Notification permission denied");
        });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(26, 28, 26, 28);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText("Remote Assistance • Feature Pack");
        title.setTextSize(23);
        root.addView(title);
        TextView notice = new TextView(this);
        notice.setPadding(0, 18, 0, 18);
        notice.setText("These buttons work on this phone, in the foreground. They do not silently send private files, screenshots, contacts, notifications or audio to Kali. Remote transfers require a separate, session-authorized transport.");
        root.addView(notice);
        message = new TextView(this);
        message.setPadding(8, 18, 8, 18);
        root.addView(message);
        add(root, "Choose shared folder (remember access)", this::pickFolder);
        add(root, "File manager • browse / share / rename / delete", this::browseFolder);
        add(root, "Stop sharing folder • revoke saved access", this::revokeFolder);
        add(root, "Import file into selected folder", () -> pickDocument(false));
        add(root, "Import photo / video", () -> pickDocument(true));
        add(root, "Screenshot → selected folder (no TigerVNC)", this::takeLocalScreenshot);
        add(root, "Backup folder now", this::manualBackup);
        add(root, "Export most recent local backup ZIP", this::exportLatestBackup);
        add(root, "Enable daily local folder backup", () -> setScheduledBackup(true));
        add(root, "Disable daily local folder backup", () -> setScheduledBackup(false));
        add(root, "Battery / Network / Storage / Android", this::deviceStatus);
        add(root, "Cloudflare Server / Account Status", this::accountStatus);
        add(root, "Export contacts to selected folder", this::requestContactsBackup);
        add(root, "Paired Bluetooth devices", this::pairedBluetooth);
        add(root, "Show a local test notification", this::testNotification);
        recordingButton = add(root, "START voice note (local)", this::toggleRecording);
        add(root, "Local action history", this::showHistory);
        add(root, "Remote Support • Request / Allow / End", this::finish);
        show("Ready. Select a folder to enable file management and backup.");
    }

    private Button add(LinearLayout root, String text, Runnable click) {
        Button button = new Button(this);
        button.setText(text);
        root.addView(button);
        button.setOnClickListener(v -> click.run());
        return button;
    }
    private void show(String text) { message.setText(text); }
    private void event(String text) { FeaturePackLog.append(this, text); }
    private Uri folderUri() {
        String value = getSharedPreferences(PREFS, MODE_PRIVATE).getString(FOLDER, null);
        return value == null ? null : Uri.parse(value);
    }
    private Uri requiredFolder() {
        Uri uri = folderUri();
        if (uri == null) { show("Choose a shared folder first."); return null; }
        return uri;
    }
    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        chooseFolder.launch(intent);
    }
    private void revokeFolder() {
        Uri selected = folderUri();
        if (selected == null) { show("No saved folder access exists."); return; }
        new AlertDialog.Builder(this).setTitle("Revoke this app's folder access?")
            .setMessage("Stops future local file and backup access. Existing backups in this app remain until app data is cleared.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("REVOKE", (d,w) -> {
                WorkManager.getInstance(this).cancelUniqueWork("consented-local-folder-backup");
                try {
                    getContentResolver().releasePersistableUriPermission(selected,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException ignored) { }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(FOLDER).apply();
                event("User revoked saved folder access and daily backup");
                show("Folder access revoked. Daily backups stopped.");
            }).show();
    }
    private void pickDocument(boolean mediaOnly) {
        if (requiredFolder() == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType(mediaOnly ? "*/*" : "*/*");
        if (mediaOnly) intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        importDocument.launch(intent);
    }
    private static final class Entry {
        Uri uri;
        String name;
        String mime;
        Entry(Uri uri, String name, String mime) { this.uri=uri; this.name=name; this.mime=mime; }
    }
    private List<Entry> entries(Uri tree) throws Exception {
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        ArrayList<Entry> results = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(children,
            new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                results.add(new Entry(child, cursor.getString(1), cursor.getString(2)));
            }
        }
        return results;
    }
    private void browseFolder() {
        Uri tree = requiredFolder(); if (tree == null) return;
        try {
            List<Entry> files = entries(tree);
            if (files.isEmpty()) { show("Selected folder is empty."); return; }
            CharSequence[] names = new CharSequence[files.size()];
            for (int i=0; i<files.size(); i++) names[i] = files.get(i).name;
            new AlertDialog.Builder(this).setTitle("Selected folder • choose file")
                .setItems(names, (dialog, which) -> fileActions(files.get(which)))
                .setNegativeButton("CLOSE", null).show();
        } catch (Exception e) { show("Cannot browse folder: " + e.getMessage()); }
    }
    private void fileActions(Entry item) {
        new AlertDialog.Builder(this).setTitle(item.name)
            .setItems(new String[]{"Share using Android", "Rename", "Delete"}, (dialog, choice) -> {
                if (choice == 0) {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType(item.mime != null ? item.mime : "application/octet-stream");
                    share.putExtra(Intent.EXTRA_STREAM, item.uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "Share file"));
                    event("User opened system sharing dialog");
                } else if (choice == 1) {
                    android.widget.EditText input = new android.widget.EditText(this);
                    input.setSingleLine(true); input.setText(item.name);
                    new AlertDialog.Builder(this).setTitle("Rename file")
                        .setView(input).setNegativeButton("CANCEL", null)
                        .setPositiveButton("RENAME", (d,w) -> {
                            String name = input.getText().toString().trim();
                            if (name.isEmpty() || name.contains("/")) { show("Invalid filename"); return; }
                            try {
                                DocumentsContract.renameDocument(getContentResolver(), item.uri, name);
                                event("Local file renamed"); show("Renamed to " + name);
                            } catch (Exception e) { show("Rename failed: " + e.getMessage()); }
                        }).show();
                } else {
                    new AlertDialog.Builder(this).setTitle("Delete " + item.name + "?")
                        .setMessage("This deletes the original file from the selected folder. It may not be recoverable.")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("DELETE", (d,w) -> {
                            try {
                                boolean done = DocumentsContract.deleteDocument(getContentResolver(), item.uri);
                                if (done) event("User deleted a local file");
                                show(done ? "File deleted" : "Provider refused deletion");
                            } catch (Exception e) { show("Delete failed: " + e.getMessage()); }
                        }).show();
                }
            }).show();
    }
    private Uri newDocument(String mime, String name) throws Exception {
        Uri tree = requiredFolder(); if (tree == null) throw new IllegalStateException("Choose folder first");
        Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri created = DocumentsContract.createDocument(getContentResolver(), dir, mime, name);
        if (created == null) throw new java.io.IOException("Provider denied file creation");
        return created;
    }
    private void copyIntoSelectedFolder(Uri source) {
        new Thread(() -> {
            Uri target = null;
            try {
                String fileName = "import-" + System.currentTimeMillis();
                String mime = getContentResolver().getType(source);
                try (Cursor cursor = getContentResolver().query(source,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) fileName = cursor.getString(0);
                }
                fileName = fileName.replace('/', '_');
                target = newDocument(mime == null ? "application/octet-stream" : mime, fileName);
                try (InputStream in = getContentResolver().openInputStream(source);
                     OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                    if (in == null || out == null) throw new java.io.IOException("Cannot open selected file");
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                event("User imported a local file");
                runOnUiThread(() -> show("File saved in your selected folder."));
            } catch (Exception e) {
                if (target != null) try { DocumentsContract.deleteDocument(getContentResolver(), target); } catch (Exception ignored) { }
                runOnUiThread(() -> show("Import failed: " + e.getMessage()));
            }
        }).start();
    }
    private void takeLocalScreenshot() {
        if (requiredFolder() == null) return;
        if (Build.VERSION.SDK_INT < 30) { show("Accessibility screenshots require Android 11+."); return; }
        new AlertDialog.Builder(this).setTitle("Capture this phone's screen now?")
            .setMessage("After pressing CAPTURE you have 4 seconds to switch to the screen you want. The PNG stays in the selected folder. Secure screens can be blocked. Nothing is automatically sent to Kali.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CAPTURE", (d,w) -> {
                // Delay gives the dialog time to close before the system takes the screenshot.
                new android.os.Handler(getMainLooper()).postDelayed(() -> {
                    try {
                        Uri output = newDocument("image/png", "Screenshot-"+System.currentTimeMillis()+".png");
                        boolean queued = InputService.captureOneScreenshot(output, (success, note) ->
                            runOnUiThread(() -> {
                                if (!success) try { DocumentsContract.deleteDocument(getContentResolver(), output); } catch (Exception ignored) { }
                                if (success) event("User saved an on-device screenshot");
                                show(success ? "Screenshot saved to selected folder" : "Screenshot failed: " + note);
                            }));
                        if (!queued) {
                            try { DocumentsContract.deleteDocument(getContentResolver(), output); } catch (Exception ignored) { }
                            show("Enable the droidVNC-NG Accessibility Service in Android Settings first.");
                        }
                    } catch (Exception e) { show("Screenshot failed: " + e.getMessage()); }
                }, 4000);
            }).show();
    }
    private void manualBackup() {
        if (requiredFolder() == null) return;
        new AlertDialog.Builder(this).setTitle("Back up your selected folder?")
            .setMessage("Copies readable top-level files into this app's private storage. No cloud upload. Limit 50 MiB per backup.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("BACK UP", (d,w) -> new Thread(() -> {
                String result = LocalFolderBackup.backup(this);
                event("User ran a local folder backup");
                runOnUiThread(() -> show(result));
            }).start()).show();
    }
    private void exportLatestBackup() {
        File latest = LocalFolderBackup.latestBackup(this);
        if (latest == null) { show("Run a local backup first."); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, latest.getName());
        exportBackupFile.launch(intent);
    }
    private void setScheduledBackup(boolean on) {
        if (on && requiredFolder() == null) return;
        if (on) {
            new AlertDialog.Builder(this).setTitle("Enable daily local backup?")
                .setMessage("Android schedules a roughly daily backup of your selected folder into private app storage. Not exact time; no background cloud upload. You can disable it here.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("ENABLE", (d,w) -> {
                    PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                        LocalFolderBackup.class, 24, TimeUnit.HOURS).build();
                    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                        "consented-local-folder-backup", ExistingPeriodicWorkPolicy.UPDATE, request);
                    event("Daily local backup enabled"); show("Daily local backup enabled.");
                }).show();
        } else {
            WorkManager.getInstance(this).cancelUniqueWork("consented-local-folder-backup");
            event("Daily local backup disabled"); show("Daily local backup disabled.");
        }
    }
    private void deviceStatus() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        long free = getFilesDir().getFreeSpace() / (1024 * 1024);
        ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        String network = caps == null ? "Offline/unknown" :
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" :
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Cellular" : "Other";
        show("Model: " + Build.MODEL + "\nAndroid: " + Build.VERSION.RELEASE +
            "\nBattery: " + battery + "%\nNetwork: " + network +
            "\nFree internal storage: " + free + " MiB");
    }
    private void accountStatus() {
        String url = getSharedPreferences("remote_assistance", MODE_PRIVATE).getString("server_url", "Not configured");
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        show("Cloudflare URL: " + url + "\nSigned in: " + (user == null ? "No" : user.getEmail()) +
            "\nLive Cloudflare connectivity is not tested by this button.");
    }
    private void requestContactsBackup() {
        if (requiredFolder() == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            contactsPermission.launch(Manifest.permission.READ_CONTACTS);
        else confirmContactsBackup();
    }
    private void confirmContactsBackup() {
        new AlertDialog.Builder(this).setTitle("Export your phone contacts locally?")
            .setMessage("This creates a CSV in your selected folder, containing contact names and phone numbers. It does not send anything to Kali or Cloudflare.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("EXPORT", (d,w) -> new Thread(() -> {
                Uri target = null;
                try {
                    target = newDocument("text/csv", "contacts-" + System.currentTimeMillis() + ".csv");
                    int count = 0;
                    try (OutputStream stream = getContentResolver().openOutputStream(target, "w")) {
                        if (stream == null) throw new java.io.IOException("Cannot open contacts export");
                        stream.write("Name,Phone\n".getBytes(StandardCharsets.UTF_8));
                        try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
                            if (c != null) while (c.moveToNext()) {
                                String name = c.getString(0); String phone = c.getString(1);
                                String line = csv(name) + "," + csv(phone) + "\n";
                                stream.write(line.getBytes(StandardCharsets.UTF_8)); count++;
                            }
                        }
                    }
                    int total = count;
                    event("User exported contacts locally");
                    runOnUiThread(() -> show("Exported " + total + " contact rows to selected folder"));
                } catch (Exception e) {
                    if (target != null) try { DocumentsContract.deleteDocument(getContentResolver(), target); } catch (Exception ignored) { }
                    runOnUiThread(() -> show("Contacts export failed: " + e.getMessage()));
                }
            }).start()).show();
    }
    private static String csv(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    private void pairedBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
        else showPairedBluetooth();
    }
    @SuppressWarnings("MissingPermission") private void showPairedBluetooth() {
        try {
            BluetoothManager mgr = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = mgr == null ? null : mgr.getAdapter();
            if (adapter == null) { show("Bluetooth is unavailable"); return; }
            Set<BluetoothDevice> paired = adapter.getBondedDevices();
            StringBuilder report = new StringBuilder("Paired Bluetooth devices: " + paired.size());
            for (BluetoothDevice d : paired) report.append("\n• ").append(d.getName());
            show(report.toString());
        } catch (SecurityException e) { show("Bluetooth permission denied"); }
    }
    private void testNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        else sendTestNotification();
    }
    private void sendTestNotification() {
        NotificationManager mgr = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (mgr == null) return;
        if (Build.VERSION.SDK_INT >= 26) mgr.createNotificationChannel(
            new NotificationChannel("feature_alerts", "Remote Assistance alerts", NotificationManager.IMPORTANCE_DEFAULT));
        mgr.notify(42, new NotificationCompat.Builder(this, "feature_alerts")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Remote Assistance")
            .setContentText("This is a notification from this phone's app.")
            .setAutoCancel(true).build());
        event("User displayed a local app notification");
        show("Local app notification sent.");
    }
    private void toggleRecording() {
        if (recording) { stopRecording(true); return; }
        if (requiredFolder() == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
        else startRecording();
    }
    private void startRecording() {
        new AlertDialog.Builder(this).setTitle("Record a voice note on this phone?")
            .setMessage("Microphone recording starts only after you confirm here and stops when you tap STOP or leave this screen. No remote microphone access.")
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("RECORD", (d,w) -> {
                try {
                    recordingFile = File.createTempFile("voice-note-", ".m4a", getCacheDir());
                    recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    recorder.setOutputFile(recordingFile.getAbsolutePath());
                    recorder.prepare(); recorder.start();
                    recording = true;
                    recordingButton.setText("STOP voice note");
                    event("User began visible voice note recording");
                    show("● Recording on this phone. Tap STOP to save. Leaving this screen stops it.");
                } catch (Exception e) {
                    cleanupRecorder();
                    show("Cannot start recording: " + e.getMessage());
                }
            }).show();
    }
    private void stopRecording(boolean save) {
        if (!recording) return;
        recording = false;
        recordingButton.setText("START voice note (local)");
        boolean successful = true;
        try { recorder.stop(); } catch (Exception e) { successful = false; }
        cleanupRecorder();
        if (save && successful && recordingFile != null) {
            File file = recordingFile;
            new Thread(() -> {
                Uri out = null;
                try {
                    out = newDocument("audio/mp4", "VoiceNote-" + System.currentTimeMillis() + ".m4a");
                    try (InputStream input = new java.io.FileInputStream(file);
                         OutputStream output = getContentResolver().openOutputStream(out, "w")) {
                        if (output == null) throw new java.io.IOException("File cannot be written");
                        byte[] buf = new byte[8192]; int n;
                        while ((n=input.read(buf)) != -1) output.write(buf,0,n);
                    }
                    event("User saved a voice note locally");
                    runOnUiThread(() -> show("Voice note saved to selected folder."));
                } catch (Exception e) {
                    if (out != null) try { DocumentsContract.deleteDocument(getContentResolver(), out); } catch (Exception ignored) {}
                    runOnUiThread(() -> show("Voice note save failed: " + e.getMessage()));
                } finally { file.delete(); }
            }).start();
        } else {
            if (recordingFile != null) recordingFile.delete();
            show("Recording stopped without saving.");
        }
        recordingFile = null;
    }
    private void cleanupRecorder() {
        if (recorder != null) {
            try { recorder.reset(); recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
    }
    private void showHistory() {
        new AlertDialog.Builder(this).setTitle("Local action history")
            .setMessage(FeaturePackLog.read(this)).setPositiveButton("OK", null).show();
    }
    @Override protected void onStop() {
        // Never record when the UI is in the background.
        if (recording) stopRecording(false);
        super.onStop();
    }
}
