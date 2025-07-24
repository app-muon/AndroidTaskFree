import android.content.Context
import android.util.Log
import com.taskfree.app.data.AppDatabaseFactory
import com.taskfree.app.ui.enc.MnemonicManager

private fun validatePhraseAndRestoreDatabase(context: Context, phrase: List<String>): Boolean {
    return try {
        // Validate against the stored hash
        if (MnemonicManager.isPhraseValid(context, phrase)) {
            // Restore the phrase and derive key
            MnemonicManager.storePhrase(context, phrase)

            // Clear existing database instance to force recreation with encryption
            AppDatabaseFactory.clearInstance()

            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.e("PhraseEntry", "Phrase validation failed", e)
        false
    }
}