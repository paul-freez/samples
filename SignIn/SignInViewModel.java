package com.project.main.viewmodel;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.Transformations;

import com.facebook.AccessToken;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.login.LoginManager;
import com.facebook.login.LoginResult;
import com.project.main.App;
import com.project.main.R;
import com.project.main.consts.*;
import com.project.main.consts.StorageConstants;
import com.project.main.models.SocialToken;
import com.project.main.models.json.response.SignResult;
import com.project.main.models.json.response.UserToken;
import com.project.main.utils.DialogManager;
import com.project.main.utils.LoaderManager;
import com.project.main.utils.ProjectUtils;
import com.project.main.utils.SharedPreferencesManager;
import com.project.main.utils.SmartLockManager;
import com.project.main.utils.Validator;
import com.project.main.web.EmailSender;
import com.project.main.web.RequestListener;
import com.project.main.web.RequestPerformer;

import java.util.Collections;

import timber.log.Timber;

import static com.project.main.consts.enums.SocialNetwork.Facebook;

public class SignInViewModel extends LoadingViewModel {

    private MutableLiveData<SigningErrorCause> error = new MutableLiveData<>();

    private MediatorLiveData<Long> ready = new MediatorLiveData<>();

    private MutableLiveData<Long> signInReady = new MutableLiveData<>();

    private LiveData<SmartLockManager.SmartLockResult> mSmartLockResult;

    private CallbackManager facebookCallbackManager;
    private SmartLockManager smartLockManager;

    private LiveData<SmartLockManager.BasicCredentials> basicCredentials;

    private SocialToken socialToken;

    // Public fields for 2-way-DataBinding
    public MutableLiveData<String> email = new MutableLiveData<>();
    public MutableLiveData<String> password = new MutableLiveData<>();

    private RequestListener<UserToken> projectTokenListener = new RequestListener<UserToken>() {
        @Override
        public void onAnyResult(@Nullable UserToken response) {
            if (response != null) {
                ProjectUtils.setUserToken(response.getAccessToken());
            }

            signInReady.setValue(System.currentTimeMillis());
        }
    };
    private RequestListener<SignResult> loginListener = new RequestListener<SignResult>() {
        @Override
        public void onSuccess(@NonNull SignResult response) {
            super.onSuccess(response);
            // Saving password
            if (password.getValue() != null) {
                SharedPreferencesManager.setUserString(StorageConstants.SHARED_PASSWORD, password.getValue());
            }

            // Smartlock
            if (smartLockManager != null) {
                smartLockManager.save(response.getUserData().getUserEmail(), password.getValue());
            }

            // Signing in using social token
            if (checkUserSocialAccounts()) {
                RequestPerformer.getUserSocialToken(socialToken.getType(), socialToken.getToken(), SignInViewModel.this, projectTokenListener);
            } else {
                RequestPerformer.getUserToken(response.getUserData().getUserEmail(), password.getValue(), SignInViewModel.this, projectTokenListener);
            }
        }

        @Override
        public boolean onInvalidResponse() {
            super.onInvalidResponse();

            if (socialToken != null) {
                String socialAccountName = "";
                if (socialToken.getType() == Facebook) {
                    socialAccountName = App.getAppResources().getString(R.string.social_facebook);
                }

                String content = String.format(App.getAppResources().getString(R.string.dialog_mess_login_social_first),
                        socialAccountName);

                socialToken.disapprove();

                DialogManager.getInstance().showQuickDialog(R.string.dialog_title_notice, content);

            } else {
                DialogManager.getInstance().showQuickDialog(R.string.dialog_mess_login_wrong);
            }

            return true;
        }
    };

    public SignInViewModel() {
        ready.addSource(signInReady, new Observer<Long>() {
            @Override
            public void onChanged(Long completionTime) {
                if (completionTime != null && mSmartLockResult != null && mSmartLockResult.getValue() != null) {
                    ready.setValue(System.currentTimeMillis());
                }
            }
        });

        //Clear all previous settings
        SharedPreferencesManager.clearLoginInfo();

        initFacebook();
    }

    public void enableSmartLock(@NonNull Activity activity) {
        smartLockManager = new SmartLockManager(activity);
        basicCredentials = Transformations.map(smartLockManager.getSmartCredentials(), new Function<SmartLockManager.BasicCredentials, SmartLockManager.BasicCredentials>() {
            @Override
            public SmartLockManager.BasicCredentials apply(SmartLockManager.BasicCredentials input) {
                loginViaForm();
                return input;
            }
        });
        mSmartLockResult = smartLockManager.getSmartLockResult();

        ready.addSource(mSmartLockResult, new Observer<SmartLockManager.SmartLockResult>() {
            @Override
            public void onChanged(SmartLockManager.SmartLockResult smartLockResult) {
                if (smartLockResult != null && signInReady.getValue() != null) {
                    ready.setValue(System.currentTimeMillis());
                }
            }
        });
    }

