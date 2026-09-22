package com.repovoyage.sign.camera

/**
 * 重连退避策略（API.md §1.2 初始值）：
 * 1、2、4、8、16 秒，最多 5 次；成功出图后清零。
 * P2 实现（测试见 p2/ReconnectPolicyTest，先红后绿）。
 */
class ReconnectPolicy {

    /** 每次断线/重试失败时调用；返回下次重试延迟，超过 5 次返回 null = 放弃 */
    fun onDisconnected(): Long? = TODO("P2：退避序列 1/2/4/8/16s × 5，之后放弃")

    /** 成功出图后调用，计数清零 */
    fun onStreamRecovered() {
        TODO("P2：成功出图后清零")
    }
}
