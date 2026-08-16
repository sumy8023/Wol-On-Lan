package com.example.wolquicktile.repository

import com.example.wolquicktile.data.dao.DeviceDao
import com.example.wolquicktile.data.dao.GroupDao
import com.example.wolquicktile.data.dao.TileBindingDao
import com.example.wolquicktile.data.entity.DeviceEntity
import com.example.wolquicktile.data.entity.GroupEntity
import com.example.wolquicktile.data.entity.GroupWithDevices
import com.example.wolquicktile.data.entity.TileBindingEntity
import com.example.wolquicktile.service.TileRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DeviceRepository(
    private val deviceDao: DeviceDao,
    private val groupDao: GroupDao,
    private val tileBindingDao: TileBindingDao
) {
    private val groupGuard = Mutex()

    companion object {
        const val DEFAULT_GROUP_NAME = "默认分组"
    }

    fun observeDevices(): Flow<List<DeviceEntity>> = deviceDao.observeDevices()

    fun observeGroups(): Flow<List<GroupEntity>> = groupDao.observeGroups()

    fun observeGroupsWithDevices(): Flow<List<GroupWithDevices>> =
        groupDao.observeGroupsWithDevices()

    fun observeTileBindings(): Flow<List<TileBindingEntity>> =
        tileBindingDao.observeBindings()

    suspend fun getDevice(id: Long): DeviceEntity? = deviceDao.getDevice(id)

    suspend fun saveDevice(device: DeviceEntity): Long {
        ensureDefaultGroup()
        return if (device.id == 0L) {
            val groupId = device.groupId ?: getFallbackGroupId()
            deviceDao.insert(device.copy(groupId = groupId))
        } else {
            val groupId = device.groupId ?: getFallbackGroupId()
            deviceDao.update(device.copy(groupId = groupId))
            device.id
        }
    }

    suspend fun deleteDevice(device: DeviceEntity) {
        tileBindingDao.deleteForDevice(device.id)
        deviceDao.delete(device)
    }

    suspend fun getTileIndexForDevice(deviceId: Long): Int? {
        return tileBindingDao.getBindingForDevice(deviceId)?.tileIndex
    }

    suspend fun saveGroup(group: GroupEntity): Long {
        ensureDefaultGroup()
        return if (group.id == 0L) {
            val nextOrder = groupDao.getMaxSortOrder() + 1
            groupDao.insert(group.copy(sortOrder = nextOrder))
        } else {
            groupDao.update(group)
            group.id
        }
    }

    suspend fun deleteGroup(group: GroupEntity): Boolean {
        val groups = groupDao.getGroups()
        if (groups.size <= 1) return false
        val targetGroup = groups.firstOrNull { it.id != group.id } ?: return false
        deviceDao.moveGroupDevices(group.id, targetGroup.id)
        groupDao.delete(group)
        refreshGroupOrder()
        return true
    }

    suspend fun updateGroupOrder(groups: List<GroupEntity>) {
        groups.forEachIndexed { index, group ->
            groupDao.updateSortOrder(group.id, index)
        }
    }

    suspend fun bindDeviceToFirstFreeTile(deviceId: Long): Int? {
        tileBindingDao.getBindingForDevice(deviceId)?.let { return it.tileIndex }
        val used = tileBindingDao.getBindings().map { it.tileIndex }.toSet()
        val freeIndex = (1..TileRegistry.MAX_TILES).firstOrNull { it !in used } ?: return null
        tileBindingDao.upsert(TileBindingEntity(freeIndex, deviceId))
        deviceDao.setTileEnabled(deviceId, true)
        return freeIndex
    }

    suspend fun getTileDevice(tileIndex: Int): DeviceEntity? {
        val binding = tileBindingDao.getBinding(tileIndex) ?: return null
        return deviceDao.getDevice(binding.deviceId)
    }

    suspend fun ensureDefaultGroup() {
        groupGuard.withLock {
            val groups = groupDao.getGroups()
            val fallbackGroupId = if (groups.isEmpty()) {
                groupDao.insert(GroupEntity(name = DEFAULT_GROUP_NAME, sortOrder = 0))
            } else {
                groups.first().id
            }
            deviceDao.assignUngroupedDevices(fallbackGroupId)
        }
    }

    private suspend fun getFallbackGroupId(): Long {
        ensureDefaultGroup()
        return groupDao.getGroups().first().id
    }

    private suspend fun refreshGroupOrder() {
        groupDao.getGroups().forEachIndexed { index, group ->
            groupDao.updateSortOrder(group.id, index)
        }
    }
}
