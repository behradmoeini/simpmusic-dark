package com.maxrave.simpmusic.utils

import com.maxrave.domain.data.type.ChartItem
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryChartsTest {
    private val charts =
        listOf(
            ChartItem("Trending Weekly - Global", "PL1"),
            ChartItem("Trending Weekly - Vietnam", "PL2"),
            ChartItem("Trending Weekly - United States", "PL3"),
            ChartItem("Trending Weekly - Persian", "PL4"),
        )

    @Test
    fun emptySelectionKeepsEveryChart() {
        assertEquals(charts, charts.filterByCountries(emptySet()))
    }

    @Test
    fun selectionKeepsOnlyChosenCountries() {
        val kept = charts.filterByCountries(setOf("Vietnam", "United States"))
        assertEquals(listOf("PL2", "PL3"), kept.map { it.ytPlaylistId })
    }

    @Test
    fun persianIsSelectableLikeACountry() {
        assertEquals(listOf("PL4"), charts.filterByCountries(setOf("Persian")).map { it.ytPlaylistId })
    }

    @Test
    fun storedValueParsesIgnoringBlanksAndSpaces() {
        assertEquals(setOf("Vietnam", "Persian"), " Vietnam, ,Persian ".toChartCountrySet())
        assertEquals(emptySet(), (null as String?).toChartCountrySet())
    }

    @Test
    fun flagsFollowTheCountry() {
        assertEquals("🇻🇳", chartFlag("Trending Weekly - Vietnam"))
        assertEquals("🇺🇸", chartFlag("Trending Weekly - United States"))
        assertEquals("🌍", chartFlag("Trending Weekly - Global"))
        assertEquals("🦁☀️", chartFlag("Trending Weekly - Persian"))
        assertEquals("", chartFlag("Some other chart"))
    }

    @Test
    fun withFlagsPrefixesTheName() {
        assertEquals(listOf("🇻🇳 Trending Weekly - Vietnam"), listOf(ChartItem("Trending Weekly - Vietnam", "PL2")).withFlags().map { it.name })
    }
}
