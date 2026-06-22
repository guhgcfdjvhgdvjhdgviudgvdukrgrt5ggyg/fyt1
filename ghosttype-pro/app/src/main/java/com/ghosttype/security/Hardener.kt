package com.ghosttype.security

import android.content.Context

object Hardener {

    fun isEnvironmentSafe(ctx: Context): Boolean = true

    fun brick(ctx: Context) {
        android.util.Log.w("GhostType", "brick() called — ignoring")
    }
}
