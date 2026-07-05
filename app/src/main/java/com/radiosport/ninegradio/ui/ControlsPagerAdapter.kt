package com.radiosport.ninegradio.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.radiosport.ninegradio.R

/**
 * ViewPager2 adapter for the controls bottom-sheet drawer.
 * Each tab inflates its own layout file; MainActivity binds the views
 * via [getViewAt] after the pager is attached.
 *
 * Tabs:
 *   0 – Mode        (fragment_tab_mode.xml)
 *   1 – Tune        (fragment_tab_tune.xml)
 *   2 – RF          (fragment_tab_rf.xml)
 *   3 – Display     (fragment_tab_display.xml)
 *   4 – Recording   (fragment_tab_recording.xml)
 *   5 – Settings    (fragment_tab_settings.xml)
 *   6 – APRS        (fragment_tab_aprs.xml)      — hidden unless DemodMode.APRS active
 *   7 – ACARS       (fragment_tab_acars.xml)     — hidden unless DemodMode.ACARS active
 *   8 – DMR         (fragment_tab_dmr.xml)       — hidden unless DemodMode.DMR active
 *   9 – YSF         (fragment_tab_ysf.xml)       — hidden unless DemodMode.YSF active
 *  10 – D-STAR      (fragment_tab_dstar.xml)     — hidden unless DemodMode.DSTAR active
 *  11 – Dig         (fragment_tab_dig.xml)        — hidden unless DemodMode.DIG active
 */
class ControlsPagerAdapter : RecyclerView.Adapter<ControlsPagerAdapter.TabHolder>() {

    companion object {
        const val TAB_MODE      = 0
        const val TAB_TUNE      = 1
        const val TAB_RF        = 2
        const val TAB_DISPLAY   = 3
        const val TAB_RECORDING = 4
        const val TAB_SETTINGS  = 5
        const val TAB_APRS      = 6
        const val TAB_ACARS     = 7
        const val TAB_DMR       = 8
        const val TAB_YSF       = 9
        const val TAB_DSTAR     = 10
        const val TAB_DIG       = 11
        const val TAB_COUNT     = 12

        val TAB_TITLES = arrayOf(
            "Mode", "Tune", "RF", "Display", "Rec", "Settings",
            "APRS", "ACARS", "DMR", "YSF", "D-STAR", "Dig"
        )

        private val LAYOUTS = intArrayOf(
            R.layout.fragment_tab_mode,
            R.layout.fragment_tab_tune,
            R.layout.fragment_tab_rf,
            R.layout.fragment_tab_display,
            R.layout.fragment_tab_recording,
            R.layout.fragment_tab_settings,
            R.layout.fragment_tab_aprs,
            R.layout.fragment_tab_acars,
            R.layout.fragment_tab_dmr,
            R.layout.fragment_tab_ysf,
            R.layout.fragment_tab_dstar,
            R.layout.fragment_tab_dig
        )
    }

    /** Keeps inflated tab root views so MainActivity can call [getViewAt]. */
    private val views = arrayOfNulls<View>(TAB_COUNT)

    inner class TabHolder(val root: View) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = TAB_COUNT

    override fun getItemViewType(position: Int) = position  // unique type per tab = no recycling

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(LAYOUTS[viewType], parent, false)
        // ViewPager2 requires every page to be match_parent in both dimensions.
        v.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        views[viewType] = v
        return TabHolder(v)
    }

    override fun onBindViewHolder(holder: TabHolder, position: Int) {
        // Nothing to bind; views are static.
    }

    /**
     * Returns the root view for a tab, or null if it hasn't been created yet.
     * ViewPager2 creates views lazily; the view for the selected tab and its
     * neighbour are guaranteed to exist once the pager is visible.
     */
    fun getViewAt(tab: Int): View? = views.getOrNull(tab)

    /**
     * Helper: attach a NestedScrollingChild-aware callback so that
     * NestedScrollView (the bottom sheet) and ViewPager2 don't fight for
     * vertical touch events.  Call once after [ViewPager2.adapter] is set.
     */
    fun attachNestedScrollWorkaround(pager: ViewPager2) {
        pager.getChildAt(0)?.let { rv ->
            if (rv is RecyclerView) {
                rv.isNestedScrollingEnabled = false
            }
        }
    }
}
