package com.bonjee.notifier.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bonjee.notifier.MainActivity
import com.bonjee.notifier.databinding.ActivityActionsBinding

class ActionsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityActionsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        binding.btnConfigureN8n.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        
        binding.btnWhatsApp.setOnClickListener {
            Toast.makeText(this, "WhatsApp integration via n8n - configure in settings", Toast.LENGTH_SHORT).show()
        }
    }
}
