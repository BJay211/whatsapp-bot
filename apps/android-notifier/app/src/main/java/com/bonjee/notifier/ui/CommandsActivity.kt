package com.bonjee.notifier.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bonjee.notifier.NotificationListener
import com.bonjee.notifier.databinding.ActivityCommandsBinding
import com.bonjee.notifier.device.CommandCatalog

/**
 * Plain-language list of everything JARVIS can do on the phone, plus the one-time
 * permissions each group needs. Every command here runs locally; the list is
 * generated from [CommandCatalog] so it always matches the engine.
 */
class CommandsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommandsBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommandsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.txtCommands.text = CommandCatalog.fullText()

        binding.btnWriteSettings.setOnClickListener {
            if (canWriteSystem()) {
                toast("Brightness control is already allowed")
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
        binding.btnContacts.setOnClickListener {
            ask(Manifest.permission.READ_CONTACTS)
        }
        binding.btnPhone.setOnClickListener {
            ask(Manifest.permission.CALL_PHONE)
        }
        binding.btnCamera.setOnClickListener {
            ask(Manifest.permission.CAMERA)
        }
        binding.btnNotifications.setOnClickListener {
            if (NotificationListener.isEnabled(this)) {
                toast("Notification access is already allowed")
            } else {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        binding.txtPermissionStatus.text = buildString {
            appendLine("CONTROL STATUS")
            appendLine("  brightness (system settings): ${yesNo(canWriteSystem())}")
            appendLine("  contacts: ${yesNo(granted(Manifest.permission.READ_CONTACTS))}")
            appendLine("  phone calls: ${yesNo(granted(Manifest.permission.CALL_PHONE))}")
            appendLine("  camera / torch: ${yesNo(granted(Manifest.permission.CAMERA))}")
            appendLine("  microphone: ${yesNo(granted(Manifest.permission.RECORD_AUDIO))}")
            append("  notification access: ${yesNo(NotificationListener.isEnabled(this@CommandsActivity))}")
        }
    }

    private fun ask(permission: String) {
        if (granted(permission)) {
            toast("Already allowed")
            return
        }
        permissionLauncher.launch(permission)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun canWriteSystem(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(this)

    private fun yesNo(value: Boolean) = if (value) "YES" else "no"

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
