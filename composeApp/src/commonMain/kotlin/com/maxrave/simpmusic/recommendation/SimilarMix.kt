package com.maxrave.simpmusic.recommendation

/**
 * Pure parts of "Like what you play" (I/O is in engine/RecommendationEngine). Seeds, candidate ranking,
 * diversity and interleaving are shared with [DiscoverMix]; this adds what is specific to a
 * catalogue that only knows names: name keys, and choosing the YouTube result for a named song.
 */
object SimilarMix {
    /** Score multiplier for a related artist the listener already plays: allowed, but behind new ones. */
    const val KNOWN_ARTIST_PENALTY = 0.5

    /** Comparison key of a name: lower case, letters and digits only, Arabic ي/ك folded to Persian. */
    fun key(text: String): String =
        text
            .lowercase()
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .filter { it.isLetterOrDigit() }

    fun trackKey(
        artist: String,
        title: String,
    ): String = key(artist) + "|" + key(stripVersion(title))

    /** "Song (feat. X) [Remastered]" → "Song": the same recording is listed under many suffixes. */
    fun stripVersion(title: String): String = title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim().ifEmpty { title }

    /** Plays per artist name (the first credited artist), most played first. */
    fun artistPlays(songs: List<Pair<String, Int>>): List<ArtistPlays> =
        songs
            .filter { it.first.isNotBlank() }
            .groupBy({ it.first.trim() }, { it.second })
            .map { (name, plays) -> ArtistPlays(name, plays.sum()) }
            .sortedByDescending { it.plays }

    /** Known artists stay in the ranking at a discount, seed artists themselves are dropped. */
    fun discountKnown(
        candidates: List<Candidate>,
        knownArtistKeys: Set<String>,
        seedKeys: Set<String>,
    ): List<Candidate> =
        candidates
            .filter { key(it.name) !in seedKeys }
            .map { if (key(it.name) in knownArtistKeys) it.copy(score = it.score * KNOWN_ARTIST_PENALTY) else it }
            .sortedByDescending { it.score }

    /**
     * Index of the search result that best answers (artist, title), or -1 for none. A result whose
     * title and artist both agree wins, then title only, then artist only; with no agreement at all
     * the first result is taken, because the catalogue and YouTube often write one Persian name in
     * two scripts and the search ranking is then the only evidence there is.
     */
    fun bestMatch(
        artist: String,
        title: String,
        results: List<Pair<String, String>>,
    ): Int {
        if (results.isEmpty()) return -1
        val wantedTitle = key(stripVersion(title))
        val wantedArtist = key(artist)
        var best = 0
        var bestScore = -1
        results.forEachIndexed { index, (resultTitle, resultArtists) ->
            val t = key(stripVersion(resultTitle))
            val a = key(resultArtists)
            val titleScore =
                when {
                    wantedTitle.isEmpty() || t.isEmpty() -> 0
                    t == wantedTitle -> 4
                    t.contains(wantedTitle) || wantedTitle.contains(t) -> 2
                    else -> 0
                }
            val artistScore = if (wantedArtist.isNotEmpty() && a.contains(wantedArtist)) 3 else 0
            val score = titleScore + artistScore
            if (score > bestScore) {
                bestScore = score
                best = index
            }
        }
        return best
    }
}
