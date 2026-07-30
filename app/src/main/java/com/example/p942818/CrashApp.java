package com.example.p942818;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileInputStream;

public class CrashApp extends Application {

    // 运行批次号：IDE 编译时把当前 runId 烤进这一行。崩溃回流广播带上此值，
    // IDE 只接收当前 runId 的崩溃，旧进程残留发的崩溃广播会被拒收，避免旧堆栈污染新控制台。
    // 占位符 "INIT" 由 IDE 在编译前替换为真实 runId。
    // 增量更新时壳类不重装：优先读 files/code_slot/run_id（由 CodeSlotProvider import 写入）。
    private static String RUN_ID = "224e60c065a907bfa76a76b09a7c0260";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // 调试容器代码槽：反射调用，正式包无 CodeSlotLoader 则忽略。
        try {
            Class<?> c = Class.forName(getClass().getPackage().getName() + ".CodeSlotLoader");
            c.getMethod("install", Context.class).invoke(null, this);
        } catch (Throwable ignored) {
            try {
                Class<?> c = Class.forName(getClass().getPackage().getName() + ".HotSwapLoader");
                c.getMethod("install", Context.class).invoke(null, this);
            } catch (Throwable ignored2) { }
        }
        try {
            Class<?> d = Class.forName(getClass().getPackage().getName() + ".DiagLogger");
            d.getMethod("init", Context.class).invoke(null, this);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Class<?> d = Class.forName(getClass().getPackage().getName() + ".DiagLogger");
            d.getMethod("init", Context.class).invoke(null, this);
        } catch (Throwable ignored) { }
        installCrashHandler();
    }

    /** 增量 import 写入的 run_id 优先于烤进壳 dex 的静态值。 */
    private static String effectiveRunId(Context ctx) {
        try {
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

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                String trace = sw.toString();

                // 把崩溃堆栈回流给 Yima IDE（显式广播，仅当 IDE 装在本机才生效；否则静默忽略）。
                // 这样「运行→崩溃」能自动出现在 IDE 控制台并交给 AI 排查，无需手动抄。
                try {
                    Intent report = new Intent("com.yimaide.app.CRASH_REPORT");
                    report.setPackage("com.yimaide.app");
                    report.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    report.putExtra("trace", trace);
                    report.putExtra("pkg", getPackageName());
                    report.putExtra("run_id", effectiveRunId(CrashApp.this));
                    sendBroadcast(report);
                } catch (Throwable ignored) { }

                // 本机展示堆栈（独立 :crash 进程，主进程被回收也留得住）。
                try {
                    Intent i = new Intent(CrashApp.this, CrashActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    i.putExtra("trace", trace);
                    startActivity(i);
                } catch (Throwable ignored) {
                    if (prev != null) prev.uncaughtException(thread, ex);
                    return;
                }
                // CrashActivity lives in the ":crash" process, so killing this (main) process is safe.
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }
}
