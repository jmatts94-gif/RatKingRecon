package io.github.jmatts94.ratkingrecon

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.SharedPreferences
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * The star and Battle Rat markers, sized to sit beside text.
 *
 * The vectors are 24dp icons, which swamp an 11sp card label, so the bounds are
 * set explicitly rather than taken from the drawable. Shared because the card
 * and the enlarged dialog both draw them, at different sizes.
 */
object CardIcons {

    fun battle(context: Context, sizeDp: Int): Drawable? =
        sized(context, R.drawable.ic_power, sizeDp)

    /**
     * The faction glyph for [faction], or null for a rat whose species
     * didn't resolve to one - see [RatEntity.faction]. Null omits the icon
     * entirely rather than falling back to a placeholder, the same way
     * [battle] omits itself for a rat that isn't on duty.
     */
    fun faction(context: Context, sizeDp: Int, faction: String?): Drawable? =
        factionIconRes(faction)?.let { sized(context, it, sizeDp) }

    /**
     * The drawable resource behind [faction], shared with [faction] above -
     * BattleActivity's own Special glyph needs the raw resource id to hand
     * an ImageView rather than a pre-sized Drawable, so this is the one
     * place either caller has to know the faction-to-icon mapping.
     */
    fun factionIconRes(faction: String?): Int? = when (faction) {
        Roster.SMUGGLERS -> R.drawable.ic_faction_smugglers
        Roster.SCAVENGERS -> R.drawable.ic_faction_scavengers
        Roster.TINKERERS -> R.drawable.ic_faction_tinkerers
        Roster.BRAWLERS -> R.drawable.ic_faction_brawlers
        Roster.FOUNDRY_BORN -> R.drawable.ic_faction_foundry_born
        else -> null
    }

    private fun sized(context: Context, resId: Int, sizeDp: Int): Drawable? {
        val icon = ContextCompat.getDrawable(context, resId) ?: return null
        val size = (sizeDp * context.resources.displayMetrics.density).toInt()
        icon.setBounds(0, 0, size, size)
        return icon
    }
}

/**
 * The Binder grid.
 *
 * A [ListAdapter] rather than a hand-populated GridLayout, so only the cards on
 * screen exist. The old grid attached one view per rat and kept them all, which
 * cost memory and layout time in proportion to the collection - and made an
 * animated frame something that had to be carefully rationed rather than simply
 * bound.
 *
 * [equippedFrame] is fixed for the life of an adapter: equipping a frame happens
 * in the Shop, and coming back here rebuilds the screen.
 *
 * [isDisabled] and [isSelected] default to "no card is" - only the Splicing
 * screen needs a card to read as unpickable or picked, so the Binder grid
 * itself passes nothing and gets its plain look unchanged.
 */
