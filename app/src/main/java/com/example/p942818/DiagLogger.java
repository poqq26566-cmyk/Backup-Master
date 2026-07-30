package com.example.p942818;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;

/**
 * 诊断日志回流到 Yima IDE 控制台。AI 排查疑难问题时在代码里埋点用：DiagLogger.d("xx", "val=" + v)。
 * 调用后日志会同时进入系统 logcat 和 IDE 控制台，方便 AI 看到运行时真实情况。
 * 正式打包时 IDE 会把本类换成无广播空实现（残留埋点仍可编译，发布包无回流）。
 * 下次调试「运行」时会自动恢复完整实现。
 */
public final class DiagLogger {

    private static final String ACTION = "com.yimaide.app.DIAG_LOG";
    private static final String IDE_PKG = "com.yimaide.app";

    // 运行批次号：IDE 编译时把当前 runId 烤进这一行。App 运行时发广播带上此值，
    // IDE 只接收当前 runId 的回流，旧进程残留发的广播会被拒收，避免旧日志污染新控制台。
    // 占位符 "INIT" 由 IDE 在编译前替换为真实 runId；若未替换则发 "INIT"，IDE 也会拒收。
    // 增量更新时优先读 files/code_slot/run_id。
    private static String RUN_ID = "224e60c065a907bfa76a76b09a7c0260";

    private static volatile Context APP_CTX;

    /** 由 CrashApp.attachBaseContext/onCreate 尽早注入，避免 currentApplication 仍为 null。 */
    public static void init(Context ctx) {
        if (ctx != null) APP_CTX = ctx.getApplicationContext();
    }

    private static String effectiveRunId() {
        try {
            Context ctx = APP_CTX;
            if (ctx == null) {
                android.app.Application app = currentApp();
                if (app != null) ctx = app;
            }
            if (ctx != null) {
                File f = new File(ctx.getFilesDir(), "code_slot/run_id");
                if (f.isFile() && f.length() > 0L && f.length() < 4096L) {
                    byte[] buf = new byte[(int) f.length()];
                    FileInputStream in = new FileInputStream(f);
                    try {
                        int n = in.read(buf);
                        if (n > 0) {
                            String s = new String(buf, 0, n, "UTF-8").trim();
                            if (s.length() > 0) return s;
                        }
                    } finally { in.close(); }
                }
            }
        } catch (Throwable ignored) { }
        return RUN_ID;
    }

    // 限流：达到该行数或该时间间隔(ms)就 flush 一次，避免循环埋点刷爆广播。
    private static final int FLUSH_LINES = 30;
    private static final long FLUSH_INTERVAL_MS = 120L;

    private static final Object LOCK = new Object();
    private static final java.util.ArrayList<String[]> PENDING = new java.util.ArrayList<>();
    private static long LAST_FLUSH = 0L;
    private static boolean SCHEDULER_ON = false;

    private DiagLogger() { }

    public static void d(String tag, String msg) { log('D', tag, msg); }
    public static void i(String tag, String msg) { log('I', tag, msg); }
    public static void w(String tag, String msg) { log('W', tag, msg); }
    public static void e(String tag, String msg) { log('E', tag, msg); }

    private static void log(char level, String tag, String msg) {
        try {
            // 仍写一份到系统 logcat，adb 也能看到。
            switch (level) {
                case 'D': Log.d(tag, msg); break;
                case 'I': Log.i(tag, msg); break;
                case 'W': Log.w(tag, msg); break;
                case 'E': Log.e(tag, msg); break;
            }
        } catch (Throwable ignored) { }
        enqueue(level, tag == null ? "" : tag, msg == null ? "" : msg);
    }

    private static void enqueue(char level, String tag, String msg) {
        synchronized (LOCK) {
            PENDING.add(new String[]{String.valueOf(level), tag, msg});
            boolean shouldFlush = PENDING.size() >= FLUSH_LINES
                    || (System.currentTimeMillis() - LAST_FLUSH) >= FLUSH_INTERVAL_MS;
            if (shouldFlush) {
                flushLocked();
            } else if (!SCHEDULER_ON) {
                SCHEDULER_ON = true;
                scheduleFlush();
            }
        }
    }

    private static void scheduleFlush() {
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(FLUSH_INTERVAL_MS); } catch (InterruptedException ignored) { }
                synchronized (LOCK) {
                    if (!PENDING.isEmpty()) flushLocked();
                    SCHEDULER_ON = false;
                }
            }
        }, "DiagLogger-flush").start();
    }

    private static void flushLocked() {
        final java.util.ArrayList<String[]> snapshot = new java.util.ArrayList<>(PENDING);
        PENDING.clear();
        LAST_FLUSH = System.currentTimeMillis();
        if (snapshot.isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    StringBuilder sb = new StringBuilder();
                    for (String[] it : snapshot) {
                        sb.append(it[0]).append('/').append(it[1]).append(": ").append(it[2]).append('\n');
                    }
                    Intent report = new Intent(ACTION);
                    report.setPackage(IDE_PKG);
                    report.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    report.putExtra("lines", sb.toString());
                    report.putExtra("run_id", effectiveRunId());
                    Context ctx = APP_CTX;
                    if (ctx == null) {
                        android.app.Application app = currentApp();
                        if (app != null) ctx = app;
                    }
                    if (ctx != null) {
                        report.putExtra("pkg", ctx.getPackageName());
                        ctx.sendBroadcast(report);
                    }
                } catch (Throwable ignored) { }
            }
        }, "DiagLogger-send").start();
    }

    private static android.app.Application currentApp() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object current = at.getMethod("currentApplication").invoke(null);
            return (android.app.Application) current;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
