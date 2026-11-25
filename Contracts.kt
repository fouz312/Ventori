package com.vantora.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/** ACTION_OPEN_DOCUMENT with the persistable-permission flag so access survives reboots. */
class OpenDocs : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}
