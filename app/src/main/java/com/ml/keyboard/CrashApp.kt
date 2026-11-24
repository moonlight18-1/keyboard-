package com.ml.keyboard

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.widget.ScrollView
import android.widget.TextView

class CrashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val trace = sw.toString()
            try {
                getSharedPreferences("crash", MODE_PRIVATE).edit().putString("last", trace).apply()
                val i = Intent(this, CrashActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                i.putExtra("trace", trace)
                startActivity(i)
            } catch (ignored: Throwable) {
            }
            Process.killProcess(Process.myPid())
        }
    }
}

class CrashActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val trace = intent?.getStringExtra("trace")
            ?: getSharedPreferences("crash", MODE_PRIVATE).getString("last", "No data")
        val tv = TextView(this)
        tv.text = "=== APP ERROR — SEND THIS SCREENSHOT ===\n\n" + trace
        tv.textSize = 11f
        tv.setTextColor(Color.WHITE)
        tv.setTextIsSelectable(true)
        tv.setPadding(30, 30, 30, 30)
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(tv)
        setContentView(sv)
    }
}
