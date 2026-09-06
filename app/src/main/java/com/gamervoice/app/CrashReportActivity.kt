package com.gamervoice.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gamervoice.app.databinding.ActivityCrashReportBinding

class CrashReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashReportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val stackTrace = intent.getStringExtra("extra_stack_trace") ?: "No stack trace available."
        binding.tvStackTrace.text = stackTrace

        binding.btnCopyCrash.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GamerVoice Crash Log", stackTrace)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Error log copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }
}
