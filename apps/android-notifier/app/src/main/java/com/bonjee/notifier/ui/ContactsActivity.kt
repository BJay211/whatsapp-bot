package com.bonjee.notifier.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivityContactsBinding
import com.bonjee.notifier.device.DeviceActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Searchable contact list with one-tap actions.
 *
 * It exists because remembering command grammar is hard on a phone: type a name,
 * tap the person, pick "WhatsApp call" or "SMS". Every action is handed to
 * [DeviceActions] so this screen and the assistant can never drift apart.
 */
class ContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactsBinding
    private val results = ArrayList<Contact>()
    private lateinit var adapter: ArrayAdapter<String>

    /** Replayed after a permission grant (contact search or the chosen action). */
    private var pendingAction: (() -> Unit)? = null

    private data class Contact(val name: String, val number: String) {
        override fun toString(): String = if (number.isBlank()) name else "$name\n$number"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) action?.invoke() else toast("That needs permission to continue")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            ArrayList(),
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as TextView).setTextColor(
                    ContextCompat.getColor(this@ContactsActivity, R.color.text_primary),
                )
                return view
            }
        }
        binding.listContacts.adapter = adapter

        binding.btnSearch.setOnClickListener { search() }
        binding.btnMessageRules.setOnClickListener {
            startActivity(Intent(this, ContactRulesActivity::class.java))
        }
        binding.editContact.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else {
                false
            }
        }
        binding.editContact.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if ((s?.length ?: 0) >= 2) search(quiet = true)
            }
        })

        binding.listContacts.setOnItemClickListener { _, _, position, _ ->
            results.getOrNull(position)?.let { showActions(it) }
        }

        val initial = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        binding.editContact.setText(initial)
        binding.editContact.setSelection(initial.length)
        if (initial.isNotBlank()) {
            search()
        } else {
            ensureContacts { search() }
        }
    }

    private fun search(quiet: Boolean = false) {
        val query = binding.editContact.text?.toString()?.trim().orEmpty()
        ensureContacts {
            lifecycleScope.launch {
                val found = withContext(Dispatchers.IO) { queryContacts(query, LIMIT) }
                results.clear()
                results.addAll(found)
                adapter.clear()
                adapter.addAll(found.map { "${it.name}  -  ${it.number}" })
                adapter.notifyDataSetChanged()
                if (!quiet) {
                    binding.txtHint.text = when {
                        found.isEmpty() && query.isBlank() -> "No contacts on this phone."
                        found.isEmpty() -> "No match for \"$query\"."
                        else -> "${found.size} match(es). Tap one to call, WhatsApp or text."
                    }
                }
            }
        }
    }

    private fun ensureContacts(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun showActions(contact: Contact) {
        val options = arrayOf(
            "Call",
            "WhatsApp call",
            "WhatsApp video call",
            "WhatsApp message",
            "SMS",
        )
        AlertDialog.Builder(this)
            .setTitle(contact.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> run("call ${contact.number}")
                    1 -> run("call ${contact.name} on whatsapp")
                    2 -> run("video call ${contact.name} on whatsapp")
                    3 -> askMessage(contact, whatsapp = true)
                    4 -> askMessage(contact, whatsapp = false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askMessage(contact: Contact, whatsapp: Boolean) {
        val input = EditText(this).apply {
            hint = if (whatsapp) "WhatsApp message" else "SMS message"
            setTextColor(ContextCompat.getColor(this@ContactsActivity, R.color.text_primary))
            setPadding(32, 24, 32, 24)
        }
        val verb = if (whatsapp) "whatsapp" else "text"
        AlertDialog.Builder(this)
            .setTitle(if (whatsapp) "Message ${contact.name} on WhatsApp" else "Text ${contact.name}")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val body = input.text?.toString()?.trim().orEmpty()
                if (body.isEmpty()) {
                    run("$verb ${contact.name}")
                } else {
                    run("$verb ${contact.name} saying $body")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Hands the sentence to the same engine the assistant uses. */
    private fun run(command: String) {
        val outcome = try {
            DeviceActions.handle(this, command)
        } catch (t: Throwable) {
            toast("I couldn't do that (${t.javaClass.simpleName})")
            return
        }
        when (outcome) {
            is DeviceActions.Result.Done -> {
                binding.txtHint.text = outcome.message
                toast(outcome.message)
            }
            is DeviceActions.Result.NeedsPermission -> {
                binding.txtHint.text = if (outcome.progress.isBlank()) {
                    outcome.why
                } else {
                    outcome.progress + "\n\n" + outcome.why
                }
                pendingAction = if (outcome.retryable) {
                    { run(command) }
                } else {
                    null
                }
                permissionLauncher.launch(outcome.permission)
            }
            is DeviceActions.Result.NeedsSettings -> {
                binding.txtHint.text = if (outcome.progress.isBlank()) {
                    outcome.why
                } else {
                    outcome.progress + "\n\n" + outcome.why
                }
                startActivity(outcome.intent)
            }
            is DeviceActions.Result.Open -> {
                binding.txtHint.text = outcome.message
                startActivity(outcome.intent)
            }
            is DeviceActions.Result.NeedInfo -> {
                binding.txtHint.text = outcome.message
                toast(outcome.message)
            }
            DeviceActions.Result.NotHandled -> toast("I didn't understand that command")
        }
    }

    private fun queryContacts(query: String, limit: Int): List<Contact> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val uri = if (query.isBlank()) {
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        } else {
            android.net.Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                android.net.Uri.encode(query),
            )
        }
        val out = ArrayList<Contact>()
        val seen = HashSet<String>()
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, null, null, null)
            while (cursor != null && cursor.moveToNext() && out.size < limit) {
                val name = cursor.getString(0)?.trim().orEmpty()
                val number = cursor.getString(1)?.trim().orEmpty()
                if (name.isEmpty() && number.isEmpty()) continue
                val key = "$name|$number"
                if (!seen.add(key)) continue
                out.add(Contact(name.ifEmpty { number }, number))
            }
        } catch (t: Throwable) {
            // Contacts provider unavailable (work profile / permission revoked).
        } finally {
            cursor?.close()
        }
        return out
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_QUERY = "contact_query"
        private const val LIMIT = 40
    }
}
