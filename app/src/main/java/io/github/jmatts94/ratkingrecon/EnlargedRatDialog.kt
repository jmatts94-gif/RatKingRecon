package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The full-screen "look at this rat" card - species, faction, stats, and the
 * Battle Rat / Scrap Run actions.
 *
 * Shared between the Binder (tapping any card) and the Splicing screen (the
 * one-time reveal shown right after a successful splice). [extraLines] is
 * how that reveal shows which parent-faction effects fired: they are drawn
 * into the card for this one showing only and never saved on the rat, so
 * opening the same card again later - from the Binder, say - shows none of
 * them.
 */
object EnlargedRatDialog {

    fun show(
        activity: AppCompatActivity,
        pet: RatEntity,
        prefs: SharedPreferences,
        extraLines: List<String> = emptyList(),
        onBattleRatToggled: () -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        // The Dialog's own window still needs the real activity context - it
        // carries the window token android.app.Dialog.show() requires, which
        // a configuration-only context (see lightSteampunkContext) does not
        // have. Only the inflater for its content is swapped, the same
        // technique ReconCard.kt uses to keep this card's colours - XML and
        // the ones looked up here in Kotlin alike - pinned to the Recon
        // Card's fixed cream-and-brass look regardless of Dark Steampunk.
        val cardContext = lightSteampunkContext(activity)
        val dialog = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(
            LayoutInflater.from(activity).cloneInContext(cardContext).inflate(R.layout.dialog_enlarged_rat, null)
        )

        // 1. Hook up the UI Elements
        val enlargedImage = dialog.findViewById<ImageView>(R.id.enlargedRatImage)
        val nameText = dialog.findViewById<TextView>(R.id.enlargedRatName)
        val speciesText = dialog.findViewById<TextView>(R.id.enlargedRatSpecies)
        val factionText = dialog.findViewById<TextView>(R.id.enlargedRatFaction)
        val powerText = dialog.findViewById<TextView>(R.id.enlargedRatPower)
        val toughnessText = dialog.findViewById<TextView>(R.id.enlargedRatToughness)
        val gear1 = dialog.findViewById<ImageView>(R.id.enlargedGear1)
        val gear2 = dialog.findViewById<ImageView>(R.id.enlargedGear2)
        val gear3 = dialog.findViewById<ImageView>(R.id.enlargedGear3)
        val effectsContainer = dialog.findViewById<LinearLayout>(R.id.enlargedEffectsContainer)
        val deployButton = dialog.findViewById<Button>(R.id.deployScrapyardButton)
        val battleRatButton = dialog.findViewById<Button>(R.id.battleRatButton)
        val closeButton = dialog.findViewById<Button>(R.id.closeEnlargedButton)
        val shareButton = dialog.findViewById<View>(R.id.enlargedShareButton)

        // 2. Set the Visuals and Stats
        enlargedImage.setImageResource(pet.imageRes)
        powerText.text = pet.effectivePower.toString()
        toughnessText.text = pet.effectiveToughness.toString()
        gear1.visibility = if (pet.gearCount >= 1) View.VISIBLE else View.GONE
        gear2.visibility = if (pet.gearCount >= 2) View.VISIBLE else View.GONE
        gear3.visibility = if (pet.gearCount >= 3) View.VISIBLE else View.GONE

        // The equipped Binder frame, if any - the same one item_rat_card.xml's
        // own grid shows, drawn at this card's larger scale instead. Left as
        // the plain bg_recon_card_outer brass border declared in the layout
        // for a rat that owns no cosmetic, matching the Recon Card export's
        // own default look (ReconCard.kt) rather than the grid's plain
        // card_border - this card is already a step more premium than a grid
        // tile before any frame is even equipped.
        val equippedFrame = Frames.byId(ShopEffects.equippedCosmetic(prefs))
        val frameAnimator = FrameAnimator()
        if (equippedFrame != null) {
            // This card's own corner radius and stroke width - bg_recon_card_outer's
            // declared 34dp radius, and the border's 5dp padding in
            // dialog_enlarged_rat.xml. Both the solid stroke below and the
            // animated overlay's own border path (see FrameOverlayDrawable's
            // trackCornerDp/trackInsetDp) have to agree with these exact
            // numbers, or the two visibly disagree at every corner - an
            // animated frame's track built for the grid's much smaller,
            // tighter card pokes out past this card's true edge otherwise.
            val cardCornerDp = 34f
            val cardStrokeDp = 5f

            val borderView = dialog.findViewById<View>(R.id.enlargedCardBorder)
            val density = cardContext.resources.displayMetrics.density
            borderView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cardCornerDp * density
                setStroke((cardStrokeDp * density).toInt(), ContextCompat.getColor(cardContext, equippedFrame.strokeColorRes))
            }
            if (equippedFrame.style != FrameStyle.STATIC) {
                val frameOverlay = dialog.findViewById<View>(R.id.enlargedFrameOverlay)
                frameOverlay.background = FrameOverlayDrawable.forFrame(
                    cardContext,
                    equippedFrame,
                    // Concentric with the stroke above: inset to its
                    // centreline, corner radius reduced by the same amount so
                    // the curve continues smoothly from cardCornerDp rather
                    // than snapping to a different one partway round.
                    trackCornerDp = cardCornerDp - cardStrokeDp / 2,
                    trackInsetDp = cardStrokeDp / 2
                )
                frameAnimator.attach(frameOverlay)
            }
        }

