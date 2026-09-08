package com.gamervoice.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PlanManager {

    private const val TAG = "PlanManager"
    private const val PREFS_NAME = "gamervoice_plan_prefs"
    private const val KEY_IS_VIP = "key_is_vip"
    private const val KEY_VIP_EXPIRY = "key_vip_expiry"

    const val FREE_SAVED_ROOM_LIMIT = 2

    private var prefs: SharedPreferences? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun isVip(): Boolean {
        return prefs?.getBoolean(KEY_IS_VIP, false) ?: false
    }

    fun setVip(isVip: Boolean, expiryLabel: String = "Active VIP Pass") {
        prefs?.edit()
            ?.putBoolean(KEY_IS_VIP, isVip)
            ?.putString(KEY_VIP_EXPIRY, expiryLabel)
            ?.apply()

        // Sync with Firestore if user is logged in
        val user = AuthManager.getCurrentUser()
        if (user != null) {
            syncVipToFirestore(user.uid, user.idToken, isVip, expiryLabel)
        }
    }

    fun getRoomLimit(): Int {
        return if (isVip()) Int.MAX_VALUE else FREE_SAVED_ROOM_LIMIT
    }

    fun getPlanName(): String {
        return if (isVip()) "VIP SQUAD MEMBER" else "FREE SQUAD PLAN"
    }

    private fun syncVipToFirestore(uid: String, idToken: String, isVip: Boolean, expiryLabel: String) {
        try {
            val url = "https://firestore.googleapis.com/v1/projects/${AuthManager.PROJECT_ID}/databases/(default)/documents/users/$uid?updateMask.fieldPaths=isVip&updateMask.fieldPaths=planType"
            val fields = JSONObject().apply {
                put("isVip", JSONObject().put("booleanValue", isVip))
                put("planType", JSONObject().put("stringValue", if (isVip) "VIP" else "FREE"))
                put("vipExpiry", JSONObject().put("stringValue", expiryLabel))
            }
            val body = JSONObject().apply {
                put("fields", fields)
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .patch(body.toString().toRequestBody(JSON_MEDIA))

            if (idToken.isNotEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer $idToken")
            }

            httpClient.newCall(reqBuilder.build()).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    Log.w(TAG, "Failed to sync VIP status to Firestore: ${e.message}")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Error syncing VIP status", e)
        }
    }
}
