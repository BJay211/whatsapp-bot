package com.bonjee.notifier.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.ai.AiClient
import com.bonjee.notifier.databinding.ActivityAiSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bring-your-own-model settings.
 *
 * The screen only stores what the user types and reports what the provider
 * actually answered. TEST sends one tiny message so a wrong key, wrong base URL
 * or unavailable model is discovered here rather than in the middle of a
 * question.
 */
class AiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiSettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var ai: AiClient
    private val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        ai = AiClient(this)

        binding.editAiBase.setText(prefs.aiBaseUrl)
        binding.editAiKey.setText(prefs.aiApiKey)
        binding.editAiModel.setText(prefs.aiModel)
        binding.switchAiEnabled.isChecked = prefs.aiEnabled

        binding.switchAiEnabled.setOnCheckedChangeListener { _, value ->
            prefs.aiEnabled = value
            refreshStatus()
        }
        binding.btnAiSave.setOnClickListener {
            save()
            Toast.makeText(this, "Saved on this phone", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        binding.btnAiTest.setOnClickListener { test() }
        binding.btnAiModels.setOnClickListener { listModels() }
        binding.btnAiClearKey.setOnClickListener { clearKey() }

        binding.txtAiFooter.text =
            "Answers come from the provider you configured, not from this app. " +
                "If the call fails, the assistant says so and falls back to the n8n archive."
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun save() {
        prefs.aiBaseUrl = binding.editAiBase.text?.toString().orEmpty()
        prefs.aiApiKey = binding.editAiKey.text?.toString().orEmpty()
        prefs.aiModel = binding.editAiModel.text?.toString().orEmpty()
        prefs.aiEnabled = binding.switchAiEnabled.isChecked
    }

    private fun refreshStatus() {
        val key = when {
            prefs.aiApiKey.isBlank() -> "missing"
            else -> "set · ends " + prefs.aiApiKey.takeLast(4)
        }
        val lastOk = if (prefs.aiLastOkAt > 0) {
            "last success " + stamp.format(Date(prefs.aiLastOkAt))
        } else {
            "not tested yet"
        }
        binding.txtAiStatus.text = buildString {
            appendLine("provider: " + prefs.aiBaseUrl)
            appendLine("model: " + prefs.aiModel)
            appendLine("key: $key")
            appendLine("state: " + if (prefs.aiEnabled) "on" else "off")
            append(lastOk)
        }
    }

    private fun test() {
        save()
        if (prefs.aiApiKey.isBlank()) {
            binding.txtAiResult.text = "Add an API key first."
            return
        }
        if (busy) return
        busy = true
        binding.txtAiResult.text = "Testing ${prefs.aiBaseUrl} with ${prefs.aiModel} ..."
        lifecycleScope.launch {
            val answer = withContext(Dispatchers.IO) { ai.test() }
            busy = false
            if (answer.ok) {
                prefs.aiLastOkAt = System.currentTimeMillis()
                binding.txtAiResult.text = buildString {
                    appendLine("Working. The provider answered:")
                    appendLine()
                    appendLine(answer.text)
                    appendLine()
                    append(answer.sourceLine())
                    if (!prefs.aiEnabled) {
                        appendLine()
                        appendLine()
                        append(
                            "The AI switch is still off, so questions keep going to the " +
                                "archive until you turn it on.",
                        )
                    }
                }
            } else {
                binding.txtAiResult.text = answer.error
            }
            refreshStatus()
        }
    }

    private fun listModels() {
        save()
        if (prefs.aiApiKey.isBlank()) {
            binding.txtAiResult.text = "Add an API key first."
            return
        }
        if (busy) return
        busy = true
        binding.txtAiResult.text = "Asking ${prefs.aiBaseUrl}/models ..."
        lifecycleScope.launch {
            val (models, error) = withContext(Dispatchers.IO) { ai.listModels() }
            busy = false
            if (models.isEmpty()) {
                binding.txtAiResult.text = error.ifBlank { "The provider returned no models." }
                return@launch
            }
            binding.txtAiResult.text =
                "${models.size} models available. Tap one to use it:\n\n" + models.joinToString("\n")
            AlertDialog.Builder(this@AiSettingsActivity)
                .setTitle("Models on this key")
                .setItems(models.toTypedArray()) { _, which ->
                    binding.editAiModel.setText(models[which])
                    prefs.aiModel = models[which]
                    refreshStatus()
                    Toast.makeText(
                        this@AiSettingsActivity,
                        "Model set to ${models[which]}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun clearKey() {
        if (prefs.aiApiKey.isBlank()) {
            Toast.makeText(this, "There is no saved key", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove the API key?")
            .setMessage(
                "The key is deleted from this phone. AI questions stop working until you add " +
                    "a key again. The provider keeps whatever it already logged.",
            )
            .setPositiveButton("Remove") { _, _ ->
                prefs.aiApiKey = ""
                prefs.aiEnabled = false
                prefs.aiLastOkAt = 0L
                binding.editAiKey.setText("")
                binding.switchAiEnabled.isChecked = false
                binding.txtAiResult.text = "Key removed from this phone."
                refreshStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
