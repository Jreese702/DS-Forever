package com.jrees.wowcompanion

import android.content.Context
import java.util.Locale

/**
 * DS FOREVER IN SPANISH AND FRENCH (the user, 2026-11-07: "we need to work on some other language support translations for the app
 * starting with Spanish and french"; picked "Start screen + login first", the app following the Thor's language with an override in
 * Settings). The app draws its own text (no Android string resources), so the English words ARE the keys: [t] gives the words in the
 * language in use, or the English when there is no translation yet (a screen not translated stays whole, in English).
 * Words with a value inside ("Updating the game to 1.60.1.70400") are [TEMPLATES]: "%s" stands for the value, carried over as it is.
 * Translated where the text is DRAWN (the kit's buttons and outlined lines, the start screen's pop-ups, the chooser, the login prompt),
 * so the app's logic and its tests keep the English.
 */
object Tr {
    data class Lang(val code: String, val label: String)

    /** the Settings row's choices: Automatic follows the Thor's language */
    val LANGS = listOf(Lang("auto", "Automatic"), Lang("en", "English"), Lang("es", "Español"), Lang("fr", "Français"))

    private const val PREFS = "dsf_lang"
    private const val KEY = "lang"
    @Volatile private var choice: String = "auto"

    /** read the saved choice once (the app's start), and the whole app's words (assets companion/i18n/<lang>.json) */
    fun init(ctx: Context) {
        choice = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "auto") }.getOrNull() ?: "auto"
        for (l in listOf("es", "fr")) runCatching {
            ctx.assets.open("companion/i18n/$l.json").use { load(l, it.readBytes().toString(Charsets.UTF_8)) }
        }
    }

    // THE REST OF THE APP (the user, 2026-11-07: "Translate the rest"): every room's words, from the assets' JSON
    // ({"plain": {en: words}, "templates": {"en with %s": "words with %s"}}); the start screen's own words above win over them
    private val filePlain = HashMap<String, HashMap<String, String>>()
    private val fileTemplates = HashMap<String, List<Pair<Regex, String>>>()

    /** [lang]'s words from a JSON text (the assets' file, or the test's) */
    fun load(lang: String, json: String) {
        val o = org.json.JSONObject(json)
        val plain = HashMap<String, String>()
        o.optJSONObject("plain")?.let { p -> for (k in p.keys()) plain[k] = p.getString(k) }
        val tm = ArrayList<Pair<Regex, String>>()
        o.optJSONObject("templates")?.let { t ->
            for (k in t.keys()) tm += Regex(k.split("%s").joinToString("(.+?)") { Regex.escape(it) }) to t.getString(k)
        }
        synchronized(this) { filePlain[lang] = plain; fileTemplates[lang] = tm; cache.clear() }
    }

    /** what [t] answered, per language, by the words: drawing asks every frame, so a lookup makes nothing new (bounded) */
    private val cache = HashMap<String, HashMap<String, String>>()
    private const val CACHE_MAX = 6000

    fun setChoice(ctx: Context, code: String) {
        if (LANGS.none { it.code == code }) return
        choice = code
        synchronized(this) { cache.clear() }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    fun choice(): String = choice

    /** the language in use: the choice, else the Thor's ("es" / "fr"), else English (remembered per device locale: asked per draw) */
    @Volatile private var memoLocale: Locale? = null
    @Volatile private var memoChoice: String? = null
    @Volatile private var memoLang = "en"
    fun lang(): String {
        val loc = Locale.getDefault(); val c = choice
        if (loc === memoLocale && c == memoChoice) return memoLang
        val l = when (c) {
            "auto" -> loc.language.lowercase(Locale.ROOT).takeIf { it == "es" || it == "fr" } ?: "en"
            else -> c
        }
        memoLocale = loc; memoChoice = c; memoLang = l
        return l
    }

    /** [s] in the language in use (the English when there is none) */
    fun t(s: String): String = t(s, lang())

    /** any text a view draws (a CharSequence too) */
    fun t(s: CharSequence): String = t(s.toString(), lang())

    fun t(s: String, lang: String): String {
        if (lang == "en" || s.length < 2) return s
        synchronized(this) { cache[lang]?.get(s)?.let { return it } }
        val r = look(s, lang)
        synchronized(this) {
            val m = cache.getOrPut(lang) { HashMap() }
            if (m.size >= CACHE_MAX) m.clear()
            m[s] = r
        }
        return r
    }

    private fun look(s: String, lang: String): String {
        val words = when (lang) { "es" -> ES; "fr" -> FR; else -> return s }
        words[s]?.let { return it }
        filePlain[lang]?.get(s)?.let { return it }
        for ((re, keys) in compiled) {
            val m = re.matchEntire(s) ?: continue
            val out = keys[lang] ?: continue
            return fill(out, m.groupValues.drop(1))
        }
        fileTemplates[lang]?.let { list ->
            for ((re, out) in list) {
                val m = re.matchEntire(s) ?: continue
                return fill(out, m.groupValues.drop(1))
            }
        }
        return s
    }

    private fun fill(out: String, values: List<String>): String {
        var r = out
        for (g in values) r = r.replaceFirst("%s", g)        // (Kotlin: a literal replace)
        return r
    }

    // ---------------------------------------------------------------- the words

    private val ES: Map<String, String> = mapOf(
        // the start screen and its pop-ups (SetupFlow)
        "Play" to "Jugar", "Report a problem" to "Informar de un problema", "Ready" to "Listo",
        "Loading." to "Cargando.", "Loading.." to "Cargando..", "Loading..." to "Cargando...",
        "Waiting for the game." to "Esperando al juego.",
        "Updating the game" to "Actualizando el juego", "Getting ready to update" to "Preparando la actualización",
        "Update starts when the launch is done" to "La actualización empezará cuando termine el inicio",
        "Already updating" to "Ya se está actualizando", "Update needs access to the game folder" to "La actualización necesita acceso a la carpeta del juego",
        "Checking the game's version - press Update again in a moment" to "Comprobando la versión del juego: pulsa Actualizar de nuevo en un momento",
        "Installing needs 60 GB free" to "La instalación necesita 60 GB libres", "Installing needs Wi-Fi" to "La instalación necesita wifi",
        "Restarting the game to sign in…" to "Reiniciando el juego para iniciar sesión…",
        "Open app settings" to "Abrir ajustes de la app", "Move DS Forever to internal storage" to "Mueve DS Forever al almacenamiento interno",
        "It is installed on the SD card, where the game's container can't be made. Settings > Apps > DS Forever > Storage > Change > Internal." to
            "Está instalado en la tarjeta SD, donde no se puede crear el contenedor del juego. Ajustes > Aplicaciones > DS Forever > Almacenamiento > Cambiar > Interno.",
        "Where is WoW Forever?" to "¿Dónde está WoW Forever?",
        "Install it (250 MB now, up to 50 GB as you play), or choose the folder with .build.info and Data." to
            "Instálalo (250 MB ahora, hasta 50 GB mientras juegas) o elige la carpeta con .build.info y Data.",
        "Choose folder" to "Elegir carpeta", "Install directly" to "Instalar directamente",
        "Your game folder is out of date" to "Tu carpeta del juego está desactualizada", "OK" to "Aceptar",
        "Storage is low" to "Queda poco almacenamiento", "Allow file access" to "Permitir acceso a archivos",
        "Android needs \"All files access\" so the game and DS Forever can read the game folder." to
            "Android necesita «Acceso a todos los archivos» para que el juego y DS Forever puedan leer la carpeta del juego.",
        "Open settings" to "Abrir ajustes",
        "The game art couldn't be built" to "No se pudo preparar el arte del juego", "The game files could not be read." to "No se pudieron leer los archivos del juego.",
        "Try again" to "Reintentar", "Update now, or later from this screen." to "Actualiza ahora o más tarde desde esta pantalla.",
        "Update" to "Actualizar", "Later" to "Más tarde",
        "Open the issue to see it." to "Abre la incidencia para verla.",
        "It is in Documents/DS Forever/reports. Open the GitHub issue to post it." to "Está en Documents/DS Forever/reports. Abre la incidencia de GitHub para publicarlo.",
        "Open GitHub issue" to "Abrir incidencia de GitHub", "Dismiss" to "Descartar",
        "Your Battle.net login" to "Tu cuenta de Battle.net",
        "Save it once and the game signs you in by itself. It stays encrypted on this device." to
            "Guárdala una vez y el juego iniciará sesión solo. Se guarda cifrada en este dispositivo.",
        "Enter login" to "Introducir cuenta", "Play without" to "Jugar sin cuenta",
        "Sign-in didn't work" to "No se pudo iniciar sesión", "The game didn't restart" to "El juego no se reinició",
        "Type it on the game screen" to "Escríbelo en la pantalla del juego", "Enter your authenticator code" to "Introduce tu código del autenticador",
        "Type your authenticator code (or password) on the game screen" to "Escribe tu código del autenticador (o tu contraseña) en la pantalla del juego",
        "Keyboard" to "Teclado", "Restart the game to sign in" to "Reiniciar el juego para iniciar sesión",
        // the waiting screen, the chooser, the menu
        "Menu" to "Menú", "Login" to "Iniciar sesión", "Battle.net login" to "Cuenta de Battle.net",
        "How do you want to play?" to "¿Cómo quieres jugar?", "Shortcuts" to "Accesos directos", "Add a shortcut" to "Añadir un acceso directo",
        "Back" to "Atrás", "Local" to "Local", "Streaming" to "Streaming",
        "WoW Forever runs on this device." to "WoW Forever se ejecuta en este dispositivo.",
        "WoW runs on your PC. DS Forever Desktop streams it here." to "WoW se ejecuta en tu PC. DS Forever Desktop lo transmite aquí.",
        "Connect to DS Forever Desktop. Type the code it shows on your PC on the top screen." to
            "Conéctate a DS Forever Desktop. Escribe en la pantalla superior el código que aparece en tu PC.",
        "Back to the chooser" to "Volver a la elección",
        "Auto login" to "Inicio automático", "Change login" to "Cambiar cuenta", "Tap again" to "Toca otra vez", "Forget login" to "Olvidar cuenta",
        "Stream settings" to "Ajustes de streaming", "Restart game" to "Reiniciar el juego", "Report issue" to "Informar de un problema",
        "Switch mode" to "Cambiar de modo", "Exit game" to "Salir del juego",
        // the login prompt
        "Email" to "Correo electrónico", "Password" to "Contraseña", "Show" to "Mostrar", "Hide" to "Ocultar",
        "Kept on this device only, encrypted." to "Solo en este dispositivo, cifrada.", "Save and log in" to "Guardar e iniciar sesión",
        "Cancel" to "Cancelar", "Region" to "Región", "Korea" to "Corea", "Taiwan" to "Taiwán",
        "The login could not be saved on this device" to "No se pudo guardar la cuenta en este dispositivo",
        // the trade window (i139)
        "Choose an item to enchant" to "Elige un objeto para encantar", "Equipped" to "Equipado", "In your bags" to "En tus bolsas",
        "No soulbound items" to "No hay objetos ligados", "Trade" to "Comerciar", "Accepted" to "Aceptado", "Will not be traded" to "No se comerciará",
        "Backpack" to "Mochila", "You" to "Tú",
        // Settings > System > Language
        "Language" to "Idioma", "The language of DS Forever's screens" to "El idioma de las pantallas de DS Forever", "Automatic" to "Automático",
    )

    private val FR: Map<String, String> = mapOf(
        "Play" to "Jouer", "Report a problem" to "Signaler un problème", "Ready" to "Prêt",
        "Loading." to "Chargement.", "Loading.." to "Chargement..", "Loading..." to "Chargement...",
        "Waiting for the game." to "En attente du jeu.",
        "Updating the game" to "Mise à jour du jeu", "Getting ready to update" to "Préparation de la mise à jour",
        "Update starts when the launch is done" to "La mise à jour commencera une fois le lancement terminé",
        "Already updating" to "Mise à jour déjà en cours", "Update needs access to the game folder" to "La mise à jour doit accéder au dossier du jeu",
        "Checking the game's version - press Update again in a moment" to "Vérification de la version du jeu : appuyez de nouveau sur Mettre à jour dans un instant",
        "Installing needs 60 GB free" to "L'installation nécessite 60 Go libres", "Installing needs Wi-Fi" to "L'installation nécessite le Wi-Fi",
        "Restarting the game to sign in…" to "Redémarrage du jeu pour la connexion…",
        "Open app settings" to "Ouvrir les paramètres de l'appli", "Move DS Forever to internal storage" to "Déplacez DS Forever vers le stockage interne",
        "It is installed on the SD card, where the game's container can't be made. Settings > Apps > DS Forever > Storage > Change > Internal." to
            "Il est installé sur la carte SD, où le conteneur du jeu ne peut pas être créé. Paramètres > Applis > DS Forever > Stockage > Modifier > Interne.",
        "Where is WoW Forever?" to "Où se trouve WoW Forever ?",
        "Install it (250 MB now, up to 50 GB as you play), or choose the folder with .build.info and Data." to
            "Installez-le (250 Mo maintenant, jusqu'à 50 Go en jouant) ou choisissez le dossier qui contient .build.info et Data.",
        "Choose folder" to "Choisir le dossier", "Install directly" to "Installer directement",
        "Your game folder is out of date" to "Votre dossier de jeu n'est pas à jour", "OK" to "OK",
        "Storage is low" to "Stockage presque plein", "Allow file access" to "Autoriser l'accès aux fichiers",
        "Android needs \"All files access\" so the game and DS Forever can read the game folder." to
            "Android a besoin de « Accès à tous les fichiers » pour que le jeu et DS Forever puissent lire le dossier du jeu.",
        "Open settings" to "Ouvrir les paramètres",
        "The game art couldn't be built" to "Les graphismes du jeu n'ont pas pu être préparés",
        "The game files could not be read." to "Les fichiers du jeu n'ont pas pu être lus.",
        "Try again" to "Réessayer", "Update now, or later from this screen." to "Mettez à jour maintenant, ou plus tard depuis cet écran.",
        "Update" to "Mettre à jour", "Later" to "Plus tard",
        "Open the issue to see it." to "Ouvrez le ticket pour le voir.",
        "It is in Documents/DS Forever/reports. Open the GitHub issue to post it." to "Il se trouve dans Documents/DS Forever/reports. Ouvrez le ticket GitHub pour le publier.",
        "Open GitHub issue" to "Ouvrir le ticket GitHub", "Dismiss" to "Ignorer",
        "Your Battle.net login" to "Votre compte Battle.net",
        "Save it once and the game signs you in by itself. It stays encrypted on this device." to
            "Enregistrez-le une fois et le jeu vous connecte tout seul. Il reste chiffré sur cet appareil.",
        "Enter login" to "Saisir le compte", "Play without" to "Jouer sans",
        "Sign-in didn't work" to "La connexion a échoué", "The game didn't restart" to "Le jeu n'a pas redémarré",
        "Type it on the game screen" to "Saisissez-le sur l'écran du jeu", "Enter your authenticator code" to "Saisissez votre code d'authentification",
        "Type your authenticator code (or password) on the game screen" to "Saisissez votre code d'authentification (ou votre mot de passe) sur l'écran du jeu",
        "Keyboard" to "Clavier", "Restart the game to sign in" to "Redémarrer le jeu pour se connecter",
        "Menu" to "Menu", "Login" to "Connexion", "Battle.net login" to "Compte Battle.net",
        "How do you want to play?" to "Comment voulez-vous jouer ?", "Shortcuts" to "Raccourcis", "Add a shortcut" to "Ajouter un raccourci",
        "Back" to "Retour", "Local" to "Local", "Streaming" to "Streaming",
        "WoW Forever runs on this device." to "WoW Forever fonctionne sur cet appareil.",
        "WoW runs on your PC. DS Forever Desktop streams it here." to "WoW fonctionne sur votre PC. DS Forever Desktop le diffuse ici.",
        "Connect to DS Forever Desktop. Type the code it shows on your PC on the top screen." to
            "Connectez-vous à DS Forever Desktop. Saisissez sur l'écran du haut le code affiché sur votre PC.",
        "Back to the chooser" to "Retour au choix",
        "Auto login" to "Connexion auto", "Change login" to "Changer de compte", "Tap again" to "Touchez encore", "Forget login" to "Oublier le compte",
        "Stream settings" to "Paramètres du streaming", "Restart game" to "Redémarrer le jeu", "Report issue" to "Signaler un problème",
        "Switch mode" to "Changer de mode", "Exit game" to "Quitter le jeu",
        "Email" to "E-mail", "Password" to "Mot de passe", "Show" to "Afficher", "Hide" to "Masquer",
        "Kept on this device only, encrypted." to "Conservé uniquement sur cet appareil, chiffré.", "Save and log in" to "Enregistrer et se connecter",
        "Cancel" to "Annuler", "Region" to "Région", "Korea" to "Corée", "Taiwan" to "Taïwan",
        "The login could not be saved on this device" to "Le compte n'a pas pu être enregistré sur cet appareil",
        "Choose an item to enchant" to "Choisissez un objet à enchanter", "Equipped" to "Équipé", "In your bags" to "Dans vos sacs",
        "No soulbound items" to "Aucun objet lié", "Trade" to "Échanger", "Accepted" to "Accepté", "Will not be traded" to "Ne sera pas échangé",
        "Backpack" to "Sac à dos", "You" to "Vous",
        "Language" to "Langue", "The language of DS Forever's screens" to "La langue des écrans de DS Forever", "Automatic" to "Automatique",
    )

    /** words with values inside: the English with %s for each value, and its translations */
    private val TEMPLATES: List<Pair<String, Map<String, String>>> = listOf(
        "DS Forever %s is out" to mapOf("es" to "Ya está disponible DS Forever %s", "fr" to "DS Forever %s est disponible"),
        "Updating the game to %s" to mapOf("es" to "Actualizando el juego a %s", "fr" to "Mise à jour du jeu vers %s"),
        "Preparing the game art: %s%" to mapOf("es" to "Preparando el arte del juego: %s%", "fr" to "Préparation des graphismes du jeu : %s %"),
        "Only %s GB free. WoW needs more room as you play. Free up some storage soon." to mapOf(
            "es" to "Solo quedan %s GB libres. WoW necesita más espacio mientras juegas. Libera espacio pronto.",
            "fr" to "Plus que %s Go libres. WoW a besoin de plus d'espace pendant que vous jouez. Libérez de l'espace bientôt."),
        "The folder %s holds %s, not WoW Forever. Choose the WoW Forever folder: the one with .build.info and Data." to mapOf(
            "es" to "La carpeta %s contiene %s, no WoW Forever. Elige la carpeta de WoW Forever: la que tiene .build.info y Data.",
            "fr" to "Le dossier %s contient %s, pas WoW Forever. Choisissez le dossier de WoW Forever : celui qui contient .build.info et Data."),
        "This step: %s" to mapOf("es" to "Este paso: %s", "fr" to "Cette étape : %s"),
        "Total: %s" to mapOf("es" to "Total: %s", "fr" to "Total : %s"),
        "Type with Keyboard; don't press %s." to mapOf("es" to "Escribe con Teclado; no pulses %s.", "fr" to "Tapez avec Clavier ; n'appuyez pas sur %s."),
    )

    private val compiled: List<Pair<Regex, Map<String, String>>> = TEMPLATES.map { (en, tr) ->
        Regex(en.split("%s").joinToString("(.+?)") { Regex.escape(it) }) to tr
    }

    /** the off-device test's view: every key of [lang] (a key missing from another language shows in English there) */
    internal fun keys(lang: String): Set<String> = when (lang) { "es" -> ES.keys; "fr" -> FR.keys; else -> emptySet() }
}
