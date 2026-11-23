package com.niki914.zafiro.platform.conflict

import com.niki914.zafiro.platform.task.ResourceKey
import com.niki914.zafiro.platform.task.ResourceKind
import com.niki914.zafiro.platform.task.ResourceLeaseCheck
import com.niki914.zafiro.platform.task.ResourceLeaseRegistry
import com.niki914.zafiro.platform.task.ResourceLeaseResult
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ScreenTarget(val alias: String, val generation: Long)
enum class LeaseOwnerKind { TASK, HUMAN }
data class LeaseOwner(val kind: LeaseOwnerKind, val id: String)
sealed interface ScreenLeaseResult {
    data class Acquired(val leaseId: String, val target: ScreenTarget, val expiresAtMillis: Long, val preempted: LeaseOwner? = null) : ScreenLeaseResult
    data class Rejected(val code: String, val message: String, val holder: LeaseOwner? = null) : ScreenLeaseResult
}
sealed interface LeaseValidation {
    data object Valid : LeaseValidation
    data object Expired : LeaseValidation
    data object NotHeld : LeaseValidation
    data class StaleTarget(val expectedGeneration: Long, val actualGeneration: Long) : LeaseValidation
}

class ScreenLeaseRegistry(private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = ReentrantLock()
    private val resources = ResourceLeaseRegistry(clock)
    private val owners = mutableMapOf<String, LeaseOwner>()

    fun acquire(target: ScreenTarget, owner: LeaseOwner, ttlMillis: Long): ScreenLeaseResult = lock.withLock {
        val key = ResourceKey(ResourceKind.SCREEN, target.alias, target.generation)
        val previous = resources.holder(key)?.let(owners::get)
        val identity = owner.kind.name + ":" + owner.id.length + ":" + owner.id
        val result = resources.acquire(key, identity, ttlMillis, preempt = owner.kind == LeaseOwnerKind.HUMAN && previous?.kind == LeaseOwnerKind.TASK)
        when (result) {
            is ResourceLeaseResult.Acquired -> {
                owners[identity] = owner
                currentTargets[target.alias] = target.generation
                ScreenLeaseResult.Acquired(result.leaseId, target, result.expiresAtMillis, previous?.takeIf { it != owner })
            }
            is ResourceLeaseResult.Rejected -> ScreenLeaseResult.Rejected(
                if (result.code == "resource-busy") "screen-busy" else result.code,
                result.message, result.holderId?.let(owners::get),
            )
        }
    }

    fun release(leaseId: String): Boolean = lock.withLock {
        val released = resources.release(leaseId)
        pruneOwners()
        released
    }

    fun validate(leaseId: String, target: ScreenTarget): LeaseValidation = when (
        val result = resources.validate(leaseId, ResourceKey(ResourceKind.SCREEN, target.alias, target.generation))
    ) {
        ResourceLeaseCheck.Valid -> LeaseValidation.Valid
        ResourceLeaseCheck.Expired -> LeaseValidation.Expired
        ResourceLeaseCheck.NotHeld -> LeaseValidation.NotHeld
        is ResourceLeaseCheck.StaleGeneration -> LeaseValidation.StaleTarget(result.expected, result.actual)
    }

    fun holder(alias: String): LeaseOwner? = lock.withLock {
        currentTargets[alias]?.let { generation ->
            resources.holder(ResourceKey(ResourceKind.SCREEN, alias, generation))?.let(owners::get)
        }
    }

    private val currentTargets = mutableMapOf<String, Long>()

    fun sweepExpired(): Int = lock.withLock {
        val count = resources.sweepExpired()
        pruneOwners()
        count
    }

    private fun pruneOwners() {
        owners.entries.removeAll { (_, owner) ->
            val identity = owner.kind.name + ":" + owner.id.length + ":" + owner.id
            currentTargets.none { (alias, generation) -> resources.holder(ResourceKey(ResourceKind.SCREEN, alias, generation)) == identity }
        }
    }

    fun retire(alias: String) = lock.withLock {
        resources.retire(ResourceKind.SCREEN, alias)
        currentTargets.remove(alias)
        pruneOwners()
    }
}
