package org.thoughtcrime.securesms.loki.redesign

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_register.*
import network.loki.messenger.R
import org.thoughtcrime.securesms.BaseActionBarActivity
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.database.IdentityDatabase
import org.thoughtcrime.securesms.loki.redesign.utilities.setUpActionBarSessionLogo
import org.thoughtcrime.securesms.util.Base64
import org.thoughtcrime.securesms.util.Hex
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.curve25519.Curve25519
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.ecc.ECKeyPair
import org.whispersystems.libsignal.util.KeyHelper
import org.whispersystems.signalservice.loki.utilities.hexEncodedPublicKey
import java.io.File
import java.io.FileOutputStream

class RegisterActivity : BaseActionBarActivity() {
    private var seed: ByteArray? = null
    private var keyPair: ECKeyPair? = null
        set(value) { field = value; updatePublicKeyTextView() }

    // region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        setUpLanguageFileDirectory()
        setUpActionBarSessionLogo()
        registerButton.setOnClickListener { register() }
        copyButton.setOnClickListener { copyPublicKey() }
        val termsExplanation = SpannableStringBuilder("By using this service, you agree to our Terms and Conditions and Privacy Statement")
        termsExplanation.setSpan(StyleSpan(Typeface.BOLD), 40, 60, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        termsExplanation.setSpan(StyleSpan(Typeface.BOLD), 65, 82, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        termsButton.text = termsExplanation
        termsButton.setOnClickListener { showTerms() }
        updateKeyPair()
    }
    // endregion

    // region General
    private fun setUpLanguageFileDirectory() {
        val languages = listOf( "english", "japanese", "portuguese", "spanish" )
        val directory = File(applicationInfo.dataDir)
        for (language in languages) {
            val fileName = "$language.txt"
            if (directory.list().contains(fileName)) { continue }
            val inputStream = assets.open("mnemonic/$fileName")
            val file = File(directory, fileName)
            val outputStream = FileOutputStream(file)
            val buffer = ByteArray(1024)
            while (true) {
                val count = inputStream.read(buffer)
                if (count < 0) { break }
                outputStream.write(buffer, 0, count)
            }
            inputStream.close()
            outputStream.close()
        }
    }
    // endregion

    // region Updating
    private fun updateKeyPair() {
        val seedCandidate = Curve25519.getInstance(Curve25519.BEST).generateSeed(16)
        try {
            this.keyPair = Curve.generateKeyPair(seedCandidate + seedCandidate) // Validate the seed
        } catch (exception: Exception) {
            return updateKeyPair()
        }
        seed = seedCandidate
    }

    private fun updatePublicKeyTextView() {
        publicKeyTextView.text = keyPair!!.hexEncodedPublicKey
    }
    // endregion

    // region Interaction
    private fun register() {
        IdentityKeyUtil.save(this, IdentityKeyUtil.lokiSeedKey, Hex.toStringCondensed(seed))
        IdentityKeyUtil.save(this, IdentityKeyUtil.IDENTITY_PUBLIC_KEY_PREF, Base64.encodeBytes(keyPair!!.publicKey.serialize()))
        IdentityKeyUtil.save(this, IdentityKeyUtil.IDENTITY_PRIVATE_KEY_PREF, Base64.encodeBytes(keyPair!!.privateKey.serialize()))
        val userHexEncodedPublicKey = keyPair!!.hexEncodedPublicKey
        val registrationID = KeyHelper.generateRegistrationId(false)
        TextSecurePreferences.setLocalRegistrationId(this, registrationID)
        DatabaseFactory.getIdentityDatabase(this).saveIdentity(Address.fromSerialized(userHexEncodedPublicKey),
            IdentityKeyUtil.getIdentityKeyPair(this).publicKey, IdentityDatabase.VerifiedStatus.VERIFIED,
            true, System.currentTimeMillis(), true)
        TextSecurePreferences.setLocalNumber(this, userHexEncodedPublicKey)
        val intent = Intent(this, DisplayNameActivity::class.java)
        startActivity(intent)
    }

    private fun copyPublicKey() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Session ID", keyPair!!.hexEncodedPublicKey)
        clipboard.primaryClip = clip
        Toast.makeText(this, R.string.activity_register_public_key_copied_message, Toast.LENGTH_SHORT).show()
    }

    private fun showTerms() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/loki-project/loki-messenger-android/blob/master/privacy-policy.md"))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open link", Toast.LENGTH_SHORT).show()
        }
    }
    // endregion
}