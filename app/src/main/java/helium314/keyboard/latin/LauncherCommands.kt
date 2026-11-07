// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import helium314.keyboard.latin.utils.Log

/**
 * The commands of the launcher mode, typed or spoken, recognized by rules: "abre o Spotify", "liga à Ema",
 * "manda mensagem à Marta pelo WhatsApp a dizer que chego às 8". Anything else returns null.
 */
object LauncherCommands {
    const val OPEN_APP = "open_app"
    const val CALL_CONTACT = "call_contact"
    const val SEND_MESSAGE = "send_message"

    /** a recognized command with its arguments (app, contact, text, whatsapp) */
    class Command(val action: String, val arguments: Map<String, Any?>)

    private val options = setOf(RegexOption.IGNORE_CASE)
    private const val ARTICLE = """(?:(?:o|a|os|as)\s+)?"""

    private val openApp = Regex("""^(?:abre|abrir|abra|abre-me|lança|lançar|inicia|iniciar|mostra|mostra-me|vai\s+(?:para|ao|à|a))\s+$ARTICLE(?:(?:app|aplicação)\s+(?:do|da|de)?\s*)?(.+)$""", options)

    private val callContact = Regex("""^(?:liga|ligar|liga-me|telefona|telefonar|chama|chamar)\s+(?:(?:para|a|à|ao|aos|às)\s+)?$ARTICLE(.+)$""", options)

    private val sendMessage = Regex("""^(?:manda|mandar|envia|enviar|escreve|escrever)(?:-lhe)?\s+(?:uma\s+)?(?:mensagem|sms|msg)?\s*(?:pelo\s+whatsapp\s+)?(?:a|à|ao|para)\s+$ARTICLE(\p{L}+)(?:\s+pelo\s+whatsapp)?\s*(?:,|:|a\s+dizer(?:\s+que)?|dizendo(?:\s+que)?|que)\s+(.+)$""", options)

    fun parse(sentence: String): Command? {
        val s = sentence.trim().trimEnd('.', '!', '?').trim()
        sendMessage.find(s)?.let {
            return Command(SEND_MESSAGE, mapOf("contact" to it.groupValues[1],
                "text" to it.groupValues[2], "whatsapp" to s.contains("whatsapp", ignoreCase = true)))
        }
        callContact.find(s)?.let {
            return Command(CALL_CONTACT, mapOf("contact" to it.groupValues[1]))
        }
        openApp.find(s)?.let {
            return Command(OPEN_APP, mapOf("app" to it.groupValues[1]))
        }
        return null
    }

    /** a contact with a phone number whose name matches, as name and number, or null */
    fun findContact(context: Context, name: String): Pair<String, String>? = try {
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name)),
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
    } catch (e: SecurityException) {
        Log.w("launcher-commands", "no permission to read contacts", e)
        null
    }
}