    private void initFacebook() {
        facebookCallbackManager = CallbackManager.Factory.create();

        LoginManager.getInstance().registerCallback(
                facebookCallbackManager,
                new FacebookCallback<LoginResult>() {
                    @Override
                    public void onSuccess(LoginResult loginResult) {
                        AccessToken fb = loginResult.getAccessToken();
                        socialToken = new SocialToken(fb.getToken(), fb.getUserId(), Facebook);
                        login(socialToken);
                    }

                    @Override
                    public void onCancel() {
                        Timber.d("On cancel");
                        hideLoader();
                    }

                    @Override
                    public void onError(FacebookException error) {
                        Timber.d("Error -%s", error.toString());

                        hideLoader();
                        DialogManager.getInstance().showQuickDialog(R.string.dialog_mess_err_login_fb);
                    }
                });
    }

    private boolean checkUserSocialAccounts() {
        if (socialToken != null) {
            if (!socialToken.isApproved()) {
                if (socialToken.getType() == Facebook) {
                    RequestPerformer.addSocialAccount(socialToken, this, null);
                }
            }
            socialToken.save();

            return true;
        }

        return false;
    }

    public void loginViaFacebook(@NonNull Activity activity) {
        showLoader();

        LoginManager.getInstance().logInWithReadPermissions(activity, Collections.singletonList("public_profile"));
    }

    public void loginViaForm() {
        error.setValue(null);
        String errorMsg;

        errorMsg = Validator.with(App.getContext())
                .validate(Validator.ValidationType.EMAIL, email.getValue());
        if (errorMsg != null) {
            error.setValue(new SigningErrorCause(errorMsg, SigninError.EMAIL));
        }
        errorMsg = Validator.with(App.getContext())
                .validate(Validator.ValidationType.PASSWORD, password.getValue());
        if (errorMsg != null) {
            error.setValue(new SigningErrorCause(errorMsg, SigninError.PASS));
        }

        if (error.getValue() == null) {
            login(null);
        }
    }

    private void login(@Nullable SocialToken sToken) {
        if (sToken != null) {
            RequestPerformer.socialLogin(sToken.getUserId(), sToken.getType(), this, loginListener);
        } else {
            RequestPerformer.signIn(email.getValue(), password.getValue(), this, loginListener);
        }
    }

    public void forgotPassword() {
        String emailError = Validator.with(App.getContext())
                .validate(Validator.ValidationType.EMAIL, email.getValue());
        error.setValue(new SigningErrorCause(emailError, SigninError.FORGOT_EMAIL));

        if (emailError == null) {
            RequestPerformer.recoverPassword(email.getValue(), this, new RequestListener<String>() {
                @Override
                public void onSuccess(String password) {
                    SignInViewModel.this.password.setValue("");
                    LoaderManager.getInstance().addLoader(SignInViewModel.this);
                    EmailSender.sendPasswordRecoveryEmail(email.getValue(), password, new RequestListener() {
                        @Override
                        public void onSuccess(Object response) {
                            LoaderManager.getInstance().requestHideLoader();
                            DialogManager.getInstance().showQuickDialog(R.string.password_recovery_success);
                        }
                    });
                }

                @Override
                public boolean onFailure(String err) {
                    try {
                        if (Integer.valueOf(err) == 4) {
                            DialogManager.getInstance().showQuickDialog(R.string.to_reset_this_password_contact_to_factility_admin);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    return super.onFailure(err);
                }

                @Override
                public boolean onFailure() {
                    DialogManager.getInstance().showQuickDialog(R.string.forgot_password_email_error);

                    return true;
                }
            });
        }
    }

    public void onSignInActivityResult(int requestCode, int resultCode, Intent data) {
        facebookCallbackManager.onActivityResult(requestCode, resultCode, data);

        smartLockManager.onSmartLockActivityResult(requestCode, resultCode, data);
    }


    public LiveData<SigningErrorCause> getError() {
        return error;
    }

    public LiveData<Long> getReady() {
        return ready;
    }

    public LiveData<SmartLockManager.Resolvable> getResolvableApi() {
        return smartLockManager.getResolvableApi();
    }

    public LiveData<SmartLockManager.BasicCredentials> getSmartCredentials() {
        return basicCredentials;
    }

    public static class SigningErrorCause {
        private String message;
        private SigninError cause;

        private SigningErrorCause(String message, SigninError cause) {
            this.message = message;
            this.cause = cause;
        }

        public String getMessage() {
            return message;
        }

        public SigninError getCause() {
            return cause;
        }
    }

    public enum SigninError {
        EMAIL, PASS,
        FORGOT_EMAIL,
        FB_NOT_LINKED,
        UNKNOWN
    }
}
