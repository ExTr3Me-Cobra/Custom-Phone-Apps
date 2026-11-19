package com.t8rin.imagetoolbox.core.resources.emoji

import android.net.Uri

/**
 * Viper Vision: search for the sheet's emojis by words. Each emoji is known by its file name
 * ("face-loudly-crying"), its category, and alternate words for those ("sad", "tears", "upset"),
 * so the exact name isn't needed. Every word typed has to match (the start of) one of them.
 */
object EmojiSearch {

    /** File names that carry a sort prefix or a short form, as words. */
    private val names = mapOf(
        "aasparkles" to "sparkles", "ahot-face" to "hot face", "face-ablow" to "face blowing kiss",
        "face-amelting" to "melting face", "face-apleading" to "pleading face", "face-asaluting" to "saluting face",
        "face-ashaking" to "shaking face", "face-athinking" to "thinking face", "face-azany" to "zany face",
        "face-dnauseated" to "nauseated face", "heart-aletter" to "heart letter", "heart_gauze" to "heart bandage mending",
        "ssparty" to "party face", "zanger" to "anger symbol", "zap" to "high voltage zap", "zbang" to "collision bang",
        "zdizzy" to "dizzy", "zdropplets" to "sweat droplets", "zfire" to "fire", "zzz" to "zzz sleeping",
        "moon_a" to "moon face", "moon_b" to "moon face", "eyes" to "eyes",
        "zzz_astudio" to "android studio logo", "zzz_kotlin" to "kotlin logo", "zzz_md" to "material design logo",
        "zzzz_image_toolbox" to "image toolbox logo", "zzzz_image_toolbox_old" to "image toolbox logo old",
        "acancer" to "cancer zodiac", "acapricorn" to "capricorn zodiac", "agemini" to "gemini zodiac",
        "aleo" to "leo zodiac", "alibra" to "libra zodiac", "aophiuchus" to "ophiuchus zodiac",
        "apisces" to "pisces zodiac", "aquarius" to "aquarius zodiac", "aries" to "aries zodiac",
        "asagittarius" to "sagittarius zodiac", "ascorpio" to "scorpio zodiac", "ataurus" to "taurus zodiac",
        "avirgo" to "virgo zodiac", "cradioactive" to "radioactive", "cwarning" to "warning",
        "natom-symbol" to "atom symbol", "male-sign-fe" to "female sign", "male_sign" to "male sign",
        "face-sun-with" to "sun with face", "clinking1" to "clinking glasses", "clinking2" to "clinking beer mugs",
        "computermouse" to "computer mouse", "die" to "dice die", "tick" to "check mark tick",
        "alien_monster" to "alien monster space invader", "ice" to "ice cube", "pole" to "barber pole",
        "card" to "credit card", "tube" to "test tube", "disk" to "floppy disk save", "crystal_ball" to "crystal ball",
        "light_bulb" to "light bulb", "money_bag" to "money bag", "heart_broken" to "broken heart",
        "heart_fire" to "heart on fire", "face-in-clouds" to "face in clouds"
    )

