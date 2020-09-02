package com.project.main.activities;

import android.annotation.TargetApi;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.app.NavUtils;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.project.main.R;
import com.project.main.activities.base.BindingActivity;
import com.project.main.consts.BundleConstants;
import com.project.main.consts.Playsight;
import com.project.main.databinding.ActivitySigninBinding;
import com.project.main.utils.OSUtils;
import com.project.main.utils.SmartLockManager;
import com.project.main.viewmodel.SignInViewModel;
import com.project.main.views.dialogs.ForgotPasswordDialog;
import com.project.main.views.dialogs.SignInDialog;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class SignInActivity extends BindingActivity<ActivitySigninBinding> {

    private SignInDialog loadingView;

    private SignInViewModel signInModel;

    private SignInViewModel getViewModel() {
        if (signInModel == null) {
            signInModel = new ViewModelProvider(this).get(SignInViewModel.class);
            signInModel.subscribeLoader(this);
        }

        return signInModel;
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();

        NavUtils.navigateUpTo(this, getParentActivityIntent());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        getViewModel().onSignInActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void init(Bundle savedInstanceState) {
        this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        binding.setLifecycleOwner(this);
        binding.setModel(getViewModel());


        setToolbarBackground(R.color.base_navy);

        loadingView = new SignInDialog();
        loadingView.setTitle(R.string.dialog_login);

        binding.btnSignin.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                getViewModel().loginViaForm();
                OSUtils.hideSoftKeyboard(SignInActivity.this);
            }
        });

        binding.btnSigninSignup.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(SignInActivity.this, SignUpActivity.class);
                startActivity(intent);
            }
        });

        binding.btnSigninFb.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                getViewModel().loginViaFacebook(SignInActivity.this);
            }
        });

        binding.etSigninPass.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int action, KeyEvent keyEvent) {
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                    binding.btnSignin.performClick();
                    return true;
                }
                return false;
            }
        });

        binding.btnSigninForgot.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                new ForgotPasswordDialog().show(getSupportFragmentManager());
            }
        });

        // Restoring provided credentials
        final Intent intent = getIntent();
        if (intent != null) {
            final String email = intent.getStringExtra(BundleConstants.BUNDLE_EMAIL);
            getViewModel().email.setValue(email);

            final String pass = intent.getStringExtra(BundleConstants.BUNDLE_PASS);
            getViewModel().password.setValue(pass);

            // Auto-signin
            if (!TextUtils.isEmpty(pass) && !TextUtils.isEmpty(email)) {
                binding.btnSignin.performClick();
            }
        }

        getViewModel().getReady().observe(this, new Observer<Long>() {
            @Override
            public void onChanged(Long time) {
                Intent intent = new Intent(SignInActivity.this, MainActivity.class);
                intent.setAction(Playsight.Action.LOGIN);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK); //clear backstack
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION); //it will looks like the transition inside the app.
                startActivity(intent);

                finish();
            }
        });

        // Highlights
        Map<TextView, EditText> inputHighlights = new HashMap<>();
        inputHighlights.put(binding.tvSigninEmail, binding.etSigninEmail);
        inputHighlights.put(binding.tvSigninPass, binding.etSigninPass);
        OSUtils.highlight(inputHighlights, this);

        getViewModel().getError().observe(this, new Observer<SignInViewModel.SigningErrorCause>() {
            @Override
            public void onChanged(SignInViewModel.SigningErrorCause error) {
                if (error != null) {
                    switch (error.getCause()) {
                        case EMAIL:
                            binding.tiSigninEmail.setError(error.getMessage());
                            binding.etSigninEmail.requestFocus();
                            break;

                        case PASS:
                            binding.tiSigninPass.setError(error.getMessage());
                            binding.etSigninPass.requestFocus();
                            break;
                    }
                }
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            autofill();
        }

        getViewModel().enableSmartLock(this);
        getViewModel().getResolvableApi().observe(this, new Observer<SmartLockManager.Resolvable>() {
            @Override
            public void onChanged(SmartLockManager.Resolvable resolvable) {
                resolvable.startResolution(SignInActivity.this);
            }
        });
        getViewModel().getSmartCredentials().observe(this, new Observer<SmartLockManager.BasicCredentials>() {
            @Override
            public void onChanged(SmartLockManager.BasicCredentials credentials) {
                if (credentials.getPassword() != null) {
                    getViewModel().password.setValue(String.valueOf(credentials.getPassword()));
                }
                if (credentials.getEmail() != null) {
                    getViewModel().email.setValue(String.valueOf(credentials.getEmail()));
                }
            }
        });
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void autofill() {
        binding.etSigninEmail.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS);
        binding.etSigninPass.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);

        binding.etSigninEmail.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        binding.etSigninPass.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
    }

    @Override
    protected int getLayout() {
        return R.layout.activity_signin;
    }

}
