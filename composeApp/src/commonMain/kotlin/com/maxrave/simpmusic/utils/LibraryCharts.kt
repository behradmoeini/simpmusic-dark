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

/** Keeps the charts whose name mentions a selected country. No selection keeps every chart. */
fun List<ChartItem>.filterByCountries(selected: Set<String>): List<ChartItem> =
    if (selected.isEmpty()) this else filter { item -> selected.any { item.name.contains(it, ignoreCase = true) } }
