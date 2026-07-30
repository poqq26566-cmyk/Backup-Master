package com.example.p942818

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.p942818.backup.BackupMainScreen
import com.example.p942818.backup.ShizukuHelper
import com.example.p942818.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化时先检测一次当前权限状态（Root 或 Shizuku）
        ShizukuHelper.detectPrivilege()

        // 监听 Shizuku 授权结果，用户在 Shizuku 里点允许/拒绝后会回调到这里
        ShizukuHelper.addPermissionResultListener { }

        // 监听 Shizuku 服务绑定，服务连接上时自动重新检测权限
        ShizukuHelper.addBinderReceivedListener { }

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BackupMainScreen()
                }
            }
        }
    }
}
