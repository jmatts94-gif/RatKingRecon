package com.example.ratkingrecon

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MissionActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    // Upgraded Real-World Timers!
    private val timeM1 = 2 * 60 * 60 * 1000L   // 2 Hours
    private val timeM2 = 8 * 60 * 60 * 1000L   // 8 Hours
    private val timeM3 = 24 * 60 * 60 * 1000L  // 24 Hours

    private val prefixes = arrayOf("The Rust", "The Toxic", "The Abandoned", "The Glowing", "The Flooded", "The Iron")
    private val suffixes = arrayOf("Pipes", "Factory", "Sewer", "Crater", "Warehouse", "Subway", "Scrapyard")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mission)

        sharedPreferences = getSharedPreferences("SaveData", Context.MODE_PRIVATE)

        // 1. Run the Daily Reroll Check
        checkDailyReroll()

        // 6. Wire up the back button first, so it works while stats load
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        // 2. Scan the collection for stats. Aggregated in SQL rather than by
        //    loading every rat, and off the main thread because Room says so.
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val dao = RatRepository.dao(this@MissionActivity)
                Triple(dao.maxPower(), dao.maxToughness(), dao.ownsShiny())
            }
            bindMissions(stats.first, stats.second, stats.third)
        }
    }

    private fun bindMissions(maxPower: Int, maxToughness: Int, ownsShiny: Boolean) {
        // 3. Load M1 (Quick Scout)
        setupMission(
            "M1", maxPower, timeM1,
            findViewById(R.id.titleM1), findViewById(R.id.reqM1), findViewById(R.id.rewardM1), findViewById(R.id.btnMission1),
            "Power"
        )

        // 4. Load M2 (Deep Dive)
        setupMission(
            "M2", maxToughness, timeM2,
            findViewById(R.id.titleM2), findViewById(R.id.reqM2), findViewById(R.id.rewardM2), findViewById(R.id.btnMission2),
            "Toughness"
        )

        // 5. Load M3 (Expedition)
        setupMission(
            "M3", if (ownsShiny) 99 else 0, timeM3,
            findViewById(R.id.titleM3), findViewById(R.id.reqM3), findViewById(R.id.rewardM3), findViewById(R.id.btnMission3),
            "Shiny"
        )
    }

    private fun checkDailyReroll() {
        val currentDay = (System.currentTimeMillis() / (1000 * 60 * 60 * 24)).toInt()
        val lastRollDay = sharedPreferences.getInt("LAST_ROLL_DAY", 0)

        if (currentDay != lastRollDay) {
            val editor = sharedPreferences.edit()

            // Only overwrite missions that are NOT currently active!
            if (!sharedPreferences.getBoolean("M1_ACTIVE", false)) {
                editor.putString("M1_TITLE", "${prefixes.random()} ${suffixes.random()}")
                editor.putInt("M1_REQ", (2..4).random())
                editor.putInt("M1_REWARD", (15..30).random())
            }
            if (!sharedPreferences.getBoolean("M2_ACTIVE", false)) {
                editor.putString("M2_TITLE", "${prefixes.random()} ${suffixes.random()}")
                editor.putInt("M2_REQ", (4..7).random())
                editor.putInt("M2_REWARD", (40..70).random())
            }
            if (!sharedPreferences.getBoolean("M3_ACTIVE", false)) {
                editor.putString("M3_TITLE", "${prefixes.random()} ${suffixes.random()}")
                editor.putInt("M3_REQ", 1) // 1 just means "needs a Shiny"
                editor.putInt("M3_REWARD", (100..200).random())
            }

            editor.putInt("LAST_ROLL_DAY", currentDay).apply()
        }
    }

    private fun setupMission(
        missionId: String, playerStat: Int, durationMs: Long,
        titleTxt: TextView, reqTxt: TextView, rewardTxt: TextView, btn: Button, statName: String
    ) {
        val isActive = sharedPreferences.getBoolean("${missionId}_ACTIVE", false)
        val endTime = sharedPreferences.getLong("${missionId}_END_TIME", 0L)
        val title = sharedPreferences.getString("${missionId}_TITLE", "Unknown Sector")
        val reqAmount = sharedPreferences.getInt("${missionId}_REQ", 1)
        val rewardAmount = sharedPreferences.getInt("${missionId}_REWARD", 10)
        val currentTime = System.currentTimeMillis()

        // Update the UI text dynamically
        titleTxt.text = title
        if (statName == "Shiny") {
            reqTxt.text = "Requires: A [SHINY] Pet"
        } else {
            reqTxt.text = "Requires: $reqAmount+ $statName"
        }
        val hours = durationMs / (1000 * 60 * 60)
        rewardTxt.text = "Time: $hours Hours  |  Reward: $rewardAmount Scrap"

        val requirementMet = playerStat >= reqAmount

        if (isActive) {
            if (currentTime >= endTime) {
                // MISSION COMPLETE
                btn.text = "Claim Reward"
                tintButton(btn, R.color.amber)

                btn.setOnClickListener {
                    val currentScrap = sharedPreferences.getInt("SCRAP", 0)
                    val editor = sharedPreferences.edit()

                    // The Relic Drop (25% chance on any success)
                    var lootMessage = "Mission Success! +$rewardAmount Scrap"
                    if ((1..100).random() <= 25) {
                        val relics = arrayOf("⚙️ Rusted Gear", "🧪 Glowing Vial", "📜 Tattered Blueprint", "🔧 Heavy Wrench")
                        val relicFound = relics.random()
                        val savedRelics = sharedPreferences.getStringSet("RELICS", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        savedRelics.add(relicFound)
                        editor.putStringSet("RELICS", savedRelics)
                        lootMessage = "SUCCESS! +$rewardAmount Scrap\nRARE DROP: $relicFound!"
                    }

                    editor.putInt("SCRAP", currentScrap + rewardAmount)
                    editor.putBoolean("${missionId}_ACTIVE", false)
                    editor.apply()

                    Toast.makeText(this, lootMessage, Toast.LENGTH_LONG).show()
                    recreate()
                }
            } else {
                // MISSION IN PROGRESS
                val minsLeft = ((endTime - currentTime) / (1000 * 60)).toInt()
                btn.text = "Exploring… (${minsLeft}m left)"
                tintButton(btn, R.color.card_border)
                btn.isEnabled = false
            }
        } else {
            // NOT STARTED
            if (requirementMet) {
                btn.text = "Start Expedition"
                tintButton(btn, R.color.amber)

                btn.setOnClickListener {
                    sharedPreferences.edit()
                        .putBoolean("${missionId}_ACTIVE", true)
                        .putLong("${missionId}_END_TIME", System.currentTimeMillis() + durationMs)
                        .apply()
                    recreate()
                }
            } else {
                btn.text = "Roster Too Weak"
                tintButton(btn, R.color.disabled_fill)
                btn.isEnabled = false
            }
        }
    }

    /**
     * Sets a MaterialButton's fill.
     *
     * setBackgroundColor() replaces the whole background drawable on a
     * MaterialButton, which throws away its rounded corners, so the tint has to
     * be applied as a tint list instead.
     */
    private fun tintButton(btn: Button, colorRes: Int) {
        btn.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
    }
}