    /** Alternate words for a word in an emoji's name. */
    private val alternates: Map<String, String> = mapOf(
        "grinning" to "happy smile smiling grin laugh lol joy glad",
        "smiling" to "happy smile glad pleased",
        "crying" to "sad cry tears upset sob",
        "loudly" to "sob bawling",
        "sad" to "unhappy down upset",
        "relieved" to "phew relief",
        "pouting" to "angry mad grumpy annoyed",
        "angry" to "mad rage furious",
        "anger" to "angry mad",
        "devil" to "evil demon imp naughty",
        "heart" to "love like romance valentine",
        "hearts" to "love",
        "eyes" to "look see watching",
        "wry" to "smirk",
        "smirking" to "smug sly smirk",
        "kiss" to "love smooch mwah",
        "blowing" to "kiss love",
        "melting" to "hot embarrassed",
        "pleading" to "please puppy beg cute sad",
        "saluting" to "salute respect yes sir",
        "shaking" to "shock scared tremble",
        "thinking" to "hmm think wonder ponder",
        "zany" to "crazy silly goofy wacky",
        "clown" to "funny silly joker",
        "cold" to "freezing frozen chilly",
        "hot" to "sweat heat warm",
        "nauseated" to "sick ill gross disgusted vomit",
        "expressionless" to "blank meh deadpan",
        "flushed" to "embarrassed blush shy",
        "clouds" to "dreamy confused foggy",
        "monkey" to "ape",
        "hear" to "deaf",
        "see" to "shy oops peek",
        "savoring" to "yum tasty delicious hungry",
        "screaming" to "scream scared horror shock afraid",
        "fear" to "scared afraid",
        "raised" to "suspicious doubt skeptical",
        "eyebrow" to "suspicious",
        "spiral" to "dizzy confused hypnotized",
        "steam" to "angry frustrated huff",
        "sunglasses" to "cool shades sunny",
        "symbols" to "swearing cursing angry",
        "tongue" to "silly playful cheeky",
        "ghost" to "boo spooky halloween",
        "goblin" to "monster ogre",
        "alien" to "ufo space extraterrestrial",
        "skull" to "dead death dying lol",
        "poo" to "poop crap",
        "hundred" to "100 perfect score",
        "points" to "100",
        "party" to "celebrate celebration birthday fun happy",
        "sparkles" to "shiny glitter magic stars new",
        "fire" to "hot lit flame burn",
        "zap" to "lightning electric thunder",
        "collision" to "boom explosion crash",
        "dizzy" to "stars dazed",
        "droplets" to "sweat water splash",
        "sleeping" to "sleep tired sleepy snore bed",
        "moai" to "statue easter island stone",
        "growing" to "love",
        "broken" to "heartbreak sad breakup",
        "letter" to "love note mail",
        "distorted" to "weird warped",
        "dotted" to "hidden invisible",
        // Events
        "medal" to "winner award first second third gold silver bronze",
        "place" to "winner rank",
        "trophy" to "winner win award champion cup",
        "ball" to "sport game",
        "balloon" to "party birthday",
        "gift" to "present birthday christmas",
        "christmas" to "xmas holiday tree",
        "jack" to "pumpkin halloween",
        "fireworks" to "celebration new year",
        "firecracker" to "celebration new year",
        "camera" to "photo picture",
        "picture" to "photo art frame",
        "palette" to "paint art colours colors",
        "joystick" to "game gaming",
        "video" to "game gaming",
        "slots" to "casino gamble",
        "performing" to "theater theatre drama masks",
        "disco" to "dance party",
        "football" to "sport",
        "boxing" to "fight punch",
        "bullseye" to "target dart",
        "ticket" to "admission event",
        // Food
        "beer" to "drink alcohol pub",
        "wine" to "drink alcohol",
        "cocktail" to "drink alcohol",
        "tropical" to "drink cocktail",
        "glass" to "drink",
        "clinking" to "cheers toast drink",
        "bubble" to "boba",
        "cake" to "birthday dessert",
        "doughnut" to "donut dessert sweet",
        "cookie" to "biscuit sweet",
        "candy" to "sweet sweets",
        "chocolate" to "sweet",
        "pizza" to "food italian",
        "hamburger" to "burger",
        "hotdog" to "sausage",
        "french" to "fries chips",
        "fries" to "chips",
        "beef" to "steak meat",
        "poultry" to "chicken drumstick",
        "pot" to "stew soup",
        "rice" to "asian",
        "shaved" to "dessert",
        "ear" to "corn",
        "eggplant" to "aubergine",
        "pepper" to "chili spicy hot",
        "food" to "eat meal",
        // Nature
        "cat" to "kitty kitten pet",
        "black" to "dark",
        "chick" to "bird baby",
        "duck" to "bird",
        "goose" to "bird",
        "swan" to "bird",
        "parrot" to "bird",
        "phoenix" to "bird fire",
        "rabbit" to "bunny",
        "pig" to "piggy",
        "polar" to "bear",
        "dolphin" to "sea ocean",
        "shark" to "sea ocean",
        "jellyfish" to "sea ocean",
        "blowfish" to "pufferfish sea",
        "fish" to "sea ocean",
        "turtle" to "tortoise",
        "crocodile" to "alligator",
        "sauropod" to "dinosaur dino",
        "mammoth" to "elephant",
        "lady" to "ladybug ladybird",
        "beetle" to "bug insect",
        "microbe" to "germ virus bacteria",
        "worm" to "bug",
        "blossom" to "flower spring",
        "rose" to "flower love",
        "sunflower" to "flower",
        "hyacinth" to "flower",
        "lotus" to "flower",
        "wilted" to "flower dead sad",
        "bouquet" to "flowers",
        "tree" to "plant",
        "herb" to "plant leaf",
        "clover" to "luck lucky",
        "sun" to "sunny weather day",
        "cloud" to "weather cloudy",
        "rain" to "weather rainy",
        "snowflake" to "snow winter cold",
        "snowman" to "snow winter",
        "rainbow" to "pride weather",
        "tornado" to "storm weather",
        "wave" to "sea ocean surf water",
        "star" to "favourite favorite night",
        "comet" to "space",
        "milky" to "galaxy space stars",
        "volcano" to "eruption",
        "mountain" to "hill",
        "tent" to "camping",
        "paw" to "pet dog cat",
        // Objects
        "bomb" to "explode",
        "brain" to "smart mind",
        "crown" to "king queen royal",
        "diamond" to "gem jewel",
        "ring" to "wedding engagement",
        "money" to "cash dollar rich",
        "key" to "lock",
        "locked" to "lock padlock private",
        "phone" to "mobile cell",
        "pill" to "medicine drug",
        "bandage" to "hurt plaster",
        "battery" to "power charge",
        "candle" to "light",
        "bulb" to "idea",
        "calendar" to "date",
        "package" to "box parcel delivery",
        "pushpin" to "pin",
        "siren" to "alarm police emergency",
        "coffin" to "death dead",
        "cigarette" to "smoking",
        // Symbols and travel
        "warning" to "caution alert",
        "prohibited" to "forbidden no banned",
        "entry" to "stop no",
        "recycling" to "recycle green",
        "speech" to "talk chat message",
        "tick" to "done yes ok correct",
        "automobile" to "car",
        "taxi" to "cab car",
        "police" to "cop",
        "racing" to "fast car",
        "fire-engine" to "firetruck",
        "rocket" to "space launch",
        "airplane" to "plane flight fly",
        "helicopter" to "fly",
        "passenger" to "boat cruise",
        "speedboat" to "boat",
        "ufo" to "alien flying saucer",
        "globe" to "world earth",
        "map" to "world travel",
        "luggage" to "travel suitcase bag",
        "planet" to "saturn space"
    )

