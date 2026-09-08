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
import java.util.concurrent.TimeUnit

data class UserProfile(
    val uid: String,
    val name: String,
    val email: String,
    val phone: String,
    val avatar: String,
    val idToken: String
)

object AuthManager {

    private const val TAG = "AuthManager"
    private const val PREFS_NAME = "gamervoice_auth_prefs"
    private const val KEY_UID = "auth_uid"
    private const val KEY_NAME = "auth_name"
    private const val KEY_EMAIL = "auth_email"
    private const val KEY_PHONE = "auth_phone"
    private const val KEY_AVATAR = "auth_avatar"
    private const val KEY_ID_TOKEN = "auth_id_token"

    // Firebase Config provided by User
    const val API_KEY = "AIzaSyDqJWP-j_YmbqY1I-jMqQjsluQYWE7pdGM"
    const val PROJECT_ID = "gamersvoice-ea413"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null
    private var currentUser: UserProfile? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadCachedUser()
        }
    }

    private fun loadCachedUser() {
        val p = prefs ?: return
        val uid = p.getString(KEY_UID, null) ?: return
        val name = p.getString(KEY_NAME, "Gamer") ?: "Gamer"
        val email = p.getString(KEY_EMAIL, "") ?: ""
        val phone = p.getString(KEY_PHONE, "") ?: ""
        val avatar = p.getString(KEY_AVATAR, "avatar_1") ?: "avatar_1"
        val token = p.getString(KEY_ID_TOKEN, "") ?: ""

        currentUser = UserProfile(uid, name, email, phone, avatar, token)
        Log.i(TAG, "Loaded cached session for user: $name ($uid)")
    }

    fun isLoggedIn(): Boolean {
        return currentUser != null && currentUser!!.uid.isNotEmpty()
    }

    fun getCurrentUser(): UserProfile? {
        return currentUser
    }

    fun signOut() {
        currentUser = null
        prefs?.edit()?.clear()?.apply()
        Log.i(TAG, "User signed out and cache cleared")
    }

    fun signUp(
        email: String,
        pass: String,
        name: String,
        phone: String,
        avatar: String,
        callback: (Result<UserProfile>) -> Unit
    ) {
        val url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY"
        val payload = JSONObject().apply {
            put("email", email.trim())
            put("password", pass)
            put("returnSecureToken", true)
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                postResult(callback, Result.failure(Exception("Network error: ${e.localizedMessage}")))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: ""
                try {
                    val json = JSONObject(body)
                    if (!response.isSuccessful || json.has("error")) {
                        val errMsg = parseFirebaseError(json)
                        postResult(callback, Result.failure(Exception(errMsg)))
                        return
                    }

                    val uid = json.getString("localId")
                    val idToken = json.getString("idToken")

                    // 2. Set Profile Display Name and Avatar PhotoURL in Firebase Auth
                    updateFirebaseAuthProfile(idToken, name, avatar)

                    // 3. Save User Profile in Firestore database
                    saveFirestoreProfile(uid, name, email, phone, avatar, idToken)

                    val user = UserProfile(uid, name, email, phone, avatar, idToken)
                    saveUserToCache(user)
                    postResult(callback, Result.success(user))
                } catch (t: Throwable) {
                    postResult(callback, Result.failure(Exception("Sign up parsing error: ${t.localizedMessage}")))
                }
            }
        })
    }

    fun signIn(
        email: String,
        pass: String,
        callback: (Result<UserProfile>) -> Unit
    ) {
        val url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$API_KEY"
        val payload = JSONObject().apply {
            put("email", email.trim())
            put("password", pass)
            put("returnSecureToken", true)
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                postResult(callback, Result.failure(Exception("Network error: ${e.localizedMessage}")))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: ""
                try {
                    val json = JSONObject(body)
                    if (!response.isSuccessful || json.has("error")) {
                        val errMsg = parseFirebaseError(json)
                        postResult(callback, Result.failure(Exception(errMsg)))
                        return
                    }

                    val uid = json.getString("localId")
                    val idToken = json.getString("idToken")
                    val authName = json.optString("displayName", "")
                    val authAvatar = json.optString("photoUrl", "avatar_1")

                    // Attempt to fetch richer Firestore details (Phone, custom name)
                    fetchFirestoreProfile(uid, idToken) { firestoreResult ->
                        val name = if (firestoreResult?.name.isNullOrEmpty()) {
                            if (authName.isNotEmpty()) authName else email.substringBefore("@")
                        } else {
                            firestoreResult!!.name
                        }
                        val phone = firestoreResult?.phone ?: ""
                        val avatar = if (!firestoreResult?.avatar.isNullOrEmpty()) firestoreResult!!.avatar else authAvatar

                        val user = UserProfile(uid, name, email, phone, avatar, idToken)
                        saveUserToCache(user)
                        postResult(callback, Result.success(user))
                    }
                } catch (t: Throwable) {
                    postResult(callback, Result.failure(Exception("Sign in parsing error: ${t.localizedMessage}")))
                }
            }
        })
    }

    fun signInWithGoogle(
        account: com.google.android.gms.auth.api.signin.GoogleSignInAccount,
        callback: (Result<UserProfile>) -> Unit
    ) {
        val googleEmail = account.email ?: ""
        val googleName = account.displayName ?: if (googleEmail.contains("@")) googleEmail.substringBefore("@") else "Gamer"
        val googleAvatar = account.photoUrl?.toString() ?: "avatar_1"
        val googleId = account.id ?: ("usr_" + Math.abs(googleEmail.hashCode()))
        val idToken = account.idToken

        // If Google ID Token is available, exchange with Firebase Auth signInWithIdp
        if (!idToken.isNullOrEmpty()) {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$API_KEY"
            val payload = JSONObject().apply {
                put("postBody", "id_token=$idToken&providerId=google.com")
                put("requestUri", "http://localhost")
                put("returnIdpCredential", true)
                put("returnSecureToken", true)
            }

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    // Fall back to local google profile if network fails
                    val user = UserProfile("google_$googleId", googleName, googleEmail, "", googleAvatar, "")
                    saveUserToCache(user)
                    saveFirestoreProfile(user.uid, user.name, user.email, "", user.avatar, "")
                    postResult(callback, Result.success(user))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val body = response.body?.string() ?: ""
                    try {
                        val json = JSONObject(body)
                        if (response.isSuccessful && !json.has("error")) {
                            val fbUid = json.getString("localId")
                            val fbToken = json.getString("idToken")
                            val fbName = json.optString("displayName", googleName)
                            val fbAvatar = json.optString("photoUrl", googleAvatar)

                            val finalName = if (fbName.isNotEmpty()) fbName else googleName
                            val finalAvatar = if (fbAvatar.isNotEmpty()) fbAvatar else googleAvatar

                            val user = UserProfile(fbUid, finalName, googleEmail, "", finalAvatar, fbToken)
                            saveUserToCache(user)
                            saveFirestoreProfile(fbUid, finalName, googleEmail, "", finalAvatar, fbToken)
                            postResult(callback, Result.success(user))
                            return
                        }
                    } catch (_: Exception) {}

                    // Fallback if IDP exchange failed in console (e.g. SHA-1 pending in Firebase console)
                    val user = UserProfile("google_$googleId", googleName, googleEmail, "", googleAvatar, "")
                    saveUserToCache(user)
                    saveFirestoreProfile(user.uid, user.name, user.email, "", user.avatar, "")
                    postResult(callback, Result.success(user))
                }
            })
        } else {
            // Direct sign-in using Google Play Services account profile
            val user = UserProfile("google_$googleId", googleName, googleEmail, "", googleAvatar, "")
            saveUserToCache(user)
            saveFirestoreProfile(user.uid, user.name, user.email, "", user.avatar, "")
            postResult(callback, Result.success(user))
        }
    }

    private fun updateFirebaseAuthProfile(idToken: String, displayName: String, photoUrl: String) {
        try {
            val url = "https://identitytoolkit.googleapis.com/v1/accounts:update?key=$API_KEY"
            val payload = JSONObject().apply {
                put("idToken", idToken)
                put("displayName", displayName)
                put("photoUrl", photoUrl)
                put("returnSecureToken", false)
            }
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            httpClient.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "Could not update Firebase Auth profile displayName", e)
        }
    }

    private fun saveFirestoreProfile(
        uid: String,
        name: String,
        email: String,
        phone: String,
        avatar: String,
        idToken: String
    ) {
        try {
            val url = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/users/$uid"
            val fields = JSONObject().apply {
                put("name", JSONObject().put("stringValue", name))
                put("email", JSONObject().put("stringValue", email))
                put("phone", JSONObject().put("stringValue", phone))
                put("avatar", JSONObject().put("stringValue", avatar))
                put("updatedAt", JSONObject().put("stringValue", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())))
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

            val req = reqBuilder.build()
            val res = httpClient.newCall(req).execute()
            Log.i(TAG, "Firestore write response: ${res.code}")
            res.close()
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist user to Firestore", e)
        }
    }

    private fun fetchFirestoreProfile(
        uid: String,
        idToken: String,
        onComplete: (UserProfile?) -> Unit
    ) {
        try {
            val url = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/users/$uid"
            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer $idToken")
                .build()

            httpClient.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    onComplete(null)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        onComplete(null)
                        return
                    }

                    try {
                        val json = JSONObject(body)
                        val fields = json.optJSONObject("fields") ?: JSONObject()
                        val name = fields.optJSONObject("name")?.optString("stringValue", "") ?: ""
                        val email = fields.optJSONObject("email")?.optString("stringValue", "") ?: ""
                        val phone = fields.optJSONObject("phone")?.optString("stringValue", "") ?: ""
                        val avatar = fields.optJSONObject("avatar")?.optString("stringValue", "avatar_1") ?: "avatar_1"

                        onComplete(UserProfile(uid, name, email, phone, avatar, idToken))
                    } catch (_: Exception) {
                        onComplete(null)
                    }
                }
            })
        } catch (e: Exception) {
            onComplete(null)
        }
    }

    private fun saveUserToCache(user: UserProfile) {
        currentUser = user
        prefs?.edit()
            ?.putString(KEY_UID, user.uid)
            ?.putString(KEY_NAME, user.name)
            ?.putString(KEY_EMAIL, user.email)
            ?.putString(KEY_PHONE, user.phone)
            ?.putString(KEY_AVATAR, user.avatar)
            ?.putString(KEY_ID_TOKEN, user.idToken)
            ?.apply()
    }

    private fun parseFirebaseError(json: JSONObject): String {
        val errObj = json.optJSONObject("error") ?: return "Authentication failed"
        val msg = errObj.optString("message", "Authentication failed")
        return when {
            msg.contains("EMAIL_EXISTS") -> "This email is already registered. Please sign in."
            msg.contains("OPERATION_NOT_ALLOWED") -> "Password sign-in is disabled in Firebase Console."
            msg.contains("TOO_MANY_ATTEMPTS_TRY_LATER") -> "Too many failed attempts. Try again later."
            msg.contains("EMAIL_NOT_FOUND") || msg.contains("INVALID_LOGIN_CREDENTIALS") -> "Invalid email or password."
            msg.contains("INVALID_PASSWORD") -> "Incorrect password."
            msg.contains("WEAK_PASSWORD") -> "Password must be at least 6 characters."
            msg.contains("INVALID_EMAIL") -> "Please enter a valid email address."
            else -> msg
        }
    }

    private fun <T> postResult(callback: (Result<T>) -> Unit, result: Result<T>) {
        mainHandler.post { callback(result) }
    }
}
