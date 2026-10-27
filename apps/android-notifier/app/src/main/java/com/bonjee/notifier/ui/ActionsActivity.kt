package com.bonjee.notifier.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bonjee.notifier.AskActivity
import com.bonjee.notifier.NotificationListener
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.databinding.ActivityActionsBinding

/**
 * Hub for the native modules.
 *
 * It used to print fake "workflow" cards and open the web dashboard, which is
 * not deployed. Every button now opens a real screen that works on this phone.
 */
class ActionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActionsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)

        binding.btnActionMemory.setOnClickListener { open(MemoryActivity::class.java) }
        binding.btnActionAutomations.setOnClickListener { open(AutomationsActivity::class.java) }
        binding.btnActionTasks.setOnClickListener { open(TasksActivity::class.java) }
        binding.btnActionCalendar.setOnClickListener { open(CalendarActivity::class.java) }
        binding.btnActionInbox.setOnClickListener { open(MessageInboxActivity::class.java) }
        binding.btnActionRules.setOnClickListener { open(ContactRulesActivity::class.java) }
        binding.btnActionMessageSettings.setOnClickListener { open(MessageSettingsActivity::class.java) }
        binding.btnActionCommands.setOnClickListener { open(CommandsActivity::class.java) }
        binding.btnActionAccess.setOnClickListener { open(SetupAccessActivity::class.java) }
        binding.btnActionAsk.setOnClickListener { open(AskActivity::class.java) }
        binding.btnActionLaptop.setOnClickListener { open(LaptopActivity::class.java) }
        binding.btnActionAi.setOnClickListener { open(AiSettingsActivity::class.java) }

        binding.txtActionsFooter.text =
            "The web dashboard is not required. Memory, tasks, calendar and automation switches " +
                "are native; only the optional cloud memory/search and task sync call the workflow. " +
                "Laptop control talks straight to the agent on your computer; AI answers come " +
                "from the provider you configured."
    }

    override fun onResume() {
        super.onResume()
        val contacts = granted(Manifest.permission.READ_CONTACTS)
        val calendar = granted(Manifest.permission.READ_CALENDAR)
        binding.txtActionsStatus.text = buildString {
            appendLine("notification access: " + if (NotificationListener.isEnabled(this@ActionsActivity)) "granted" else "NOT granted")
            appendLine("contacts: " + if (contacts) "granted" else "not granted")
            appendLine("calendar: " + if (calendar) "granted" else "not granted")
            append("workflow: ").append(prefs.appApiUrl())
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }
}
