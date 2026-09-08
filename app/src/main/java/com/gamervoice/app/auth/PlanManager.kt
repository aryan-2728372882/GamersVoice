package com.gamervoice.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class PlanTier(
    val id: String,
    val title: String,
    val durationDays: Int,
    val priceInr: Int,
    val priceUsd: Double
) {
    FREE("FREE", "Free Squad Plan", 0, 0, 0.0),
    WEEKLY("WEEKLY", "Weekly Gamer Pass (7 Days)", 7, 29, 0.99),
    MONTHLY("MONTHLY", "Monthly Squad Pro (30 Days)", 30, 89, 2.99),
    LIFETIME("LIFETIME", "Lifetime Legend Pass", -1, 249, 7.99)
}

object PlanManager {

    private const val TAG = "PlanManager"
    private const val PREFS_NAME = "gamervoice_plan_prefs"
    private const val KEY_IS_VIP = "key_is_vip"
    private const val KEY_PLAN_TIER = "key_plan_tier"
    private const val KEY_EXPIRY_TIMESTAMP = "key_expiry_timestamp"
    private const val KEY_EXPIRY_LABEL = "key_vip_expiry_label"
    private const val KEY_PURCHASED_AT = "key_purchased_at"
    private const val KEY_USER_EMAIL = "key_user_email"
    private const val KEY_PAYMENT_ID = "key_payment_id"

    const val RAZORPAY_KEY_ID = "rzp_live_SWhlEskNokZ9rR"
    const val FREE_SAVED_ROOM_LIMIT = 2

