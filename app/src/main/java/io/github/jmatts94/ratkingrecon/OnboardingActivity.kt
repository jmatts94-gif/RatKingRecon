package io.github.jmatts94.ratkingrecon

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton

/**
 * The first-launch walkthrough.
 *
 * Shown once, from MainActivity, and retired by [Onboarding.KEY_COMPLETE]. The
 * cards themselves are data in [Onboarding.pages] - adding or reordering one
 * needs no change here, and the dots follow the list rather than a fixed count.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var nextButton: MaterialButton
    private lateinit var skipButton: MaterialButton

    private val lastIndex get() = Onboarding.pages.size - 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pager = findViewById(R.id.onboardingPager)
        dots = findViewById(R.id.onboardingDots)
        nextButton = findViewById(R.id.onboardingNextButton)
        skipButton = findViewById(R.id.onboardingSkipButton)

        pager.adapter = PageAdapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = render(position)
        })

        buildDots()

        // Skip goes straight to the finish, rather than to the last card - the
        // point of skipping is to be done with it.
        skipButton.setOnClickListener { dismiss() }

        nextButton.setOnClickListener {
            if (pager.currentItem == lastIndex) dismiss() else pager.currentItem += 1
        }

        // Back steps through the cards, and closes the walkthrough from the
        // first one. Trapping the player here would be worse than letting them
        // out, and the flag is set either way so it cannot become a loop.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pager.currentItem == 0) dismiss() else pager.currentItem -= 1
            }
        })

        render(0)
    }

    /** Marks the walkthrough done and hands the player to the workshop. */
    private fun dismiss() {
        Onboarding.markComplete(RatRepository.prefs(this))
        finish()
    }

    private fun buildDots() {
        Onboarding.pages.indices.forEach { _ ->
            val dot = TextView(this).apply {
                text = getString(R.string.onboarding_dot)
                textSize = 12f
                val gap = (6 * resources.displayMetrics.density).toInt()
                setPadding(gap, 0, gap, 0)
            }
            dots.addView(dot)
        }
    }

    private fun render(position: Int) {
        val onLast = position == lastIndex

        nextButton.setText(
            if (onLast) R.string.onboarding_get_started else R.string.onboarding_next
        )
        // Nothing left to skip to once the final card is up, and leaving it
        // there would put two buttons that do the same thing side by side.
        skipButton.visibility = if (onLast) View.INVISIBLE else View.VISIBLE

        for (i in 0 until dots.childCount) {
            (dots.getChildAt(i) as TextView).setTextColor(
                ContextCompat.getColor(
                    this,
                    if (i == position) R.color.amber else R.color.disabled_fill
                )
            )
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.PageHolder>() {

        inner class PageHolder(val card: View) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(layoutInflater.inflate(R.layout.view_onboarding_page, parent, false))

        override fun getItemCount(): Int = Onboarding.pages.size

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val page = Onboarding.pages[position]
            holder.card.findViewById<ImageView>(R.id.pageIcon).setImageResource(page.iconRes)
            holder.card.findViewById<TextView>(R.id.pageTitle).setText(page.titleRes)
            holder.card.findViewById<TextView>(R.id.pageBody).text =
                if (page.bodyArg != null) getString(page.bodyRes, page.bodyArg)
                else getString(page.bodyRes)
        }
    }
}
