package com.example.wolquicktile

import android.app.Application
import com.example.wolquicktile.data.database.AppDatabase
import com.example.wolquicktile.data.preferences.ProxySettingsRepository
import com.example.wolquicktile.repository.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WolQuickTileApp : Application() {
    val database by lazy { AppDatabase.getInstance(this) }

    val proxySettingsRepository by lazy {
        ProxySettingsRepository(this)
    }

    val repository by lazy {
        DeviceRepository(
            database.deviceDao(),
            database.groupDao(),
            database.tileBindingDao()
        )
    }

    val proxyNodeDao by lazy { database.proxyNodeDao() }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            val old = proxySettingsRepository.getSettings()
            if (old.isConfigured && proxyNodeDao.count() == 0) {
                val uri = runCatching { java.net.URI(old.serverUrl) }.getOrNull()
                val address = if (uri?.host != null) {
                    "${uri.scheme}://${uri.host}${uri.path.orEmpty().trimEnd('/')}"
                } else old.serverUrl.substringBeforeLast(':', old.serverUrl)
                val port = uri?.port?.takeIf { it > 0 } ?: 14250
                val nodeId = proxyNodeDao.insert(com.example.wolquicktile.data.entity.ProxyNodeEntity(name = "旧版代理", address = address, port = port, key = old.key))
                database.deviceDao().bindLegacyProxyDevices(nodeId)
            }
        }
    }
}
