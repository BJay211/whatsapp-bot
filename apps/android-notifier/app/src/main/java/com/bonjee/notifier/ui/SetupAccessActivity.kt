package com.bonjee.notifier.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
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
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivitySetupAccessBinding

/**
 * One screen that walks through every access the phone actions need.
 *
 * Android never lets an app grant these itself, and the switches live in
 * different corners of Settings, so instead of failing with a message the
 * assistant sends the user here. Each step says what it unlocks, what the
 * current state is, and opens exactly the right screen. Returning to this page
 * (back button) refreshes the status, so the user can see the tick appear.
 */
class SetupAccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupAccessBinding
    private var index = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    private data class Step(
        val title: String,
        val why: String,
        val permission: String? = null,
        val action: String? = null,
        val packageScoped: Boolean = false,
        val granted: (SetupAccessActivity) -> Boolean,
    )

    private val steps: List<Step> by lazy {
        listOf(
            Step(
                title = "NOTIFICATION ACCESS",
                why = "Reads your notifications so JARVIS can archive them, and so " +
                    "\"read my notifications\" works. Turn JARVIS on in the list.",
                action = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            ) { NotificationListener.isEnabled(it) },
            Step(
                title = "MICROPHONE",
                why = "So you can talk to JARVIS instead of typing.",
                permission = Manifest.permission.RECORD_AUDIO,
            ) { it.has(Manifest.permission.RECORD_AUDIO) },
            Step(
                title = "CONTACTS",
                why = "Finds people by name: \"call mum\", \"text John\".",
                permission = Manifest.permission.READ_CONTACTS,
            ) { it.has(Manifest.permission.READ_CONTACTS) },
            Step(
                title = "PHONE CALLS",
                why = "Places the call itself instead of only opening the dialer.",
                permission = Manifest.permission.CALL_PHONE,
            ) { it.has(Manifest.permission.CALL_PHONE) },
            Step(
                title = "CAMERA AND TORCH",
                why = "Switches the torch on and off, and opens the camera.",
                permission = Manifest.permission.CAMERA,
            ) { it.has(Manifest.permission.CAMERA) },
            Step(
                title = "SMS",
                why = "Lets \"text John saying I'm coming\" send the message instead of " +
                    "only opening the messaging app. Without it the text is written and " +
                    "you press send.",
                permission = Manifest.permission.SEND_SMS,
            ) { it.has(Manifest.permission.SEND_SMS) },
            Step(
                title = "BRIGHTNESS (MODIFY SYSTEM SETTINGS)",
                why = "Android hides this one. On the next screen find JARVIS in the " +
                    "list and switch \"Allow modifying system settings\" ON - that is " +
                    "what lets brightness 10% or 100% change the whole phone.",
                action = Settings.ACTION_MANAGE_WRITE_SETTINGS,
                packageScoped = true,
            ) { it.canWriteSystem() },
            Step(
                title = "DO NOT DISTURB ACCESS",
                why = "Needed only for \"silent mode\" and \"vibrate\". Volume up/down " +
                    "works without it. Allow JARVIS on the next screen.",
                action = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            ) { it.hasDndAccess() },
            Step(
                title = "WHATSAPP TAP ASSIST",
                why = "Optional. WhatsApp does not let apps start calls or press send, so " +
                    "with this on JARVIS presses the call/send button for you. Find " +
                    "\"JARVIS tap assist\" on the next screen and switch it on.",
                action = Settings.ACTION_ACCESSIBILITY_SETTINGS,
            ) { com.bonjee.notifier.device.TapAssistService.isEnabled(it) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupAccessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpen.setOnClickListener { openCurrent() }
        binding.btnNext.setOnClickListener {
            if (index < steps.lastIndex) {
                index++
                refresh()
            } else {
                finish()
            }
        }
        binding.btnBackSetup.setOnClickListener {
            if (index > 0) {
                index--
                refresh()
            } else {
                finish()
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a Settings screen: show the new state immediately.
        if (::binding.isInitialized) refresh()
    }

    private fun refresh() {
        val step = steps[index]
        val granted = step.granted(this)

        binding.txtStep.text = "STEP ${index + 1} OF ${steps.size}"
        binding.txtTitle.text = step.title
        binding.txtWhy.text = step.why
        binding.txtStatus.text = if (granted) "STATUS: allowed \u2713" else "STATUS: not allowed"
        binding.txtStatus.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.green else R.color.amber),
        )
        binding.btnOpen.text = if (granted) "ALREADY ALLOWED" else "OPEN THE SETTING"
        binding.btnNext.text = if (index == steps.lastIndex) "FINISH" else "NEXT STEP"
        binding.btnBackSetup.text = if (index == 0) "CLOSE" else "PREVIOUS STEP"
    }

    private fun openCurrent() {
        val step = steps[index]
        if (step.granted(this)) {
            toast("Already allowed - nothing to do")
            return
        }

        val permission = step.permission
        if (permission != null) {
            permissionLauncher.launch(permission)
            return
        }

        val action = step.action ?: return
        val intent = Intent(action)
        if (step.packageScoped) {
            intent.data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            // Some ROMs rename these screens; fall back to the app's own page,
            // then to the top of Settings so the user is never stuck.
            val fallback = if (step.packageScoped) {
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
            try {
                startActivity(fallback)
            } catch (ignored: Throwable) {
                toast("This phone does not expose that screen")
            }
        }
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun canWriteSystem(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(this)

    private fun hasDndAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        return manager.isNotificationPolicyAccessGranted
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
