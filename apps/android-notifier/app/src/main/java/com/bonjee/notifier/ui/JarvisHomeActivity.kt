package com.bonjee.notifier.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.AskActivity
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivityJarvisHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class JarvisHomeActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityJarvisHomeBinding
    private lateinit var prefs: Prefs
    private var isVoiceMode = true
    
    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0) ?: ""
            if (spokenText.isNotEmpty()) {
                val intent = Intent(this, AskActivity::class.java)
                intent.putExtra("query", spokenText)
                startActivity(intent)
            }
        }
        stopVoiceAnimation()
    }

    /** Android 10+ assistant role dialog opened from the drawer. */
    private val assistantRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val isDefault = com.bonjee.notifier.assistant.AssistantRole.isDefault(this)
        Toast.makeText(
            this,
            if (isDefault) "JARVIS is now your default assistant"
            else "JARVIS is not the default assistant yet",
            Toast.LENGTH_SHORT,
        ).show()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJarvisHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs.get(this)
        
        setupAnimations()
        setupClickListeners()
        setupNavigationDrawer()
        handleAssistantLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAssistantLaunch(intent)
    }

    /**
     * Handles an invocation from Android's assistant role.
     *
     * Two intents reach us:
     *  - EXTRA_FROM_ASSISTANT, set by our own voice-interaction session;
     *  - ACTION_ASSIST, sent directly by launchers/OEM triggers that bypass the
     *    session and start an assist activity.
     *
     * The system can call either while the phone is locked, so the window is
     * allowed to show over the keyguard and turn the screen on. Listening starts
     * right away, which is what the long-press gesture is expected to do.
     */
    private fun handleAssistantLaunch(intent: Intent?) {
        val fromAssistant =
            intent?.getBooleanExtra(EXTRA_FROM_ASSISTANT, false) == true ||
                intent?.action == Intent.ACTION_ASSIST
        if (!fromAssistant) return

        showOverKeyguard()

        // Make sure the voice card is the one on screen before the recogniser opens.
        isVoiceMode = true
        binding.voiceInputCard.visibility = View.VISIBLE
        binding.textInputCard.visibility = View.GONE

        // Wait for the window to be attached; launching the recogniser straight
        // from onCreate makes some OEM builds drop the dialog.
        binding.root.post {
            if (!isFinishing) startVoiceRecognition()
        }
    }

    private fun showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshConnectionStatus()
    }

    /**
     * Pings the n8n health webhook and the dashboard health endpoint so the
     * headline honestly reports whether the phone can reach the backend.
     */
    private fun refreshConnectionStatus() {
        binding.txtStatus.text = "CHECKING •••"
        binding.txtStatus.setTextColor(ContextCompat.getColor(this, R.color.amber))
        lifecycleScope.launch {
            val n8nOk = withContext(Dispatchers.IO) {
                com.bonjee.notifier.ApiClient.shared.get(prefs.healthUrl(), prefs.headers()).ok
            }
            val dashboardOk = withContext(Dispatchers.IO) {
                com.bonjee.notifier.ApiClient.shared
                    .get(prefs.dashboardUrl("/api/health"), prefs.headers()).ok
            }
            binding.txtStatus.text = when {
                n8nOk && dashboardOk -> "ONLINE • n8n + CLOUD SYNCED"
                n8nOk -> "ONLINE • n8n READY"
                dashboardOk -> "ONLINE • WEB READY"
                else -> "OFFLINE • CHECK CONFIG"
            }
            binding.txtStatus.setTextColor(
                ContextCompat.getColor(
                    this@JarvisHomeActivity,
                    if (n8nOk || dashboardOk) R.color.green else R.color.red
                )
            )
        }
    }
    
    private fun setupAnimations() {
        val pulseAnim = AnimationUtils.loadAnimation(this, R.anim.pulse)
        binding.glowLayer1.startAnimation(pulseAnim)
        binding.glowLayer2.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_slow))
        binding.coreOuterRing.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate))
    }
    
    private fun setupClickListeners() {
        // Menu button
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        
        // Voice mode
        binding.btnModeVoice.setOnClickListener {
            isVoiceMode = true
            binding.voiceInputCard.visibility = View.VISIBLE
            binding.textInputCard.visibility = View.GONE
            binding.btnModeVoice.strokeColor = ContextCompat.getColorStateList(this, R.color.cyan)
            binding.btnModeVoice.setTextColor(ContextCompat.getColor(this, R.color.cyan))
            binding.btnModeType.strokeColor = ContextCompat.getColorStateList(this, R.color.border)
            binding.btnModeType.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
        
        // Type mode
        binding.btnModeType.setOnClickListener {
            isVoiceMode = false
            binding.voiceInputCard.visibility = View.GONE
            binding.textInputCard.visibility = View.VISIBLE
            binding.btnModeType.strokeColor = ContextCompat.getColorStateList(this, R.color.cyan)
            binding.btnModeType.setTextColor(ContextCompat.getColor(this, R.color.cyan))
            binding.btnModeVoice.strokeColor = ContextCompat.getColorStateList(this, R.color.border)
            binding.btnModeVoice.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
        
        // Voice input
        binding.btnVoice.setOnClickListener {
            startVoiceRecognition()
        }
        
        // Text send
        binding.btnSpeak.setOnClickListener {
            val query = binding.editQuery.text.toString()
            if (query.isNotEmpty()) {
                val intent = Intent(this, AskActivity::class.java)
                intent.putExtra("query", query)
                startActivity(intent)
                binding.editQuery.setText("")
            } else {
                Toast.makeText(this, "Please enter a command", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setupNavigationDrawer() {
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_home -> {
                    // Already on home
                }
                R.id.nav_ask -> {
                    startActivity(Intent(this, AskActivity::class.java))
                }
                R.id.nav_memory -> {
                    startActivity(Intent(this, MemoryActivity::class.java))
                }
                R.id.nav_inbox -> {
                    startActivity(Intent(this, MessageInboxActivity::class.java))
                }
                R.id.nav_tasks -> {
                    startActivity(Intent(this, TasksActivity::class.java))
                }
                R.id.nav_calendar -> {
                    startActivity(Intent(this, CalendarActivity::class.java))
                }
                R.id.nav_automations -> {
                    startActivity(Intent(this, AutomationsActivity::class.java))
                }
                R.id.nav_devices -> {
                    startActivity(Intent(this, MessageSettingsActivity::class.java))
                }
                R.id.nav_app_settings -> {
                    startActivity(Intent(this, com.bonjee.notifier.MainActivity::class.java))
                }
                R.id.nav_assistant -> {
                    val roleIntent =
                        com.bonjee.notifier.assistant.AssistantRole.roleRequestIntent(this)
                    if (roleIntent != null &&
                        com.bonjee.notifier.assistant.AssistantRole.canHandle(this, roleIntent)
                    ) {
                        try {
                            assistantRoleLauncher.launch(roleIntent)
                        } catch (t: Throwable) {
                            com.bonjee.notifier.assistant.AssistantRole.openSettings(this)
                        }
                    } else {
                        com.bonjee.notifier.assistant.AssistantRole.openSettings(this)
                    }
                }
                R.id.nav_permissions -> {
                    startActivity(Intent(this, PermissionsActivity::class.java))
                }
                R.id.nav_actions -> {
                    startActivity(Intent(this, ActionsActivity::class.java))
                }
                R.id.nav_commands -> {
                    startActivity(Intent(this, CommandsActivity::class.java))
                }
                R.id.nav_setup_access -> {
                    startActivity(Intent(this, SetupAccessActivity::class.java))
                }
                R.id.nav_contacts -> {
                    startActivity(Intent(this, ContactsActivity::class.java))
                }
                R.id.nav_message_rules -> {
                    startActivity(Intent(this, ContactRulesActivity::class.java))
                }
                R.id.nav_messages -> {
                    startActivity(Intent(this, MessageInboxActivity::class.java))
                }
                R.id.nav_message_settings -> {
                    startActivity(Intent(this, MessageSettingsActivity::class.java))
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, com.bonjee.notifier.MainActivity::class.java))
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS...")
        }
        try {
            startVoiceAnimation()
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice recognition not available", Toast.LENGTH_SHORT).show()
            stopVoiceAnimation()
        }
    }
    
    private fun startVoiceAnimation() {
        binding.waveformContainer.visibility = View.VISIBLE
        binding.wave1.startAnimation(AnimationUtils.loadAnimation(this, R.anim.wave_anim))
        binding.wave2.startAnimation(AnimationUtils.loadAnimation(this, R.anim.wave_anim2))
        binding.wave3.startAnimation(AnimationUtils.loadAnimation(this, R.anim.wave_anim3))
        binding.wave4.startAnimation(AnimationUtils.loadAnimation(this, R.anim.wave_anim))
        binding.wave5.startAnimation(AnimationUtils.loadAnimation(this, R.anim.wave_anim2))
        binding.wave6.startAnimation(AnimationUtils.loadAnimation(this, R.anim.wave_anim3))
        binding.wave7.startAnimation(AnimationUtils.loadAnimation(this, R.anim.wave_anim))
        binding.txtStatus.text = "LISTENING..."
        binding.txtStatus.setTextColor(ContextCompat.getColor(this, R.color.orange))
    }
    
    private fun stopVoiceAnimation() {
        binding.waveformContainer.visibility = View.GONE
        binding.wave1.clearAnimation()
        binding.wave2.clearAnimation()
        binding.wave3.clearAnimation()
        binding.wave4.clearAnimation()
        binding.wave5.clearAnimation()
        binding.wave6.clearAnimation()
        binding.wave7.clearAnimation()
        binding.txtStatus.text = "ONLINE • READY"
        binding.txtStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
        refreshConnectionStatus()
    }
    
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        /**
         * Set by the assistant session so the home screen knows to listen
         * immediately instead of waiting for a tap on the microphone.
         */
        const val EXTRA_FROM_ASSISTANT = "bonjee.extra.FROM_ASSISTANT"
    }
}
