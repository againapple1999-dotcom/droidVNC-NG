package net.christianbeier.droidvnc_ng.ui;

import android.os.Bundle;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import net.christianbeier.droidvnc_ng.R;

public class LoginActivity extends AppCompatActivity {

    private FirebaseAuth auth;
    private EditText serverInput, emailInput, passwordInput;
    private TextView status;
    private Button loginBtn, createBtn, resetBtn, signOutBtn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_login);

        auth = FirebaseAuth.getInstance();

        serverInput = findViewById(R.id.server_url_input);
        serverInput.setText(getSharedPreferences("remote_assistance", MODE_PRIVATE)
            .getString("server_url", ""));
        emailInput = findViewById(R.id.email_input);
        passwordInput = findViewById(R.id.password_input);
        status = findViewById(R.id.login_status);

        loginBtn = findViewById(R.id.btn_email_login);
        createBtn = findViewById(R.id.btn_create_account);
        resetBtn = findViewById(R.id.btn_reset_password);
        signOutBtn = findViewById(R.id.btn_sign_out);

        findViewById(R.id.btn_save_server).setOnClickListener(v -> {
            if (saveServerUrl()) {
                FirebaseUser signedIn = auth.getCurrentUser();
                if (signedIn != null && signedIn.isEmailVerified()) {
                    // Update Firestore only after this phone's user explicitly saves the URL.
                    registerDevice();
                } else {
                    status.setText("Server URL saved. Sign in with your account.");
                }
            }
        });
        createBtn.setOnClickListener(v -> createAccount());
        loginBtn.setOnClickListener(v -> signIn());
        resetBtn.setOnClickListener(v -> resetPassword());
        signOutBtn.setOnClickListener(v -> signOut());
        findViewById(R.id.btn_check_request)
            .setOnClickListener(v -> checkConnectionRequest());
        findViewById(R.id.btn_register_device)
            .setOnClickListener(v -> registerDevice());
        findViewById(R.id.btn_end_request)
            .setOnClickListener(v -> endRequest());
        findViewById(R.id.btn_feature_pack).setOnClickListener(v ->
            startActivity(new android.content.Intent(this, FeaturePackActivity.class)));
        findViewById(R.id.btn_start_remote_session)
            .setOnClickListener(v -> startRemoteSession());

        updateStatus();
    }

    // Cloudflare URL is configuration only, not permission to access the phone.
    // Never put passwords, Firebase tokens, or TURN credentials in this preference.
    private boolean saveServerUrl() {
        String raw = serverInput.getText().toString().trim();
        try {
            java.net.URI uri = new java.net.URI(raw);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null || host.isEmpty() || !host.contains(".")
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() != -1
                || (uri.getRawPath() != null
                    && !uri.getRawPath().isEmpty()
                    && !"/".equals(uri.getRawPath()))) {
                status.setText("Use an HTTPS server root URL only");
                return false;
            }
            String normalized = "https://" + host.toLowerCase(java.util.Locale.ROOT);
            getSharedPreferences("remote_assistance", MODE_PRIVATE)
                .edit().putString("server_url", normalized).apply();
            serverInput.setText(normalized);
            return true;
        } catch (java.net.URISyntaxException e) {
            status.setText("Invalid Cloudflare server URL");
            return false;
        }
    }

    private String email() {
        return emailInput.getText().toString().trim();
    }

    private String password() {
        return passwordInput.getText().toString();
    }

    private boolean validEmail() {
        if (!Patterns.EMAIL_ADDRESS.matcher(email()).matches()) {
            status.setText("Enter a valid email address");
            return false;
        }
        return true;
    }

    private boolean validPassword() {
        if (password().length() < 6) {
            status.setText("Password must have at least 6 characters");
            return false;
        }
        return true;
    }

    private void setBusy(boolean busy) {
        loginBtn.setEnabled(!busy);
        createBtn.setEnabled(!busy);
        resetBtn.setEnabled(!busy);
        signOutBtn.setEnabled(!busy);
    }

    private void showError(String action, Exception e) {
        String message = e == null ? "Unknown error" :
            e.getLocalizedMessage();
        status.setText(action + " failed: " + message);
        setBusy(false);
    }

    private void createAccount() {
        if (!saveServerUrl()) return;
        if (!validEmail() || !validPassword()) return;

        setBusy(true);
        status.setText("Creating account...");

        auth.createUserWithEmailAndPassword(
            email(), password()
        ).addOnCompleteListener(this, task -> {
            if (!task.isSuccessful()) {
                showError("Create account", task.getException());
                return;
            }

            FirebaseUser user = auth.getCurrentUser();
            if (user != null) {
                sendVerification(user);
            } else {
                auth.signOut();
                status.setText("Account created. Please sign in.");
                setBusy(false);
            }
        });
    }

    private void sendVerification(FirebaseUser user) {
        status.setText("Sending verification email...");

        user.sendEmailVerification()
            .addOnCompleteListener(this, task -> {
                auth.signOut();
                passwordInput.setText("");
                setBusy(false);

                if (task.isSuccessful()) {
                    status.setText(
                        "Check your email. Verify your account, then sign in."
                    );
                } else {
                    status.setText(
                        "Verification email could not be sent. " +
                        "Try signing in again."
                    );
                }
            });
    }

    private void signIn() {
        if (!saveServerUrl()) return;
        if (!validEmail() || !validPassword()) return;

        setBusy(true);
        status.setText("Signing in...");

        auth.signInWithEmailAndPassword(
            email(), password()
        ).addOnCompleteListener(this, task -> {
            if (!task.isSuccessful()) {
                showError("Sign in", task.getException());
                return;
            }

            FirebaseUser user = auth.getCurrentUser();

            if (user == null) {
                status.setText("Unable to load account");
                setBusy(false);
                return;
            }

            if (!user.isEmailVerified()) {
                sendVerification(user);
                return;
            }

            setBusy(false);
            passwordInput.setText("");
            updateStatus();
            // Same-account discovery: enroll this phone following a verified sign-in.
            // This is NOT approval for an incoming remote-control session.
            registerDevice();
        });
    }

    private void resetPassword() {
        if (!validEmail()) return;

        setBusy(true);
        status.setText("Sending password reset email...");

        auth.sendPasswordResetEmail(email())
            .addOnCompleteListener(this, task -> {
                setBusy(false);

                if (task.isSuccessful()) {
                    status.setText(
                        "If the email is registered, check your inbox."
                    );
                } else {
                    showError("Password reset", task.getException());
                }
            });
    }

    private void signOut() {
        stopService(new android.content.Intent(this, VncBridgeService.class));
        // Signing out must never leave a screen-sharing VNC service running locally.
        // Server-side revocation for future Internet relays must ALSO be enforced by
        // the relay itself (this Firestore update is best-effort if offline).
        FirebaseUser user = auth.getCurrentUser();
        String deviceId = getSharedPreferences("remote_assistance", MODE_PRIVATE)
            .getString("device_id", null);
        if (user != null && deviceId != null) {
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").document(user.getUid())
                .collection("devices").document(deviceId)
                .update("remoteRequest.status", "ended",
                    "remoteRequest.approvalSignature", "");
        }
        try {
            android.content.SharedPreferences prefs =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
            net.christianbeier.droidvnc_ng.Defaults defaults =
                new net.christianbeier.droidvnc_ng.Defaults(this);
            android.content.Intent stop = new android.content.Intent(
                this, net.christianbeier.droidvnc_ng.MainService.class);
            stop.setAction(net.christianbeier.droidvnc_ng.MainService.ACTION_STOP);
            stop.putExtra(net.christianbeier.droidvnc_ng.MainService.EXTRA_ACCESS_KEY,
                prefs.getString(net.christianbeier.droidvnc_ng.Constants.PREFS_KEY_SETTINGS_ACCESS_KEY,
                    defaults.getAccessKey()));
            androidx.core.content.ContextCompat.startForegroundService(this, stop);
        } catch (RuntimeException error) {
            android.util.Log.w("RemoteAuth", "VNC stop request failed", error);
        }
        auth.signOut();
        passwordInput.setText("");
        updateStatus();
    }


    private void registerDevice() {
        if (!saveServerUrl()) return;
        FirebaseUser user = auth.getCurrentUser();

        if (user == null || !user.isEmailVerified()) {
            status.setText("Please sign in with a verified email first");
            return;
        }

        android.content.SharedPreferences prefs =
            getSharedPreferences("remote_assistance", MODE_PRIVATE);

        String deviceId = prefs.getString("device_id", null);

        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
        }

        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("name",
            android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        data.put("platform", "android");
        data.put("serverUrl", prefs.getString("server_url", ""));
        data.put("status", "registered");
        data.put("updatedAt",
            com.google.firebase.firestore.FieldValue.serverTimestamp());
        try {
            data.put("approvalPublicKey", DeviceApprovalCrypto.enrollmentPublicKey(deviceId));
        } catch (Exception e) {
            status.setText("Device key setup failed: " + e.getClass().getSimpleName());
            return;
        }

        status.setText("Registering device...");

        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users")
            .document(user.getUid())
            .collection("devices")
            .document(deviceId)
            .set(data, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener(v ->
                status.setText("Device registered successfully")
            )
            .addOnFailureListener(e ->
                status.setText("Registration failed: " + e.getMessage())
            );
    }


    private void checkConnectionRequest() {
        FirebaseUser user = auth.getCurrentUser();

        if (user == null || !user.isEmailVerified()) {
            status.setText("Please sign in first");
            return;
        }

        String deviceId = getSharedPreferences(
            "remote_assistance", MODE_PRIVATE
        ).getString("device_id", null);

        if (deviceId == null) {
            status.setText("Register this device first");
            return;
        }

        status.setText("Checking connection request...");

        com.google.firebase.firestore.FirebaseFirestore
            .getInstance()
            .collection("users")
            .document(user.getUid())
            .collection("devices")
            .document(deviceId)
            .get(com.google.firebase.firestore.Source.SERVER)
            .addOnSuccessListener(doc -> {

                String requestStatus =
                    doc.getString("remoteRequest.status");

                String source =
                    doc.getString("remoteRequest.source");

                com.google.firebase.Timestamp expiry =
                    doc.getTimestamp("remoteRequest.expiresAt");

                if (!"pending".equals(requestStatus)
                    || expiry == null
                    || expiry.toDate().before(new java.util.Date())) {
                    status.setText("No active connection request");
                    return;
                }

                String localServer = getSharedPreferences(
                    "remote_assistance", MODE_PRIVATE
                ).getString("server_url", "");
                String requestServer = doc.getString("remoteRequest.serverUrl");
                if (localServer.isEmpty() || !localServer.equals(requestServer)) {
                    status.setText("Server URL mismatch. Check Kali and phone settings.");
                    return;
                }

                String requestId =
                    doc.getString("remoteRequest.id");

                if (requestId == null) {
                    status.setText("Invalid connection request");
                    return;
                }

                new androidx.appcompat.app.AlertDialog.Builder(
                    LoginActivity.this
                )
                .setTitle("Remote Connection Request")
                .setMessage(
                    "Request from: " +
                    (source == null ? "Unknown" : source) +
                    "\n\nAllow this remote session request?"
                )
                .setPositiveButton("ALLOW", (dialog, which) ->
                    respondToRequest(
                        doc.getReference(), requestId, "approved"
                    )
                )
                .setNegativeButton("DENY", (dialog, which) ->
                    respondToRequest(
                        doc.getReference(), requestId, "denied"
                    )
                )
                .show();
            })
            .addOnFailureListener(e ->
                status.setText(
                    "Request check failed: " + e.getMessage()
                )
            );
    }

    private void respondToRequest(
        com.google.firebase.firestore.DocumentReference ref,
        String requestId,
        String decision
    ) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || !user.isEmailVerified()) {
            status.setText("Please sign in first");
            return;
        }

        String deviceId = getSharedPreferences("remote_assistance", MODE_PRIVATE)
            .getString("device_id", null);
        if (deviceId == null) {
            status.setText("Register this device first");
            return;
        }
        // Device enrollment public key must be published by Register This Device.
        status.setText("Signing local decision...");

        com.google.firebase.firestore.FirebaseFirestore
            .getInstance()
            .runTransaction(transaction -> {
                com.google.firebase.firestore.DocumentSnapshot doc =
                    transaction.get(ref);

                String id = doc.getString("remoteRequest.id");
                String state = doc.getString("remoteRequest.status");
                com.google.firebase.Timestamp expiry =
                    doc.getTimestamp("remoteRequest.expiresAt");

                if (!requestId.equals(id)
                    || !"pending".equals(state)
                    || expiry == null
                    || !expiry.toDate().after(new java.util.Date())) {
                    throw new IllegalStateException(
                        "Request expired or changed"
                    );
                }

                try {
                    String signature = DeviceApprovalCrypto.sign(
                        user.getUid(), deviceId, requestId, decision,
                        expiry.toDate().getTime());
                    transaction.update(ref,
                        "remoteRequest.status", decision,
                        "remoteRequest.approvalSignature", signature,
                        "remoteRequest.approvalDeviceId", deviceId,
                        "remoteRequest.decidedAt",
                        com.google.firebase.firestore.FieldValue.serverTimestamp());
                } catch (Exception error) {
                    throw new IllegalStateException("Device approval signature failed", error);
                }
                return null;
            })
            .addOnSuccessListener(result ->
                status.setText("Request " + decision)
            )
            .addOnFailureListener(e ->
                status.setText(
                    "Decision failed: " + e.getMessage()
                )
            );
    }

    // The person holding the phone must explicitly start a visible relay session.
    // Firestore status is not sufficient: the AndroidKeyStore signature was
    // generated locally at approval and this request must still be unexpired.
    private void startRemoteSession() {
        FirebaseUser user = auth.getCurrentUser();
        String deviceId = getSharedPreferences("remote_assistance", MODE_PRIVATE)
            .getString("device_id", null);
        String serverUrl = getSharedPreferences("remote_assistance", MODE_PRIVATE)
            .getString("server_url", "");
        if (user == null || !user.isEmailVerified() || deviceId == null || serverUrl.isEmpty()) {
            status.setText("Sign in, save server URL and register this phone first");
            return;
        }
        String vncPassword = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this).getString(
                net.christianbeier.droidvnc_ng.Constants.PREFS_KEY_SETTINGS_PASSWORD,
                new net.christianbeier.droidvnc_ng.Defaults(this).getPassword());
        if (vncPassword == null || vncPassword.trim().length() < 8) {
            status.setText("Set a VNC password of at least 8 characters first");
            return;
        }
        String iface = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this).getString(
                net.christianbeier.droidvnc_ng.Constants.PREFS_KEY_SETTINGS_INTERFACE, "");
        if (iface == null || !iface.equals("lo")) {
            status.setText("Set VNC interface to loopback (lo) in the main screen first");
            return;
        }
        status.setText("Checking current approval...");
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(user.getUid())
            .collection("devices").document(deviceId)
            .get(com.google.firebase.firestore.Source.SERVER)
            .addOnSuccessListener(doc -> {
                String id = doc.getString("remoteRequest.id");
                String state = doc.getString("remoteRequest.status");
                String approvedBy = doc.getString("remoteRequest.approvalDeviceId");
                String signature = doc.getString("remoteRequest.approvalSignature");
                String requestedServer = doc.getString("remoteRequest.serverUrl");
                com.google.firebase.Timestamp expiry = doc.getTimestamp("remoteRequest.expiresAt");
                if (id == null || !"approved".equals(state) ||
                    !deviceId.equals(approvedBy) || signature == null || signature.isEmpty() ||
                    !serverUrl.equals(requestedServer) || expiry == null ||
                    expiry.toDate().getTime() <= System.currentTimeMillis()) {
                    status.setText("No currently approved remote session. Ask Kali to request again.");
                    return;
                }
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Start visible remote screen session?")
                    .setMessage("Only the VNC server you start yourself is shared. " +
                        "Android may ask for screen-capture permission. " +
                        "A foreground notification will provide Disconnect. " +
                        "Start the VNC server first from the main app screen.")
                    .setNegativeButton("CANCEL", (d, w) -> {})
                    .setPositiveButton("START SESSION", (d, w) -> {
                        android.content.Intent intent = new android.content.Intent(
                            this, VncBridgeService.class).setAction(VncBridgeService.ACTION_START);
                        intent.putExtra(VncBridgeService.EXTRA_DEVICE_ID, deviceId);
                        intent.putExtra(VncBridgeService.EXTRA_REQUEST_ID, id);
                        intent.putExtra(VncBridgeService.EXTRA_EXPIRES_MS,
                            expiry.toDate().getTime());
                        androidx.core.content.ContextCompat.startForegroundService(this, intent);
                        status.setText("Remote bridge requested. Disconnect via notification or Sign Out.");
                    }).show();
            }).addOnFailureListener(e ->
                status.setText("Approval check failed: " + e.getClass().getSimpleName()));
    }

    private void endRequest() {
        stopService(new android.content.Intent(this, VncBridgeService.class));
        FirebaseUser user = auth.getCurrentUser();
        String deviceId = getSharedPreferences("remote_assistance", MODE_PRIVATE)
            .getString("device_id", null);
        if (user == null || deviceId == null) {
            status.setText("Not signed in or not registered");
            return;
        }
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(user.getUid())
            .collection("devices").document(deviceId)
            .update("remoteRequest.status", "ended",
                "remoteRequest.approvalSignature", "")
            .addOnSuccessListener(v -> status.setText("Request ended on this phone"))
            .addOnFailureListener(e ->
                status.setText("Could not end request: " + e.getMessage()));
        // This marks signaling as ended. It does not start/stop the VNC server;
        // use the VNC service's visible Stop/Disconnect controls separately.
    }

    private void updateStatus() {
        FirebaseUser user = auth.getCurrentUser();

        if (user == null) {
            status.setText("Not signed in");
        } else if (user.isEmailVerified()) {
            status.setText("Signed in: " + user.getEmail());
        } else {
            auth.signOut();
            status.setText("Email not verified. Please sign in again.");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (auth != null) updateStatus();
    }
}
