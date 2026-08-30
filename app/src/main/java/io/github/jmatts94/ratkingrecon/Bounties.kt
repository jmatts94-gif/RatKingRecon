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
 * Reward-per-step rises with tier on purpose, so committing to one long
 * contract beats grinding the same distance out of repeated short ones -
 * SHORT alone paid better per step than MEDIUM or LONG did, which made
 * spamming the cheapest, fastest contract the strictly optimal play. LONG's
 * range floor (300) still tops out below SHORT's ceiling run twenty times
 * over (20 * 15 = 300), so the two are only ever a wash in that single most
 * extreme case - LONG wins on any real roll, not just on average.
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
        rewards = 65..85
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
        rewards = 300..390
    )
}
