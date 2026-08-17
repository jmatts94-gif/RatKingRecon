package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.content.SharedPreferences
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
 */
class RatCardAdapter(
    private val prefs: SharedPreferences,
    private val equippedFrame: CardFrame?,
    private val animator: FrameAnimator,
    private val onCardClick: (RatEntity) -> Unit
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

        // The frame is the same on every card, so it is applied once here rather
        // than on every bind - a recycled holder already has it.
        equippedFrame?.let { frame ->
            val context = parent.context
            holder.card.strokeColor = ContextCompat.getColor(context, frame.strokeColorRes)
            holder.card.strokeWidth =
                (Frames.STROKE_DP * context.resources.displayMetrics.density).toInt()

            if (frame.style != FrameStyle.STATIC) {
                // The accent, not the border colour: the moving parts have to
                // stand off the edge they sit against to read at all.
                holder.overlay.background = FrameOverlayDrawable(
                    frame.style,
                    ContextCompat.getColor(context, frame.accentColorRes),
                    ContextCompat.getColor(context, frame.accentAltColorRes)
                ).apply { setDensity(context.resources.displayMetrics.density) }
            }
        }

        return holder
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        val pet = getItem(position)
        val context = holder.itemView.context

        // The stat icons live in item_rat_card.xml, so these are bare numbers.
        // Effective, not stored - a Rare or Legendary card reads the number it
        // actually fights with.
        holder.image.setImageResource(pet.imageRes)
        holder.power.text = pet.effectivePower.toString()
        holder.toughness.text = pet.effectiveToughness.toString()

        // The star is now part of the name text itself rather than a
        // compound drawable, so it reads left-to-right as "Name ☆" - Battle
        // Rat stays a drawable at the far end, the one marker that was never
        // about the name.
        holder.name.text = if (pet.shiny) {
            context.getString(R.string.card_name_shiny, pet.name)
        } else {
            pet.name
        }
        holder.name.setTextColor(
            ContextCompat.getColor(context, if (pet.shiny) R.color.shiny_gold else R.color.text_primary)
        )

        val onDuty = BattleRat.isBattleRat(prefs, pet.id)
        holder.name.setCompoundDrawablesRelative(
            null, null, if (onDuty) CardIcons.battle(context, 14) else null, null
        )
        holder.name.compoundDrawablePadding = (3 * context.resources.displayMetrics.density).toInt()

        // One gear per rarity tier, stacked in the corner of the art.
        holder.gear1.visibility = if (pet.gearCount >= 1) View.VISIBLE else View.GONE
        holder.gear2.visibility = if (pet.gearCount >= 2) View.VISIBLE else View.GONE
        holder.gear3.visibility = if (pet.gearCount >= 3) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onCardClick(pet) }
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
