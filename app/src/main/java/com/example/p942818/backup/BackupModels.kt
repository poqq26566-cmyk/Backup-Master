package com.example.p942818.backup

import android.graphics.Bitmap
import android.net.wifi.WifiConfiguration

/**
 * 备份类别枚举
 */
enum class BackupType(
    val label: String,
    val description: String,
    val icon: String  // emoji 图标
) {
    APK("应用APK备份", "导出已安装应用的安装包(.apk)文件", "\uD83D\uDCE6"),
    SMS("短信备份", "备份手机短信记录为JSON格式", "\uD83D\uDCAC"),
    CALL_LOG("通话记录备份", "备份通话记录为JSON格式", "\uD83D\uDCDE"),
    WALLPAPER("壁纸备份", "保存当前桌面壁纸图片", "\uD83D\uDDBC\uFE0F"),
    WIFI("WiFi网络备份", "导出已保存的WiFi网络配置", "\uD83D\uDCF6"),
    DESKTOP_LAYOUT("桌面布局备份", "备份桌面图标位置与布局", "\uD83D\uDCD1"),
    CONTACTS("联系人备份", "备份联系人姓名、电话与邮箱为JSON格式", "\uD83D\uDC64")
}

/**
 * 单个备份项目的数据状态
 */
data class BackupItem(
    val type: BackupType,
    var status: BackupStatus = BackupStatus.IDLE,
    var lastBackupTime: Long? = null,
    var itemCount: Int = 0,       // 短信/通话/应用数量
    var fileSize: Long = 0,       // 最新备份文件大小
    var filePath: String? = null,  // 最新备份文件路径
    var errorMessage: String? = null
)

enum class BackupStatus {
    IDLE,          // 未备份
    RUNNING,       // 备份中
    SUCCESS,       // 备份成功
    ERROR          // 备份失败
}

/**
 * 短信数据模型
 */
data class SmsRecord(
    val id: Long,
    val address: String,         // 号码
    val person: String?,         // 联系人
    val date: Long,              // 时间戳
    val dateString: String,      // 格式化时间
    val body: String,            // 内容
    val type: Int,               // 1=收件箱, 2=已发送, 3=草稿, 4=发件箱, 5=失败, 6=队列
    val typeString: String,      // 类型描述
    val read: Boolean,           // 是否已读
    val threadId: Long?          // 会话ID
)

/**
 * 通话记录数据模型
 */
data class CallLogRecord(
    val id: Long,
    val number: String,          // 号码
    val name: String?,           // 联系人姓名
    val date: Long,              // 时间戳
    val dateString: String,      // 格式化时间
    val duration: Long,          // 通话时长(秒)
    val durationString: String,  // 格式化时长
    val type: Int,               // 1=来电, 2=拨出, 3=未接, 4=语音邮箱, 5=拒接, 6=拦截
    val typeString: String,      // 类型描述
    val countryIso: String?,     // 国家代码
    val geocodedLocation: String? // 地理位置
)

/**
 * 联系人电话号码
 */
data class ContactPhone(
    val number: String,
    val type: Int  // ContactsContract.CommonDataKinds.Phone.TYPE_*
)

/**
 * 联系人数据模型
 */
data class ContactRecord(
    val id: Long,
    val displayName: String,
    val phones: List<ContactPhone>,
    val emails: List<String>
)

/**
 * 已安装应用信息
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val sourceDir: String,       // APK 文件路径
    val isSystemApp: Boolean,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val iconBase64: String? = null // 图标base64（可选的）
)

/**
 * WiFi 网络信息
 */
data class WifiNetwork(
    val ssid: String,            // 网络名称
    val bssid: String?,          // MAC地址
    val securityType: String,    // 安全类型: WPA/WPA2/WPA3/WEP/无
    val hidden: Boolean,         // 是否隐藏
    val priority: Int,           // 优先级
    val status: String           // 当前状态
)

/**
 * 备份结果
 */
data class BackupResult(
    val type: BackupType,
    val success: Boolean,
    val filePath: String? = null,
    val fileSize: Long = 0,
    val itemCount: Int = 0,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 壁纸信息
 */
data class WallpaperInfo(
    val width: Int,
    val height: Int,
    val fileSize: Long,
    val format: String,  // PNG/JPEG
    val filePath: String
)
