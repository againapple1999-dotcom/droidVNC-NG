package net.christianbeier.droidvnc_ng.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import com.google.firebase.Timestamp;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Deliberately foreground-only read-only monitor. User must see this screen
 * and tap START for a fresh, on-device approved request. No screen capture,
 * Accessibility, filesystem, microphone, contacts, or remote input.
 */
public final class DeviceMonitorActivity extends AppCompatActivity {
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expiryStop = () -> stopMonitor("Approval expired. Request again from Kali.");
    private WebSocket websocket;
    private Button startButton;
    private TextView status;
    private long expiresMs;
    private boolean active;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(36, 40, 36, 24);
        TextView heading = new TextView(this);
        heading.setText("Device Monitor (NO VNC)");
        heading.setTextSize(23);
        layout.addView(heading);
        TextView notice = new TextView(this);
        notice.setPadding(0, 24, 0, 24);
        notice.setText("Only Model, Android version, Battery, Network and Free Storage can be requested. " +
            "No files, contacts, microphone, screenshots or remote touch. " +
            "You can STOP any time. Leaving this screen disconnects.");
        layout.addView(notice);
        status = new TextView(this);
        status.setText("Waiting for you to START. Approve a fresh request first.");
        status.setPadding(0, 16, 0, 16);
        layout.addView(status);
        startButton = new Button(this);
        startButton.setText("START DEVICE MONITOR");
        startButton.setOnClickListener(v -> checkApproval());
        layout.addView(startButton);
        Button stop = new Button(this);
        stop.setText("STOP / DISCONNECT");
        stop.setOnClickListener(v -> stopMonitor("Stopped by phone owner."));
        layout.addView(stop);
        setContentView(layout);
    }

    private void checkApproval() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String deviceId = getSharedPreferences("remote_assistance", MODE_PRIVATE)
            .getString("device_id", null);
        String server = getSharedPreferences("remote_assistance", MODE_PRIVATE)
            .getString("server_url", "");
        if (user == null || !user.isEmailVerified() || deviceId == null ||
            !server.startsWith("https://")) {
            status.setText("Sign in and Register This Device first."); return;
        }
        startButton.setEnabled(false);
        status.setText("Checking fresh Android approval...");
        FirebaseFirestore.getInstance().collection("users").document(user.getUid())
            .collection("devices").document(deviceId).get(Source.SERVER)
            .addOnSuccessListener(doc -> {
                String rid = doc.getString("remoteRequest.id");
                String state = doc.getString("remoteRequest.status");
                String approvedBy = doc.getString("remoteRequest.approvalDeviceId");
                String signature = doc.getString("remoteRequest.approvalSignature");
                String approvedServer = doc.getString("remoteRequest.serverUrl");
                Timestamp expires = doc.getTimestamp("remoteRequest.expiresAt");
                if (rid == null || !"approved".equals(state) ||
                    !deviceId.equals(approvedBy) || signature == null || signature.isEmpty() ||
                    !server.equals(approvedServer) || expires == null ||
                    expires.toDate().getTime() <= System.currentTimeMillis()) {
                    startButton.setEnabled(true);
                    status.setText("No valid approved request. Ask Kali to request again."); return;
                }
                long until = expires.toDate().getTime();
                new AlertDialog.Builder(this)
                    .setTitle("Share device status with Kali?")
                    .setMessage("Only model, Android version, battery, network type and free storage. " +
                        "This works only while this screen stays visible, and stops when approval expires.")
                    .setNegativeButton("CANCEL", (dialog, which) -> startButton.setEnabled(true))
                    .setPositiveButton("START MONITOR", (dialog, which) ->
                        connect(user, server, deviceId, rid, until))
                    .show();
            })
            .addOnFailureListener(error -> {
                startButton.setEnabled(true);
                status.setText("Approval check failed. Check Internet connection.");
            });
    }

    private void connect(FirebaseUser user, String server, String did, String rid, long until) {
        if (System.currentTimeMillis() >= until) {
            startButton.setEnabled(true); status.setText("Approval expired."); return;
        }
        active = true;
        expiresMs = until;
        handler.removeCallbacks(expiryStop);
        handler.postDelayed(expiryStop, Math.max(1, until - System.currentTimeMillis()));
        status.setText("Connecting to Cloudflare monitor...");
        user.getIdToken(false).addOnSuccessListener(result -> {
            if (!active || result.getToken() == null) return;
            String wsUrl = "wss://" + server.substring(8) +
                "/monitor?role=host&deviceId=" + did + "&requestId=" + rid;
            Request req = new Request.Builder().url(wsUrl)
                .header("Authorization", "Bearer " + result.getToken()).build();
            WebSocket ws = client.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) {
                    runOnUiThread(() -> { if (active) status.setText("Monitor ready. Open kali_monitor.py on Kali."); });
                }
                @Override public void onMessage(WebSocket socket, String raw) {
                    if (!active || System.currentTimeMillis() >= expiresMs) {socket.close(1000, "Expired"); return;}
                    try {
                        JSONObject obj = new JSONObject(raw);
                        String type = obj.optString("type");
                        if ("status_request".equals(type)) {
                            String id = obj.optString("id", "");
                            if (!id.matches("[0-9a-fA-F-]{36}")) return;
                            JSONObject reply = new JSONObject();
                            reply.put("type", "status_result");
                            reply.put("id", id);
                            reply.put("status", collectDeviceStatus());
                            socket.send(reply.toString());
                            runOnUiThread(() -> {
                                if (active) status.setText("Device status shared with approved Kali session.");
                            });
                        } else if ("end".equals(type)) {
                            runOnUiThread(() -> stopMonitor("Remote session ended."));
                        } else if ("peer".equals(type) && "left".equals(obj.optString("state"))) {
                            runOnUiThread(() -> { if (active) status.setText("Kali disconnected. Waiting for approved peer."); });
                        }
                    } catch (JSONException ignored) {
                        // Unknown messages do not trigger operations.
                    }
                }
                @Override public void onFailure(WebSocket socket, Throwable t, Response response) {
                    runOnUiThread(() -> { if (active) stopMonitor("Monitor connection failed. Recheck approval/server."); });
                }
                @Override public void onClosed(WebSocket socket, int code, String reason) {
                    runOnUiThread(() -> { if (active) stopMonitor("Monitor connection closed."); });
                }
            });
            if (active) websocket = ws; else ws.close(1000, "Left screen");
        }).addOnFailureListener(error -> stopMonitor("Firebase token unavailable."));
    }

    private JSONObject collectDeviceStatus() throws JSONException {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        String network = caps == null ? "Offline/unknown" :
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" :
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Cellular" : "Other";
        JSONObject obj = new JSONObject();
        obj.put("model", Build.MODEL);
        obj.put("android", Build.VERSION.RELEASE);
        obj.put("batteryPercent", battery);
        obj.put("network", network);
        obj.put("freeStorageMiB", getFilesDir().getFreeSpace() / (1024 * 1024));
        return obj;
    }

    private void stopMonitor(String note) {
        active = false;
        handler.removeCallbacks(expiryStop);
        WebSocket old = websocket;
        websocket = null;
        if (old != null) old.close(1000, "Stopped by phone owner");
        if (startButton != null) startButton.setEnabled(true);
        if (status != null) status.setText(note);
    }

    @Override protected void onStop() {
        stopMonitor("Monitor stopped because the screen is no longer visible.");
        super.onStop();
    }
    @Override protected void onDestroy() {
        client.dispatcher().executorService().shutdown();
        super.onDestroy();
    }
}
