package com.example.p942818.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 成为"默认短信App"必须声明的组件之一，用来接收彩信推送。空实现即可。 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 空实现，不做任何处理
    }
}
