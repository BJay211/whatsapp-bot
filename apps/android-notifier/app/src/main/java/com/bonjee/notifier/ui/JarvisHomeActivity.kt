package com.bonjee.notifier.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bonjee.notifier.AskActivity
import com.bonjee.notifier.MainActivity
import com.bonjee.notifier.databinding.ActivityJarvisHomeBinding

class JarvisHomeActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityJarvisHomeBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJarvisHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupClickListeners()
    }
    
    private fun setupClickListeners() {
        binding.btnVoice.setOnClickListener {
            Toast.makeText(this, "Voice input initializing...", Toast.LENGTH_SHORT).show()
        }
        
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
        
        binding.btnStop.setOnClickListener {
            Toast.makeText(this, "Interrupted", Toast.LENGTH_SHORT).show()
        }
        
        binding.cardMemory.setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }
        
        binding.cardPermissions.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        
        binding.cardActions.setOnClickListener {
            startActivity(Intent(this, ActionsActivity::class.java))
        }
        
        binding.cardSettings.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }
}
