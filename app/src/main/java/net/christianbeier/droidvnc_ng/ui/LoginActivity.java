package net.christianbeier.droidvnc_ng.ui;

import android.os.Bundle;
import android.os.CancellationSignal;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

import net.christianbeier.droidvnc_ng.R;

public class LoginActivity extends AppCompatActivity {

    private Button signInButton;
    private TextView loginStatus;
    private FirebaseAuth auth;
    private CredentialManager credentialManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        signInButton = findViewById(R.id.btn_sign_in);
        loginStatus = findViewById(R.id.login_status);

        auth = FirebaseAuth.getInstance();
        credentialManager = CredentialManager.create(this);

        updateStatus();

        signInButton.setOnClickListener(v -> startGoogleSignIn());
    }

    private void updateStatus() {
        FirebaseUser user = auth.getCurrentUser();

        if (user == null) {
            loginStatus.setText("Not signed in");
        } else {
            loginStatus.setText("Signed in: " + user.getEmail());
        }
    }

    private void startGoogleSignIn() {
        loginStatus.setText("Opening Google Sign-In...");
        signInButton.setEnabled(false);

        GetGoogleIdOption option =
            new GetGoogleIdOption.Builder()
                .setServerClientId(
                    getString(R.string.default_web_client_id)
                )
                .setFilterByAuthorizedAccounts(false)
                .build();

        GetCredentialRequest request =
            new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();

        credentialManager.getCredentialAsync(
            this,
            request,
            new CancellationSignal(),
            ContextCompat.getMainExecutor(this),
            new CredentialManagerCallback<
                GetCredentialResponse,
                GetCredentialException>() {

                @Override
                public void onResult(GetCredentialResponse result) {
                    handleCredential(result.getCredential());
                }

                @Override
                public void onError(@NonNull GetCredentialException e) {
                    loginStatus.setText("Sign-in cancelled or failed");
                    signInButton.setEnabled(true);
                }
            }
        );
    }

    private void handleCredential(Credential credential) {
        if (!(credential instanceof CustomCredential)) {
            loginStatus.setText("Unsupported credential");
            signInButton.setEnabled(true);
            return;
        }

        CustomCredential custom = (CustomCredential) credential;

        if (!GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                .equals(custom.getType())) {
            loginStatus.setText("Invalid credential type");
            signInButton.setEnabled(true);
            return;
        }

        try {
            GoogleIdTokenCredential googleCredential =
                GoogleIdTokenCredential.createFrom(custom.getData());

            auth.signInWithCredential(
                GoogleAuthProvider.getCredential(
                    googleCredential.getIdToken(), null
                )
            ).addOnCompleteListener(this, task -> {
                signInButton.setEnabled(true);

                if (task.isSuccessful()) {
                    updateStatus();
                } else {
                    loginStatus.setText("Firebase authentication failed");
                }
            });

        } catch (Exception e) {
            loginStatus.setText("Unable to read Google credential");
            signInButton.setEnabled(true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (auth != null) {
            updateStatus();
        }
    }
}
