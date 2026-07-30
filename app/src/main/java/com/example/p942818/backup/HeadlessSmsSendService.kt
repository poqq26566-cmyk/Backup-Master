package com.example.p942818.backup

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** 成为"默认短信App"必须声明的组件之一，用于处理"通过消息回复"这种系统级请求。空实现即可。 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
