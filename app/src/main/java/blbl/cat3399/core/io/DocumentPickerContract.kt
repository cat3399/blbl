package blbl.cat3399.core.io

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** The same capability and result rules apply to opening a file and choosing an export target. */
class DocumentPickerContract : ActivityResultContract<DocumentPickerContract.Request, DocumentPickerContract.Result>() {
    sealed interface Request {
        data class Open(val action: OpenAction, val mimeTypes: List<String>) : Request
        data class Create(val mimeType: String, val fileName: String) : Request
    }

    enum class OpenAction(val intentAction: String) {
        OPEN_DOCUMENT(Intent.ACTION_OPEN_DOCUMENT),
        GET_CONTENT(Intent.ACTION_GET_CONTENT),
    }

    sealed interface Result {
        data class Selected(val uri: Uri) : Result
        data object Cancelled : Result
        data object MissingUri : Result
        data class Failed(val resultCode: Int) : Result
    }

    override fun createIntent(context: Context, input: Request): Intent {
        val mimeTypes = when (input) {
            is Request.Open -> input.mimeTypes
            is Request.Create -> listOf(input.mimeType)
        }
        require(mimeTypes.isNotEmpty() && mimeTypes.none { it.isBlank() }) { "A document MIME type is required" }
        val intent = when (input) {
            is Request.Open -> Intent(input.action.intentAction)
                .setType(mimeTypes.singleOrNull() ?: "*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            is Request.Create -> Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType(input.mimeType)
                .putExtra(Intent.EXTRA_TITLE, input.fileName)
        }.addCategory(Intent.CATEGORY_OPENABLE)

        // Matching <queries> entries are required for these lookups on Android 11+.
        // EXTRA_MIME_TYPES is a picker hint, not an intent-resolution filter: querying */* alone
        // also finds photo-only pickers. Dispatch only to the union of the accepted MIME types.
        val packageManager = context.packageManager
        val handlers = mimeTypes.flatMap { mimeType ->
            packageManager.queryIntentActivities(Intent(intent).setType(mimeType), PackageManager.MATCH_DEFAULT_ONLY)
        }.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.distinct()

        // The AOSP TV placeholder advertises OPEN/GET/CREATE but cannot select or save a file.
        val pickers = handlers.filterNot { it.packageName == "com.android.tv.frameworkpackagestubs" }
        if (pickers.isEmpty()) {
            throw ActivityNotFoundException("No document picker for ${intent.action}; mimeTypes=$mimeTypes handlers=$handlers")
        }
        if (pickers.size == 1) return intent.setComponent(pickers.single())

        val defaultActivity = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val defaultPicker = defaultActivity?.let { ComponentName(it.packageName, it.name) }
        if (defaultPicker in pickers) return intent.setComponent(defaultPicker)

        val targeted = pickers.map { Intent(intent).setComponent(it) }
        return Intent.createChooser(targeted.first(), null)
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, targeted.drop(1).toTypedArray())
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Result {
        if (resultCode == Activity.RESULT_CANCELED) return Result.Cancelled
        if (resultCode != Activity.RESULT_OK) return Result.Failed(resultCode)
        val uri = intent?.data
            ?: intent?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
            ?: return Result.MissingUri
        return Result.Selected(uri)
    }
}
