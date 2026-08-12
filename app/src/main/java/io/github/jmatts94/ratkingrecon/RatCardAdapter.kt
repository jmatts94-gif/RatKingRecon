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

    fun star(context: Context, sizeDp: Int): Drawable? = sized(context, R.drawable.ic_star, sizeDp)

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
                holder.overlay.background = FrameOverlayDrawable(
                    frame.style,
                    ContextCompat.getColor(context, frame.strokeColorRes)
                ).apply { setDensity(context.resources.displayMetrics.density) }
            }
        }

        return holder
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        val pet = getItem(position)
        val context = holder.itemView.context

        // The stat icons live in item_rat_card.xml, so these are bare numbers.
        holder.image.setImageResource(pet.imageRes)
        holder.power.text = pet.power.toString()
        holder.toughness.text = pet.toughness.toString()
        holder.name.text = pet.name

        // Shiny marks the start of the label, Battle Rat the end, so a rat that
        // is both keeps both markers instead of one hiding the other.
        val onDuty = BattleRat.isBattleRat(prefs, pet.id)
        val padding = (3 * context.resources.displayMetrics.density).toInt()

        if (pet.shiny) {
            holder.name.setTextColor(ContextCompat.getColor(context, R.color.shiny_gold))
            holder.name.setCompoundDrawablesRelative(
                CardIcons.star(context, 14), null,
                if (onDuty) CardIcons.battle(context, 14) else null, null
            )
        } else {
            holder.name.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            holder.name.setCompoundDrawablesRelative(
                null, null,
                if (onDuty) CardIcons.battle(context, 14) else null, null
            )
        }
        holder.name.compoundDrawablePadding = padding

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
