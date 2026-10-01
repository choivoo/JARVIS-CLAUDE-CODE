package com.jarvis.assistant.command

import android.content.Context
import android.provider.ContactsContract
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactResolver(private val context: Context) {
    sealed interface Result {
        data class Found(val name: String, val number: String) : Result
        data object NotFound : Result
        data object NoPermission : Result
    }

    /** Looks a contact up by (partial) display name. Needs the optional READ_CONTACTS permission. */
    suspend fun find(name: String): Result {
        if (!Perms.hasContacts(context)) return Result.NoPermission
        return withContext(Dispatchers.IO) {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%${name.trim()}%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { c ->
                if (c.moveToFirst()) Result.Found(c.getString(0).orEmpty(), c.getString(1).orEmpty()) else Result.NotFound
            } ?: Result.NotFound
        }
    }
}
