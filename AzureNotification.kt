package com.project.main.utils.notification

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import com.google.firebase.iid.FirebaseInstanceId
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.microsoft.windowsazure.messaging.NotificationHub
import com.project.main.activities.MainActivity
import com.project.main.activities.overview.OverviewActivity
import com.project.main.consts.BundleConstants
import com.project.main.consts.StorageConstants
import com.project.main.models.json.response.NotificationHubResponse
import com.project.main.utils.DeeplinkingUtils
import com.project.main.utils.SharedPreferencesManager
import com.project.main.web.ProjectApi
import com.project.main.web.RequestListener
import com.project.main.web.RequestPerformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object NotificationHubDefault {
    val lastNotification= MutableLiveData<RemoteNotification>()

    private var hub: NotificationHub? = null

    private val scope = CoroutineScope(Dispatchers.Default)

    var isRegistered = false
        private set

    fun register(activity: Activity) {
        scope.launch {
            retrieveConnectionData().also {
                Timber.d("Connecting notifications to ${it.first} - ${it.second}")
                connectToHub(activity, it.first, it.second).also { id -> isRegistered = !id.isNullOrEmpty() }
            }
        }
    }

    fun unregister() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    hub?.unregister().also { isRegistered = false }
                    SharedPreferencesManager.with(SharedPreferencesManager.PreferenceType.USER).remove(StorageConstants.SHARED_HUBNAME)
                    SharedPreferencesManager.with(SharedPreferencesManager.PreferenceType.USER).remove(StorageConstants.SHARED_HUBSIGNATURE)

                    Timber.d("UnRegistered Successfully")
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }
    }

    private suspend fun retrieveConnectionData(): Pair<String, String> {
        val sharedPreferences: SharedPreferences = SharedPreferencesManager.get(SharedPreferencesManager.PreferenceType.USER)

        val hubName = sharedPreferences.getString(StorageConstants.SHARED_HUBNAME, null)
        val hubConnectionString = sharedPreferences.getString(StorageConstants.SHARED_HUBSIGNATURE, null)

        return suspendCoroutine { continuation ->
            if (hubName.isNullOrEmpty() || hubConnectionString.isNullOrEmpty()) {
                Timber.d("Fetching default notification connection data")
                RequestPerformer.getDefaultNotificationHub(object : RequestListener<NotificationHubResponse>() {
                    override fun onSuccess(response: NotificationHubResponse) {
                        super.onSuccess(response)

                        continuation.resume(Pair(response.hubName, response.accessSignature))
                    }

                    override fun onFailure(err: String?): Boolean {
                        continuation.resumeWithException(Exception(err))
                        return super.onFailure(err)
                    }
                })
            } else {
                continuation.resume(Pair(hubName, hubConnectionString))
            }
        }
    }

    private suspend fun retrieveFCMToken(): String {
        return suspendCoroutine { continuation ->
            FirebaseInstanceId.getInstance().instanceId.addOnSuccessListener { instanceIdResult ->
                instanceIdResult.token.run {
                    Timber.d("FCM Registration Token: $this")
                    continuation.resume(this)
                }
            }
        }
    }

    private suspend fun connectToHub(ctx: Context, hubName: String, hubConnection: String): String? {
        val sharedPreferences: SharedPreferences = SharedPreferencesManager.get(SharedPreferencesManager.PreferenceType.USER)

        var resultString: String
        var regID: String? = null

        val tag = PlaySightApi.getPlaySightUserId().toString()

        val KEY_REG_ID = "registrationID"
        val KEY_FCM_TOKEN = "FCMtoken"

        val fcmToken = retrieveFCMToken()
        try {
            suspend fun updateConnection(token: String): String =
                    withContext(Dispatchers.IO) {
                        hub = NotificationHub(hubName, hubConnection, ctx).also { regID = it.register(token, tag).registrationId }
                        sharedPreferences.edit {
                            putString(KEY_REG_ID, regID)
                            putString(KEY_FCM_TOKEN, token)
                        }
                        "New NH Registration Successfully - RegId : $regID"
                    }

            // Storing the registration ID that indicates whether the generated token has been
            // sent to your server. If it is not stored, send the token to your server.
            // Otherwise, your server should have already received the token.
            if (sharedPreferences.getString(KEY_REG_ID, null).also { regID = it }.isNullOrEmpty()
                            .also { isMissing ->
                                if (isMissing) {
                                    Timber.d("Attempting a new registration with NH using FCM token : $fcmToken")
                                }
                            }
                    || (sharedPreferences.getString(KEY_FCM_TOKEN, null) != fcmToken).also { unEqual ->
                        if (unEqual) {
                            Timber.d("NH Registration refreshing with token : $fcmToken")
                        }
                    }) {
                resultString = updateConnection(fcmToken)
            } else {
                resultString = "Previously Registered Successfully - RegId : $regID"
            }
        } catch (e: java.lang.Exception) {
            Timber.e(e, "Failed to complete registration".also {
                resultString = it
            })
            // If an exception happens while fetching the new token or updating registration data
            // on a third-party server, this ensures that we'll attempt the update at a later time.
        }

        return regID.also { Timber.d(resultString) }
    }
}

@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class NotificationService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val notification = RemoteNotification.create(remoteMessage.data)

        NotificationHubDefault.lastNotification.postValue(notification)

        val extras = HashMap<String, Any>()
        extras[BundleConstants.BUNDLE_NOTIFICATION] = Gson().toJson(notification)
        extras[BundleConstants.BUNDLE_SESSIONID] = notification.sessionId ?: -1

        if (notification.type.restriction != NotificationRestriction.LoggedInSmartCourt && notification.message.isNotEmpty()) {
            prepareNotificationForDisplaying(applicationContext, extras, notification)
        }

        if (notification.type.restriction != NotificationRestriction.LoggedInSmartCourt || SharedPreferencesManager.isUserInLoginSession()) {
            requestContentUpdate(applicationContext, notification)
        }
    }

    /**
     * Sends the broadcast to make sure opened activities will receive the notification and update their content
     *
     * @param context      App context
     * @param notification Notification that was received and that requires update
     */
    private fun requestContentUpdate(context: Context, notification: RemoteNotification) {
        if (notification.type == NotificationType.ClipReady) {
            DeeplinkingUtils.createFastDeeplinkForClip(notification.sessionId!!, notification.fileName!!)
        }
        val updateIntent = Intent(NotificationInterceptor.EVENT_REQUEST_UPDATE)
        updateIntent.putExtra(BundleConstants.BUNDLE_NOTIFICATION, Gson().toJson(notification))
        context.sendBroadcast(updateIntent)
    }


    companion object {
        /**
         * Prepares Notification content and displaying it
         *
         * @param c            App context
         * @param extras       Extra params
         * @param notification new notification
         */
        fun prepareNotificationForDisplaying(c: Context?, extras: Map<String, Any>, notification: RemoteNotification) {
            val futureIntentClass = when (notification.type.restriction) {
                NotificationRestriction.LoggedInSmartCourt -> OverviewActivity::class.java
                else -> MainActivity::class.java
            }
            NotificationHelper.createSimpleNotification(c, notification.id, object : NotificationIntentApplier {
                override fun getIntentClass(): Class<*> {
                    return futureIntentClass
                }

                override fun getExtras(): Map<String, Any> {
                    return extras
                }

                override fun getMessage(): String {
                    return notification.message
                }
            })
        }
    }
}
