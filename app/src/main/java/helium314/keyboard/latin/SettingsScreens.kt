// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Intent
import android.provider.Settings
import java.text.Normalizer

/**
 * A screen of the system's Settings opened by a public action, for the search of the launcher mode: its name in
 * Portuguese and in English, and other words it is looked for by. Android keeps the index of its own Settings search to
 * itself, so this table stands for it.
 */
class SettingsScreen(val id: String, val action: String, val pt: String, val en: String, synonyms: List<String> = emptyList(),
                     /** a preference of the screen to show highlighted, as the Settings search does with its results */
                     val highlight: String? = null) {
    /** the names and the other words, without accents and in lower case */
    val words = (listOf(pt, en) + synonyms).map { normalize(it) }

    fun intent(): Intent = Intent(action).apply { highlight?.let { putExtra(FRAGMENT_ARG_KEY, it) } }

    /**
     * How well [q] (without accents, lower case) names this screen, as the apps are ranked: 0 when a name or word starts
     * with it, 1 when a word of one does, 2 when one has it inside (3 letters or more); null when none.
     */
    fun rank(q: String): Int? = when {
        q.isEmpty() -> null
        words.any { it.startsWith(q) } -> 0
        words.any { name -> name.split(' ', '-', '&').any { it.startsWith(q) } } -> 1
        q.length >= 3 && words.any { it.contains(q) } -> 2
        else -> null
    }

    companion object {
        /** the extra with which the Settings highlight one of their preferences (SettingsActivity.EXTRA_FRAGMENT_ARG_KEY) */
        private const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"

        fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "")

        val ALL = listOf(
            SettingsScreen("wifi", Settings.ACTION_WIFI_SETTINGS, "Wi-Fi", "Wi-Fi", listOf("wifi", "wireless", "rede sem fios")),
            SettingsScreen("bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth", "Bluetooth", listOf("bt")),
            SettingsScreen("nfc", Settings.ACTION_NFC_SETTINGS, "NFC", "NFC"),
            SettingsScreen("nfc_payment", Settings.ACTION_NFC_PAYMENT_SETTINGS, "Pagamentos sem contacto", "Contactless payments",
                listOf("nfc", "pagamentos", "carteira")),
            SettingsScreen("network", Settings.ACTION_WIRELESS_SETTINGS, "Rede e Internet", "Network & internet",
                listOf("rede", "internet")),
            SettingsScreen("mobile", Settings.ACTION_NETWORK_OPERATOR_SETTINGS, "Rede móvel", "Mobile network",
                listOf("dados moveis", "mobile data", "operadora", "sim")),
            SettingsScreen("data_usage", Settings.ACTION_DATA_USAGE_SETTINGS, "Utilização de dados", "Data usage", listOf("dados")),
            SettingsScreen("roaming", Settings.ACTION_DATA_ROAMING_SETTINGS, "Roaming", "Roaming"),
            SettingsScreen("apn", Settings.ACTION_APN_SETTINGS, "Nomes de pontos de acesso", "Access point names", listOf("apn")),
            SettingsScreen("airplane", Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Modo de voo", "Airplane mode", listOf("aviao", "voo")),
            SettingsScreen("vpn", Settings.ACTION_VPN_SETTINGS, "VPN", "VPN"),
            SettingsScreen("display", Settings.ACTION_DISPLAY_SETTINGS, "Ecrã", "Display", listOf("brilho", "brightness", "ecra")),
            SettingsScreen("night_light", Settings.ACTION_NIGHT_DISPLAY_SETTINGS, "Luz noturna", "Night Light"),
            SettingsScreen("screen_saver", Settings.ACTION_DREAM_SETTINGS, "Proteção de ecrã", "Screen saver"),
            SettingsScreen("sound", Settings.ACTION_SOUND_SETTINGS, "Som e vibração", "Sound & vibration",
                listOf("volume", "toque", "ringtone", "vibracao")),
            SettingsScreen("dnd", Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, "Não incomodar", "Do Not Disturb", listOf("dnd", "silencio")),
            SettingsScreen("battery", Intent.ACTION_POWER_USAGE_SUMMARY, "Bateria", "Battery"),
            SettingsScreen("battery_saver", Settings.ACTION_BATTERY_SAVER_SETTINGS, "Poupança de bateria", "Battery Saver",
                listOf("bateria", "battery")),
            SettingsScreen("battery_optimization", Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, "Otimização da bateria",
                "Battery optimization", listOf("bateria", "battery")),
            SettingsScreen("location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Localização", "Location", listOf("gps")),
            SettingsScreen("security", Settings.ACTION_SECURITY_SETTINGS, "Segurança", "Security", listOf("bloqueio", "lock")),
            SettingsScreen("privacy", Settings.ACTION_PRIVACY_SETTINGS, "Privacidade", "Privacy", listOf("permissoes", "permissions")),
            SettingsScreen("accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, "Acessibilidade", "Accessibility"),
            SettingsScreen("captions", Settings.ACTION_CAPTIONING_SETTINGS, "Legendas", "Captions"),
            SettingsScreen("notifications", Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS, "Notificações", "Notifications"),
            SettingsScreen("notification_access", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, "Acesso às notificações",
                "Notification access", listOf("notificacoes", "notifications")),
            SettingsScreen("apps", Settings.ACTION_APPLICATION_SETTINGS, "Apps", "Apps", listOf("aplicacoes")),
            SettingsScreen("all_apps", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, "Todas as apps", "All apps",
                listOf("aplicacoes", "apps")),
            SettingsScreen("default_apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "Apps predefinidas", "Default apps",
                listOf("aplicacoes", "apps")),
            SettingsScreen("home_app", Settings.ACTION_HOME_SETTINGS, "App do ecrã principal", "Home app", listOf("launcher")),
            SettingsScreen("usage_access", Settings.ACTION_USAGE_ACCESS_SETTINGS, "Acesso à utilização", "Usage access"),
            SettingsScreen("overlay", Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "Mostrar sobre outras apps", "Display over other apps"),
            SettingsScreen("write_settings", Settings.ACTION_MANAGE_WRITE_SETTINGS, "Modificar definições do sistema",
                "Modify system settings"),
            SettingsScreen("unknown_apps", Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "Instalar apps desconhecidas",
                "Install unknown apps", listOf("apk")),
            SettingsScreen("storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "Armazenamento", "Storage", listOf("espaco")),
            SettingsScreen("developer", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "Opções de programador", "Developer options",
                listOf("programador", "developer", "dev")),
            SettingsScreen("wireless_debugging", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "Depuração sem fios",
                "Wireless debugging", listOf("adb", "debug", "depuracao"),
                // the key of the preference in AOSP's development_settings.xml
                highlight = "toggle_adb_wireless"),
            SettingsScreen("date", Settings.ACTION_DATE_SETTINGS, "Data e hora", "Date & time", listOf("hora", "time", "fuso")),
            SettingsScreen("languages", Settings.ACTION_LOCALE_SETTINGS, "Idiomas", "Languages", listOf("lingua", "language")),
            SettingsScreen("keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS, "Teclado", "Keyboard",
                listOf("teclados", "metodos de introducao", "input")),
            SettingsScreen("physical_keyboard", Settings.ACTION_HARD_KEYBOARD_SETTINGS, "Teclado físico", "Physical keyboard"),
            SettingsScreen("dictionary", Settings.ACTION_USER_DICTIONARY_SETTINGS, "Dicionário pessoal", "Personal dictionary"),
            SettingsScreen("voice_input", Settings.ACTION_VOICE_INPUT_SETTINGS, "Entrada de voz", "Voice input"),
            SettingsScreen("accounts", Settings.ACTION_SYNC_SETTINGS, "Contas", "Accounts", listOf("sincronizacao", "sync")),
            SettingsScreen("add_account", Settings.ACTION_ADD_ACCOUNT, "Adicionar conta", "Add account", listOf("contas")),
            SettingsScreen("printing", Settings.ACTION_PRINT_SETTINGS, "Impressão", "Printing", listOf("imprimir", "print")),
            SettingsScreen("cast", Settings.ACTION_CAST_SETTINGS, "Transmitir", "Cast", listOf("chromecast")),
            SettingsScreen("wallet", Settings.ACTION_QUICK_ACCESS_WALLET_SETTINGS, "Carteira", "Wallet"),
            SettingsScreen("about", Settings.ACTION_DEVICE_INFO_SETTINGS, "Acerca do telemóvel", "About phone",
                listOf("sobre", "versao", "android")),
        )
    }
}
