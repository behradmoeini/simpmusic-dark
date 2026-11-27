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

    /** One search result, as far as choosing between results needs it. */
    data class NamedResult(
        val title: String,
        val artists: String,
        val durationSeconds: Int?,
    )

    /** Below this a result is not trusted to be the named song. */
    const val CONFIDENT = 4

    /**
     * Consonants of a name in Latin letters, so one Persian name written in Persian and in Latin
     * script compares equal: "تو همیشه بخند" and "To Hamishe Bekhand" are both "tmsbknd". Vowels and
     * the letters that are written in one script and silent or optional in the other (h, v, w, y)
     * are dropped, digraphs fall out with them (sh → s, kh → k, ch → c, gh → g).
     */
    fun skeleton(text: String): String {
        val out = StringBuilder()
        for (ch in text.lowercase()) {
            val mapped =
                when (ch) {
                    'ب' -> 'b'
                    'پ' -> 'p'
                    'ت', 'ط' -> 't'
                    'ث', 'س', 'ص', 'ش' -> 's'
                    'ج' -> 'j'
                    'چ' -> 'c'
                    'خ', 'ک', 'ك' -> 'k'
                    'د' -> 'd'
                    'ذ', 'ز', 'ض', 'ظ', 'ژ' -> 'z'
                    'ر' -> 'r'
                    'غ', 'ق', 'گ', 'q' -> 'g'
                    'ف' -> 'f'
                    'ل' -> 'l'
                    'م' -> 'm'
                    'ن' -> 'n'
                    'a', 'e', 'i', 'o', 'u', 'y', 'h', 'w', 'v' -> null
                    in 'a'..'z', in '0'..'9' -> ch
                    else -> null
                } ?: continue
            if (out.isEmpty() || out.last() != mapped) out.append(mapped)
        }
        return out.toString()
    }

    /** Two skeletons of the same name: equal, or one slip apart per five letters. */
    private fun skeletonsAgree(
        a: String,
        b: String,
    ): Boolean {
        if (a.length < 3 || b.length < 3) return false
        if (a == b) return true
        val allowed = minOf(a.length, b.length) / 5
        return allowed > 0 && editDistance(a, b) <= allowed
    }

    private fun editDistance(
        a: String,
        b: String,
    ): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    /**
     * How well a result answers a named song: title (4 equal, 3 the same name across scripts or
     * spellings, 2 one inside the other), artist (3 named in the result, 2 across scripts) and
     * running time (3 within two seconds, 1 within five). The running time is the one sign that does
     * not depend on how a name is written.
     */
    fun matchScore(
        artists: List<String>,
        title: String,
        durationSeconds: Int?,
        result: NamedResult,
    ): Int {
        val wantedTitle = key(stripVersion(title))
        val resultTitle = key(stripVersion(result.title))
        val titleScore =
            when {
                wantedTitle.isEmpty() || resultTitle.isEmpty() -> 0
                resultTitle == wantedTitle -> 4
                skeletonsAgree(skeleton(stripVersion(title)), skeleton(stripVersion(result.title))) -> 3
                resultTitle.contains(wantedTitle) || wantedTitle.contains(resultTitle) -> 2
                else -> 0
            }
        val resultArtists = key(result.artists)
        val resultArtistsSkeleton = skeleton(result.artists)
        val artistScore =
            when {
                artists.any { key(it).isNotEmpty() && resultArtists.contains(key(it)) } -> 3
                artists.any { skeleton(it).length >= 3 && resultArtistsSkeleton.contains(skeleton(it)) } -> 2
                else -> 0
            }
        val apart = if (durationSeconds != null && result.durationSeconds != null) kotlin.math.abs(durationSeconds - result.durationSeconds) else null
        val durationScore =
            when {
                apart == null -> 0
                apart <= 2 -> 3
                apart <= 5 -> 1
                else -> 0
            }
        return titleScore + artistScore + durationScore
    }

    /**
     * Index of the result that is the named song, or -1 when none can be trusted to be it. Unlike
     * [bestMatch] this never falls back to the first result: it is for a song the listener picked
     * by name, where playing a different song is worse than playing nothing.
     */
    fun confidentMatch(
        artists: List<String>,
        title: String,
        durationSeconds: Int?,
        results: List<NamedResult>,
    ): Int {
        var best = -1
        var bestScore = CONFIDENT - 1
        results.forEachIndexed { index, result ->
            val score = matchScore(artists, title, durationSeconds, result)
            if (score > bestScore) {
                bestScore = score
                best = index
            }
        }
        return best
    }
}