        // Read from artKey rather than trusted from pet.name - the one place
        // on this screen that answers "what species is this" regardless of
        // what the name field happens to say. Faction rides the same lookup,
        // since it is flavor drawn from the same roster entry.
        val species = Roster.all.firstOrNull { it.artKey == pet.artKey }
        speciesText.text = activity.getString(R.string.enlarged_rat_species, species?.name ?: pet.name)
        val factionLine = activity.getString(
            R.string.enlarged_rat_faction,
            species?.faction ?: activity.getString(R.string.enlarged_rat_faction_unknown)
        )
        // What this rat is worth on the Scrap Run, appended so the bonus is a
        // visible reason to deploy this rat rather than a hidden incentive -
        // see TaskBonuses.
        factionText.text = TaskBonuses.descriptionFor(pet.faction)?.let {
            "$factionLine  ·  ${activity.getString(it)}"
        } ?: factionLine

        // The star is part of the name text now, not a compound drawable -
        // Battle Rat stays a drawable at the far end, unaffected. The
        // faction glyph takes the opposite, otherwise-empty drawableStart
        // slot, same as the grid card - see RatCardAdapter.
        val onDuty = BattleRat.isBattleRat(prefs, pet.id)
        nameText.text = if (pet.shiny) activity.getString(R.string.card_name_shiny, pet.name) else pet.name
        nameText.setTextColor(
            ContextCompat.getColor(cardContext, if (pet.shiny) R.color.shiny_gold else R.color.text_primary)
        )
        nameText.setCompoundDrawablesRelative(
            CardIcons.faction(cardContext, 24, pet.faction),
            null,
            if (onDuty) CardIcons.battle(cardContext, 22) else null,
            null
        )
        nameText.compoundDrawablePadding = (6 * activity.resources.displayMetrics.density).toInt()

        // The faction effects a splice's parents rolled, if any - see the
        // class comment. Gone entirely rather than shown empty, so an
        // ordinary rat's card looks exactly as it always has.
        effectsContainer.removeAllViews()
        if (extraLines.isEmpty()) {
            effectsContainer.visibility = View.GONE
        } else {
            effectsContainer.visibility = View.VISIBLE
            val inflater = LayoutInflater.from(activity).cloneInContext(cardContext)
            extraLines.forEach { line ->
                val lineView = inflater.inflate(
                    R.layout.item_splice_effect_line, effectsContainer, false
                ) as TextView
                lineView.text = line
                effectsContainer.addView(lineView)
            }
        }

        // The same button stands a rat down again, the way buying an equipped
        // Binder frame a second time takes it off.
        battleRatButton.text = activity.getString(
            if (onDuty) R.string.btn_clear_battle_rat else R.string.btn_set_battle_rat
        )
        battleRatButton.setOnClickListener {
            val nowOnDuty = BattleRat.toggle(prefs, pet.id)
            Toast.makeText(
                activity,
                if (nowOnDuty) {
                    activity.getString(R.string.toast_battle_rat_set, pet.name)
                } else {
                    activity.getString(R.string.toast_battle_rat_cleared, pet.name)
                },
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
            onBattleRatToggled()
        }

        // 3. --- THE DISPATCH LOGIC ---
        deployButton.setOnClickListener {

            // A rat on combat duty is not available for the Scrap Run. That is
            // the cost of the designation, and the reason it is free to change.
            if (BattleRat.isBattleRat(prefs, pet.id)) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_battle_rat_busy, pet.name),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            // Block them if a rat is already out there!
            if (prefs.getBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, false)) {
                Toast.makeText(activity, activity.getString(R.string.toast_rat_already_out), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 4 hours from right now, shortened for a Scavenger or skipped
            // outright for a Brawler's instant-complete - see TaskBonuses.
            // Rolled once, here, rather than re-checked on every later look at
            // the tile: the outcome is decided the moment the rat is sent out.
            val msToAdd = 4 * 60 * 60 * 1000L
            val startTime = System.currentTimeMillis()
            val endTime = TaskBonuses.endTimeFor(startTime, msToAdd, pet.faction)

            // Save it to the exact same file the Home Screen checks
            prefs.edit()
                .putBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, true)
                .putLong("DEPLOYED_RAT_ID", pet.id) // Remembering exactly which rat we sent
                .putLong(ShopEffects.KEY_EXPEDITION_END, endTime)
                .apply()

            Toast.makeText(activity, activity.getString(R.string.toast_rat_deployed, pet.name), Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }

        shareButton.setOnClickListener { ReconCard.shareRat(activity, pet) }

        // 4. Closing the pop-up
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.enlargedLayout).setOnClickListener { dialog.dismiss() }

        // Only ever attached to one overlay for this one dialog's own
        // lifetime, so stopping it here rather than on some shared Activity
        // lifecycle callback is enough - nothing else keeps it alive.
        dialog.setOnDismissListener {
            frameAnimator.stop()
            frameAnimator.clear()
            onDismiss()
        }
        dialog.show()
    }
}
