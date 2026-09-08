package com.gamervoice.app.model

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gamervoice.app.auth.AuthManager
import com.gamervoice.app.auth.PlanManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SavedRoom(
    val roomCode: String,
    val roomName: String,
    val createdAt: String,
    val ownerUid: String,
    val pin: String = ""
)

object RoomPersistenceManager {

    private const val TAG = "RoomPersistence"
    private const val PREFS_NAME = "gamervoice_saved_rooms"
    private const val KEY_CACHED_ROOMS = "cached_rooms_json"

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
        }
    }

    /**
     * Returns locally cached saved rooms immediately.
     */
    fun getCachedRooms(): List<SavedRoom> {
        val jsonStr = prefs?.getString(KEY_CACHED_ROOMS, null) ?: return emptyList()
        val list = mutableListOf<SavedRoom>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SavedRoom(
                        roomCode = obj.optString("roomCode"),
                        roomName = obj.optString("roomName", "Squad Room"),
                        createdAt = obj.optString("createdAt", ""),
                        ownerUid = obj.optString("ownerUid", ""),
                        pin = obj.optString("pin", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing cached rooms", e)
        }
        return list
    }

    private fun updateLocalCache(rooms: List<SavedRoom>) {
        try {
            val arr = JSONArray()
            for (r in rooms) {
                val obj = JSONObject().apply {
                    put("roomCode", r.roomCode)
                    put("roomName", r.roomName)
                    put("createdAt", r.createdAt)
                    put("ownerUid", r.ownerUid)
                    put("pin", r.pin)
                }
                arr.put(obj)
            }
            prefs?.edit()?.putString(KEY_CACHED_ROOMS, arr.toString())?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating local cache", e)
        }
    }

    /**
     * Fetches saved rooms from Firestore and updates local cache.
     */
    fun fetchSavedRooms(callback: (List<SavedRoom>) -> Unit) {
        val user = AuthManager.getCurrentUser()
        if (user == null) {
            callback(getCachedRooms())
            return
        }

        val url = "https://firestore.googleapis.com/v1/projects/${AuthManager.PROJECT_ID}/databases/(default)/documents/users/${user.uid}/saved_rooms"
        val reqBuilder = Request.Builder().url(url).get()
        if (user.idToken.isNotEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer ${user.idToken}")
        }

        httpClient.newCall(reqBuilder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.w(TAG, "Network error fetching rooms: ${e.message}")
                mainHandler.post { callback(getCachedRooms()) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: ""
                response.close()
                val list = mutableListOf<SavedRoom>()
                try {
                    val root = JSONObject(body)
                    val docs = root.optJSONArray("documents")
                    if (docs != null) {
                        for (i in 0 until docs.length()) {
                            val doc = docs.getJSONObject(i)
                            val fields = doc.optJSONObject("fields") ?: continue
                            val code = fields.optJSONObject("roomCode")?.optString("stringValue") ?: ""
                            val name = fields.optJSONObject("roomName")?.optString("stringValue") ?: "Squad Room"
                            val created = fields.optJSONObject("createdAt")?.optString("stringValue") ?: ""
                            val owner = fields.optJSONObject("ownerUid")?.optString("stringValue") ?: user.uid
                            val pin = fields.optJSONObject("pin")?.optString("stringValue") ?: ""

                            if (code.isNotEmpty()) {
                                list.add(SavedRoom(code, name, created, owner, pin))
                            }
                        }
                    }
                    updateLocalCache(list)
                    mainHandler.post { callback(list) }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing Firestore rooms", e)
                    mainHandler.post { callback(getCachedRooms()) }
                }
            }
        })
    }

    sealed class SaveResult {
        data class Success(val savedRoom: SavedRoom) : SaveResult()
        object LimitReached : SaveResult()
        data class Error(val message: String) : SaveResult()
    }

    /**
     * Saves a room in Firestore, enforcing Free plan limits (max 2 rooms).
     * VIP members can provide custom roomName and an optional private squad PIN.
     */
    fun saveRoom(roomCode: String, customName: String? = null, pin: String? = null, callback: (SaveResult) -> Unit) {
        val user = AuthManager.getCurrentUser()
        if (user == null) {
            callback(SaveResult.Error("User must be logged in to save rooms"))
            return
        }

        val cached = getCachedRooms().toMutableList()
        val existing = cached.find { it.roomCode == roomCode }
        if (existing != null) {
            callback(SaveResult.Success(existing))
            return
        }

        val limit = PlanManager.getRoomLimit()
        if (cached.size >= limit) {
            callback(SaveResult.LimitReached)
            return
        }

        val roomName = customName ?: "Squad Room #${cached.size + 1}"
        val roomPin = pin ?: ""
        val nowFormatted = SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date())
        val newRoom = SavedRoom(roomCode, roomName, nowFormatted, user.uid, roomPin)

        cached.add(newRoom)
        updateLocalCache(cached)

        val url = "https://firestore.googleapis.com/v1/projects/${AuthManager.PROJECT_ID}/databases/(default)/documents/users/${user.uid}/saved_rooms/$roomCode"
        val fields = JSONObject().apply {
            put("roomCode", JSONObject().put("stringValue", roomCode))
            put("roomName", JSONObject().put("stringValue", roomName))
            put("createdAt", JSONObject().put("stringValue", nowFormatted))
            put("ownerUid", JSONObject().put("stringValue", user.uid))
            put("pin", JSONObject().put("stringValue", roomPin))
        }
        val body = JSONObject().apply {
            put("fields", fields)
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .patch(body.toString().toRequestBody(JSON_MEDIA))

        if (user.idToken.isNotEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer ${user.idToken}")
        }

        httpClient.newCall(reqBuilder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.w(TAG, "Network warning saving room to Firestore: ${e.message}")
                mainHandler.post { callback(SaveResult.Success(newRoom)) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                mainHandler.post { callback(SaveResult.Success(newRoom)) }
            }
        })
    }

    /**
     * Deletes a saved room from Firestore and local cache.
     */
    fun deleteRoom(roomCode: String, callback: (Boolean) -> Unit) {
        val user = AuthManager.getCurrentUser()
        val cached = getCachedRooms().filter { it.roomCode != roomCode }
        updateLocalCache(cached)

        if (user == null) {
            callback(true)
            return
        }

        val url = "https://firestore.googleapis.com/v1/projects/${AuthManager.PROJECT_ID}/databases/(default)/documents/users/${user.uid}/saved_rooms/$roomCode"
        val reqBuilder = Request.Builder().url(url).delete()
        if (user.idToken.isNotEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer ${user.idToken}")
        }

        httpClient.newCall(reqBuilder.build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                mainHandler.post { callback(true) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                mainHandler.post { callback(true) }
            }
        })
    }
}
