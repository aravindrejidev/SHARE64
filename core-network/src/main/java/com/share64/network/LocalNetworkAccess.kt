package com.share64.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17 gates LAN access behind ACCESS_LOCAL_NETWORK, but only for apps whose targetSdk is 37+.
 * While the app targets 36 this is a no-op; once targetSdk is raised, request [PERMISSION] at runtime
 * (part of the Nearby devices group) before starting [Share64Node].
 */
object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    fun isRuntimePermissionRequired(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 37 && context.applicationInfo.targetSdkVersion >= 37

    fun isGranted(context: Context): Boolean =
        !isRuntimePermissionRequired(context) ||
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
