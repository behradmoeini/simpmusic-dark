package com.maxrave.simpmusic.utils

import com.maxrave.common.CHART_SUPPORTED_COUNTRY
import com.maxrave.domain.data.type.ChartItem

/** DataStore key: the countries whose charts show in Your library, comma-separated. Empty = all. */
const val LIBRARY_CHART_COUNTRIES_KEY = "library_chart_countries"

/** The Persian chart is built from YouTube Music search, so it is not in the chart server's list. */
const val PERSIAN_CHART = "Persian"

/** Every name a chart can be filtered by: the supported countries plus Persian. */
fun chartCountryNames(): List<String> = CHART_SUPPORTED_COUNTRY.itemsData.toList() + PERSIAN_CHART

fun String?.toChartCountrySet(): Set<String> =
    this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

/**
 * The flag for a chart name: the country's ISO code as regional-indicator letters, a globe for the
 * worldwide chart, and the Lion and Sun for Persian. Empty when the name matches no known country.
 */
fun chartFlag(chartName: String): String {
    // Iran has no emoji for the Lion and Sun, so the lion and the sun stand in for it.
    if (chartName.contains(PERSIAN_CHART, ignoreCase = true)) return "🦁☀️"
    val index = CHART_SUPPORTED_COUNTRY.itemsData.indexOfFirst { chartName.contains(it, ignoreCase = true) }
    if (index < 0) return ""
    val code = CHART_SUPPORTED_COUNTRY.items[index]
    return if (code == "ZZ") "🌍" else flagOf(code)
}

private fun flagOf(code: String): String =
    buildString {
        code.uppercase().forEach { appendCodePoint(0x1F1E6 + (it - 'A')) }
    }

/** Prefixes each chart's name with its flag, for display. */
fun List<ChartItem>.withFlags(): List<ChartItem> =
    map { item ->
        val flag = chartFlag(item.name)
        if (flag.isEmpty()) item else item.copy(name = "$flag ${item.name}")
    }

/** Keeps the charts whose name mentions a selected country. No selection keeps every chart. */
fun List<ChartItem>.filterByCountries(selected: Set<String>): List<ChartItem> =
    if (selected.isEmpty()) this else filter { item -> selected.any { item.name.contains(it, ignoreCase = true) } }
