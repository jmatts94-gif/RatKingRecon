package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The combat rules, checked directly.
 *
 * [Battle] has no Android dependencies and no randomness, so the whole system
 * can be verified here rather than by walking around with a phone.
 */
class BattleTest {

    private fun battle(
        ratPower: Int = 3,
        ratHp: Int = 30,
        botPower: Int = 3,
        botHp: Int = 30
    ) = Battle("Rat", ratPower, ratHp, "Rustbot", botPower, botHp)

    @Test
    fun `attack deals damage equal to power`() {
        val b = battle(ratPower = 4)
        val r = b.advance(BattleAction.ATTACK)
        assertEquals(4, r.damageDealt)
        assertEquals(26, b.botHp)
    }

    @Test
    fun `special deals one and a half times power`() {
        assertEquals(5, battle(ratPower = 3).specialDamage())   // 4.5 rounds to 5
        assertEquals(6, battle(ratPower = 4).specialDamage())
        assertEquals(2, battle(ratPower = 1).specialDamage())   // 1.5 rounds to 2
    }

    @Test
    fun `special is available immediately then locked for the cooldown`() {
        val b = battle()
        assertTrue(b.specialAvailable)

        b.advance(BattleAction.SPECIAL)
        assertFalse("locked the round after use", b.specialAvailable)

        b.advance(BattleAction.ATTACK)
        assertFalse("still locked two rounds after", b.specialAvailable)

        b.advance(BattleAction.ATTACK)
        assertTrue("ready again three rounds later", b.specialAvailable)
    }

    @Test
    fun `requesting special on cooldown falls back to attack`() {
        val b = battle(ratPower = 4)
        b.advance(BattleAction.SPECIAL)
        val r = b.advance(BattleAction.SPECIAL)
        assertEquals(BattleAction.ATTACK, r.action)
        assertEquals(4, r.damageDealt)
    }

    @Test
    fun `defend halves incoming damage and deals none`() {
        val b = battle(botPower = 6)
        val r = b.advance(BattleAction.DEFEND)
        assertEquals(0, r.damageDealt)
        assertEquals(3, r.damageTaken)
        assertEquals(30, b.botHp)
    }

    @Test
    fun `the bot does not strike back if it died this round`() {
        val b = battle(ratPower = 10, botHp = 5, botPower = 7)
        val r = b.advance(BattleAction.ATTACK)
        assertEquals(0, r.damageTaken)
        assertEquals(b.ratMaxHp, b.ratHp)
        assertEquals(BattleOutcome.PLAYER_WON, r.outcome)
    }

    @Test
    fun `player loses when the rat reaches zero`() {
        val b = battle(ratPower = 1, ratHp = 4, botPower = 4, botHp = 100)
        val r = b.advance(BattleAction.ATTACK)
        assertEquals(0, b.ratHp)
        assertEquals(BattleOutcome.PLAYER_LOST, r.outcome)
    }

    // --- the agreed difficulty curve -------------------------------------

    @Test
    fun `ramp starts high, passes parity around eight and caps at eleven`() {
        // A first fight should cost something. Starting at 0.45 left 82% of a
        // rat's health on a level one win, which is not a fight.
        assertEquals(0.750, RustbotFactory.rampFor(1), 0.001)
        assertEquals(0.925, RustbotFactory.rampFor(6), 0.001)
        assertEquals(0.995, RustbotFactory.rampFor(8), 0.001)

        // Past parity, because a mirror match is not an even fight: the rat
        // swings first and the Rustbot does not reply on the round it dies.
        assertEquals(1.10, RustbotFactory.rampFor(11), 0.001)
        assertEquals(1.10, RustbotFactory.rampFor(40), 0.001)
        assertTrue("the ramp must climb past parity", RustbotFactory.rampFor(40) > 1.0)

        // And it must only ever climb.
        val curve = (1..20).map { RustbotFactory.rampFor(it) }
        assertEquals(curve, curve.sorted())
    }

