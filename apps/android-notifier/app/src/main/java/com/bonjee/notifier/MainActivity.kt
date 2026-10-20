package com.bonjee.notifier

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bonjee.notifier.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("bonjee_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("webhook_url", "")
        binding.editWebhookUrl.setText(savedUrl)

        binding.btnGrantPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        binding.btnSave.setOnClickListener {
            val url = binding.editWebhookUrl.text.toString().trim()
            prefs.edit().putString("webhook_url", url).apply()
            Toast.makeText(this, "Webhook URL saved", Toast.LENGTH_SHORT).show()
        }

        binding.btnStartService.setOnClickListener {
            if (NotificationListener.isEnabled(this)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(Intent(this, ForegroundService::class.java))
                } else {
                    startService(Intent(this, ForegroundService::class.java))
                }
                Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Notification access not granted", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnStopService.setOnClickListener {
            stopService(Intent(this, ForegroundService::class.java))
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val enabled = NotificationListener.isEnabled(this)
        binding.txtStatus.text = if (enabled) "Status: Permission Granted ✓" else "Status: Permission Not Granted ✗"
    }
}
