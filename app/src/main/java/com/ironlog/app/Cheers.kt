package com.ironlog.app

import android.content.Context

/** The summary's heading after a workout: one of 100 lines, never one of the last 30 you saw. */
object Cheers {
    val LINES = listOf(
        // Cocky
        "Built different. 💪", "Gravity just filed a complaint. 📝", "The weights are scared of you now. 😤",
        "That was a war crime against iron. ⚔️", "Somebody call the gym, it's been robbed. 🚨", "Main character energy. 🎬",
        "The barbell asked for your autograph. ✍️", "You didn't lift. You negotiated with gravity and won. 🤝",
        "Too strong. Leave some gains for the rest of us. 🏋️", "Your muscles just sent a thank-you card. 💌",
        "Certified unit. 🦍", "That's not a workout, that's a highlight reel. 🎥", "Hall of fame session. 🏆",
        "Iron: 0. You: everything. 🔥", "Mirror's gonna need a minute. 🪞", "Absolute menace behaviour. 😈",
        "Greek statues are taking notes. 🏛️", "Today's forecast: 100% gains. ⛈️", "Someone check on the dumbbells. 🩹",
        "Your future self just high-fived you. ✋", "Pain is temporary. Being this strong is forever. 🗿",
        "Even your rest days are jealous. 😏", "The plates stacked themselves out of respect. 🫡",
        "Built like a vending machine. 🥤", "You are now 3% more terrifying. 📈",
        // Funny
        "Your couch misses you. Don't go back. 🛋️", "Achievement unlocked: stairs are now a threat. 🪜",
        "Tomorrow's walk will be… interesting. 🚶", "Protein shake? You've earned a protein lake. 🥛",
        "Sit down carefully. Very carefully. 🪑", "Your legs will remember this. Forever. 🦵",
        "Another session, zero excuses. 📋", "That's one way to scare a sofa. 😱", "Soreness loading… ⏳",
        "Congrats, you're now legally a gym regular. 📜", "Your muscles said 'why' and you said 'because'. 🤷",
        "Sweat is just your fat crying. 💧", "Doors will now open with suspicious ease. 🚪",
        "Your shirt sleeves are filing for more space. 👕", "Snack o'clock: officially earned. 🍫",
        "You lifted, the weights lowered their expectations. ⬇️", "New PR: personal reluctance overcome. ✅",
        "Hydrate or diedrate. 🚰", "Shake it off. Literally, your arms are shaking. 🫨", "Dinner is now a reward, not a meal. 🍝",
        "Stretch. Please. Your hamstrings are begging. 🧘", "Quietly becoming a problem. 🤫",
        "Lift heavy, nap heavier. 😴", "Arms like this need a permit. 🪪", "Showered in sweat and glory. 🚿",
        "Went in human, came out upgraded. 🤖", "The gym called. It wants you back tomorrow. ☎️",
        "Your playlist did half the work. You did the rest. 🎧", "Another brick in the wall of muscle. 🧱",
        "That's how legends are forged. 🔨", "Clocked in. Crushed it. Clocked out. ⏱️", "Done and dusted. Mostly dusted with chalk. 🤍",
        "Bones: reinforced. Spirit: unbreakable. 🦴", "The pump is real and it's spectacular. 🎈",
        "Workout complete. Ego: also complete. 👑", "Another day, another chance to be terrifying. 🌅",
        "Somewhere a treadmill is crying. 😢", "You're the reason the gym needs new plates. 🍽️",
        "Fueled by caffeine and spite. ☕",
        // Slang and memes, kept clean
        "Locked in. 🔒", "Let him cook. 🍳", "Aura +1000. ✨", "Sigma session. 🗿", "W workout. 🏅",
        "Absolutely goated. 🐐", "Mogged the weights. 😎", "It's giving… strength. 💅", "No cap, that was elite. 🧢",
        "Not an NPC. Main character arc. 🎮", "That session was bussin. 🔥", "Lowkey highkey a beast. 🐺",
        "Rizz: unchanged. Strength: up. 📈", "Six seven? More like six-pack. 🤌", "Even Ohio couldn't stop you. 🌽",
        "Aura farming complete. 🌾", "Built different, fr fr. 💯", "Gains? Understood the assignment. 📚",
        "Slay. Just slay. 💥", "Big W. Huge W. Massive W. 🏆", "Cooked the weights, not yourself. 👨‍🍳",
        "Ate and left no crumbs. 🍽️", "That's the GOAT behaviour. 🐐", "Delulu? No, the gains are real. 💭",
        "Main quest progressed. 🗺️", "Skill issue? Not today. 🎯", "Bro's on a different level. 🆙",
        "Respectfully: beast mode. 🦁", "Real ones lift. You lifted. 🤝", "Certified gym rat. 🐀",
        "Plot twist: you got stronger. 🎭", "Rent free in the weights' heads. 🧠",
        "Unbothered. Moisturised. Stronger. 🧴", "That's a core memory now. 🧠", "Peak performance unlocked. 🏔️",
        "The grind don't stop. ⚙️",
    )

    private const val KEY = "cheers_recent"
    private const val AVOID = 30

    /** A line for this workout: random, avoiding the last 30 shown (remembered on the phone). */
    fun pick(c: Context): String {
        val prefs = c.getSharedPreferences("loadout_ui", Context.MODE_PRIVATE)
        val recent = prefs.getString(KEY, "").orEmpty().split(",").mapNotNull { it.toIntOrNull() }
        val pool = LINES.indices.filter { it !in recent }.ifEmpty { LINES.indices.toList() }
        val i = pool.random()
        prefs.edit().putString(KEY, (recent + i).takeLast(AVOID).joinToString(",")).apply()
        return LINES[i]
    }
}