    private var prefs: SharedPreferences? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            checkAndEnforceExpiry()
            enforceValidPaidStatus()
        }
    }

    fun isVip(): Boolean {
        checkAndEnforceExpiry()
        return prefs?.getBoolean(KEY_IS_VIP, false) ?: false
    }

    fun getCurrentPlanTier(): PlanTier {
        val tierId = prefs?.getString(KEY_PLAN_TIER, PlanTier.FREE.id) ?: PlanTier.FREE.id
        return PlanTier.values().find { it.id == tierId } ?: PlanTier.FREE
    }

    fun getRoomLimit(): Int {
        return if (isVip()) Int.MAX_VALUE else FREE_SAVED_ROOM_LIMIT
    }

    fun getPlanName(): String {
        return if (isVip()) {
            when (getCurrentPlanTier()) {
                PlanTier.WEEKLY -> "VIP WEEKLY (7 DAYS)"
                PlanTier.MONTHLY -> "VIP MONTHLY (30 DAYS)"
                PlanTier.LIFETIME -> "VIP LIFETIME LEGEND"
                PlanTier.FREE -> "FREE SQUAD PLAN"
            }
        } else {
            "FREE SQUAD PLAN"
        }
    }

    fun getExpiryLabel(): String {
        if (!isVip()) return "Free Plan"
        val tier = getCurrentPlanTier()
        if (tier == PlanTier.LIFETIME) return "N/A (Permanent)"
        val exp = prefs?.getString(KEY_EXPIRY_LABEL, "") ?: ""
        return if (exp.isNotEmpty()) exp else "Active"
    }

    fun getExpiryCountdown(): String {
        if (!isVip()) return "Free Plan (2 Rooms Max)"
        val tier = getCurrentPlanTier()
        if (tier == PlanTier.LIFETIME) return "Permanent Access (N/A)"

        val expiryTimestamp = prefs?.getLong(KEY_EXPIRY_TIMESTAMP, -1L) ?: -1L
        if (expiryTimestamp == -1L) return "Active VIP"

        val now = System.currentTimeMillis()
        val diffMs = expiryTimestamp - now
        if (diffMs <= 0) {
            return "Expired"
        }

        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs) % 24
        val expiryDateStr = prefs?.getString(KEY_EXPIRY_LABEL, "") ?: ""

        return if (days > 0) {
            "$days days, $hours hrs left (Until $expiryDateStr)"
        } else {
            "$hours hrs left (Until $expiryDateStr)"
        }
    }

    /**
     * Checks if current VIP subscription has expired (e.g. 7 or 30 days passed).
     * If expired, automatically reverts to Free version and syncs with Firestore.
     */
    fun checkAndEnforceExpiry(onExpired: (() -> Unit)? = null): Boolean {
        val p = prefs ?: return false
        val isCurrentVip = p.getBoolean(KEY_IS_VIP, false)
        if (!isCurrentVip) return false

        val tierId = p.getString(KEY_PLAN_TIER, PlanTier.FREE.id) ?: PlanTier.FREE.id
        if (tierId == PlanTier.LIFETIME.id) {
            return false // Lifetime never expires
        }

        val expiryTimestamp = p.getLong(KEY_EXPIRY_TIMESTAMP, -1L)
        if (expiryTimestamp > 0 && System.currentTimeMillis() > expiryTimestamp) {
            // Expired! Revert immediately to Free plan
            Log.i(TAG, "VIP subscription expired. Reverting user to Free plan.")
            p.edit()
                .putBoolean(KEY_IS_VIP, false)
                .putString(KEY_PLAN_TIER, PlanTier.FREE.id)
                .putString(KEY_EXPIRY_LABEL, "Expired")
                .apply()

            val user = AuthManager.getCurrentUser()
            if (user != null) {
                syncExpiredToFirestore(user.uid, user.idToken, user.email)
            }

            mainHandler.post { onExpired?.invoke() }
            return true
        }
        return false
    }

    fun getPaymentId(): String? {
        return prefs?.getString(KEY_PAYMENT_ID, null)
    }

    /**
     * Revokes VIP privileges immediately, resets local cache to Free plan, and updates Cloud Firestore.
     */
    fun revokeVip(callback: (() -> Unit)? = null) {
        Log.i(TAG, "Revoking VIP status. Reverting to Free Plan.")
        prefs?.edit()
            ?.putBoolean(KEY_IS_VIP, false)
            ?.putString(KEY_PLAN_TIER, PlanTier.FREE.id)
            ?.putString(KEY_EXPIRY_LABEL, "Free Plan")
            ?.putLong(KEY_EXPIRY_TIMESTAMP, -1L)
            ?.remove(KEY_PAYMENT_ID)
            ?.remove(KEY_PURCHASED_AT)
            ?.apply()

        val user = AuthManager.getCurrentUser()
        if (user != null) {
            syncExpiredToFirestore(user.uid, user.idToken, user.email)
        }
        mainHandler.post { callback?.invoke() }
    }

    /**
     * Enforces that only accounts with a genuine Razorpay paymentId (pay_*) hold VIP status.
     * Any unverified or legacy test status is revoked immediately.
     */
    fun enforceValidPaidStatus(callback: (() -> Unit)? = null) {
        val p = prefs ?: return
        val isVip = p.getBoolean(KEY_IS_VIP, false)
        val paymentId = p.getString(KEY_PAYMENT_ID, null)

        if (isVip && (paymentId.isNullOrEmpty() || !paymentId.startsWith("pay_"))) {
            Log.w(TAG, "Unverified VIP detected without authentic Razorpay payment ID. Revoking to Free tier.")
            revokeVip(callback)
            return
        }

        val user = AuthManager.getCurrentUser()
        if (user != null) {
            fetchUserPlanFromFirestore(user.uid, user.idToken) { firestoreVip, _, pid ->
                if (!firestoreVip || pid.isNullOrEmpty() || !pid.startsWith("pay_")) {
                    if (isVip) {
                        Log.i(TAG, "Firestore shows no verified paid VIP for user. Revoking.")
                        revokeVip(callback)
                    }
                }
            }
        }
    }

    fun fetchUserPlanFromFirestore(uid: String, idToken: String, callback: (Boolean, PlanTier, String?) -> Unit) {
        val url = "https://firestore.googleapis.com/v1/projects/${AuthManager.PROJECT_ID}/databases/(default)/documents/users/$uid"
        val reqBuilder = Request.Builder().url(url).get()
        if (idToken.isNotEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer $idToken")
        }
        httpClient.newCall(reqBuilder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                // Keep local state on network error
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: ""
                try {
                    if (response.isSuccessful) {
                        val json = JSONObject(body)
                        val fields = json.optJSONObject("fields")
                        val isVip = fields?.optJSONObject("isVip")?.optBoolean("booleanValue") ?: false
                        val planTypeStr = fields?.optJSONObject("planType")?.optString("stringValue") ?: PlanTier.FREE.id
                        val paymentId = fields?.optJSONObject("paymentId")?.optString("stringValue")
                        val tier = PlanTier.values().find { it.id == planTypeStr } ?: PlanTier.FREE
                        mainHandler.post { callback(isVip, tier, paymentId) }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error parsing Firestore user plan: ${t.message}")
                } finally {
                    response.close()
                }
            }
        })
    }

    /**
     * Purchases a plan tier and persists details (email, timestamps, 7d/30d/N/A, paymentId) to Cloud Firestore.
     */
    fun purchasePlan(tier: PlanTier, paymentId: String? = null, callback: (Boolean) -> Unit) {
        val user = AuthManager.getCurrentUser()
        if (user == null) {
            Log.e(TAG, "User must be logged in to purchase VIP")
            callback(false)
            return
        }

        val purchaseTime = System.currentTimeMillis()
        val expiryTime = if (tier == PlanTier.LIFETIME) {
            -1L
        } else {
            purchaseTime + (tier.durationDays.toLong() * 24L * 60L * 60L * 1000L)
        }

        val expiryLabel = if (tier == PlanTier.LIFETIME) {
            "N/A"
        } else {
            SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date(expiryTime))
        }

        val purchasedAtLabel = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(purchaseTime))

        // Save locally
        prefs?.edit()
            ?.putBoolean(KEY_IS_VIP, true)
            ?.putString(KEY_PLAN_TIER, tier.id)
            ?.putLong(KEY_EXPIRY_TIMESTAMP, expiryTime)
            ?.putString(KEY_EXPIRY_LABEL, expiryLabel)
            ?.putString(KEY_PURCHASED_AT, purchasedAtLabel)
            ?.putString(KEY_USER_EMAIL, user.email)
            ?.apply {
                if (paymentId != null) {
                    putString(KEY_PAYMENT_ID, paymentId)
                }
            }
            ?.apply()

        // Sync with Cloud Firestore
        syncPurchaseToFirestore(
            uid = user.uid,
            idToken = user.idToken,
            email = user.email,
            tier = tier,
            paymentId = paymentId,
            purchasedAt = purchasedAtLabel,
            expiresAt = expiryLabel,
            expiryTimestamp = expiryTime,
            callback = callback
        )
    }

    /**
     * For testing/demo: immediately sets expiry in the past to test auto-expiry revert to Free plan.
     */
    fun simulateExpiry(callback: () -> Unit) {
        prefs?.edit()
            ?.putLong(KEY_EXPIRY_TIMESTAMP, System.currentTimeMillis() - 5000L)
            ?.apply()
        checkAndEnforceExpiry {
            callback()
        }
        callback()
    }

    /**
     * For testing/demo: activates a tier locally and in Firestore.
     */
    fun activateTestTier(tier: PlanTier, callback: (Boolean) -> Unit) {
        purchasePlan(tier, null, callback)
    }

    private fun syncPurchaseToFirestore(
        uid: String,
        idToken: String,
        email: String,
        tier: PlanTier,
        paymentId: String?,
        purchasedAt: String,
        expiresAt: String,
        expiryTimestamp: Long,
        callback: (Boolean) -> Unit
    ) {
        try {
            var url = "https://firestore.googleapis.com/v1/projects/${AuthManager.PROJECT_ID}/databases/(default)/documents/users/$uid?updateMask.fieldPaths=isVip&updateMask.fieldPaths=planType&updateMask.fieldPaths=userEmail&updateMask.fieldPaths=purchasedAt&updateMask.fieldPaths=expiresAt&updateMask.fieldPaths=expiryTimestamp"
            if (!paymentId.isNullOrEmpty()) {
                url += "&updateMask.fieldPaths=paymentId"
            }
            val fields = JSONObject().apply {
                put("isVip", JSONObject().put("booleanValue", true))
                put("planType", JSONObject().put("stringValue", tier.id))
                put("userEmail", JSONObject().put("stringValue", email))
                put("purchasedAt", JSONObject().put("stringValue", purchasedAt))
                put("expiresAt", JSONObject().put("stringValue", expiresAt))
                put("expiryTimestamp", JSONObject().put("integerValue", expiryTimestamp.toString()))
                if (!paymentId.isNullOrEmpty()) {
                    put("paymentId", JSONObject().put("stringValue", paymentId))
                }
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
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.w(TAG, "Network failure syncing purchase to Firestore: ${e.message}")
                    mainHandler.post { callback(true) } // Optimistic success locally
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                    mainHandler.post { callback(true) }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing purchase to Firestore", e)
            mainHandler.post { callback(true) }
        }
    }

    private fun syncExpiredToFirestore(uid: String, idToken: String, email: String) {
        try {
            val url = "https://firestore.googleapis.com/v1/projects/${AuthManager.PROJECT_ID}/databases/(default)/documents/users/$uid?updateMask.fieldPaths=isVip&updateMask.fieldPaths=planType&updateMask.fieldPaths=expiresAt"
            val fields = JSONObject().apply {
                put("isVip", JSONObject().put("booleanValue", false))
                put("planType", JSONObject().put("stringValue", "FREE"))
                put("expiresAt", JSONObject().put("stringValue", "Expired"))
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
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.w(TAG, "Failed syncing expired state to Firestore: ${e.message}")
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Error syncing expired status to Firestore", e)
        }
    }
}
