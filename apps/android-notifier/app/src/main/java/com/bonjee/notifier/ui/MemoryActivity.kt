package com.bonjee.notifier.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bonjee.notifier.databinding.ActivityMemoryBinding

class MemoryActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMemoryBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        binding.btnAddMemory.setOnClickListener {
            Toast.makeText(this, "Memory core interface coming soon", Toast.LENGTH_SHORT).show()
        }
    }
}
