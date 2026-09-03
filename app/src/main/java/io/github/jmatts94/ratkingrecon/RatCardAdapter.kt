package io.github.jmatts94.ratkingrecon

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
    fun faction(context: Context, sizeDp: Int, faction: String?): Drawable? {
        val resId = when (faction) {
            Roster.SMUGGLERS -> R.drawable.ic_faction_smugglers
            Roster.SCAVENGERS -> R.drawable.ic_faction_scavengers
            Roster.TINKERERS -> R.drawable.ic_faction_tinkerers
            Roster.BRAWLERS -> R.drawable.ic_faction_brawlers
            Roster.FOUNDRY_BORN -> R.drawable.ic_faction_foundry_born
            else -> return null
        }
        return sized(context, resId, sizeDp)
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
        // it - the same amber the rest of the app uses for a highlighted
        // choice, thickened so it reads at a glance in a grid this small.
        if (selected) {
            holder.card.strokeColor = ContextCompat.getColor(context, R.color.amber)
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
    }

    override fun onViewDetachedFromWindow(holder: CardHolder) {
        super.onViewDetachedFromWindow(holder)
        animator.detach(holder.overlay)
    }
}
