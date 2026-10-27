package com.bonjee.notifier.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivityLaptopBinding
import com.bonjee.notifier.laptop.LaptopClient
import com.bonjee.notifier.laptop.LaptopCommand
import com.bonjee.notifier.laptop.LaptopDiscovery
import com.bonjee.notifier.laptop.LaptopHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native laptop control.
 *
 * The phone talks straight to the agent the user runs on their computer (see
 * `laptop-agent/`). Everything is explicit: the address is typed or discovered,
 * the token comes from the agent's config, and - unless the user turns it off -
 * every command is shown in full and must be approved before it runs.
 *
 * The screen never invents a result. It shows the agent's HTTP status, exit code,
 * stdout and stderr exactly as they came back.
 */
class LaptopActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLaptopBinding
    private lateinit var prefs: Prefs
    private lateinit var client: LaptopClient
    private lateinit var history: LaptopHistoryStore

    private var agent: LaptopClient.AgentInfo? = null
    private val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
    private var busy = false

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (spoken.isNotBlank()) {
            binding.editLaptopCommand.setText(spoken)
            binding.editLaptopCommand.setSelection(spoken.length)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLaptopBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        client = LaptopClient(this)
        history = LaptopHistoryStore(this)

        loadSettings()
        bindButtons()
        renderHistory()

        binding.txtLaptopFooter.text =
            "The agent runs only what its config.json allows unless you switch it to free mode. " +
                "Its token never leaves your Wi-Fi. Keep the agent off any network you do not trust."
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ------------------------------------------------------------------ state

    private fun loadSettings() {
        binding.switchLaptopEnabled.isChecked = prefs.laptopEnabled
        binding.editLaptopHost.setText(prefs.laptopHost)
        binding.editLaptopPort.setText(prefs.laptopPort.toString())
        binding.editLaptopToken.setText(prefs.laptopToken)
        binding.switchAskBeforeRun.isChecked = prefs.laptopAskBeforeRun
        binding.switchFreeMode.isChecked = prefs.laptopFreeMode
        binding.txtFreeWarning.visibility =
            if (prefs.laptopFreeMode) View.VISIBLE else View.GONE
        binding.txtFreeWarning.text =
            "Free mode lets any text in the box run as PowerShell. Use it only if you accept " +
                "that a mistyped or mis-heard command can delete files."
    }

    private fun bindButtons() {
        binding.btnLaptopSave.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Saved on this phone", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        binding.switchLaptopEnabled.setOnCheckedChangeListener { _, value ->
            prefs.laptopEnabled = value
            refreshStatus()
        }
        binding.switchAskBeforeRun.setOnCheckedChangeListener { _, value ->
            prefs.laptopAskBeforeRun = value
            refreshStatus()
        }
        binding.switchFreeMode.setOnCheckedChangeListener { _, value ->
            prefs.laptopFreeMode = value
            binding.txtFreeWarning.visibility = if (value) View.VISIBLE else View.GONE
            if (value) {
                AlertDialog.Builder(this)
                    .setTitle("Allow raw PowerShell?")
                    .setMessage(
                        "With this on, anything typed or spoken into the command box is sent to " +
                            "the laptop as a script. The agent must also be in free mode.\n\n" +
                            "A wrong word from voice input could delete files. Approval still " +
                            "applies while \"ask me first\" is on.",
                    )
                    .setPositiveButton("I understand") { _, _ -> refreshStatus() }
                    .setNegativeButton("Turn it off") { _, _ ->
                        binding.switchFreeMode.isChecked = false
                        prefs.laptopFreeMode = false
                        binding.txtFreeWarning.visibility = View.GONE
                        refreshStatus()
                    }
                    .setCancelable(false)
                    .show()
            } else {
                refreshStatus()
            }
        }

        binding.btnLaptopTest.setOnClickListener { testConnection() }
        binding.btnLaptopDiscover.setOnClickListener { discover() }
        binding.btnLaptopRun.setOnClickListener { runTyped() }
        binding.btnLaptopCommands.setOnClickListener { showCommands() }
        binding.btnLaptopClearHistory.setOnClickListener {
            history.clear()
            renderHistory()
            Toast.makeText(this, "History cleared on this phone", Toast.LENGTH_SHORT).show()
        }
        binding.btnLaptopMic.setOnClickListener { listen() }
    }

    private fun saveSettings() {
        prefs.laptopHost = binding.editLaptopHost.text?.toString().orEmpty()
        val port = binding.editLaptopPort.text?.toString()?.trim()?.toIntOrNull()
        if (port != null) prefs.laptopPort = port
        prefs.laptopToken = binding.editLaptopToken.text?.toString().orEmpty()
        prefs.laptopEnabled = binding.switchLaptopEnabled.isChecked
        prefs.laptopAskBeforeRun = binding.switchAskBeforeRun.isChecked
        prefs.laptopFreeMode = binding.switchFreeMode.isChecked
    }

    private fun refreshStatus() {
        val base = prefs.laptopBaseUrl()
        val token = if (prefs.laptopToken.isBlank()) "missing" else "set"
        val mode = when {
            prefs.laptopFreeMode -> "free PowerShell"
            else -> "allowlist"
        }
        val state = when {
            !prefs.laptopEnabled -> "off"
            base.isEmpty() -> "no address"
            prefs.laptopToken.isBlank() -> "no token"
            agent != null -> "connected · ${agent?.mode.orEmpty()} · ${agent?.commands?.size ?: 0} commands"
            prefs.laptopLastSeenAt > 0 -> "last seen " + stamp.format(Date(prefs.laptopLastSeenAt))
            else -> "not tested"
        }
        binding.txtLaptopStatus.text = buildString {
            appendLine("address: " + base.ifEmpty { "(not set)" })
            appendLine("token: $token")
            appendLine("state: $state")
            append("approval: " + if (prefs.laptopAskBeforeRun) "ask first" else "OFF - runs immediately")
            if (!prefs.laptopFreeMode) append(" · app mode: $mode")
        }
    }

    // -------------------------------------------------------------- connection

    private fun testConnection() {
        saveSettings()
        if (!prefs.laptopConfigured()) {
            binding.txtLaptopStatus.text =
                "Add the laptop address and the token from config.json first."
            return
        }
        if (busy) return
        busy = true
        binding.txtLaptopStatus.text = "Contacting ${prefs.laptopBaseUrl()} ..."
        lifecycleScope.launch {
            val health = withContext(Dispatchers.IO) { client.health() }
            busy = false
            if (health.ok && health.info != null) {
                agent = health.info
                prefs.laptopLastSeenAt = System.currentTimeMillis()
                val preview = health.info.commands.take(6).joinToString(", ") { it.name }
                binding.txtLaptopStatus.text = buildString {
                    appendLine(health.message)
                    appendLine("mode: ${health.info.mode} · commands: ${health.info.commands.size}")
                    appendLine("host: ${health.info.host}")
                    appendLine("workdir: ${health.info.workingDirectory}")
                    appendLine("examples: ${preview.ifBlank { "(none configured)" }}")
                    append(
                        "approval: " + if (prefs.laptopAskBeforeRun) "ask first" else "OFF - runs immediately",
                    )
                }
            } else {
                agent = null
                binding.txtLaptopStatus.text = health.message
            }
        }
    }

    private fun discover() {
        if (busy) return
        busy = true
        binding.txtLaptopStatus.text = "Scanning this Wi-Fi for the agent ..."
        val port = binding.editLaptopPort.text?.toString()?.trim()?.toIntOrNull()
            ?: prefs.laptopPort
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                LaptopDiscovery().scan(port, listOfNotNull(prefs.laptopHost.ifBlank { null }))
            }
            busy = false
            when {
                found.isEmpty() -> {
                    binding.txtLaptopStatus.text = buildString {
                        appendLine("No agent answered on this network.")
                        appendLine()
                        appendLine("Check that start-agent.ps1 is still running on the laptop and ")
                        appendLine("that the phone is on the same Wi-Fi. Windows Firewall may also ")
                        append("be blocking port $port - see laptop-agent/README.md.")
                    }
                }
                found.size == 1 -> {
                    applyFound(found.first())
                }
                else -> {
                    val labels = found.map { "${it.address} · ${it.name}" }.toTypedArray()
                    AlertDialog.Builder(this@LaptopActivity)
                        .setTitle("Pick the laptop")
                        .setItems(labels) { _, which -> applyFound(found[which]) }
                        .show()
                }
            }
        }
    }

    private fun applyFound(found: LaptopDiscovery.Found) {
        binding.editLaptopHost.setText(found.address)
        binding.editLaptopPort.setText(found.port.toString())
        saveSettings()
        binding.txtLaptopStatus.text =
            "Found ${found.name} at ${found.url}. Now paste the token from config.json and tap TEST."
        Toast.makeText(this, "Found ${found.address}", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------- run

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the laptop command")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
        }
        try {
            voiceLauncher.launch(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "No speech recogniser is available on this phone.", Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun runTyped() {
        val typed = binding.editLaptopCommand.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty()) {
            Toast.makeText(this, "Type or speak a command first", Toast.LENGTH_SHORT).show()
            return
        }
        // Accept the same "on my laptop ..." phrasing the assistant uses.
        val inner = LaptopCommand.stripPrefix(typed) ?: typed
        val parsed = LaptopCommand.parse(inner)
        if (parsed.name.isBlank()) {
            Toast.makeText(this, "That command is empty", Toast.LENGTH_SHORT).show()
            return
        }
        submit(parsed)
    }

    private fun submit(parsed: LaptopCommand.Parsed) {
        if (!prefs.laptopEnabled) {
            Toast.makeText(this, "Laptop control is switched off above", Toast.LENGTH_SHORT).show()
            return
        }
        if (!prefs.laptopConfigured()) {
            binding.txtLaptopResult.text = "Set the address and the token first, then tap TEST."
            return
        }
        if (prefs.laptopAskBeforeRun) {
            confirm(parsed) { execute(parsed) }
        } else {
            execute(parsed)
        }
    }

    private fun confirm(parsed: LaptopCommand.Parsed, onApprove: () -> Unit) {
        val known = agent?.commands?.firstOrNull { command ->
            command.name.equals(parsed.name, ignoreCase = true) ||
                command.name.equals(parsed.raw, ignoreCase = true) ||
                command.aliases.any { it.equals(parsed.name, ignoreCase = true) } ||
                command.aliases.any { it.equals(parsed.raw, ignoreCase = true) }
        }
        val preview = known?.preview.orEmpty()
        val missing = agent?.commands?.map { it.name }.orEmpty()
        val message = buildString {
            appendLine("This will run on your laptop:")
            appendLine()
            appendLine("  " + LaptopCommand.format(parsed))
            if (preview.isNotBlank()) {
                appendLine()
                appendLine("Agent script:")
                appendLine(preview)
            }
            if (agent != null && known == null && !prefs.laptopFreeMode) {
                appendLine()
                appendLine(
                    "This is not one of the agent's ${missing.size} configured commands. " +
                        "It will probably be refused.",
                )
            }
            if (LaptopCommand.isDangerous(parsed)) {
                appendLine()
                append("WARNING: this looks destructive. Read it again before approving.")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Approve this command?")
            .setMessage(message.trim())
            .setPositiveButton("Approve & run") { _, _ -> onApprove() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun execute(parsed: LaptopCommand.Parsed) {
        if (busy) return
        busy = true
        binding.btnLaptopRun.isEnabled = false
        binding.txtLaptopResult.text =
            "Running \"" + LaptopCommand.format(parsed) + "\" on ${prefs.laptopBaseUrl()} ..."
        lifecycleScope.launch {
            val run = withContext(Dispatchers.IO) { client.execute(parsed) }
            busy = false
            binding.btnLaptopRun.isEnabled = true
            history.add(LaptopHistoryStore.from(run))
            binding.txtLaptopResult.setTextColor(
                ContextCompat.getColor(
                    this@LaptopActivity,
                    if (run.ok) R.color.green_bright else R.color.orange_bright,
                ),
            )
            binding.txtLaptopResult.text = buildString {
                appendLine(run.summary())
                appendLine()
                append("request " + run.requestId.take(8))
            }
            renderHistory()
            refreshStatus()
        }
    }

    // --------------------------------------------------------------- commands

    private fun showCommands() {
        val known = agent
        if (known == null) {
            testConnection()
            Toast.makeText(
                this,
                "Connect first - the command list comes from the laptop.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (known.commands.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No commands configured")
                .setMessage(
                    "The agent is running but its config.json has no commands. Add entries under " +
                        "\"commands\" or set \"mode\": \"free\".",
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = known.commands.map { command ->
            buildString {
                append(command.name)
                if (command.args.isNotEmpty()) append(" (").append(command.args.joinToString(", ")).append(")")
                if (command.aliases.isNotEmpty()) {
                    append("\nalso: ").append(command.aliases.joinToString(", "))
                }
                if (command.description.isNotBlank()) append("\n").append(command.description)
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Agent commands · ${known.mode}")
            .setItems(labels) { _, which ->
                val command = known.commands[which]
                binding.editLaptopCommand.setText(suggest(command))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Pre-fills the box so the user only has to fill in the arguments. */
    private fun suggest(command: LaptopClient.AgentCommand): String {
        if (command.args.isEmpty()) return command.name
        return command.name + " " + command.args.joinToString(" ") { "$it=" }
    }

    // ---------------------------------------------------------------- history

    private fun renderHistory() {
        val entries = history.load()
        if (entries.isEmpty()) {
            binding.txtLaptopHistory.text = "Nothing run from this phone yet."
            return
        }
        binding.txtLaptopHistory.text = entries.take(12).joinToString("\n") { entry ->
            val state = when {
                entry.ok -> "ok"
                entry.exitCode >= 0 -> "exit ${entry.exitCode}"
                else -> "error"
            }
            stamp.format(Date(entry.at)) + "  " + state + "  " + entry.command.take(60)
        }
    }
}
