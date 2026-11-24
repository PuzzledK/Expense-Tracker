package com.expensetracker.app.data

/**
 * Guesses a category from the note text with keyword rules.
 *
 * Rules are checked tier by tier, first match wins. Some categories appear twice:
 * a "strong" tier (e.g. "lunch", "zepto") that must beat a weaker one
 * (e.g. "paneer" in "Lunch, Paneer Roll" should still be Food).
 */
object Categorizer {

    private val TIERS: List<Pair<Category, List<String>>> = listOf(
        Category.SAVINGS to listOf("fd", "rd", "sip", "mutual fund", "savings", "investment"),
        Category.RENT to listOf("rent", "electricity", "hostel", "maintenance", "wifi bill", "water bill"),
        Category.BILLS to listOf(
            "recharge", "airtel", "jio", "vi", "vpn", "mullvad", "claude", "prime", "gold pass",
            "gplay", "google play", "netflix", "spotify", "youtube premium", "subscription", "hotstar",
        ),
        Category.HEALTH to listOf(
            "protein", "creatine", "medicine", "medicines", "doctor", "pharmacy", "gym", "eno", "tablet",
        ),
        Category.PEOPLE to listOf("mammi", "mummy", "richa", "anu", "gift", "mother's day", "father's day", "birthday"),
        Category.GROCERIES to listOf(
            "groceries", "grocery", "zepto", "smart bazaar", "blinkit", "instamart", "bigbasket", "dmart",
        ),
        Category.SHOPPING to listOf(
            "boiler", "cutting board", "cutter", "strainer", "induction", "kadhai", "knife", "mr diy", "spoons",
        ),
        Category.FOOD to listOf(
            "lunch", "dinner", "breakfast", "biryani", "roll", "bowl", "pizza", "domino", "domnick",
            "zomato", "swiggy", "eatclub", "momos", "maggie", "dosa", "kravings", "cookhouse",
            "restaurant", "garlic bread", "ice cream", "meal", "thali",
        ),
        Category.GROCERIES to listOf(
            "paneer", "egg", "roti", "bread", "rice", "milk", "curd", "dahi", "tomato", "tomatoes",
            "onion", "cheese", "sooji", "toilet paper", "surf excel", "sirf excel", "vim", "sponge",
            "salt", "oil", "sauce", "chutney", "chilli", "oregano", "cream", "buttermilk", "pasta",
            "noodles", "yippie", "yippee", "yippi", "maggi", "spread", "atta", "dal", "vegetables",
            "fruits", "banana", "egg tray",
        ),
        Category.FOOD to listOf(
            "snacks", "coke", "naan", "chat", "chaat", "bhel", "bhelpuri", "cafe", "coffee", "kurkure",
            "chips", "chocolate", "chocolates", "lassi", "samosa", "ramen", "matcha", "dessert",
            "cookies", "cookie", "cheesecake", "mojito", "soda", "tikki", "water bottle", "bottle",
            "papdi", "paratha", "parathe", "tea", "chai", "juice", "sandwich", "burger", "food",
        ),
        Category.TRANSPORT to listOf(
            "rapido", "auto", "metro", "train", "flight", "ticket", "uber", "ola", "cab", "bus",
            "petrol", "fuel", "toll", "parking",
        ),
        Category.ENTERTAINMENT to listOf(
            "pog", "district", "pickle ball", "pickleball", "coc", "movie", "pvr", "concert",
            "bowling", "party", "outing",
        ),
        Category.SHOPPING to listOf(
            "shirt", "t shirt", "tshirt", "jeans", "watch", "earphones", "earbuds", "buds", "deo",
            "shampoo", "facewash", "face wash", "max fashion", "vest", "socks", "hdmi", "cable",
            "extender", "skirt", "nightsuit", "pyjama", "volumizer", "belt", "colors", "skin",
            "case", "charger", "shoes", "amazon", "flipkart", "myntra", "clothes", "haircut",
        ),
    )

    private val COMPILED: List<Pair<Category, Regex>> = TIERS.map { (cat, words) ->
        val alternation = words.sortedByDescending { it.length }.joinToString("|") { w ->
            Regex.escape(normalize(w)).replace(" ", "\\E\\s+\\Q")
        }
        // Word boundary on both sides, allowing a plural "s"/"es" suffix.
        cat to Regex("""(?<![a-z0-9])(?:$alternation)(?:es|s)?(?![a-z0-9])""")
    }

    fun guess(note: String): Category {
        val n = normalize(note)
        if (n.isBlank()) return Category.OTHER
        for ((cat, regex) in COMPILED) {
            if (regex.containsMatchIn(n)) return cat
        }
        return Category.OTHER
    }

    /** Lowercase, "mr.diy" → "mr diy", "t-shirt" → "t shirt", "domino's" → "domino's". */
    private fun normalize(s: String): String =
        s.lowercase()
            .replace('’', '\'')
            .replace(Regex("""[^a-z0-9']+"""), " ")
            .replace(Regex("""'s\b"""), "")       // "domino's" → "domino", "mother's day" → "mother day"
            .replace("'", "")
            .trim()
}
