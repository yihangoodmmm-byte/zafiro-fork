package com.niki914.zafiro.platform.task

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class ResourceKind { SCREEN, BROWSER_TAB, TERMINAL, RUNTIME, EXTENSION, DEVICE }

data class ResourceKey(val kind: ResourceKind, val id: String, val generation: Long)

sealed interface ResourceLeaseResult {
    data class Acquired(val leaseId: String, val key: ResourceKey, val expiresAtMillis: Long) : ResourceLeaseResult
    data class Rejected(val code: String, val message: String, val holderId: String? = null) : ResourceLeaseResult
}

sealed interface ResourceLeaseCheck {
    data object Valid : ResourceLeaseCheck
    data object Expired : ResourceLeaseCheck
    data object NotHeld : ResourceLeaseCheck
    data class StaleGeneration(val expected: Long, val actual: Long) : ResourceLeaseCheck
}

/** Key, reverse index and generation fence change under the same lock. */
class ResourceLeaseRegistry(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Slot(val kind: ResourceKind, val id: String)
    private data class Record(val leaseId: String, val key: ResourceKey, val owner: String, val expires: Long)
    private val lock = ReentrantLock()
    private val records = mutableMapOf<Slot, Record>()
    private val byLease = mutableMapOf<String, Slot>()
    private val generations = mutableMapOf<Slot, Long>()

    fun acquire(key: ResourceKey, ownerId: String, ttlMillis: Long, preempt: Boolean = false): ResourceLeaseResult = lock.withLock {
        require(key.id.isNotBlank() && key.generation >= 0 && ownerId.isNotBlank())
        val now = clock()
        require(ttlMillis > 0 && now <= Long.MAX_VALUE - ttlMillis)
        val slot = Slot(key.kind, key.id)
        val generation = generations[slot]
        if (generation != null && key.generation < generation) {
            return ResourceLeaseResult.Rejected("stale-target", "Target generation is stale.")
        }
        val previous = records[slot]
        if (previous != null && previous.key.generation == key.generation && previous.expires > now) {
            if (previous.owner == ownerId) {
                val renewed = previous.copy(expires = now + ttlMillis)
                records[slot] = renewed
                return ResourceLeaseResult.Acquired(renewed.leaseId, key, renewed.expires)
            }
            if (!preempt) return ResourceLeaseResult.Rejected("resource-busy", "Resource is held by another owner.", previous.owner)
        }
        previous?.let { byLease.remove(it.leaseId) }
        val next = Record(UUID.randomUUID().toString(), key, ownerId, now + ttlMillis)
        records[slot] = next
        generations[slot] = key.generation
        byLease[next.leaseId] = slot
        ResourceLeaseResult.Acquired(next.leaseId, key, next.expires)
    }

    fun release(leaseId: String): Boolean = lock.withLock { releaseLocked(leaseId) }

    fun releaseByOwner(ownerId: String): Int = lock.withLock {
        records.values.filter { it.owner == ownerId }.map { it.leaseId }.count { releaseLocked(it) }
    }

    fun validate(leaseId: String, key: ResourceKey): ResourceLeaseCheck = lock.withLock {
        val slot = byLease[leaseId] ?: return ResourceLeaseCheck.NotHeld
        if (slot != Slot(key.kind, key.id)) return ResourceLeaseCheck.NotHeld
        val record = records[slot] ?: return ResourceLeaseCheck.NotHeld
        if (record.expires <= clock()) return ResourceLeaseCheck.Expired
        if (record.key.generation != key.generation) return ResourceLeaseCheck.StaleGeneration(key.generation, record.key.generation)
        ResourceLeaseCheck.Valid
    }

    fun holder(key: ResourceKey): String? = lock.withLock {
        records[Slot(key.kind, key.id)]?.takeIf { it.expires > clock() && it.key.generation == key.generation }?.owner
    }

    fun sweepExpired(): Int = lock.withLock {
        val now = clock()
        records.values.filter { it.expires <= now }.map { it.leaseId }.count { releaseLocked(it) }
    }

    /** Only a trusted resource owner may retire an alias after all references have been invalidated. */
    fun retire(kind: ResourceKind, id: String) = lock.withLock {
        val slot = Slot(kind, id)
        records[slot]?.let { releaseLocked(it.leaseId) }
        generations.remove(slot)
        Unit
    }

    private fun releaseLocked(id: String): Boolean {
        val slot = byLease.remove(id) ?: return false
        return records.remove(slot) != null
    }
}
