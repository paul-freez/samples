package com.project.main.utils;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.android.gms.auth.api.credentials.Credential;
import com.google.android.gms.auth.api.credentials.CredentialPickerConfig;
import com.google.android.gms.auth.api.credentials.CredentialRequest;
import com.google.android.gms.auth.api.credentials.CredentialRequestResponse;
import com.google.android.gms.auth.api.credentials.Credentials;
import com.google.android.gms.auth.api.credentials.CredentialsClient;
import com.google.android.gms.auth.api.credentials.CredentialsOptions;
import com.google.android.gms.auth.api.credentials.HintRequest;
import com.google.android.gms.auth.api.credentials.IdentityProviders;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.project.main.R;

import timber.log.Timber;

public class SmartLockManager {

    private static final int RC_READ = 94;
    private static final int RC_SAVE = 93;
    private static final int RC_HINT = 91;

    private MutableLiveData<Resolvable> resolvableApi = new MutableLiveData<>();

    private CredentialsClient mCredentialClient;

    private MutableLiveData<BasicCredentials> smartCredentials = new MutableLiveData<>();

    private MutableLiveData<SmartLockResult> smartResult = new MutableLiveData<>();

    public SmartLockManager(@NonNull Activity activity) {
        init(activity);
    }

    public static void disableSignIn(@NonNull Activity activity) {
        Credentials.getClient(activity).disableAutoSignIn();
    }

    private void init(@NonNull Activity activity) {
        if (!GooglePlayApi.checkAvailability(activity)) {
            smartResult.setValue(SmartLockResult.FAILED);
        }

        CredentialsOptions options = new CredentialsOptions.Builder()
                .forceEnableSaveDialog()
                .build();
        mCredentialClient = Credentials.getClient(activity, options);

        CredentialRequest credentialRequest = new CredentialRequest.Builder()
                .setPasswordLoginSupported(true)
                .build();

        mCredentialClient.request(credentialRequest).addOnCompleteListener(new OnCompleteListener<CredentialRequestResponse>() {
            @Override
            public void onComplete(@NonNull Task<CredentialRequestResponse> task) {
                if (task.isSuccessful()) {
                    onSmartLockCredentialRetreived(task.getResult().getCredential());
                }
                Exception e = task.getException();
                if (e instanceof ResolvableApiException) {
                    // This is most likely the case where the user has multiple saved
                    // credentials and needs to pick one. This requires showing UI to
                    // resolve the read request.
                    ResolvableApiException rae = (ResolvableApiException) e;
                    resolvableApi.setValue(new Resolvable(rae, RC_READ));

                } else if (e instanceof ApiException) {
                    // The user must create an account or sign in manually.
                    Timber.e(e);

                    HintRequest hintRequest = new HintRequest.Builder()
                            .setHintPickerConfig(new CredentialPickerConfig.Builder()
                                    .setShowCancelButton(true)
                                    .build())
                            .setEmailAddressIdentifierSupported(true)
                            .setAccountTypes(IdentityProviders.GOOGLE)
                            .build();

                    PendingIntent intent = mCredentialClient.getHintPickerIntent(hintRequest);
                    try {
                        activity.startIntentSenderForResult(intent.getIntentSender(), RC_HINT, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException hintException) {
                        Timber.e(hintException);
                    }
                }
            }
        });
    }

    public void save(String email, String password) {
        Credential credential = new Credential.Builder(email)
                .setPassword(password)
                .build();
        mCredentialClient.save(credential).addOnCompleteListener(new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull Task<Void> task) {
                if (task.isSuccessful()) {
                    Timber.d("Credentials saved");
                    smartResult.setValue(SmartLockResult.RESOLVED);
                } else {
                    Exception e = task.getException();
                    if (e instanceof ResolvableApiException) {
                        // Try to resolve the save request. This will prompt the user if
                        // the credential is new.
                        ResolvableApiException rae = (ResolvableApiException) e;
                        resolvableApi.setValue(new Resolvable(rae, RC_SAVE));
                    } else {
                        // Request has no resolution
                        Timber.e("Saving smartlock credentials failed");
                        smartResult.setValue(SmartLockResult.FAILED);
                    }
                }
            }
        });
    }

    private void onSmartLockCredentialRetreived(Credential credential) {
        String accountType = credential.getAccountType();
        if (accountType == null) {
            smartCredentials.setValue(new BasicCredentials(credential.getId(), credential.getPassword()));
        } else {
            Timber.w("Received some account data. Please debug!");
            // TODO:
        }
    }

    public void onSmartLockActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case RC_READ:
                if (resultCode == Activity.RESULT_OK) {
                    Credential credential = data.getParcelableExtra(Credential.EXTRA_KEY);
                    onSmartLockCredentialRetreived(credential);
                } else {
                    Timber.e("Credential Read: NOT OK");
                }
                break;

            case RC_SAVE:
                if (resultCode != Activity.RESULT_OK) {
                    Timber.e("Smartlock credentials save denied by user");
                    smartResult.setValue(SmartLockResult.FAILED);
                } else {
                    Timber.d("Credentials saved");
                    smartResult.setValue(SmartLockResult.RESOLVED);
                }
                break;

            case RC_HINT:
                if (resultCode == Activity.RESULT_OK) {
                    Credential credential = data.getParcelableExtra(Credential.EXTRA_KEY);
                    onSmartLockCredentialRetreived(credential);
                } else {
                    Timber.e("Hint Read: NOT OK");
                    DialogManager.showToasty(R.string.hint_read_failed);
                }
                break;
        }
    }

    public LiveData<Resolvable> getResolvableApi() {
        return resolvableApi;
    }

    public LiveData<BasicCredentials> getSmartCredentials() {
        return smartCredentials;
    }

    public LiveData<SmartLockResult> getSmartLockResult() {
        return smartResult;
    }

    public static class BasicCredentials {
        private CharSequence email;
        private CharSequence password;

        public BasicCredentials(CharSequence email, CharSequence password) {
            this.email = email;
            this.password = password;
        }

        public CharSequence getEmail() {
            return email;
        }

        public CharSequence getPassword() {
            return password;
        }
    }

    public enum  SmartLockResult {
        RESOLVED, FAILED
    }

    public static class Resolvable {

        private int requestCode;

        private ResolvableApiException exception;

        public Resolvable(ResolvableApiException exception, int requestCode) {
            this.requestCode = requestCode;
            this.exception = exception;
        }

        public void startResolution(@NonNull Activity activity) {
            try {
                exception.startResolutionForResult(activity, requestCode);
            } catch (IntentSender.SendIntentException e) {
                e.printStackTrace();
            }
        }
    }
}
