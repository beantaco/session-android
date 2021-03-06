package org.thoughtcrime.securesms.loki.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor

import org.thoughtcrime.securesms.database.Database
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper
import org.thoughtcrime.securesms.loki.utilities.*

import org.session.libsession.messaging.threads.Address
import org.session.libsession.messaging.threads.recipients.Recipient
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.service.loki.api.opengroups.PublicChat

import org.session.libsignal.utilities.JsonUtil
import org.session.libsignal.service.loki.database.LokiThreadDatabaseProtocol
import org.session.libsignal.service.loki.utilities.PublicKeyValidation

class LokiThreadDatabase(context: Context, helper: SQLCipherOpenHelper) : Database(context, helper), LokiThreadDatabaseProtocol {

    companion object {
        private val sessionResetTable = "loki_thread_session_reset_database"
        val publicChatTable = "loki_public_chat_database"
        val threadID = "thread_id"
        private val friendRequestStatus = "friend_request_status"
        private val sessionResetStatus = "session_reset_status"
        val publicChat = "public_chat"
        @JvmStatic val createSessionResetTableCommand = "CREATE TABLE $sessionResetTable ($threadID INTEGER PRIMARY KEY, $sessionResetStatus INTEGER DEFAULT 0);"
        @JvmStatic val createPublicChatTableCommand = "CREATE TABLE $publicChatTable ($threadID INTEGER PRIMARY KEY, $publicChat TEXT);"
    }

    override fun getThreadID(hexEncodedPublicKey: String): Long {
        val address = Address.fromSerialized(hexEncodedPublicKey)
        val recipient = Recipient.from(context, address, false)
        return DatabaseFactory.getThreadDatabase(context).getOrCreateThreadIdFor(recipient)
    }

    fun getAllPublicChats(): Map<Long, PublicChat> {
        val database = databaseHelper.readableDatabase
        var cursor: Cursor? = null
        val result = mutableMapOf<Long, PublicChat>()
        try {
            cursor = database.rawQuery("select * from $publicChatTable", null)
            while (cursor != null && cursor.moveToNext()) {
                val threadID = cursor.getLong(threadID)
                val string = cursor.getString(publicChat)
                val publicChat = PublicChat.fromJSON(string)
                if (publicChat != null) { result[threadID] = publicChat }
            }
        } catch (e: Exception) {
            // Do nothing
        }  finally {
            cursor?.close()
        }
        return result
    }

    fun getAllPublicChatServers(): Set<String> {
        return getAllPublicChats().values.fold(setOf()) { set, chat -> set.plus(chat.server) }
    }

    override fun getPublicChat(threadID: Long): PublicChat? {
        if (threadID < 0) { return null }
        val database = databaseHelper.readableDatabase
        return database.get(publicChatTable, "${Companion.threadID} = ?", arrayOf( threadID.toString() )) { cursor ->
            val publicChatAsJSON = cursor.getString(publicChat)
            PublicChat.fromJSON(publicChatAsJSON)
        }
    }

    override fun setPublicChat(publicChat: PublicChat, threadID: Long) {
        if (threadID < 0) { return }
        val database = databaseHelper.writableDatabase
        val contentValues = ContentValues(2)
        contentValues.put(Companion.threadID, threadID)
        contentValues.put(Companion.publicChat, JsonUtil.toJson(publicChat.toJSON()))
        database.insertOrUpdate(publicChatTable, contentValues, "${Companion.threadID} = ?", arrayOf( threadID.toString() ))
    }

    override fun removePublicChat(threadID: Long) {
        databaseHelper.writableDatabase.delete(publicChatTable, "${Companion.threadID} = ?", arrayOf( threadID.toString() ))
    }
}