package com.bonjee.notifier

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.databinding.ActivityAskBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Asks the n8n assistant a question and shows the grounded answer/evidence.
 * Uses the same endpoint exposed by the Bonjee n8n workflow.
 */
class AskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAskBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)

        binding.btnAsk.setOnClickListener { ask() }
        binding.editQuestion.setOnClickListener { }
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun ask() {
        val question = binding.editQuestion.text.toString().trim()
        if (question.isEmpty()) {
            Toast.makeText(this, "Type a question first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnAsk.isEnabled = false
        binding.txtAnswer.text = "Thinking..."

        lifecycleScope.launch {
            val payload = JSONObject().apply { put("question", question) }.toString()
            val result = withContext(Dispatchers.IO) {
                ApiClient.shared.postJson(prefs.askUrl(), payload, prefs.headers())
            }
            binding.btnAsk.isEnabled = true
            if (result.ok) {
                binding.txtAnswer.text = prettyPrint(result.body)
            } else {
                binding.txtAnswer.text = "Request failed.\n\nURL: ${prefs.askUrl()}\n${result.summary}"
            }
        }
    }

    private fun prettyPrint(raw: String): String {
        return try {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) return raw
            JSONObject(trimmed).toString(2)
        } catch (e: Exception) {
            raw
        }
    }
}
