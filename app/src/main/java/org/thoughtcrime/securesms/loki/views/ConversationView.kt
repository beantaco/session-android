package org.thoughtcrime.securesms.loki.views

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import kotlinx.android.synthetic.main.view_conversation.view.*
import network.loki.messenger.R
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.loki.utilities.MentionManagerUtilities.populateUserPublicKeyCacheIfNeeded
import org.thoughtcrime.securesms.loki.utilities.MentionUtilities.highlightMentions
import org.thoughtcrime.securesms.mms.GlideRequests
import org.thoughtcrime.securesms.util.DateUtils
import java.util.*

class ConversationView : LinearLayout {
    var thread: ThreadRecord? = null

    // region Lifecycle
    constructor(context: Context) : super(context) {
        setUpViewHierarchy()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        setUpViewHierarchy()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        setUpViewHierarchy()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        setUpViewHierarchy()
    }

    private fun setUpViewHierarchy() {
        LayoutInflater.from(context)
                .inflate(R.layout.view_conversation, this)
    }
    // endregion

    // region Updating
    fun bind(thread: ThreadRecord, isTyping: Boolean, glide: GlideRequests) {
        this.thread = thread
        populateUserPublicKeyCacheIfNeeded(thread.threadId, context) // FIXME: This is a bad place to do this
        if (thread.recipient.isBlocked) {
            accentView.setBackgroundResource(R.color.destructive)
            accentView.visibility = View.VISIBLE
        } else {
            accentView.setBackgroundResource(R.color.accent)
            accentView.visibility = if (thread.unreadCount > 0) View.VISIBLE else View.INVISIBLE
        }
        profilePictureView.glide = glide
        profilePictureView.update(thread.recipient, thread.threadId)
        val senderDisplayName = if (thread.recipient.isLocalNumber) context.getString(R.string.note_to_self) else if (!thread.recipient.name.isNullOrEmpty()) thread.recipient.name else thread.recipient.address.toString()
        btnGroupNameDisplay.text = senderDisplayName
        timestampTextView.text = DateUtils.getBriefRelativeTimeSpanString(context, Locale.getDefault(), thread.date)
        muteIndicatorImageView.visibility = if (thread.recipient.isMuted) VISIBLE else GONE
        val rawSnippet = thread.getDisplayBody(context)
        val snippet = highlightMentions(rawSnippet, thread.threadId, context)
        snippetTextView.text = snippet
        snippetTextView.typeface = if (thread.unreadCount > 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        snippetTextView.visibility = if (isTyping) View.GONE else View.VISIBLE
        if (isTyping) {
            typingIndicatorView.startAnimation()
        } else {
            typingIndicatorView.stopAnimation()
        }
        typingIndicatorView.visibility = if (isTyping) View.VISIBLE else View.GONE
        statusIndicatorImageView.visibility = View.VISIBLE
        when {
            !thread.isOutgoing || thread.isVerificationStatusChange -> statusIndicatorImageView.visibility = View.GONE
            thread.isFailed -> statusIndicatorImageView.setImageResource(R.drawable.ic_error)
            thread.isPending -> statusIndicatorImageView.setImageResource(R.drawable.ic_circle_dot_dot_dot)
            thread.isRemoteRead -> statusIndicatorImageView.setImageResource(R.drawable.ic_filled_circle_check)
            else -> statusIndicatorImageView.setImageResource(R.drawable.ic_circle_check)
        }
    }

    fun recycle() {
        profilePictureView.recycle()
    }

    private fun getUserDisplayName(publicKey: String?): String? {
        if (TextUtils.isEmpty(publicKey)) return null
        return DatabaseFactory.getLokiUserDatabase(context).getDisplayName(publicKey!!)
    }
    // endregion
}