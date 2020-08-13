package org.thoughtcrime.securesms.loki.activities

import android.content.Intent
import android.graphics.Bitmap
import android.os.AsyncTask
import android.os.Bundle
import android.support.v4.app.LoaderManager
import android.support.v4.content.Loader
import android.support.v7.widget.LinearLayoutManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_create_closed_group.*
import kotlinx.android.synthetic.main.activity_linked_devices.recyclerView
import network.loki.messenger.R
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.conversation.ConversationActivity
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.groups.GroupManager
import org.thoughtcrime.securesms.loki.protocol.ClosedGroupsProtocol
import org.thoughtcrime.securesms.mms.GlideApp
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.libsignal.util.guava.Optional
import java.lang.ref.WeakReference

class CreateClosedGroupActivity : PassphraseRequiredActionBarActivity(), MemberClickListener, LoaderManager.LoaderCallbacks<List<String>> {
    private var members = listOf<String>()
        set(value) { field = value; createClosedGroupAdapter.members = value }

    private val createClosedGroupAdapter by lazy {
        val result = CreateClosedGroupAdapter(this)
        result.glide = GlideApp.with(this)
        result.memberClickListener = this
        result
    }

    private val selectedMembers: Set<String>
        get() { return createClosedGroupAdapter.selectedMembers }

    companion object {
        public val createNewPrivateChatResultCode = 100
    }

    // region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)
        setContentView(R.layout.activity_create_closed_group)
        supportActionBar!!.title = resources.getString(R.string.activity_create_closed_group_title)
        recyclerView.adapter = createClosedGroupAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)
        createNewPrivateChatButton.setOnClickListener { createNewPrivateChat() }
        LoaderManager.getInstance(this).initLoader(0, null, this)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_create_closed_group, menu)
        return members.isNotEmpty()
    }
    // endregion

    // region Updating
    override fun onCreateLoader(id: Int, bundle: Bundle?): Loader<List<String>> {
        return CreateClosedGroupLoader(this)
    }

    override fun onLoadFinished(loader: Loader<List<String>>, members: List<String>) {
        update(members)
    }

    override fun onLoaderReset(loader: Loader<List<String>>) {
        update(listOf())
    }

    private fun update(members: List<String>) {
        this.members = members
        mainContentContainer.visibility = if (members.isEmpty()) View.GONE else View.VISIBLE
        emptyStateContainer.visibility = if (members.isEmpty()) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }
    // endregion

    // region Interaction
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        when(id) {
            R.id.createClosedGroupButton -> createClosedGroup()
            else -> { /* Do nothing */ }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun createNewPrivateChat() {
        setResult(createNewPrivateChatResultCode)
        finish()
    }

    override fun onMemberClick(member: String) {
        createClosedGroupAdapter.onMemberClick(member)
    }

    private fun createClosedGroup() {
        if (ClosedGroupsProtocol.isSharedSenderKeysEnabled) {
            createSSKBasedClosedGroup()
        } else {
            createLegacyClosedGroup()
        }
    }

    private fun createSSKBasedClosedGroup() {
        val name = nameEditText.text.trim()
        if (name.isEmpty()) {
            return Toast.makeText(this, R.string.activity_create_closed_group_group_name_missing_error, Toast.LENGTH_LONG).show()
        }
        if (name.length >= 64) {
            return Toast.makeText(this, R.string.activity_create_closed_group_group_name_too_long_error, Toast.LENGTH_LONG).show()
        }
        val selectedMembers = this.selectedMembers
        if (selectedMembers.count() < 2) {
            return Toast.makeText(this, R.string.activity_create_closed_group_not_enough_group_members_error, Toast.LENGTH_LONG).show()
        }
        if (selectedMembers.count() > 49) { // Minus one because we're going to include self later
            return Toast.makeText(this, R.string.activity_create_closed_group_too_many_group_members_error, Toast.LENGTH_LONG).show()
        }
        val userPublicKey = TextSecurePreferences.getLocalNumber(this)
        val groupID = ClosedGroupsProtocol.createClosedGroup(this, name.toString(), selectedMembers + setOf( userPublicKey ))
        val threadID = DatabaseFactory.getThreadDatabase(this).getThreadIdFor(Recipient.from(this, Address.fromSerialized(groupID), false))
        openConversation(threadID, Recipient.from(this, Address.fromSerialized(groupID), false))
    }

    private fun createLegacyClosedGroup() {
        val name = nameEditText.text.trim()
        if (name.isEmpty()) {
            return Toast.makeText(this, R.string.activity_create_closed_group_group_name_missing_error, Toast.LENGTH_LONG).show()
        }
        if (name.length >= 64) {
            return Toast.makeText(this, R.string.activity_create_closed_group_group_name_too_long_error, Toast.LENGTH_LONG).show()
        }
        val selectedMembers = this.selectedMembers
        if (selectedMembers.count() < 2) {
            return Toast.makeText(this, R.string.activity_create_closed_group_not_enough_group_members_error, Toast.LENGTH_LONG).show()
        }
        if (selectedMembers.count() > 10) {
            return Toast.makeText(this, R.string.activity_create_closed_group_too_many_group_members_error, Toast.LENGTH_LONG).show()
        }
        val recipients = selectedMembers.map {
            Recipient.from(this, Address.fromSerialized(it), false)
        }.toSet()
        val masterHexEncodedPublicKey = TextSecurePreferences.getMasterHexEncodedPublicKey(this) ?: TextSecurePreferences.getLocalNumber(this)
        val admin = Recipient.from(this, Address.fromSerialized(masterHexEncodedPublicKey), false)
        CreateClosedGroupTask(WeakReference(this), null, name.toString(), recipients, setOf( admin )).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    private fun openConversation(threadId: Long, recipient: Recipient) {
        val intent = Intent(this, ConversationActivity::class.java)
        intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, threadId)
        intent.putExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, ThreadDatabase.DistributionTypes.DEFAULT)
        intent.putExtra(ConversationActivity.ADDRESS_EXTRA, recipient.address)
        startActivity(intent)
        finish()
    }
    // endregion

    // region Tasks
    internal class CreateClosedGroupTask(
            private val activity: WeakReference<CreateClosedGroupActivity>,
            private val profilePicture: Bitmap?,
            private val name: String?,
            private val members: Set<Recipient>,
            private val admins: Set<Recipient>
    ) : AsyncTask<Void, Void, Optional<GroupManager.GroupActionResult>>() {

        override fun doInBackground(vararg params: Void?): Optional<GroupManager.GroupActionResult> {
            val activity = activity.get() ?: return Optional.absent()
            return Optional.of(GroupManager.createGroup(activity, members, profilePicture, name, false, admins))
        }

        override fun onPostExecute(result: Optional<GroupManager.GroupActionResult>) {
            val activity = activity.get() ?: return super.onPostExecute(result)
            if (result.isPresent && result.get().threadId > -1) {
                if (!activity.isFinishing) {
                    activity.openConversation(result.get().threadId, result.get().groupRecipient)
                }
            } else {
                super.onPostExecute(result)
                Toast.makeText(activity.applicationContext, R.string.activity_create_closed_group_invalid_session_id_error, Toast.LENGTH_LONG).show()
            }
        }
    }
    // endregion
}