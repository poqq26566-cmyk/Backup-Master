package com.example.p942818.backup

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * 成为"默认短信App"必须声明的组件之一，理论上系统会在用户点击"发短信"链接时打开这个页面。
 * 我们这个App的定位是备份/恢复工具，不提供真正的发短信界面，所以这里直接提示一下就关闭，
 * 不影响备份/恢复短信数据这个核心功能。
 */
class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "备份大师不提供发送短信功能，请用系统短信App发送", Toast.LENGTH_LONG).show()
        finish()
    }
}