class RatCardAdapter(
    private val prefs: SharedPreferences,
    private val equippedFrame: CardFrame?,
    private val animator: FrameAnimator,
    private val onCardClick: (RatEntity) -> Unit,
    private val isDisabled: (RatEntity) -> Boolean = { false },
    private val isSelected: (RatEntity) -> Boolean = { false }
) : ListAdapter<RatEntity, RatCardAdapter.CardHolder>(DIFF) {

    private companion object {
        /**
         * Rats are compared by row id, and their contents by value.
         *
         * [RatEntity] is a data class, so equals covers every field that shows
         * on a card - a spliced stat change or a rename redraws without the
         * whole list being thrown away.
         */
        val DIFF = object : DiffUtil.ItemCallback<RatEntity>() {
            override fun areItemsTheSame(old: RatEntity, new: RatEntity) = old.id == new.id
            override fun areContentsTheSame(old: RatEntity, new: RatEntity) = old == new
        }
    }

    class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view as MaterialCardView
        val image: ImageView = view.findViewById(R.id.cardImage)
        val name: TextView = view.findViewById(R.id.cardName)
        val power: TextView = view.findViewById(R.id.cardPower)
        val toughness: TextView = view.findViewById(R.id.cardToughness)
        val gear1: ImageView = view.findViewById(R.id.cardGear1)
        val gear2: ImageView = view.findViewById(R.id.cardGear2)
        val gear3: ImageView = view.findViewById(R.id.cardGear3)
        val overlay: View = view.findViewById(R.id.cardFrameOverlay)
        val taskBadge: ImageView = view.findViewById(R.id.cardTaskBadge)
        val shinyFoil: View = view.findViewById(R.id.cardShinyFoil)

        /** Set on every bind, read by onViewAttachedToWindow - see RatEntity.showsFoil. */
        var showsFoil: Boolean = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rat_card, parent, false)

        val holder = CardHolder(view)

        // The frame's overlay is the same on every card, so it is built once
        // here rather than on every bind - a recycled holder already has it.
        // The border itself is set per bind instead, alongside it - see
        // onBindViewHolder - because a selected card needs to override it.
        equippedFrame?.let { frame ->
            // The accent, not the border colour: the moving parts have to
            // stand off the edge they sit against to read at all - see
            // FrameOverlayDrawable.forFrame, shared with EnlargedRatDialog.
            if (frame.style != FrameStyle.STATIC) {
                holder.overlay.background = FrameOverlayDrawable.forFrame(parent.context, frame)
            }
        }

        // Built once per holder for the same reason the frame overlay above
        // is: nothing about it varies between rats, only whether it is shown
        // - see onBindViewHolder's visibility toggle.
        holder.shinyFoil.background = ShinyFoilDrawable.create(parent.context)

        return holder
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        val pet = getItem(position)
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density

        // The stat icons live in item_rat_card.xml, so these are bare numbers.
        // Effective, not stored - a Rare or Legendary card reads the number it
        // actually fights with.
        holder.image.setImageResource(pet.imageRes)
        holder.power.text = pet.effectivePower.toString()
        holder.toughness.text = pet.effectiveToughness.toString()
        holder.shinyFoil.visibility = if (pet.showsFoil) View.VISIBLE else View.GONE
        holder.showsFoil = pet.showsFoil

        // The star is part of the name text itself rather than a compound
        // drawable, so it reads left-to-right as "Name ☆" - Battle Rat stays
        // a drawable at the far end, the one marker that was never about the
        // name. The faction glyph takes the opposite end (drawableStart),
        // which was empty until now, so it never competes with either.
        holder.name.text = if (pet.shiny) {
            context.getString(R.string.card_name_shiny, pet.name)
        } else {
            pet.name
        }

        // One gear per rarity tier, stacked in the corner of the art.
        holder.gear1.visibility = if (pet.gearCount >= 1) View.VISIBLE else View.GONE
        holder.gear2.visibility = if (pet.gearCount >= 2) View.VISIBLE else View.GONE
        holder.gear3.visibility = if (pet.gearCount >= 3) View.VISIBLE else View.GONE

        val disabled = isDisabled(pet)
        val selected = isSelected(pet)

        // Disabled reads the same as a locked Achievement: desaturated art,
        // dimmed, muted text - an option that exists but cannot be taken right
        // now, not one that has vanished.
        if (disabled) {
            holder.image.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            holder.image.alpha = 0.3f
        } else {
            holder.image.colorFilter = null
            holder.image.alpha = 1f
        }
        holder.name.setTextColor(
            ContextCompat.getColor(
                context,
                when {
                    disabled -> R.color.text_muted
                    pet.shiny -> R.color.shiny_gold
                    else -> R.color.text_primary
                }
            )
        )

        val onDuty = BattleRat.isBattleRat(prefs, pet.id)
        holder.name.setCompoundDrawablesRelative(
            CardIcons.faction(context, 13, pet.faction),
            null,
            if (onDuty) CardIcons.battle(context, 14) else null,
            null
        )
        holder.name.compoundDrawablePadding = (3 * density).toInt()

        // The frame's own border, unless a selection needs to stand out over
        // it. teal_fill rather than amber - amber sat too close to Brass/
        // Ember/Riveted Copper/Clockwork's own warm amber-and-copper
        // borders to tell a selected card apart from an equipped frame at
        // a glance (worse still for the app's partially colour-blind
        // owner - see teal_fill's own comment in colors.xml, the same
        // "read as a different signal by anyone" reasoning Power Surge and
        // the Golden Wrench already lean on). Thickened so it reads at a
        // glance in a grid this small.
        if (selected) {
            holder.card.strokeColor = ContextCompat.getColor(context, R.color.teal_fill)
            holder.card.strokeWidth = (3 * density).toInt()
        } else {
            holder.card.strokeColor = ContextCompat.getColor(
                context, equippedFrame?.strokeColorRes ?: R.color.card_border
            )
            holder.card.strokeWidth =
                ((equippedFrame?.let { Frames.STROKE_DP.toFloat() } ?: 1f) * density).toInt()
        }

        holder.itemView.isEnabled = !disabled
        holder.itemView.setOnClickListener(if (disabled) null else View.OnClickListener { onCardClick(pet) })

        // At most one rat in the whole roster is ever out on a Scrap Run at
        // once - see ShopEffects.KEY_EXPEDITION_ACTIVE - but up to three can
        // be out on a Ledger Task at the same time, one per M1/M2/M3 slot
        // (see LedgerTasks.runningTierFor) - naming a rat for a slot's bonus
        // does not reserve it against the Scrap Run either, so the two are
        // checked independently and either one alone is enough to badge the
        // card. "DEPLOYED_RAT_ID" is the same raw key EnlargedRatDialog/
        // GalleryActivity/SpliceEligibility already read and write directly,
        // not a typo.
        val onExpedition = prefs.getBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, false) &&
            prefs.getLong("DEPLOYED_RAT_ID", -1L) == pet.id
        val onLedgerTask = LedgerTasks.runningTierFor(prefs, pet.id) != null
        if (onExpedition || onLedgerTask) {
            holder.taskBadge.visibility = View.VISIBLE
            // Only ever started once per appearance - a bind that finds the
            // wobble already running (a stat change redrawing the same
            // still-deployed card, say) must not restart it and stutter.
            if (holder.taskBadge.tag == null) {
                holder.taskBadge.tag = wobbleForever(holder.taskBadge)
            }
        } else {
            (holder.taskBadge.tag as? ObjectAnimator)?.cancel()
            holder.taskBadge.tag = null
            holder.taskBadge.rotation = 0f
            holder.taskBadge.visibility = View.GONE
        }
    }

    /**
     * Settles, twitches, settles again - a small idle animation rather than
     * a continuous shimmy, so it reads as "this one's doing something" out
     * of the corner of the eye without the grid feeling restless. 2000ms
     * per cycle: still for the first 1300ms, then a wider six-beat wobble
     * and back to rest, repeating for as long as the card stays bound to
     * this rat - see onBindViewHolder/onViewRecycled for when that ends.
     *
     * Widened from an original +-10deg/400ms burst after "make the wobble
     * a little more pronounced" - both the swing (now +-18deg at its peak)
     * and the window it plays in (700ms instead of 400ms) grew, so it reads
     * clearly rather than needing a burst of screenshots to catch the way
     * the first pass did.
     */
    private fun wobbleForever(view: View): ObjectAnimator {
        val rotation = PropertyValuesHolder.ofKeyframe(
            View.ROTATION,
            Keyframe.ofFloat(0f, 0f),
            Keyframe.ofFloat(0.65f, 0f),
            Keyframe.ofFloat(0.70f, -18f),
            Keyframe.ofFloat(0.75f, 16f),
            Keyframe.ofFloat(0.80f, -12f),
            Keyframe.ofFloat(0.85f, 8f),
            Keyframe.ofFloat(0.90f, -4f),
            Keyframe.ofFloat(0.95f, 2f),
            Keyframe.ofFloat(1f, 0f)
        )
        return ObjectAnimator.ofPropertyValuesHolder(view, rotation).apply {
            duration = 2000L
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    /**
     * A recycled holder's badge animator would otherwise keep running
     * against a view RecyclerView is about to hand to a completely
     * different rat - stopped here rather than relying on the next bind to
     * get around to it, since a view can sit recycled for a while first.
     */
    override fun onViewRecycled(holder: CardHolder) {
        super.onViewRecycled(holder)
        (holder.taskBadge.tag as? ObjectAnimator)?.cancel()
        holder.taskBadge.tag = null
    }

    /**
     * RecyclerView says exactly which cards are on screen, so the animator is
     * told rather than having to work it out.
     *
     * This is what the recycling bought: the old grid had to test every card's
     * bounds against the viewport on every scroll, because every card existed
     * whether it was visible or not.
     */
    override fun onViewAttachedToWindow(holder: CardHolder) {
        super.onViewAttachedToWindow(holder)
        if (equippedFrame?.style?.let { it != FrameStyle.STATIC } == true) {
            animator.attach(holder.overlay)
        }
        // Independent of the check above: a shiny rat's foil ticks whether
        // or not the equipped Binder frame is itself animated - see
        // ShinyFoilDrawable.
        if (holder.showsFoil) {
            animator.attach(holder.shinyFoil)
        }
    }

    override fun onViewDetachedFromWindow(holder: CardHolder) {
        super.onViewDetachedFromWindow(holder)
        animator.detach(holder.overlay)
        animator.detach(holder.shinyFoil)
    }
}
