package com.example.wolquicktile.service

import android.content.ComponentName
import android.content.Context

object TileRegistry {
    const val MAX_TILES = 50

    fun componentName(context: Context, index: Int): ComponentName {
        require(index in 1..MAX_TILES) { "Unsupported tile index: $index" }
        val className = "com.example.wolquicktile.service.WolTileService${index.toString().padStart(2, '0')}"
        return ComponentName(context.packageName, className)
    }
}