    private class Entry(val uri: Uri, val words: List<String>, val nameWords: Set<String>)

    @Volatile
    private var index: List<Entry>? = null

    private fun index(): List<Entry> = index ?: Emoji.allIconsCategorized.flatMap { data ->
        data.emojis.map { uri ->
            val category = uri.pathSegments.getOrNull(uri.pathSegments.size - 2).orEmpty()
            val file = uri.lastPathSegment.orEmpty().substringBeforeLast('.')
            val name = (names[file] ?: file).lowercase().split('-', '_', ' ').filter(String::isNotBlank)
            val words = LinkedHashSet<String>()
            words += name
            words += file.lowercase()
            for (w in name) alternates[w]?.split(' ')?.let(words::addAll)
            alternates[file]?.split(' ')?.let(words::addAll)
            categoryWords[category]?.split(' ')?.let(words::addAll)
            Entry(uri, words.toList(), name.toSet())
        }
    }.also { if (it.isNotEmpty()) index = it }

    private val categoryWords = mapOf(
        "emotions" to "face feeling emotion smiley",
        "food" to "food eat",
        "nature" to "nature animal plant weather",
        "objects" to "object thing",
        "events" to "activity sport event",
        "transportation" to "travel place transport vehicle",
        "symbols" to "symbol sign"
    )

    /** The emojis matching [query] (best first), or empty for a blank query. */
    fun search(query: String): List<Uri> {
        val terms = query.lowercase().split(' ', ',').map(String::trim).filter(String::isNotEmpty)
        if (terms.isEmpty()) return emptyList()
        fun matches(word: String, term: String) =
            word.startsWith(term) || (term.length > 3 && term.endsWith("s") && word.startsWith(term.dropLast(1)))
        return index().mapNotNull { entry ->
            var score = 0
            for (term in terms) {
                // Whole words before partial ones ("car" finds cars before carrots).
                score += when {
                    entry.nameWords.any { it == term } -> 4
                    entry.words.any { it == term } -> 3
                    entry.nameWords.any { matches(it, term) } -> 2
                    entry.words.any { matches(it, term) } -> 1
                    else -> return@mapNotNull null
                }
            }
            entry to score
        }.sortedByDescending { it.second }.map { it.first.uri }
    }
}
