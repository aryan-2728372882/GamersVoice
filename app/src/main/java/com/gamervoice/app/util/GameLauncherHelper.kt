package com.gamervoice.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log

data class InstalledGame(
    val packageName: String,
    val appName: String,
    val appIcon: Drawable,
    val isInstalled: Boolean = true
)

object GameLauncherHelper {

    private const val TAG = "GameLauncherHelper"

    // Recognized competitive gaming titles
    private val KNOWN_GAMES = listOf(
        "com.dts.freefireth" to "Free Fire",
        "com.dts.freefiremax" to "Free Fire MAX",
        "com.pubg.imobile" to "BGMI",
        "com.tencent.ig" to "PUBG Mobile",
        "com.activision.callofduty.shooter" to "Call of Duty: Mobile",
        "com.roblox.client" to "Roblox",
        "com.mojang.minecraftpe" to "Minecraft",
        "com.miHoYo.GenshinImpact" to "Genshin Impact",
        "com.mobile.legends" to "Mobile Legends",
        "com.supercell.clashofclans" to "Clash of Clans",
        "com.supercell.brawlstars" to "Brawl Stars",
        "com.supercell.clashroyale" to "Clash Royale",
        "com.kitkagames.fallbuddies" to "Stumble Guys",
        "com.krafton.battlegroundsmobile" to "BGMI",
        "com.ea.gp.apexmobile" to "Apex Legends"
    )

    fun detectInstalledGames(context: Context): List<InstalledGame> {
        val pm = context.packageManager
        val detected = mutableListOf<InstalledGame>()
        val seenPackages = mutableSetOf<String>()

        // 1. Check known high-profile game packages first
        for ((pkg, defaultName) in KNOWN_GAMES) {
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                val name = pm.getApplicationLabel(appInfo).toString().ifEmpty { defaultName }
                val icon = pm.getApplicationIcon(appInfo)
                detected.add(InstalledGame(pkg, name, icon, isInstalled = true))
                seenPackages.add(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                // Game not installed
            }
        }

        // 2. Query other launcher activities that are marked as CATEGORY_GAME
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolvedList = pm.queryIntentActivities(mainIntent, 0)
            for (resolveInfo in resolvedList) {
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == context.packageName || seenPackages.contains(pkg)) continue

                val appInfo = resolveInfo.activityInfo.applicationInfo
                val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appInfo.category == ApplicationInfo.CATEGORY_GAME
                } else {
                    @Suppress("DEPRECATION")
                    (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                }

                if (isGame) {
                    val name = resolveInfo.loadLabel(pm).toString()
                    val icon = resolveInfo.loadIcon(pm)
                    detected.add(InstalledGame(pkg, name, icon, isInstalled = true))
                    seenPackages.add(pkg)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying launcher apps for games", e)
        }

        return detected
    }

    fun launchGame(context: Context, packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                // Fallback to Google Play Store to install
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching game: $packageName", e)
            false
        }
    }
}
