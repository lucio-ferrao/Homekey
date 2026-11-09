// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * The emoji offered in the suggestion strip while a ":" and what follows it are typed, as wassup offers them
 * (github.com/lucio-ferrao/wassup, src/emojicodes.rs, MIT, by the same author): its classic smileys, and its emoji
 * names in European Portuguese (without accents, to be typed quickly) and in English.
 */
object EmojiCodes {
    /** an emoji with its names in Portuguese and in English; a name may be shared ("amor", the heart and the faces in love) */
    private class Named(val emoji: String, val pt: List<String>, val en: List<String>)

    /** classic smileys, each group with the emoji it stands for */
    private val EMOTICONS = listOf(
        listOf(":)", ":-)", "=)") to "🙂",
        listOf(":D", ":-D", "=D") to "😁",
        listOf(":(", ":-(", "=(") to "🙁",
        listOf(";)", ";-)") to "😉",
        listOf(":P", ":-P", ":p", ":-p") to "😛",
        listOf(";P", ";-P", ";p") to "😜",
        listOf(":*", ":-*") to "😘",
        listOf(":O", ":-O", ":o", ":-o") to "😮",
        listOf(":'(", ":'-(") to "😢",
        listOf(":/", ":-/", ":\\", ":-\\") to "😕",
        listOf(":|", ":-|") to "😐",
        listOf(":\$", ":-\$") to "😳",
        listOf(":X", ":-X", ":x", ":-x") to "🤐",
        listOf(">:(", ">:-(") to "😠",
        listOf("B)", "B-)", "8)", "8-)") to "😎",
        listOf("xD", "XD", "xd") to "😆",
        listOf("<3") to "❤️",
        listOf("</3") to "💔",
        listOf(":3") to "😊",
        listOf("^^", "^_^") to "😊",
        listOf("-_-") to "😑",
        listOf("o/") to "👋",
        listOf("\\o/") to "🙌",
    )

