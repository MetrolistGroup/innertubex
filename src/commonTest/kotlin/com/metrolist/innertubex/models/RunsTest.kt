package com.metrolist.innertubex.models

import kotlin.test.Test
import kotlin.test.assertEquals

class RunsTest {
    @Test
    fun splitsOnlyExactBulletSeparatorsAndPreservesRunMetadata() {
        val artist = Run("Artist", NavigationEndpoint(browseEndpoint = BrowseEndpoint("UCfixture")), bold = true)
        val literalBullet = Run("•")
        val album = Run("Album", italics = true)

        assertEquals(
            listOf(listOf(artist, literalBullet), listOf(album)),
            listOf(artist, literalBullet, Run(" • "), album).splitBySeparator(),
        )
    }

    @Test
    fun splitPreservesEmptyGroupsAtBoundariesAndBetweenSeparators() {
        val separator = Run(" • ")
        assertEquals(listOf(emptyList()), emptyList<Run>().splitBySeparator())
        assertEquals(
            listOf(emptyList(), emptyList(), listOf(Run("Album")), emptyList()),
            listOf(separator, separator, Run("Album"), separator).splitBySeparator(),
        )
    }

    @Test
    fun cleanDropsTheLeadingUnlinkedCategoryGroup() {
        val remaining = listOf(listOf(Run("Artist")), listOf(Run("Album")))

        assertEquals(remaining, (listOf(listOf(Run("Song"), Run("Category detail"))) + remaining).clean())
        assertEquals(remaining, (listOf(emptyList<Run>()) + remaining).clean())
        assertEquals(emptyList(), emptyList<List<Run>>().clean())
        assertEquals(emptyList(), listOf(listOf(Run("Song"))).clean())
    }

    @Test
    fun cleanKeepsLeadingLinkedOrMultiArtistGroups() {
        for (first in listOf(
            Run("Artist", NavigationEndpoint(browseEndpoint = BrowseEndpoint("UCfixture"))),
            Run("Artist & Guest"),
            Run("Artist, Guest"),
        )) {
            val groups = listOf(listOf(first), listOf(Run("Album")))
            assertEquals(groups, groups.clean())
        }
    }

    @Test
    fun oddElementsSelectsFirstThirdAndFifthPositions() {
        val artists = listOf(Run("First"), Run(", "), Run("Second"), Run(" & "), Run("Third"))

        assertEquals(listOf(artists[0], artists[2], artists[4]), artists.oddElements())
        assertEquals(listOf(artists[0], artists[2]), artists.dropLast(1).oddElements())
        assertEquals(listOf(artists[0]), artists.take(1).oddElements())
        assertEquals(emptyList(), emptyList<Run>().oddElements())
    }
}
