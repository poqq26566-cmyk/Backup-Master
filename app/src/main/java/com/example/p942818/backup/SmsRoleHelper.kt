package com.example.p942818.backup

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

/**
 * 处理"临时成为默认短信App"这件事——参考 SMS Import/Export (sms-ie) 项目的做法。
 * 一旦持有这个身份，写短信数据库就是系统完全允许的正常操作，不需要 Root/Shizuku。
 */
object SmsRoleHelper {

    /** 当前App是否已经是默认短信App */
    fun isDefaultSmsApp(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = context.getSystemService(RoleManager::class.java)
                roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
            } catch (e: Exception) {
                false
            }
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }
    }

    /** 这台设备上，"默认短信App"这个角色本身是否可用（极少数定制设备可能没有） */
    fun isRoleAvailable(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = context.getSystemService(RoleManager::class.java)
                roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true
            } catch (e: Exception) {
                false
            }
        } else {
            true
        }
    }

    /** 构造请求成为默认短信App的Intent，配合 ActivityResultLauncher 使用 */
    fun getRequestRoleIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
        }
    }
}