    // --- the Rustbot's Special --------------------------------------------

    @Test
    fun `the bot special lands on its own cadence, out of phase with the rat`() {
        val b = battle(ratPower = 1, ratHp = 500, botPower = 4, botHp = 500)

        // Rounds 1 and 2 are plain swings; the third is the Special.
        assertFalse(b.advance(BattleAction.ATTACK).botUsedSpecial)
        assertFalse(b.advance(BattleAction.ATTACK).botUsedSpecial)

        val special = b.advance(BattleAction.ATTACK)
        assertTrue("the bot should special on round 3", special.botUsedSpecial)
        assertEquals(6, special.damageTaken)

        assertFalse(b.advance(BattleAction.ATTACK).botUsedSpecial)
        assertFalse(b.advance(BattleAction.ATTACK).botUsedSpecial)
        assertTrue("and again three rounds later", b.advance(BattleAction.ATTACK).botUsedSpecial)
    }

    @Test
    fun `the bot special deals one and a half times its power`() {
        assertEquals(6, battle(botPower = 4).botSpecialDamage())
        assertEquals(5, battle(botPower = 3).botSpecialDamage())
        assertEquals(2, battle(botPower = 1).botSpecialDamage())
    }

    @Test
    fun `defending halves the bot special too`() {
        val b = battle(ratPower = 1, ratHp = 500, botPower = 6, botHp = 500)
        b.advance(BattleAction.ATTACK)
        b.advance(BattleAction.ATTACK)

        val blocked = b.advance(BattleAction.DEFEND)
        assertTrue(blocked.botUsedSpecial)
        assertEquals("9 halved", 4, blocked.damageTaken)
    }

    @Test
    fun `a dying bot lands no special`() {
        // Three swings of 10 exactly finish 30 HP, so the bot dies on the very
        // round its Special was due.
        val b = battle(ratPower = 10, ratHp = 500, botPower = 6, botHp = 30)

        assertFalse(b.advance(BattleAction.ATTACK).botUsedSpecial)
        assertFalse(b.advance(BattleAction.ATTACK).botUsedSpecial)

        val last = b.advance(BattleAction.ATTACK)
        assertEquals(BattleOutcome.PLAYER_WON, last.outcome)
        assertEquals("a dead Rustbot does not swing", 0, last.damageTaken)
        assertFalse(last.botUsedSpecial)
    }

    /** The bot having a Special is what makes an even fight actually even. */
    @Test
    fun `an ordinary encounter is now losable`() {
        val rat = RatEntity(artKey = "bolt_pic", name = "R", power = 5, toughness = 5, shiny = false)
        val bot = RustbotFactory.forEncounter(20, rat)

        // The ramp lives in HP now. Power stops at parity however high it goes,
        // which is what stops a bigger rat meeting a harder Rustbot.
        assertEquals("an ordinary Rustbot never out-hits its rat", rat.power, bot.power)
        assertTrue("but it does outlast it", bot.maxHp > rat.maxHp)

        val result = AutoResolver.resolve(
            Encounter(1, bot.name, bot.power, bot.maxHp, 0).toBattle(rat)
        )
        assertEquals(BattleOutcome.PLAYER_LOST, result.outcome)
    }

    /** And the Shop is what turns it back around. */
    @Test
    fun `a combat item turns a losing encounter into a win`() {
        val rat = RatEntity(artKey = "bolt_pic", name = "R", power = 5, toughness = 5, shiny = false)
        val bot = RustbotFactory.forEncounter(20, rat)
        val encounter = Encounter(1, bot.name, bot.power, bot.maxHp, 0)

        assertEquals(
            BattleOutcome.PLAYER_LOST,
            AutoResolver.resolve(encounter.toBattle(rat)).outcome
        )
        assertEquals(
            BattleOutcome.PLAYER_WON,
            AutoResolver.resolve(
                encounter.toBattle(rat, Loadout(ShopEffects.SURGE_MULTIPLIER))
            ).outcome
        )
    }