    private val EMOJI = listOf(
        // faces
        Named("😊", listOf("sorriso"), listOf("smile", "blush")),
        Named("😄", listOf("riso", "risota"), listOf("grin", "smiley")),
        Named("😂", listOf("gargalhada", "lol"), listOf("joy", "lol")),
        Named("🤣", listOf("chorar_a_rir"), listOf("rofl")),
        Named("😉", listOf("piscadela"), listOf("wink")),
        Named("😛", listOf("lingua"), listOf("tongue", "stuck_out_tongue")),
        Named("😘", listOf("beijinho"), listOf("kissing_heart")),
        Named("😍", listOf("apaixonado", "apaixonada", "olhos_de_coracao", "amor", "paixao", "coracao"), listOf("heart_eyes", "in_love", "love")),
        Named("🥰", listOf("coracoes", "carinho", "apaixonado", "apaixonada", "amor", "paixao", "coracao"), listOf("smiling_face_with_hearts", "three_hearts", "in_love", "love")),
        Named("🤔", listOf("pensativo", "a_pensar"), listOf("thinking")),
        Named("😐", listOf("neutro", "sem_expressao"), listOf("neutral", "neutral_face")),
        Named("😢", listOf("triste", "lagrima"), listOf("cry")),
        Named("😭", listOf("choro", "a_chorar"), listOf("sob")),
        Named("😠", listOf("zangado", "chateado"), listOf("angry")),
        Named("😡", listOf("furioso"), listOf("rage")),
        Named("😱", listOf("medo", "assustado", "grito"), listOf("scream")),
        Named("😮", listOf("espantado", "boquiaberto"), listOf("open_mouth")),
        Named("😎", listOf("oculos_de_sol", "fixolas"), listOf("sunglasses", "cool")),
        Named("🤓", listOf("cromo", "totó"), listOf("nerd")),
        Named("😴", listOf("sono", "a_dormir"), listOf("sleeping")),
        Named("🤒", listOf("doente", "febre"), listOf("sick", "face_with_thermometer")),
        Named("🥳", listOf("festa", "festejar"), listOf("party", "partying_face")),
        Named("🫂", listOf("abraco", "abracao", "abraco_grande"), listOf("hug", "hugs", "people_hugging")),
        Named("🤗", listOf("abracinho", "abraco"), listOf("hugging_face", "hug", "hugs")),
        Named("🤫", listOf("chiu", "silencio"), listOf("shush", "shushing_face")),
        Named("🤥", listOf("mentiroso", "pinoquio"), listOf("lying", "lying_face")),
        Named("🤮", listOf("enjoado", "vomitar"), listOf("vomit", "vomiting")),
        Named("😇", listOf("anjo", "santinho"), listOf("angel", "innocent")),
        Named("😈", listOf("diabo", "diabinho"), listOf("devil", "smiling_imp")),
        Named("🤡", listOf("palhaco"), listOf("clown")),
        Named("💀", listOf("caveira"), listOf("skull")),
        Named("💩", listOf("coco", "trampa"), listOf("poop", "hankey")),
        Named("👻", listOf("fantasma"), listOf("ghost")),
        Named("🤖", listOf("robo"), listOf("robot")),
        // hands
        Named("👍", listOf("fixe", "gosto", "polegar", "+1"), listOf("thumbsup", "+1", "like")),
        Named("👎", listOf("nao", "nao_gosto", "-1"), listOf("thumbsdown", "-1", "dislike")),
        Named("👌", listOf("ok", "perfeito"), listOf("ok_hand", "ok")),
        Named("👏", listOf("palmas", "aplausos"), listOf("clap")),
        Named("🙏", listOf("rezar", "obrigado", "por_favor"), listOf("pray", "thanks", "please")),
        Named("💪", listOf("forca", "musculo"), listOf("muscle", "strong")),
        Named("👋", listOf("ola", "adeus", "acenar"), listOf("wave", "hello", "bye")),
        Named("✊", listOf("punho"), listOf("fist")),
        Named("👊", listOf("murro", "soco"), listOf("punch", "fist_bump")),
        Named("✌️", listOf("paz", "vitoria"), listOf("v", "peace", "victory")),
        Named("🤘", listOf("metal", "rock"), listOf("metal", "rock")),
        Named("🤙", listOf("liga_me", "shaka"), listOf("call_me", "shaka")),
        Named("👉", listOf("aponta", "direita_mao"), listOf("point_right")),
        Named("👈", listOf("esquerda_mao"), listOf("point_left")),
        Named("👀", listOf("olhos", "a_ver"), listOf("eyes")),
        Named("🧠", listOf("cerebro"), listOf("brain")),
        // hearts and symbols
        Named("❤️", listOf("coracao", "amor"), listOf("heart", "love")),
        Named("💋", listOf("beijo"), listOf("kiss", "lips")),
        Named("💔", listOf("coracao_partido", "desgosto"), listOf("broken_heart")),
        Named("🧡", listOf("coracao_laranja"), listOf("orange_heart")),
        Named("💛", listOf("coracao_amarelo"), listOf("yellow_heart")),
        Named("💚", listOf("coracao_verde"), listOf("green_heart")),
        Named("💙", listOf("coracao_azul"), listOf("blue_heart")),
        Named("💜", listOf("coracao_roxo"), listOf("purple_heart")),
        Named("🖤", listOf("coracao_preto"), listOf("black_heart")),
        Named("✨", listOf("brilho", "brilhos"), listOf("sparkles")),
        Named("⭐", listOf("estrela"), listOf("star")),
        Named("🔥", listOf("fogo", "lume"), listOf("fire")),
        Named("💯", listOf("cem", "cem_por_cento"), listOf("100")),
        Named("💣", listOf("bomba"), listOf("bomb")),
        Named("🎉", listOf("viva", "confetes", "parabens"), listOf("tada", "party_popper", "congrats")),
        Named("🎁", listOf("prenda", "presente"), listOf("gift")),
        Named("🎂", listOf("bolo", "bolo_de_anos"), listOf("cake", "birthday")),
        Named("🎈", listOf("balao"), listOf("balloon")),
        Named("🏆", listOf("taca", "trofeu"), listOf("trophy")),
        Named("🏅", listOf("medalha"), listOf("medal")),
        Named("✅", listOf("certo", "visto", "feito"), listOf("check", "done", "white_check_mark")),
        Named("❌", listOf("errado", "cruz"), listOf("x", "cross")),
        Named("⚠️", listOf("aviso", "atencao", "cuidado"), listOf("warning")),
        Named("🚫", listOf("proibido"), listOf("no_entry", "forbidden")),
        Named("❓", listOf("pergunta", "interrogacao"), listOf("question")),
        Named("❗", listOf("exclamacao"), listOf("exclamation")),
        Named("ℹ️", listOf("info", "informacao"), listOf("info", "information")),
        Named("⏰", listOf("despertador", "alarme"), listOf("alarm", "alarm_clock")),
        Named("🕐", listOf("relogio", "hora", "horas"), listOf("clock")),
        Named("📅", listOf("calendario", "agenda"), listOf("calendar")),
        Named("⬆️", listOf("cima", "seta_cima"), listOf("up", "arrow_up")),
        Named("⬇️", listOf("baixo", "seta_baixo"), listOf("down", "arrow_down")),
        Named("⬅️", listOf("esquerda", "seta_esquerda"), listOf("left", "arrow_left")),
        Named("➡️", listOf("direita", "seta_direita"), listOf("right", "arrow_right")),
        Named("♻️", listOf("reciclar", "reciclagem"), listOf("recycle")),
        Named("♾️", listOf("infinito"), listOf("infinity")),
        // weather and nature
        Named("☀️", listOf("sol"), listOf("sun", "sunny")),
        Named("🌙", listOf("lua"), listOf("moon")),
        Named("🌧️", listOf("chuva"), listOf("rain")),
        Named("☁️", listOf("nuvem", "nublado"), listOf("cloud")),
        Named("🌈", listOf("arco_iris"), listOf("rainbow")),
        Named("❄️", listOf("neve", "frio"), listOf("snow", "snowflake")),
        Named("⚡", listOf("raio", "trovoada"), listOf("zap", "lightning")),
        Named("🌊", listOf("onda", "mar"), listOf("wave_water", "ocean")),
        Named("🌍", listOf("terra", "mundo"), listOf("earth", "world")),
        Named("🌳", listOf("arvore"), listOf("tree")),
        Named("🌸", listOf("flor"), listOf("flower", "blossom")),
        Named("🌹", listOf("rosa"), listOf("rose")),
        Named("🌻", listOf("girassol"), listOf("sunflower")),
        Named("🌵", listOf("cacto"), listOf("cactus")),
        // animals
        Named("🐶", listOf("cao", "cachorro"), listOf("dog")),
        Named("🐱", listOf("gato"), listOf("cat")),
        Named("🐭", listOf("rato"), listOf("mouse")),
        Named("🐰", listOf("coelho"), listOf("rabbit")),
        Named("🐻", listOf("urso"), listOf("bear")),
        Named("🐼", listOf("panda"), listOf("panda")),
        Named("🦁", listOf("leao"), listOf("lion")),
        Named("🐮", listOf("vaca"), listOf("cow")),
        Named("🐷", listOf("porco"), listOf("pig")),
        Named("🐸", listOf("sapo", "ra"), listOf("frog")),
        Named("🐵", listOf("macaco"), listOf("monkey")),
        Named("🐔", listOf("galinha"), listOf("chicken")),
        Named("🐓", listOf("galo", "galo_de_barcelos"), listOf("rooster")),
        Named("🐧", listOf("pinguim"), listOf("penguin")),
        Named("🐦", listOf("passaro", "passarinho"), listOf("bird")),
        Named("🐟", listOf("peixe", "bacalhau", "sardinha"), listOf("fish")),
        Named("🐙", listOf("polvo"), listOf("octopus")),
        Named("🦋", listOf("borboleta"), listOf("butterfly")),
        Named("🐝", listOf("abelha"), listOf("bee")),
        Named("🐍", listOf("cobra"), listOf("snake")),
        Named("🐴", listOf("cavalo"), listOf("horse")),
        Named("🦄", listOf("unicornio"), listOf("unicorn")),
        Named("🐌", listOf("caracol"), listOf("snail")),
        // food and drink
        Named("🍕", listOf("pizza"), listOf("pizza")),
        Named("🍔", listOf("hamburguer"), listOf("burger", "hamburger")),
        Named("🍟", listOf("batatas_fritas"), listOf("fries")),
        Named("☕", listOf("cafe", "bica"), listOf("coffee")),
        Named("🍵", listOf("cha"), listOf("tea")),
        Named("🍺", listOf("cerveja", "imperial", "fino"), listOf("beer")),
        Named("🍻", listOf("saude", "brinde"), listOf("cheers", "beers")),
        Named("🍷", listOf("vinho", "copo_de_vinho"), listOf("wine")),
        Named("🍾", listOf("champanhe", "espumante"), listOf("champagne")),
        Named("🍸", listOf("cocktail"), listOf("cocktail")),
        Named("💧", listOf("agua", "gota"), listOf("droplet", "water")),
        Named("🍎", listOf("maca"), listOf("apple")),
        Named("🍌", listOf("banana"), listOf("banana")),
        Named("🍊", listOf("laranja", "tangerina"), listOf("orange", "tangerine")),
        Named("🍓", listOf("morango"), listOf("strawberry")),
        Named("🍇", listOf("uvas"), listOf("grapes")),
        Named("🍞", listOf("pao"), listOf("bread")),
        Named("🧀", listOf("queijo"), listOf("cheese")),
        Named("🥚", listOf("ovo"), listOf("egg")),
        Named("🍦", listOf("gelado"), listOf("icecream", "ice_cream")),
        Named("🍫", listOf("chocolate"), listOf("chocolate")),
        // transport and places
        Named("🚗", listOf("carro"), listOf("car")),
        Named("🚌", listOf("autocarro"), listOf("bus")),
        Named("🚆", listOf("comboio"), listOf("train")),
        Named("🚋", listOf("eletrico"), listOf("tram")),
        Named("✈️", listOf("aviao"), listOf("airplane", "plane")),
        Named("⛵", listOf("barco", "veleiro"), listOf("boat", "sailboat")),
        Named("🚲", listOf("bicicleta", "bike"), listOf("bike", "bicycle")),
        Named("🚀", listOf("foguetao"), listOf("rocket")),
        Named("🏠", listOf("casa"), listOf("house", "home")),
        Named("🏢", listOf("escritorio", "predio"), listOf("office")),
        Named("🏖️", listOf("praia"), listOf("beach")),
        // objects and work
        Named("📞", listOf("telefone"), listOf("phone", "telephone")),
        Named("📱", listOf("telemovel"), listOf("iphone", "mobile")),
        Named("💻", listOf("computador", "portatil"), listOf("computer", "laptop")),
        Named("📧", listOf("email", "mail"), listOf("email", "e-mail")),
        Named("✉️", listOf("envelope", "carta"), listOf("envelope")),
        Named("📖", listOf("livro"), listOf("book")),
        Named("✏️", listOf("lapis"), listOf("pencil")),
        Named("📎", listOf("clipe", "anexo"), listOf("paperclip", "attachment")),
        Named("📁", listOf("pasta"), listOf("folder")),
        Named("🔍", listOf("lupa", "procurar"), listOf("mag", "search")),
        Named("🔑", listOf("chave"), listOf("key")),
        Named("🔒", listOf("cadeado", "trancado"), listOf("lock")),
        Named("💰", listOf("dinheiro", "saco_de_dinheiro"), listOf("moneybag", "money")),
        Named("💶", listOf("euro", "euros"), listOf("euro")),
        Named("💳", listOf("cartao", "multibanco"), listOf("credit_card", "card")),
        Named("📈", listOf("grafico", "a_subir"), listOf("chart", "chart_up")),
        Named("💡", listOf("ideia", "lampada"), listOf("bulb", "idea")),
        Named("🔧", listOf("chave_inglesa", "ferramenta"), listOf("wrench", "tool")),
        Named("🔨", listOf("martelo"), listOf("hammer")),
        Named("⚙️", listOf("engrenagem", "definicoes"), listOf("gear", "settings")),
        Named("🧱", listOf("tijolo"), listOf("brick")),
        // sports and leisure
        Named("⚽", listOf("bola", "futebol"), listOf("soccer", "football")),
        Named("🏀", listOf("basquetebol"), listOf("basketball")),
        Named("🎾", listOf("tenis"), listOf("tennis")),
        Named("⛳", listOf("golfe"), listOf("golf")),
        Named("🎵", listOf("musica", "nota"), listOf("music", "note")),
        Named("🎸", listOf("guitarra"), listOf("guitar")),
        Named("🎤", listOf("microfone", "karaoke"), listOf("mic", "microphone")),
        Named("🎬", listOf("filme", "cinema"), listOf("movie", "clapper")),
        Named("🎮", listOf("jogo", "consola"), listOf("game", "video_game")),
        Named("🎲", listOf("dado"), listOf("dice", "game_die")),
        // people
        Named("👶", listOf("bebe"), listOf("baby")),
        Named("👨‍👩‍👧", listOf("familia"), listOf("family")),
        Named("💑", listOf("casal", "namorados"), listOf("couple")),
        // flags
        Named("🇵🇹", listOf("portugal", "pt"), listOf("portugal", "pt")),
        Named("🇧🇷", listOf("brasil", "br"), listOf("brazil", "br")),
        Named("🇪🇸", listOf("espanha", "es"), listOf("spain", "es")),
        Named("🇫🇷", listOf("franca", "fr"), listOf("france", "fr")),
        Named("🇬🇧", listOf("reino_unido", "inglaterra", "uk"), listOf("uk", "gb")),
        Named("🇺🇸", listOf("eua", "estados_unidos", "us"), listOf("usa", "us")),
        Named("🇩🇪", listOf("alemanha", "de"), listOf("germany", "de")),
    )

