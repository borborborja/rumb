package cat.hudpro.opentracks.viewer

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView

/**
 * ViewPager2 adapter that shows exactly two pre-built, full-screen pages (map + data). Using real
 * views (not a Compose pager) keeps the MapLibre MapView measured and alive correctly across swipes.
 */
class ViewerPagesAdapter(private val pages: List<View>) : RecyclerView.Adapter<ViewerPagesAdapter.PageHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemCount() = pages.size
    override fun getItemViewType(position: Int) = position
    override fun getItemId(position: Int) = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val view = pages[viewType]
        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        return PageHolder(view)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {}

    class PageHolder(view: View) : RecyclerView.ViewHolder(view)
}

/**
 * Top switcher: segmented "Mapa" / "Dades" plus a trailing gear that opens the quick-settings sheet.
 */
@Composable
fun ViewerSwitcher(currentPage: Int, onSelect: (Int) -> Unit, onGear: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x99000000))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwitchTab("Mapa", currentPage == 0) { onSelect(0) }
        SwitchTab("Dades", currentPage == 1) { onSelect(1) }
        Icon(
            Icons.Filled.Settings,
            contentDescription = "Ajustos del visor",
            tint = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onGear)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .size(22.dp),
        )
    }
}

@Composable
private fun SwitchTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Black else Color.White,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
    )
}