    /**
     * A first encounter should be won, and should cost something.
     *
     * It used to cost almost nothing: at 0.45 the Rustbot came out with 1 Power
     * against a rat with 30 HP, and a level one win left 82% of that health.
     * The ramp starts at 0.75 now, so the same fight takes about half.
     */
    @Test
    fun `a level one encounter is winnable but not free`() {
        val rat = RatEntity(artKey = "bolt_pic", name = "R", power = 3, toughness = 3, shiny = false)
        val bot = RustbotFactory.forEncounter(1, rat)

        assertEquals(2, bot.power)
        assertEquals("75% of the rat's 30 HP", 23, bot.maxHp)

        val result = AutoResolver.resolve(
            Battle("R", rat.power, rat.maxHp, bot.name, bot.power, bot.maxHp)
        )
        assertEquals(BattleOutcome.PLAYER_WON, result.outcome)
        assertTrue("a first win should leave a mark", result.ratHp < rat.maxHp * 3 / 4)
        assertTrue("but not be a near-death experience", result.ratHp > 0)
    }

    @Test
    fun `a fight at the top of the ramp is winnable but close`() {
        val rat = RatEntity(artKey = "bolt_pic", name = "R", power = 3, toughness = 3, shiny = false)
        val bot = RustbotFactory.forEncounter(12, rat)

        assertEquals("Power stops at parity", 3, bot.power)
        assertEquals("110% of the rat's 30 HP", 33, bot.maxHp)

        val result = AutoResolver.resolve(
            Battle("R", rat.power, rat.maxHp, bot.name, bot.power, bot.maxHp)
        )
        assertEquals(BattleOutcome.PLAYER_WON, result.outcome)
        assertTrue("should be a real fight", result.ratHp < rat.maxHp / 2)
    }

    @Test
    fun `scaling protects a weak rat at high level`() {
        val weak = RatEntity(artKey = "bolt_pic", name = "W", power = 1, toughness = 1, shiny = false)
        val bot = RustbotFactory.forEncounter(30, weak)
        assertEquals("never outclasses a weak rat", 1, bot.power)

        val result = AutoResolver.resolve(
            Battle("W", weak.power, weak.maxHp, bot.name, bot.power, bot.maxHp)
        )
        assertEquals(BattleOutcome.PLAYER_WON, result.outcome)
    }

    @Test
    fun `auto resolve always terminates`() {
        for (p in 1..10) for (t in 1..10) {
            val rat = RatEntity(artKey = "bolt_pic", name = "R", power = p, toughness = t, shiny = false)
            for (level in intArrayOf(1, 6, 12, 30)) {
                val bot = RustbotFactory.forEncounter(level, rat)
                val done = AutoResolver.resolve(
                    Battle("R", rat.power, rat.maxHp, bot.name, bot.power, bot.maxHp)
                )
                assertTrue(
                    "P$p T$t L$level did not finish",
                    done.outcome != BattleOutcome.ONGOING
                )
            }
        }
    }

    // --- reviving mid-fight with Scrap --------------------------------------

    @Test
    fun `reviving a lost battle restores full hp and reopens it for play`() {
        val b = battle(ratPower = 1, ratHp = 4, botPower = 4, botHp = 500)
        val loss = b.advance(BattleAction.ATTACK)
        assertEquals(BattleOutcome.PLAYER_LOST, loss.outcome)
        assertEquals(0, b.ratHp)

        b.revive()

        assertEquals(BattleOutcome.ONGOING, b.outcome)
        assertEquals("full hp, not merely alive", b.ratMaxHp, b.ratHp)

        // And the fight actually continues rather than only looking like it does.
        val next = b.advance(BattleAction.ATTACK)
        assertEquals(1, next.damageDealt)
    }

