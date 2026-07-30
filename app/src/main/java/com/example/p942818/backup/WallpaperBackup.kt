package com.example.p942818.backup

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 壁纸备份模块 - 导出/导入壁纸
 */
object WallpaperBackup {

    private const val TAG = "WallpaperBackup"
    private val df = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    fun getCurrentWallpaper(context: Context): Bitmap? {
        return try {
            val wm = WallpaperManager.getInstance(context)
            val drawable = wm.drawable
            if (drawable is android.graphics.drawable.BitmapDrawable) {
                drawable.bitmap
            } else if (drawable != null) {
                val bmp = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            } else null
        } catch (e: Exception) { Log.e(TAG, "获取壁纸失败", e); null }
    }

    /** 导出壁纸 */
    fun backupWallpaper(context: Context, backupDir: File): BackupResult {
        return try {
            val dir = File(backupDir, "Wallpaper").also { it.mkdirs() }
            val bmp = getCurrentWallpaper(context) ?: return BackupResult(BackupType.WALLPAPER, false, errorMessage = "无法读取当前壁纸")
            val file = File(dir, "壁纸_${df.format(Date())}.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // 也存 JPEG 预览
            val jpg = File(dir, "壁纸_${df.format(Date())}.jpg")
            FileOutputStream(jpg).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            BackupResult(BackupType.WALLPAPER, true, file.absolutePath, file.length(), 1)
        } catch (e: Exception) { BackupResult(BackupType.WALLPAPER, false, errorMessage = "备份失败: ${e.message}") }
    }

    /** ===== 恢复壁纸（设置壁纸） ===== */
    fun restoreWallpaper(context: Context, imageFile: File): BackupResult {
        return try {
            val bmp = BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: return BackupResult(BackupType.WALLPAPER, false, errorMessage = "无法解析图片文件")
            val wm = WallpaperManager.getInstance(context)
            wm.setBitmap(bmp)
            BackupResult(BackupType.WALLPAPER, true, itemCount = 1)
        } catch (e: Exception) {
            BackupResult(BackupType.WALLPAPER, false, errorMessage = "设置壁纸失败: ${e.message}")
        }
    }
}
