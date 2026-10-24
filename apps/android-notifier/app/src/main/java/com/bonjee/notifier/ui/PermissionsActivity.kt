package com.bonjee.notifier.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bonjee.notifier.NotificationListener
import com.bonjee.notifier.databinding.ActivityPermissionsBinding

class PermissionsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityPermissionsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupSwitches()
        
        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            })
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateSwitchStates()
    }
    
    private fun setupSwitches() {
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !NotificationListener.isEnabled(this)) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        
        binding.switchMicrophone.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            }
        }
        
        binding.switchCamera.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasPermission(Manifest.permission.CAMERA)) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
            }
        }
        
        binding.switchContacts.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasPermission(Manifest.permission.READ_CONTACTS)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 102)
            }
        }
    }
    
    private fun updateSwitchStates() {
        binding.switchNotifications.isChecked = NotificationListener.isEnabled(this)
        binding.switchMicrophone.isChecked = hasPermission(Manifest.permission.RECORD_AUDIO)
        binding.switchCamera.isChecked = hasPermission(Manifest.permission.CAMERA)
        binding.switchContacts.isChecked = hasPermission(Manifest.permission.READ_CONTACTS)
    }
    
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
