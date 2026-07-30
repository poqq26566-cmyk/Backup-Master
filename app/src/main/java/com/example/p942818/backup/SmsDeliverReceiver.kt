package com.example.p942818.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 成为"默认短信App"必须声明的组件之一，用来接收系统投递的短信。
 * 我们的App只是想借用默认短信身份来写入恢复数据，不需要真的处理日常收信，
 * 所以这里留空即可（真正的收信仍然会正常显示在系统短信数据库里）。
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 空实现，不做任何处理
    }
}
