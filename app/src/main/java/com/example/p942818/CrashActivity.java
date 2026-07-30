package com.example.p942818;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

public class CrashActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String trace = getIntent() != null ? getIntent().getStringExtra("trace") : null;
        if (trace == null) trace = "(no stack trace)";
        TextView tv = new TextView(this);
        tv.setText(trace);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(11);
        tv.setPadding(32, 64, 32, 32);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFB00020);
        sv.addView(tv);
        setContentView(sv);
    }
}