    @Test
    fun `reviving does not reset the round count or the bot's own special cooldown`() {
        // Tuned so round 3 lands the bot's Special without killing the rat, and
        // round 4's plain hit is what finishes it - so the loss happens with the
        // bot's cooldown already mid-cycle, which is the state revive must not
        // disturb.
        val b = Battle("Rat", 1, 85, "Bot", 20, 5000)
        b.advance(BattleAction.ATTACK)
        b.advance(BattleAction.ATTACK)
        val special = b.advance(BattleAction.ATTACK)
        assertTrue("bot special due on round 3", special.botUsedSpecial)
        assertFalse("cooldown just started", b.botSpecialReady)

        val loss = b.advance(BattleAction.ATTACK)
        assertEquals(BattleOutcome.PLAYER_LOST, loss.outcome)

        b.revive()

        assertEquals("the round count is not reset", 4, b.round)
        assertFalse(
            "the bot's cooldown must not be reset to ready by a revive",
            b.botSpecialReady
        )
    }

    @Test
    fun `reviving an ongoing or already-won battle is a caller error`() {
        assertThrows(IllegalStateException::class.java) { battle().revive() }

        val won = battle(ratPower = 100, botHp = 1)
        won.advance(BattleAction.ATTACK)
        assertThrows(IllegalStateException::class.java) { won.revive() }
    }

    // --- a boss's named Special ---------------------------------------------

    @Test
    fun `a boss's named move deals bonus damage against its target faction`() {
        val b = Battle(
            "Rat", 1, 5000, "The Junk Golem", 20, 5000,
            bossId = "junk_golem", ratFaction = Roster.SMUGGLERS
        )
        repeat(2) { b.advance(BattleAction.ATTACK) }
        val hit = b.advance(BattleAction.ATTACK)

        assertEquals(R.string.boss_move_gear_smash, hit.bossMoveNameRes)
        assertEquals("30 base Special, +10% for the matching faction", 33, hit.damageTaken)
    }

    @Test
    fun `a boss's named move deals no bonus outside its target faction`() {
        val b = Battle(
            "Rat", 1, 5000, "The Junk Golem", 20, 5000,
            bossId = "junk_golem", ratFaction = Roster.TINKERERS
        )
        repeat(2) { b.advance(BattleAction.ATTACK) }
        val hit = b.advance(BattleAction.ATTACK)

        assertEquals(R.string.boss_move_gear_smash, hit.bossMoveNameRes)
        assertEquals("no faction match, no bonus", 30, hit.damageTaken)
    }

    @Test
    fun `a boss move never applies to an ordinary encounter`() {
        val b = battle(ratPower = 1, ratHp = 5000, botPower = 20, botHp = 5000)
        repeat(2) { b.advance(BattleAction.ATTACK) }
        val hit = b.advance(BattleAction.ATTACK)

        assertNull(hit.bossMoveNameRes)
        assertEquals(30, hit.damageTaken)
        assertEquals(0, hit.dotDamage)
    }

    @Test
    fun `rusty rake applies a lingering dot for three rounds, whether or not the bonus lands`() {
        val b = Battle(
            "Rat", 1, 5000, "Old Ironclaw", 20, 5000,
            // A mismatched faction, so this also checks the dot is unconditional
            // even when the bonus damage on the hit itself is not.
            bossId = "old_ironclaw", ratFaction = Roster.FOUNDRY_BORN
        )

        val r1 = b.advance(BattleAction.ATTACK)
        val r2 = b.advance(BattleAction.ATTACK)
        assertEquals(0, r1.dotDamage)
        assertEquals(0, r2.dotDamage)

        val r3 = b.advance(BattleAction.ATTACK)
        assertEquals(R.string.boss_move_rusty_rake, r3.bossMoveNameRes)
        assertEquals("no bonus - Foundry-born is not Rusty Rake's target", 30, r3.damageTaken)
        assertEquals(3, r3.dotDamage)

        val r4 = b.advance(BattleAction.ATTACK)
        assertNull("only the round the move lands names it", r4.bossMoveNameRes)
        assertEquals(20, r4.damageTaken)
        assertEquals("corrosion outlasts the round it started on", 3, r4.dotDamage)

        val r5 = b.advance(BattleAction.ATTACK)
        assertEquals(3, r5.dotDamage)
    }

