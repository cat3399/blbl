package blbl.cat3399.core.io

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** Single-use file access through a system picker or a compatible file manager. */
class OpenFileContract(
    private val mimeTypes: Array<String>,
) : ActivityResultContract<OpenFileContract.Action, OpenFileContract.Result>() {
    enum class Action(val intentAction: String) {
        OPEN_DOCUMENT(Intent.ACTION_OPEN_DOCUMENT),
        GET_CONTENT(Intent.ACTION_GET_CONTENT),
    }

    sealed interface Result {
        data class Selected(val uri: Uri) : Result
        data object Cancelled : Result
        data object MissingUri : Result
    }

    override fun createIntent(context: Context, input: Action): Intent {
        // Multiple MIME types accommodate file managers which label JSON as text or binary data.
        val intent =
            Intent(input.intentAction)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)

        // Matching <queries> entries are required for this lookup on Android 11+.
        // EXTRA_MIME_TYPES is a picker hint, not an intent-resolution filter: querying */* alone
        // also finds photo-only pickers. Query each accepted type and dispatch only to that union.
        val packageManager = context.packageManager
        val handlers =
            mimeTypes.flatMap { mimeType ->
                packageManager.queryIntentActivities(Intent(intent).setType(mimeType), PackageManager.MATCH_DEFAULT_ONLY)
            }
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                .distinct()
        // AOSP TV can advertise a placeholder which only reports that no app can open the file.
        // Exclude that platform stub, irrespective of the device manufacturer or Android version.
        val pickers = handlers.filterNot { it.packageName == "com.android.tv.frameworkpackagestubs" }
        if (pickers.isEmpty()) {
            throw ActivityNotFoundException("No file picker for ${input.intentAction}; handlers=$handlers")
        }

        val targeted = pickers.map { Intent(intent).setComponent(it) }
        if (targeted.size == 1) return targeted.single()

        // Honor an existing default only when it can actually supply one of the accepted types.
        val defaultActivity = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val defaultPicker = defaultActivity?.let { ComponentName(it.packageName, it.name) }
        if (defaultPicker in pickers) return intent.setComponent(defaultPicker)
        return Intent.createChooser(targeted.first(), null)
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, targeted.drop(1).toTypedArray())
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Result {
        if (resultCode != Activity.RESULT_OK) return Result.Cancelled
        val uri =
            intent?.data
                ?: intent?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
                ?: return Result.MissingUri
        return Result.Selected(uri)
    }
}
