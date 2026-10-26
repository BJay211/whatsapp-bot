package com.bonjee.notifier.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivityContactRulesBinding
import com.bonjee.notifier.databinding.DialogEditRuleBinding
import com.bonjee.notifier.databinding.ItemRuleBinding
import com.bonjee.notifier.whatsapp.AutoReplyMode
import com.bonjee.notifier.whatsapp.ContactCategory
import com.bonjee.notifier.whatsapp.ContactResolver
import com.bonjee.notifier.whatsapp.ContactRule
import com.bonjee.notifier.whatsapp.ContactRules
import com.bonjee.notifier.whatsapp.ContactRulesStore
import com.bonjee.notifier.whatsapp.JarvisApi
import com.bonjee.notifier.whatsapp.PhoneNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Important / Auto-Reply / Other, in one screen.
 *
 * Every edit is persisted locally first, then offered to the workflow with
 * `contact.settings.update`. If the workflow does not answer, the local rule
 * still applies to notifications - but auto-reply stays off because server
 * authorisation is a separate, required flag.
 */
class ContactRulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactRulesBinding
    private lateinit var store: ContactRulesStore
    private lateinit var prefs: Prefs
    private lateinit var api: JarvisApi

    private var shown: List<ContactRule> = emptyList()
    private var pendingContactsAction: (() -> Unit)? = null

    private val categoryLabels = arrayOf("Important", "Auto-Reply", "Other")
    private val modeLabels = arrayOf("Off", "Approved template", "AI (workflow decides)")

    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingContactsAction
        pendingContactsAction = null
        if (granted) action?.invoke() else toast("Contacts permission is needed to pick a contact")
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = shown[position].number.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = if (convertView == null) {
                ItemRuleBinding.inflate(layoutInflater, parent, false)
            } else {
                ItemRuleBinding.bind(convertView)
            }
            val rule = shown[position]
            row.txtRuleName.text = rule.displayName
            row.txtRuleName.setTextColor(
                ContextCompat.getColor(
                    this@ContactRulesActivity,
                    when (rule.category) {
                        ContactCategory.IMPORTANT -> R.color.cyan
                        ContactCategory.AUTO_REPLY -> R.color.green
                        ContactCategory.OTHER -> R.color.text_primary
                    },
                ),
            )
            row.txtRuleNumber.text = PhoneNumber.pretty(rule.number)
            row.txtRuleMeta.text = metaFor(rule)
            return row.root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = ContactRulesStore(this)
        prefs = Prefs.get(this)
        api = JarvisApi(this)

        binding.listRules.adapter = adapter
        binding.listRules.setOnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { editRule(it) }
        }
        binding.listRules.setOnItemLongClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { confirmDelete(it) } ?: false
            true
        }

        binding.switchAutoReply.isChecked = prefs.autoReplyGlobalEnabled
        binding.switchAutoReply.setOnCheckedChangeListener { _, checked ->
            prefs.autoReplyGlobalEnabled = checked
            binding.txtRuleStatus.text = if (checked) {
                "Automatic replies are allowed, but only for Auto-Reply contacts the workflow authorises."
            } else {
                "All automatic replies are stopped. Incoming messages are still shown."
            }
        }

        binding.btnAddContact.setOnClickListener { addFromInput() }
        binding.btnPickContact.setOnClickListener { pickFromContacts() }
        binding.btnSyncRules.setOnClickListener { syncAll() }
        binding.btnRuleSettings.setOnClickListener {
            startActivity(Intent(this, MessageSettingsActivity::class.java))
        }
        binding.btnRuleInbox.setOnClickListener {
            startActivity(Intent(this, MessageInboxActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        shown = store.load().sortedForDisplay()
        adapter.notifyDataSetChanged()
        val counts = store.load().counts()
        val important = counts[ContactCategory.IMPORTANT] ?: 0
        val auto = counts[ContactCategory.AUTO_REPLY] ?: 0
        val other = counts[ContactCategory.OTHER] ?: 0
        val authorised = prefs.serverAuthorizedNumbers.size
        binding.txtRuleSummary.text =
            "important $important   ·   auto-reply $auto   ·   other $other\n" +
                "workflow-authorised auto-replies: $authorised"
        if (shown.isEmpty()) {
            binding.txtRuleStatus.text =
                "No rules yet. Add a number above, or pick one from your phone contacts. " +
                    "Everything starts as Other and never auto-replies."
        }
    }

    private fun metaFor(rule: ContactRule): String {
        val parts = ArrayList<String>()
        parts.add(rule.category.label.uppercase())
        if (rule.category == ContactCategory.AUTO_REPLY) {
            parts.add(
                when {
                    !rule.autoReplyEnabled -> "auto-reply OFF"
                    rule.autoReplyMode == AutoReplyMode.TEMPLATE -> "template"
                    rule.autoReplyMode == AutoReplyMode.AI -> "AI (approval policy applies)"
                    else -> "off"
                },
            )
            val authorised = prefs.isServerAuthorized(rule.number)
            parts.add(if (authorised) "server: authorised" else "server: not authorised")
        }
        if (rule.category == ContactCategory.IMPORTANT) {
            parts.add(if (rule.announce) "announced" else "silent")
            if (rule.priority) parts.add("priority")
        }
        return parts.joinToString("  ·  ")
    }

    // ------------------------------------------------------------------ add

    private fun addFromInput() {
        val raw = binding.editNewContact.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            toast("Type a name or a phone number")
            return
        }
        val number = PhoneNumber.normalize(raw, prefs.defaultCountryCode)
        val name = if (PhoneNumber.looksLikeNumber(raw)) "" else raw
        if (number.isEmpty()) {
            // A bare name can still be resolved through contacts on this phone.
            val match = ContactResolver.numberFor(this, raw)
            if (match == null) {
                toast("Add the country code, for example +234 803 123 4567")
                return
            }
            openEditor(ContactRule(number = match.number, name = match.name, category = ContactCategory.OTHER))
            return
        }
        val existing = store.find(number)
        binding.editNewContact.setText("")
        openEditor(existing ?: ContactRule(number = number, name = name, category = ContactCategory.OTHER))
    }

    private fun pickFromContacts() {
        val action: () -> Unit = {
            val contacts = ContactResolver.search(this, "", 60)
            if (contacts.isEmpty()) {
                toast("No contacts found on this phone")
            } else {
                val labels = contacts.map { "${it.name}\n${PhoneNumber.pretty(it.number)}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Pick a contact")
                    .setItems(labels) { _, which ->
                        val match = contacts[which]
                        val existing = store.find(match.number)
                        openEditor(
                            existing ?: ContactRule(
                                number = match.number,
                                name = match.name,
                                category = ContactCategory.OTHER,
                            ),
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        if (ContactResolver.hasPermission(this)) {
            action()
        } else {
            pendingContactsAction = action
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    // ----------------------------------------------------------------- edit

    private fun editRule(rule: ContactRule) = openEditor(rule)

    private fun openEditor(rule: ContactRule) {
        val view = DialogEditRuleBinding.inflate(layoutInflater)
        view.editRuleName.setText(rule.name)
        view.editRuleNumber.setText(PhoneNumber.pretty(rule.number))

        view.spinnerCategory.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            categoryLabels,
        )
        view.spinnerCategory.setSelection(
            when (rule.category) {
                ContactCategory.IMPORTANT -> 0
                ContactCategory.AUTO_REPLY -> 1
                ContactCategory.OTHER -> 2
            },
            false,
        )

        view.spinnerMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modeLabels,
        )
        view.spinnerMode.setSelection(
            when (rule.autoReplyMode) {
                AutoReplyMode.TEMPLATE -> 1
                AutoReplyMode.AI -> 2
                AutoReplyMode.OFF -> 0
            },
            false,
        )

        view.editRuleTemplate.setText(rule.template)
        view.switchRuleAnnounce.isChecked = rule.announce
        view.switchRulePriority.isChecked = rule.priority
        view.switchRuleAuto.isChecked = rule.autoReplyEnabled

        fun updateHelp() {
            val categoryIndex = view.spinnerCategory.selectedItemPosition
            val auto = view.switchRuleAuto.isChecked
            view.switchRuleAuto.isEnabled = categoryIndex == 1
            view.spinnerMode.isEnabled = categoryIndex == 1 && auto
            view.editRuleTemplate.isEnabled = categoryIndex == 1 && auto
            view.switchRulePriority.isEnabled = categoryIndex == 0
            view.txtRuleHelp.text = when (categoryIndex) {
                0 -> "Important: notified and optionally announced. This is NOT permission to auto-reply. " +
                    "Replies still need your approval."
                1 -> if (auto) {
                    "Auto-Reply: the workflow may answer. It also needs server-side authorisation for this " +
                        "number, and it never answers OTP, bank or payment wording. AI replies may still come " +
                        "back for your approval."
                } else {
                    "Auto-Reply bucket, but automatic replies are OFF for this contact."
                }
                else -> "Other: messages are shown and can be answered by you. Nothing is ever sent automatically."
            }
        }

        view.spinnerCategory.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                updateHelp()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        view.switchRuleAuto.setOnCheckedChangeListener { _, _ -> updateHelp() }
        updateHelp()

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (rule.name.isBlank()) "New rule" else "Edit ${rule.displayName}")
            .setView(view.root)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        if (store.find(rule.number) != null) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Delete") { _, _ ->
                store.remove(rule.number)
                refresh()
                toast("Rule removed")
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val category = when (view.spinnerCategory.selectedItemPosition) {
                    0 -> ContactCategory.IMPORTANT
                    1 -> ContactCategory.AUTO_REPLY
                    else -> ContactCategory.OTHER
                }
                val mode = when (view.spinnerMode.selectedItemPosition) {
                    1 -> AutoReplyMode.TEMPLATE
                    2 -> AutoReplyMode.AI
                    else -> AutoReplyMode.OFF
                }
                val edited = ContactRule(
                    number = view.editRuleNumber.text?.toString().orEmpty(),
                    name = view.editRuleName.text?.toString().orEmpty(),
                    category = category,
                    autoReplyEnabled = view.switchRuleAuto.isChecked && category == ContactCategory.AUTO_REPLY,
                    autoReplyMode = if (category == ContactCategory.AUTO_REPLY) mode else AutoReplyMode.OFF,
                    template = view.editRuleTemplate.text?.toString().orEmpty(),
                    announce = view.switchRuleAnnounce.isChecked,
                    priority = view.switchRulePriority.isChecked,
                    updatedAt = System.currentTimeMillis(),
                ).sanitized(prefs.defaultCountryCode)

                if (edited == null) {
                    toast("That number is not valid. Use the international format.")
                } else {
                    store.upsert(edited)
                    dialog.dismiss()
                    refresh()
                    pushToWorkflow(edited)
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(rule: ContactRule): Boolean {
        AlertDialog.Builder(this)
            .setTitle("Remove rule?")
            .setMessage("${rule.displayName} will go back to being an Other number. No messages are deleted.")
            .setPositiveButton("Remove") { _, _ ->
                store.remove(rule.number)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
        return true
    }

    // -------------------------------------------------------------- backend

    private fun pushToWorkflow(rule: ContactRule) {
        // Disabling a rule immediately drops any previous server authorisation,
        // so re-enabling it later cannot ride on a stale grant.
        if (rule.category != ContactCategory.AUTO_REPLY || !rule.autoReplyEnabled) {
            prefs.serverAuthorizedNumbers = prefs.serverAuthorizedNumbers - rule.number
        }
        if (!api.isConfigured()) {
            binding.txtRuleStatus.text =
                "Saved on this phone. No workflow endpoint is configured, so the server does not know yet."
            return
        }
        binding.txtRuleStatus.text = "Saved locally. Sending the rule to the workflow..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.updateContactSettings(rule, prefs.isServerAuthorized(rule.number))
            }
            val authorised = result.boolField("auto_reply_authorized")
                ?: result.boolField("authorized")
            if (result.ok && authorised != null) {
                val set = prefs.serverAuthorizedNumbers.toMutableSet()
                if (authorised) set.add(rule.number) else set.remove(rule.number)
                prefs.serverAuthorizedNumbers = set
            }
            binding.txtRuleStatus.text = when {
                !result.ok -> result.userMessage()
                authorised == true -> "Workflow authorised auto-reply for ${rule.displayName}. Automatic replies can run."
                authorised == false -> "Rule saved, but the workflow did NOT authorise auto-reply for ${rule.displayName}. It will be shown only."
                else -> "Saved and sent to the workflow (HTTP ${result.code}). Authorisation was not reported."
            }
            refresh()
        }
    }

    private fun syncAll() {
        val rules = store.load()
        if (!api.isConfigured()) {
            binding.txtRuleStatus.text = "No workflow endpoint configured. Rules are local only."
            return
        }
        binding.txtRuleStatus.text = "Syncing ${rules.items.size} rule(s)..."
        lifecycleScope.launch {
            var ok = 0
            var authorised = 0
            var failure = ""
            withContext(Dispatchers.IO) {
                for (rule in rules.items) {
                    val result = api.updateContactSettings(rule, prefs.isServerAuthorized(rule.number))
                    if (result.ok) {
                        ok++
                        val flag = result.boolField("auto_reply_authorized") ?: result.boolField("authorized")
                        if (flag == true) {
                            authorised++
                            val set = prefs.serverAuthorizedNumbers.toMutableSet()
                            set.add(rule.number)
                            prefs.serverAuthorizedNumbers = set
                        }
                    } else {
                        failure = result.userMessage()
                    }
                }
            }
            binding.txtRuleStatus.text = if (failure.isBlank()) {
                "Synced $ok rule(s). $authorised authorised for auto-reply."
            } else {
                "Synced $ok rule(s), $authorised authorised. Last error: $failure"
            }
            refresh()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