    /** names offered ahead of the others whenever they fit, in this order: ":lo" gives the heart before "lol" */
    private val FIRST = listOf("love")

    /** the most emoji taken from the other names, which can fit many for a short prefix */
    private const val MAX_BY_NAME = 40

    /**
     * Each name's emoji when typed whole: the emoji that lists it soonest among its names (Portuguese, then English),
     * on a tie the first in the table, so "amor" is the heart, which has it second, not the faces in love.
     */
    private val owners: Map<String, String> by lazy {
        val owners = HashMap<String, String>()
        val soonest = HashMap<String, Int>()
        for (named in EMOJI) (named.pt + named.en).forEachIndexed { i, name ->
            if ((soonest[name] ?: Int.MAX_VALUE) <= i) return@forEachIndexed
            owners[name] = named.emoji
            soonest[name] = i
        }
        owners
    }

    /**
     * The emoji for what follows a ":" being typed ([prefix]), each once. First the smileys that start that way (":D"
     * for "D"; for nothing yet, the basic ones, ":)", ":D", ":("...), in the order of their table. Then the emoji with a
     * name in either language that starts with [prefix], in any case, each by its first such name, one in the user's
     * language ([portuguese] or English) when both have one: those in [FIRST] ahead, then the shorter names first, so an
     * exact match ("fixe") comes before a longer one, and on a tie the emoji the name stands for when typed whole first,
     * then the table's order. When no name of the table fits a [prefix] of letters, the emoji [byName] finds for it
     * (the names the keyboard has for all emoji) instead.
     */
    fun complete(prefix: String, portuguese: Boolean, byName: (String) -> List<String>): List<String> {
        val out = ArrayList<String>()
        for ((faces, emoji) in EMOTICONS)
            if (faces.any { it.startsWith(":") && it.substring(1).startsWith(prefix) } && emoji !in out) out.add(emoji)
        if (prefix.isEmpty() || !prefix.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_+-" }) return out
        val p = prefix.lowercase()
        val named = ArrayList<Pair<String, String>>()
        for (entry in EMOJI) {
            val (own, other) = if (portuguese) entry.pt to entry.en else entry.en to entry.pt
            val name = own.firstOrNull { it.startsWith(p) } ?: other.firstOrNull { it.startsWith(p) } ?: continue
            if (entry.emoji !in out && named.none { it.first == entry.emoji }) named.add(entry.emoji to name)
        }
        fun first(name: String) = FIRST.indexOf(name).let { if (it < 0) FIRST.size else it }
        // sortedWith is stable: the table's order stays on a full tie
        if (named.isEmpty())
            return if (prefix.all { it.isLetter() }) out + byName(prefix).filter { it !in out }.take(MAX_BY_NAME) else out
        return out + named.sortedWith(compareBy<Pair<String, String>> { first(it.second) }.thenBy { it.second.length }
            .thenBy { if (owners[it.second] == it.first) 0 else 1 }).map { it.first }
    }
}