    // --- the other direction: a rat exploiting the boss's own weakness ------

    @Test
    fun `a rat from the boss's weak faction deals bonus damage on its own special`() {
        // Junk Golem is weak to Brawlers - see BossMovesTest.
        val b = Battle(
            "Rat", 20, 5000, "The Junk Golem", 1, 5000,
            bossId = "junk_golem", ratFaction = Roster.BRAWLERS
        )
        val plain = b.advance(BattleAction.ATTACK)
        assertFalse(plain.ratWeaknessBonusApplied)

        val special = b.advance(BattleAction.SPECIAL)
        assertTrue(special.ratWeaknessBonusApplied)
        assertEquals("30 base Special, +10% for the boss's weak faction", 33, special.damageDealt)
    }

    @Test
    fun `a rat outside the boss's weak faction gets no bonus on its special`() {
        val b = Battle(
            "Rat", 20, 5000, "The Junk Golem", 1, 5000,
            bossId = "junk_golem", ratFaction = Roster.SMUGGLERS
        )
        val special = b.advance(BattleAction.SPECIAL)
        assertFalse(special.ratWeaknessBonusApplied)
        assertEquals(30, special.damageDealt)
    }

    @Test
    fun `foundry-born gets no bonus and no penalty against any boss`() {
        val bosses = listOf("junk_golem", "old_ironclaw", "boiler_baron", "circuit_reaper", "rustbringer")

        for (bossId in bosses) {
            // The rat's own special: no weakness bonus for a faction outside the wheel.
            val outgoing = Battle(
                "Rat", 20, 5000, "Boss", 1, 5000, bossId = bossId, ratFaction = Roster.FOUNDRY_BORN
            )
            val ratHit = outgoing.advance(BattleAction.SPECIAL)
            assertFalse("$bossId should not favour Foundry-born", ratHit.ratWeaknessBonusApplied)
            assertEquals("$bossId: plain special damage only", 30, ratHit.damageDealt)

            // The boss's own move: only rustbringer (targetFaction = null) may
            // still hit a Foundry-born rat - every named boss must spare it.
            val incoming = Battle(
                "Rat", 1, 5000, "Boss", 20, 5000, bossId = bossId, ratFaction = Roster.FOUNDRY_BORN
            )
            repeat(2) { incoming.advance(BattleAction.ATTACK) }
            val botHit = incoming.advance(BattleAction.ATTACK)
            if (bossId == "rustbringer") {
                assertTrue("rustbringer spares no faction", botHit.bossMoveBonusApplied)
            } else {
                assertFalse("$bossId should not target Foundry-born", botHit.bossMoveBonusApplied)
            }
        }
    }

    @Test
    fun `rustbringer's bonus reaches a faction every other boss spares`() {
        // Foundry-born is the one faction none of the four named moves target.
        val b = Battle(
            "Rat", 1, 5000, "The Rustbringer", 20, 5000,
            bossId = "rustbringer", ratFaction = Roster.FOUNDRY_BORN
        )
        repeat(2) { b.advance(BattleAction.ATTACK) }
        val hit = b.advance(BattleAction.ATTACK)

        assertEquals(R.string.boss_move_gear_smash, hit.bossMoveNameRes)
        assertEquals("no faction is spared from the last boss", 33, hit.damageTaken)
    }

    // --- bot HP is scaled, not derived from an integer ---------------------

