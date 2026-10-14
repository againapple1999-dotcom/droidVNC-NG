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
    private EditText emailInput, passwordInput;
    private TextView status;
    private Button loginBtn, createBtn, resetBtn, signOutBtn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_login);

        auth = FirebaseAuth.getInstance();

        emailInput = findViewById(R.id.email_input);
        passwordInput = findViewById(R.id.password_input);
        status = findViewById(R.id.login_status);

        loginBtn = findViewById(R.id.btn_email_login);
        createBtn = findViewById(R.id.btn_create_account);
        resetBtn = findViewById(R.id.btn_reset_password);
        signOutBtn = findViewById(R.id.btn_sign_out);

        createBtn.setOnClickListener(v -> createAccount());
        loginBtn.setOnClickListener(v -> signIn());
        resetBtn.setOnClickListener(v -> resetPassword());
        signOutBtn.setOnClickListener(v -> signOut());

        updateStatus();
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
        auth.signOut();
        passwordInput.setText("");
        updateStatus();
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
