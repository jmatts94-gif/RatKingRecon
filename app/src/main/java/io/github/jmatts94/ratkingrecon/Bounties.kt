package io.github.jmatts94.ratkingrecon

/**
 * One rolled contract, ready to show on the board.
 *
 * [reward] is fixed the moment the offer is rolled, so what the button
 * advertises is exactly what [MainActivity.startActiveBounty] banks.
 */
data class BountyOffer(
    val name: String,
    val steps: Float,
    val minutes: Int,
    val reward: Int
)

/**
 * A difficulty band on the Contract Board.
 *
 * Each band keeps a pool of flavour names and a reward range; [roll] picks one
 * of each, so the board reads differently every time it is opened.
 */
data class BountyTier(
    val names: List<String>,
    val steps: Float,
    val minutes: Int,
    val rewards: IntRange
) {
    fun roll(): BountyOffer = BountyOffer(
        name = names.random(),
        steps = steps,
        minutes = minutes,
        reward = rewards.random()
    )
}

/**
 * The contract pool.
 *
 * Rewards are deliberately small relative to the Scrap sinks they feed - the
 * Fusion Pot costs 5 and the Masterwork Hatchery 800 - so a single long contract
 * does not immediately pay for everything.
 */
object Bounties {

    val SHORT = BountyTier(
        names = listOf("Quick Haul", "Loose Bolt Pickup", "Back-Alley Errand"),
        steps = 50f,
        minutes = 2,
        rewards = 8..15
    )

    val MEDIUM = BountyTier(
        names = listOf(
            "Copper Wire Raid",
            "Rusty Gear Retrieval",
            "Steam Vent Patrol",
            "Loading Dock Shuffle"
        ),
        steps = 250f,
        minutes = 10,
        rewards = 25..40
    )

    val LONG = BountyTier(
        names = listOf(
            "Industrial Scavenge",
            "The Junkyard Deep Dive",
            "Midnight Foundry Run",
            "The Great Scrap Convoy"
        ),
        steps = 1000f,
        minutes = 30,
        rewards = 70..100
    )
}
