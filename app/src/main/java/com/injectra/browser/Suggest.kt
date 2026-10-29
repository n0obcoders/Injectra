package com.injectra.browser

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView

/** One address-bar suggestion. [toString] is what lands in the field when picked. */
data class Suggestion(val title: String, val url: String, val kind: Kind) {
    enum class Kind { HISTORY, BOOKMARK, TOP_SITE, SEARCH }
    override fun toString(): String = url
}

/**
 * Address-bar autocomplete, like Chrome's omnibox suggestions. It draws from the
 * user's own history and bookmarks plus a small list of popular sites, matching
 * anywhere in the url or title (case-insensitive). Nothing leaves the device.
 */
class SuggestAdapter(
    context: Context,
    private val source: () -> List<Suggestion>
) : ArrayAdapter<Suggestion>(context, 0) {

    private var items: List<Suggestion> = emptyList()
    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Suggestion = items[position]

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: inflater.inflate(android.R.layout.simple_list_item_2, parent, false)
        val s = items[position]
        val icon = when (s.kind) {
            Suggestion.Kind.HISTORY -> "🕒 "
            Suggestion.Kind.BOOKMARK -> "📑 "
            Suggestion.Kind.TOP_SITE -> "🌐 "
            Suggestion.Kind.SEARCH -> "🔍 "
        }
        row.findViewById<TextView>(android.R.id.text1).apply {
            text = icon + s.title
            setTextColor(0xFFECE9F0.toInt())
            maxLines = 1
        }
        row.findViewById<TextView>(android.R.id.text2).apply {
            text = s.url
            setTextColor(0xFF9A93A6.toInt())
            maxLines = 1
            textSize = 12f
        }
        return row
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val q = constraint?.toString()?.trim()?.lowercase().orEmpty()
            val matched = if (q.isEmpty()) emptyList()
            else source()
                .asSequence()
                .filter { it.url.lowercase().contains(q) || it.title.lowercase().contains(q) }
                .distinctBy { it.url }
                // Prefer matches where the host starts with the query (closer to what you'd type).
                .sortedBy { if (hostOf(it.url).startsWith(q)) 0 else 1 }
                .take(8)
                .toList()
            return FilterResults().apply { values = matched; count = matched.size }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            items = (results?.values as? List<Suggestion>) ?: emptyList()
            if (items.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? Suggestion)?.url ?: ""
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore('/').lowercase()

    companion object {
        /** A small curated list so suggestions appear before any history is built up. */
        val TOP_SITES: List<Suggestion> = listOf(
            "Google" to "https://www.google.com",
            "YouTube" to "https://www.youtube.com",
            "Gmail" to "https://mail.google.com",
            "Wikipedia" to "https://www.wikipedia.org",
            "GitHub" to "https://github.com",
            "Reddit" to "https://www.reddit.com",
            "X (Twitter)" to "https://x.com",
            "Facebook" to "https://www.facebook.com",
            "Instagram" to "https://www.instagram.com",
            "WhatsApp Web" to "https://web.whatsapp.com",
            "Amazon" to "https://www.amazon.com",
            "Netflix" to "https://www.netflix.com",
            "Prime Video" to "https://www.primevideo.com",
            "ChatGPT" to "https://chat.openai.com",
            "Stack Overflow" to "https://stackoverflow.com",
            "LinkedIn" to "https://www.linkedin.com",
            "Yahoo" to "https://www.yahoo.com",
            "Bing" to "https://www.bing.com",
            "DuckDuckGo" to "https://duckduckgo.com",
            "Twitch" to "https://www.twitch.tv"
        ).map { Suggestion(it.first, it.second, Suggestion.Kind.TOP_SITE) }
    }
}