    /**
     * The bug this model replaced.
     *
     * HP used to come from a rounded Toughness times ten, so scaling a rat's
     * stat across a whole number moved the Rustbot's health by a fifth in one
     * step - and a 5/5 rat crossed that line where a 4/4 did not, meeting a
     * harder fight for being the better rat. HP now tracks the ratio directly.
     */
    @Test
    fun `bot hp scales in points rather than in tens`() {
        val rat = RatEntity(artKey = "bolt_pic", name = "R", power = 4, toughness = 4, shiny = false)

        val hps = (1..20).map { RustbotFactory.forEncounter(it, rat).maxHp }

        assertTrue("HP should never fall as the ramp climbs", hps == hps.sorted())
        assertTrue(
            "ten-HP steps mean the old formula is still in play",
            hps.distinct().size > hps.count { it % 10 == 0 }
        )
    }

    @Test
    fun `an ordinary rustbot never out-hits the rat it was built for`() {
        for (level in intArrayOf(1, 12, 14, 40)) {
            for (stat in 1..12) {
                val rat = RatEntity(
                    artKey = "bolt_pic", name = "R",
                    power = stat, toughness = stat, shiny = false
                )
                val bot = RustbotFactory.forEncounter(level, rat)
                assertTrue(
                    "L$level rat $stat met a bot with power ${bot.power}",
                    bot.power <= rat.power
                )
            }
        }
    }

    /** The inversion, asserted gone: a bigger rat must not draw a harder fight. */
    @Test
    fun `difficulty does not depend on how big the rat is`() {
        val outcomes = intArrayOf(2, 3, 4, 6, 8, 10, 12).map { stat ->
            val rat = RatEntity(
                artKey = "bolt_pic", name = "R",
                power = stat, toughness = stat, shiny = false
            )
            val bot = RustbotFactory.forEncounter(40, rat)
            AutoResolver.resolve(
                Encounter(1, bot.name, bot.power, bot.maxHp, 0).toBattle(rat)
            ).outcome
        }

        assertEquals(
            "a mirror fight should read the same at every rat size",
            1,
            outcomes.distinct().size
        )
    }

    // --- the four combat items ----------------------------------------------

    @Test
    fun `using an item with none named falls back to attack`() {
        val b = battle(ratPower = 4)
        val r = b.advance(BattleAction.USE_ITEM)
        assertEquals(BattleAction.ATTACK, r.action)
        assertEquals(4, r.damageDealt)
    }

    @Test
    fun `round result names which item was used, or null for an ordinary round`() {
        val b = battle()
        val r = b.advance(BattleAction.USE_ITEM, BattleItem.CLEANSE)
        assertEquals(BattleItem.CLEANSE, r.itemUsed)

        val plain = b.advance(BattleAction.ATTACK)
        assertNull(plain.itemUsed)
    }

    @Test
    fun `using an item deals no damage and does not halve the reply, unlike defend`() {
        val b = battle(botPower = 6)
        val r = b.advance(BattleAction.USE_ITEM, BattleItem.HP_TONIC)
        assertEquals(0, r.damageDealt)
        assertEquals("full exposure, not halved like Defend", 6, r.damageTaken)
    }

    @Test
    fun `hp tonic restores 70 percent of max hp`() {
        val b = Battle("Rat", 1, 100, "Bot", 80, 5000)
        b.advance(BattleAction.ATTACK) // ratHp: 100 - 80 = 20
        assertEquals(20, b.ratHp)

        // 70 healed to 90, then the same round's reply takes 80 back off.
        b.advance(BattleAction.USE_ITEM, BattleItem.HP_TONIC)
        assertEquals(10, b.ratHp)
    }

    @Test
    fun `hp tonic cannot heal past max hp`() {
        val b = Battle("Rat", 1, 100, "Bot", 10, 5000)
        b.advance(BattleAction.ATTACK) // ratHp: 100 - 10 = 90
        assertEquals(90, b.ratHp)

        // 70 healed would be 160, capped at 100, then this round's reply takes 10.
        b.advance(BattleAction.USE_ITEM, BattleItem.HP_TONIC)
        assertEquals(90, b.ratHp)
    }

