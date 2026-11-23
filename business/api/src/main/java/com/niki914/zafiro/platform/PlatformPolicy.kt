package com.niki914.zafiro.platform

import com.niki914.zafiro.platform.conflict.ApprovalRace
import com.niki914.zafiro.platform.conflict.ScreenLeaseRegistry
import com.niki914.zafiro.platform.journal.OperationJournal
import com.niki914.zafiro.platform.task.ResourceLeaseRegistry

/**
 * 冲突判定的组合入口。
 *
 * 四个注册表都是进程内单例的候选实现；接入业务时由组合根创建一份并注入，
 * 不要在每个调用点各 new 一份，否则「谁持有租约」会分裂成多份真相。
 */
class PlatformPolicy(
    val screenLeases: ScreenLeaseRegistry = ScreenLeaseRegistry(),
    val resourceLeases: ResourceLeaseRegistry = ResourceLeaseRegistry(),
    val approvals: ApprovalRace = ApprovalRace(),
    val journal: OperationJournal = OperationJournal(),
) {
    fun sweepExpired(): Int =
        screenLeases.sweepExpired() + resourceLeases.sweepExpired() + approvals.sweepExpired()
}
