package net.christianbeier.droidvnc_ng.ui;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Locally backs up user-granted root-level files. Never contacts the network. */
public final class LocalFolderBackup extends Worker {
    public LocalFolderBackup(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }
    @NonNull @Override public Result doWork() {
        String text = backup(getApplicationContext());
        FeaturePackLog.append(getApplicationContext(), "Scheduled local folder backup: " + (text.startsWith("Backup saved") ? "OK" : "Failed"));
        return text.startsWith("Backup saved") ? Result.success() : Result.failure();
    }
    public static File latestBackup(Context context) {
        File dir = new File(context.getFilesDir(), "consented-backups");
        File[] files = dir.listFiles((parent, name) -> name.startsWith("backup-") && name.endsWith(".zip"));
        if (files == null || files.length == 0) return null;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files[0];
    }
    public static String backup(Context context) {
        String treeValue = context.getSharedPreferences("remote_feature_pack", Context.MODE_PRIVATE)
            .getString("selected_folder_uri", null);
        if (treeValue == null) return "No folder selected";
        File directory = new File(context.getFilesDir(), "consented-backups");
        if (!directory.exists() && !directory.mkdirs()) return "Cannot create backup directory";
        File output = new File(directory, "backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip");
        long bytes = 0;
        int count = 0;
        try {
            Uri tree = Uri.parse(treeValue);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (FileOutputStream out = new FileOutputStream(output);
                 ZipOutputStream zip = new ZipOutputStream(out);
                 Cursor cursor = context.getContentResolver().query(children,
                     new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                         DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                         DocumentsContract.Document.COLUMN_MIME_TYPE,
                         DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                if (cursor != null) while (cursor.moveToNext()) {
                    String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                    long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) ||
                        name == null || name.contains("/") || name.contains("\\") ||
                        name.equals(".") || name.equals("..")) continue;
                    if (size > 10L*1024*1024 || (size > 0 && bytes + size > 50L*1024*1024) || count >= 250) continue;
                    Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    try (InputStream in = context.getContentResolver().openInputStream(child)) {
                        if (in == null) continue;
                        // Avoid Zip-Slip; entries are flat and duplicate names are deduplicated.
                        zip.putNextEntry(new ZipEntry(count + "-" + name));
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            bytes += n;
                            if (bytes > 50L*1024*1024) throw new java.io.IOException("Backup 50 MiB limit exceeded");
                            zip.write(buffer, 0, n);
                        }
                        zip.closeEntry(); count++;
                    }
                }
            }
            File[] archives = directory.listFiles((dir, name) -> name.startsWith("backup-") && name.endsWith(".zip"));
            if (archives != null && archives.length > 7) {
                java.util.Arrays.sort(archives, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i=7; i<archives.length; i++) archives[i].delete();
            }
            return "Backup saved locally: " + output.getName() + " (" + count + " files)";
        } catch (Exception e) {
            output.delete();
            return "Backup failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }
}