    @Test
    fun `corrosive charge stacks compound rather than refresh`() {
        val b = Battle("Rat", 10, 5000, "Bot", 1, 5000)

        val r1 = b.advance(BattleAction.USE_ITEM, BattleItem.CORROSIVE_CHARGE)
        assertEquals("20% of the rat's own Power, ticking the same round it lands", 2, r1.enemyDotDamage)

        val r2 = b.advance(BattleAction.USE_ITEM, BattleItem.CORROSIVE_CHARGE)
        assertEquals("a second stack adds to the first rather than replacing it", 4, r2.enemyDotDamage)

        val r3 = b.advance(BattleAction.ATTACK)
        assertEquals("both stacks are still on their third and final round each", 4, r3.enemyDotDamage)

        val r4 = b.advance(BattleAction.ATTACK)
        assertEquals("the first stack has expired; only the second remains", 2, r4.enemyDotDamage)

        val r5 = b.advance(BattleAction.ATTACK)
        assertEquals("both stacks have now expired", 0, r5.enemyDotDamage)
    }

    @Test
    fun `corrosive charge can finish the bot off, and a dead bot does not reply`() {
        val b = Battle("Rat", 100, 5000, "Bot", 50, 3)
        // 20% of 100 Power is 20, comfortably past the bot's 3 HP.
        val r = b.advance(BattleAction.USE_ITEM, BattleItem.CORROSIVE_CHARGE)

        assertEquals(0, b.botHp)
        assertEquals(BattleOutcome.PLAYER_WON, r.outcome)
        assertEquals("a bot killed by corrosion does not swing back", 0, r.damageTaken)
    }

    @Test
    fun `reinforced plating cuts incoming damage for three rounds including the one it is used on`() {
        val b = Battle("Rat", 1, 5000, "Bot", 10, 5000)

        val r1 = b.advance(BattleAction.USE_ITEM, BattleItem.REINFORCED_PLATING)
        assertEquals("30% off a plain 10, applied the same round it activates", 7, r1.damageTaken)

        val r2 = b.advance(BattleAction.ATTACK)
        assertEquals("still active on its second round", 7, r2.damageTaken)

        // Round three is also the bot's own Special (every three rounds), so
        // the reduction here is checked against that base instead of a plain hit.
        val r3 = b.advance(BattleAction.ATTACK)
        assertTrue(r3.botUsedSpecial)
        assertEquals("30% off a 15 Special - its third and last protected round", 11, r3.damageTaken)

        val r4 = b.advance(BattleAction.ATTACK)
        assertEquals("worn off by the fourth round", 10, r4.damageTaken)
    }

    @Test
    fun `cleanse clears an active dot on the player's own rat`() {
        val b = Battle(
            "Rat", 1, 5000, "Old Ironclaw", 20, 5000,
            bossId = "old_ironclaw", ratFaction = Roster.FOUNDRY_BORN
        )
        repeat(2) { b.advance(BattleAction.ATTACK) }
        val hit = b.advance(BattleAction.ATTACK)
        assertEquals("rusty rake has armed the corrosion", 3, hit.dotDamage)

        val cleansed = b.advance(BattleAction.USE_ITEM, BattleItem.CLEANSE)
        assertEquals("gone the same round it is cleansed", 0, cleansed.dotDamage)

        val after = b.advance(BattleAction.ATTACK)
        assertEquals("and stays gone", 0, after.dotDamage)
    }

    @Test
    fun `rewards stay inside the intended band`() {
        for (level in intArrayOf(1, 10, 30)) {
            val base = 10 + level * 2
            repeat(200) {
                val reward = RustbotFactory.rewardFor(level)
                assertTrue("$reward too low for L$level", reward >= (base * 0.85).toInt())
                assertTrue("$reward too high for L$level", reward <= (base * 1.15).toInt() + 1)
            }
        }
    }
}
