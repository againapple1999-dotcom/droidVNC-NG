/* Visible, session-bound relay for an already-started local droidVNC-NG VNC server.
 * Does NOT request MediaProjection, start screen capture, or bypass Android permissions.
 */
package net.christianbeier.droidvnc_ng.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import net.christianbeier.droidvnc_ng.R;
import net.christianbeier.droidvnc_ng.Defaults;
import net.christianbeier.droidvnc_ng.Constants;
import androidx.preference.PreferenceManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class VncBridgeService extends Service {
    public static final String ACTION_START = "remote.bridge.START";
    public static final String ACTION_STOP = "remote.bridge.STOP";
    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_DEVICE_ID = "deviceId";
    public static final String EXTRA_EXPIRES_MS = "expiresMs";
    private static final String TAG = "RemoteBridge";
    private static final String CHANNEL = "remote_assistance_active";
    private static final int NOTIFICATION_ID = 741;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build();
    private volatile boolean running;
    private volatile WebSocket websocket;
    private volatile Socket vncSocket;
    private String requestId, deviceId, serverUrl;
    private long expiresMs;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSession(); return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (running) return START_NOT_STICKY;
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID);
        expiresMs = intent.getLongExtra(EXTRA_EXPIRES_MS, 0);
        serverUrl = getSharedPreferences("remote_assistance", MODE_PRIVATE)
            .getString("server_url", "");
        String iface = PreferenceManager.getDefaultSharedPreferences(this).getString(
            Constants.PREFS_KEY_SETTINGS_INTERFACE, "");
        if (iface == null || !iface.equals("lo")) {
            Log.w(TAG, "Loopback-only VNC interface required");
            stopSelf(); return START_NOT_STICKY;
        }
        if (requestId == null || deviceId == null || !serverUrl.startsWith("https://")
            || expiresMs <= System.currentTimeMillis()) {
            stopSelf(); return START_NOT_STICKY;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "Active Remote Assistance", NotificationManager.IMPORTANCE_LOW));
        }
        running = true;
        startForeground(NOTIFICATION_ID, notification("Remote assistance connection active"));
        connect();
        return START_NOT_STICKY; // no unattended restart after process death
    }

    private Notification notification(String message) {
        Intent stop = new Intent(this, VncBridgeService.class).setAction(ACTION_STOP);
        PendingIntent p = PendingIntent.getService(this, 741, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Remote assistance — sharing session")
            .setContentText(message).setOngoing(true)
            .addAction(0, "Disconnect", p).build();
    }

    private void connect() {
        if (!running || System.currentTimeMillis() >= expiresMs) {
            stopSession(); return;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.isEmailVerified()) { stopSession(); return; }
        user.getIdToken(false).addOnSuccessListener(result -> {
            if (!running || result.getToken() == null) return;
            String url = "wss://" + serverUrl.substring(8)
                + "/vnc?role=host&deviceId=" + deviceId + "&requestId=" + requestId;
            Request request = new Request.Builder().url(url)
                .header("Authorization", "Bearer " + result.getToken()).build();
            websocket = client.newWebSocket(request, new WebSocketListener() {
                @Override public void onMessage(WebSocket ws, String message) {
                    if (message.contains("\"joined\"")) connectLocalVnc(ws);
                    else if (message.contains("\"left\"")) closeLocalVnc();
                    else if (message.contains("\"end\"")) stopSession();
                }
                @Override public void onMessage(WebSocket ws, ByteString bytes) {
                    Socket socket = vncSocket;
                    if (socket == null || socket.isClosed()) return;
                    try { synchronized (socket) { socket.getOutputStream().write(bytes.toByteArray()); } }
                    catch (IOException e) { closeLocalVnc(); }
                }
                @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(1000, "Closing"); }
                @Override public void onClosed(WebSocket ws, int code, String reason) { closeLocalVnc(); retry(); }
                @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                    Log.w(TAG, "Relay disconnected: " + error.getClass().getSimpleName());
                    closeLocalVnc(); retry();
                }
            });
        }).addOnFailureListener(e -> retry());
    }

    private void connectLocalVnc(WebSocket ws) {
        new Thread(() -> {
            if (!running || vncSocket != null) return;
            int port = PreferenceManager.getDefaultSharedPreferences(this).getInt(
                Constants.PREFS_KEY_SETTINGS_PORT, new Defaults(this).getPort());
            if (port < 1 || port > 65535) { Log.w(TAG,"VNC port disabled"); return; }
            Socket socket = new Socket();
            try {
                // Only connect to the VNC server on this same device. Never another IP.
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                if (!running) { socket.close(); return; }
                vncSocket = socket;
                byte[] b = new byte[16384];
                InputStream in = socket.getInputStream();
                int n;
                while (running && vncSocket == socket && (n = in.read(b)) > 0) {
                    if (!ws.send(ByteString.of(b, 0, n))) break;
                }
            } catch (IOException e) {
                Log.w(TAG,"Local VNC unavailable; start VNC with screen-sharing consent", e);
            } finally { closeLocalVnc(); }
        }, "RemoteBridge-LocalVnc").start();
    }

    private void closeLocalVnc() {
        Socket old = vncSocket;
        vncSocket = null;
        if (old != null) try { old.close(); } catch (IOException ignored) {}
    }

    private void retry() {
        if (running) handler.postDelayed(this::connect, 5000);
    }

    private void stopSession() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        closeLocalVnc();
        WebSocket old = websocket; websocket = null;
        if (old != null) old.close(1000, "Host disconnected");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onDestroy() { stopSession(); client.dispatcher().executorService().shutdown(); super.onDestroy(); }
}
