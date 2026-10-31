package com.bonjee.notifier.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivityFridayBinding
import com.bonjee.notifier.friday.FridayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pairing and status for Friday, the assistant on the laptop.
 *
 * Everything here reflects what Friday actually reported. A failed test shows
 * the failure and the address that was tried; a pairing code is only stored
 * after the laptop has issued a device token; the certificate fingerprint is
 * shown so it can be compared with the laptop.
 */
class FridayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFridayBinding
    private lateinit var prefs: Prefs
    private lateinit var client: FridayClient

    private var busy = false
    private val stamp = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFridayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        client = FridayClient(this)

        loadSettings()
        bindButtons()

        binding.txtFridayFooter.text =
            "On the same Wi-Fi the connection is TLS and pinned to Friday's certificate. With a " +
                "relay URL it uses the relay's own TLS from any network, and Friday still " +
                "authenticates this phone with its device token. Approvals are always decided by " +
                "Friday: if remote approval is off on the laptop, this phone can only watch."
    }

    override fun onResume() {
        super.onResume()
        refreshLocalStatus()
    }

    // ----------------------------------------------------------------- state

    private fun loadSettings() {
        binding.switchFridayEnabled.isChecked = prefs.fridayEnabled
        binding.editFridayHost.setText(prefs.fridayHost)
        binding.editFridayPort.setText(prefs.fridayPort.toString())
        binding.editFridayRelayUrl.setText(prefs.fridayRelayUrl)
        binding.editFridayPrefix.setText(prefs.fridayInvokePrefix)
        binding.editFridayCode.setText("")
        binding.txtFridayPairing.text = if (prefs.fridayToken.isBlank()) {
            "Not paired on this phone."
        } else {
            buildString {
                appendLine("Paired as ${prefs.fridayDeviceId.ifBlank { "(unknown id)" }}")
                if (prefs.fridayPairedAt > 0) {
                    appendLine("paired " + stamp.format(Date(prefs.fridayPairedAt)))
                }
                if (prefs.fridayUseRelay()) {
                    appendLine("routes through the relay as ${prefs.fridayRelayLaptop}")
                    append("certificate: relay's own TLS")
                } else {
                    append(
                        "certificate " +
                            if (prefs.fridayFingerprint.isBlank()) "(not pinned yet)" else "pinned",
                    )
                }
            }
        }
    }

    private fun bindButtons() {
        binding.switchFridayEnabled.setOnCheckedChangeListener { _, value ->
            prefs.fridayEnabled = value
            refreshLocalStatus()
        }
        binding.btnFridaySave.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Saved on this phone", Toast.LENGTH_SHORT).show()
            refreshLocalStatus()
        }
        binding.btnFridayTest.setOnClickListener { testConnection() }
        binding.btnFridayPair.setOnClickListener { pair() }
        binding.btnFridayRefresh.setOnClickListener { refreshLive(verbose = true) }
        binding.btnFridayUnpair.setOnClickListener { confirmUnpair() }
        binding.btnFridayStop.setOnClickListener { confirmEmergencyStop() }
    }

    private fun saveSettings() {
        prefs.fridayHost = binding.editFridayHost.text?.toString().orEmpty()
        val port = binding.editFridayPort.text?.toString()?.trim()?.toIntOrNull()
        if (port != null) prefs.fridayPort = port
        prefs.fridayRelayUrl = binding.editFridayRelayUrl.text?.toString().orEmpty()
        prefs.fridayInvokePrefix = binding.editFridayPrefix.text?.toString().orEmpty()
        prefs.fridayEnabled = binding.switchFridayEnabled.isChecked
    }

    private fun refreshLocalStatus() {
        val base = prefs.fridayBaseUrl()
        val state = when {
            !prefs.fridayEnabled -> "off"
            base.isEmpty() -> "no address"
            prefs.fridayToken.isBlank() -> "not paired"
            else -> "paired"
        }
        binding.txtFridayStatus.text = buildString {
            appendLine("address: " + base.ifEmpty { "(not set)" })
            appendLine("state: $state")
            appendLine("wake word: \"${prefs.fridayInvokePrefix}\"")
            if (prefs.fridayUseRelay()) {
                appendLine("relay laptop: ${prefs.fridayRelayLaptop}")
                append("certificate: relay's own TLS (no pin)")
            } else {
                val fingerprint = prefs.fridayFingerprint
                append(
                    "certificate: " + if (fingerprint.isBlank()) {
                        "not pinned"
                    } else {
                        fingerprint.take(16) + "…" + fingerprint.takeLast(8)
                    },
                )
            }
            if (prefs.fridayLastOkAt > 0) {
                append(" · last success " + stamp.format(Date(prefs.fridayLastOkAt)))
            }
        }
    }

    // ------------------------------------------------------------- connection

    private fun testConnection() {
        saveSettings()
        if (prefs.fridayHost.isBlank() && prefs.fridayRelayUrl.isBlank()) {
            binding.txtFridayLive.text =
                "Enter the laptop's address, or a relay URL, first (Friday > Settings shows both)."
            return
        }
        if (prefs.fridayRelayUrl.isNotBlank() && prefs.fridayRelayLaptop.isBlank()) {
            binding.txtFridayLive.setTextColor(
                ContextCompat.getColor(this@FridayActivity, R.color.text_secondary),
            )
            binding.txtFridayLive.text =
                "Relay URL saved. Pair once through it and this phone learns the laptop id, then " +
                    "the relay works from any network."
            return
        }
        if (busy) return
        busy = true
        binding.txtFridayLive.text = "Contacting ${prefs.fridayBaseUrl()} ..."
        lifecycleScope.launch {
            val ping = withContext(Dispatchers.IO) { FridayClient(this@FridayActivity).ping() }
            busy = false
            val value = ping.value
            if (!ping.ok || value == null) {
                binding.txtFridayLive.setTextColor(
                    ContextCompat.getColor(this@FridayActivity, R.color.orange_bright),
                )
                binding.txtFridayLive.text = ping.message
                return@launch
            }

            binding.txtFridayLive.setTextColor(
                ContextCompat.getColor(this@FridayActivity, R.color.green_bright),
            )
            val mismatch = !prefs.fridayUseRelay() &&
                prefs.fridayFingerprint.isNotBlank() &&
                value.fingerprint.isNotBlank() &&
                !value.fingerprint.equals(prefs.fridayFingerprint, ignoreCase = true)

            binding.txtFridayLive.text = buildString {
                appendLine("${value.name} ${value.version} (protocol ${value.protocol})")
                appendLine("machine: ${value.machine}")
                appendLine("TLS: " + if (value.tls) "on" else "OFF - this connection is not encrypted")
                appendLine("emergency stop: " + if (value.emergencyStop) "ENGAGED" else "released")
                appendLine("pairing required: " + if (value.pairingRequired) "yes" else "no")
                append("fingerprint: " + value.fingerprint.ifBlank { "(not reported)" })
                if (mismatch) {
                    appendLine()
                    appendLine()
                    append(
                        "WARNING: this is not the certificate this phone paired with. " +
                            "Nothing will be sent until you pair again.",
                    )
                }
            }
            if (mismatch) {
                binding.txtFridayLive.setTextColor(
                    ContextCompat.getColor(this@FridayActivity, R.color.orange_bright),
                )
            }
            if (prefs.fridayToken.isNotBlank()) refreshLive(verbose = false)
        }
    }

    private fun pair() {
        saveSettings()
        val code = binding.editFridayCode.text?.toString().orEmpty()
        if (prefs.fridayHost.isBlank() && prefs.fridayRelayUrl.isBlank()) {
            binding.txtFridayPairing.text = "Enter the laptop's address or a relay URL above first."
            return
        }
        if (code.replace("-", "").trim().length != 8) {
            binding.txtFridayPairing.text = "A pairing code is 8 characters, like ABCD-EFGH."
            return
        }
        if (busy) return
        busy = true
        binding.txtFridayPairing.text = "Pairing with ${prefs.fridayPairingBaseUrl()} ..."
        val name = prefs.deviceName
        lifecycleScope.launch {
            val paired = withContext(Dispatchers.IO) {
                FridayClient(this@FridayActivity).pair(code, name)
            }
            busy = false
            val value = paired.value
            if (!paired.ok || value == null) {
                binding.txtFridayPairing.setTextColor(
                    ContextCompat.getColor(this@FridayActivity, R.color.orange_bright),
                )
                binding.txtFridayPairing.text = paired.message
                return@launch
            }
            binding.editFridayCode.setText("")
            binding.txtFridayPairing.setTextColor(
                ContextCompat.getColor(this@FridayActivity, R.color.green_bright),
            )
            binding.txtFridayPairing.text = buildString {
                appendLine("Paired as ${value.deviceName}.")
                if (prefs.fridayUseRelay()) {
                    appendLine("Route: internet relay as ${prefs.fridayRelayLaptop}")
                    append(
                        "Works from mobile data or any Wi-Fi. Friday still checks this phone's " +
                            "own device token on every request.",
                    )
                } else {
                    appendLine("certificate pinned: " + value.fingerprint.ifBlank { "(not reported)" })
                    append("Compare that with Friday > Settings > Phone on the laptop.")
                }
            }
            loadSettings()
            refreshLocalStatus()
            refreshLive(verbose = false)
        }
    }

    private fun confirmUnpair() {
        if (prefs.fridayToken.isBlank()) {
            binding.txtFridayPairing.text = "This phone is not paired, so there is nothing to remove."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Unpair this phone?")
            .setMessage(
                "The token on this phone is deleted. If the laptop is reachable it is also " +
                    "revoked there, so the old token cannot be reused.",
            )
            .setPositiveButton("Unpair") { _, _ -> unpair() }
            .setNegativeButton("Keep it", null)
            .show()
    }

    private fun unpair() {
        val deviceId = prefs.fridayDeviceId
        binding.txtFridayPairing.text = "Revoking on the laptop ..."
        lifecycleScope.launch {
            val revoked = if (deviceId.isBlank()) null else withContext(Dispatchers.IO) {
                FridayClient(this@FridayActivity).revokeOnServer(deviceId)
            }
            FridayClient(this@FridayActivity).forget()
            binding.txtFridayPairing.setTextColor(
                ContextCompat.getColor(this@FridayActivity, R.color.text_secondary),
            )
            binding.txtFridayPairing.text = buildString {
                appendLine("This phone forgot Friday.")
                if (revoked == null) {
                    append("No device id was stored, so nothing was revoked on the laptop.")
                } else if (revoked.ok) {
                    append("Laptop said: " + revoked.message)
                } else {
                    appendLine("The laptop could not be told: " + revoked.message)
                    append("Remove the device under Friday > Settings > Phone on the laptop.")
                }
            }
            loadSettings()
            refreshLocalStatus()
        }
    }

    private fun refreshLive(verbose: Boolean) {
        if (prefs.fridayToken.isBlank()) {
            if (verbose) {
                binding.txtFridayLive.text = "Pair this phone first - status needs a device token."
            }
            return
        }
        if (busy) return
        busy = true
        if (verbose) binding.txtFridayLive.text = "Reading status from Friday ..."
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                FridayClient(this@FridayActivity).status()
            }
            busy = false
            val value = status.value
            if (!status.ok || value == null) {
                binding.txtFridayLive.setTextColor(
                    ContextCompat.getColor(this@FridayActivity, R.color.orange_bright),
                )
                binding.txtFridayLive.text = status.message
                return@launch
            }
            prefs.fridayLastOkAt = System.currentTimeMillis()
            binding.txtFridayLive.setTextColor(
                ContextCompat.getColor(this@FridayActivity, R.color.text_secondary),
            )
            binding.txtFridayLive.text = buildString {
                appendLine("machine: ${value.machine}")
                appendLine("state: ${value.state}" + if (value.detail.isBlank()) "" else " · ${value.detail}")
                appendLine("AI configured on the laptop: " + if (value.aiConfigured) "yes" else "no")
                appendLine("voice on the laptop: " + if (value.voice) "ready" else "unavailable")
                appendLine("running tasks: ${value.runningTasks}")
                append(
                    "emergency stop: " +
                        if (value.emergencyStop) "ENGAGED - commands are refused" else "released",
                )
                if (value.recentTasks.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("RECENT")
                    value.recentTasks.take(6).forEach { task ->
                        appendLine(
                            task.status.padEnd(16).take(16) + " " +
                                task.instruction.take(38).ifBlank { task.summary.take(38) },
                        )
                    }
                }
            }
            binding.switchFridayEnabled.isChecked = prefs.fridayEnabled
            refreshLocalStatus()
        }
    }

    private fun confirmEmergencyStop() {
        if (prefs.fridayToken.isBlank()) {
            binding.txtFridayLive.text = "Pair this phone first."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Emergency stop?")
            .setMessage(
                "Friday cancels everything queued or running and refuses new commands until " +
                    "you release the stop on the laptop itself.",
            )
            .setPositiveButton("Stop everything") { _, _ -> emergencyStop() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun emergencyStop() {
        if (busy) return
        busy = true
        binding.btnFridayStop.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FridayClient(this@FridayActivity).emergencyStop(true)
            }
            busy = false
            binding.btnFridayStop.isEnabled = true
            binding.txtFridayLive.setTextColor(
                ContextCompat.getColor(
                    this@FridayActivity,
                    if (result.ok) R.color.orange_bright else R.color.text_secondary,
                ),
            )
            binding.txtFridayLive.text = result.message
            if (result.ok) {
                AlertDialog.Builder(this@FridayActivity)
                    .setTitle("Stopped")
                    .setMessage(
                        "Release it on the laptop (Friday > Settings, or the red banner on its " +
                            "screen) before sending anything else.",
                    )
                    .setPositiveButton("OK", null)
                    .show()
                refreshLive(verbose = false)
            }
        }
    }
}
