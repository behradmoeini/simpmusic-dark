package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.simpmusic.recommendation.DiscoverMixLoader
import com.maxrave.simpmusic.chart.toIranChart
import com.maxrave.simpmusic.recommendation.DeezerAlbum
import com.maxrave.simpmusic.recommendation.DeezerCatalog
import com.maxrave.simpmusic.recommendation.DeezerTrack
import com.maxrave.simpmusic.recommendation.HomeShelves
import com.maxrave.simpmusic.recommendation.HomeShelvesLoader
import com.maxrave.simpmusic.recommendation.MixCard
import com.maxrave.simpmusic.recommendation.SimilarMix
import com.maxrave.simpmusic.recommendation.SimilarTracksLoader
import com.maxrave.simpmusic.recommendation.YouTubeMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import simpmusic.composeapp.generated.resources.home_mix_name
import simpmusic.composeapp.generated.resources.home_shelf_not_found
import org.koin.core.component.get
import simpmusic.composeapp.generated.resources.discover_mix_title
import simpmusic.composeapp.generated.resources.similar_mix_title
import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.common.SUPPORTED_LANGUAGE
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.utils.withoutYouTubePodcasts
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.music_video
import simpmusic.composeapp.generated.resources.new_release
import simpmusic.composeapp.generated.resources.song
import simpmusic.composeapp.generated.resources.view_count

class HomeViewModel(
    private val dataStoreManager: DataStoreManager,
    private val homeRepository: HomeRepository,
) : BaseViewModel() {
    private val _homeItemList: MutableStateFlow<List<HomeItem>> =
        MutableStateFlow(arrayListOf())
    val homeItemList: StateFlow<List<HomeItem>> = _homeItemList

    private var _homeListState = MutableStateFlow<ListState>(ListState.IDLE)
    val homeListState: StateFlow<ListState> = _homeListState

    private var _continuation = MutableStateFlow<String?>(null)
    val continuation: StateFlow<String?> = _continuation

    private val _exploreMoodItem: MutableStateFlow<Mood?> = MutableStateFlow(null)
    val exploreMoodItem: StateFlow<Mood?> = _exploreMoodItem
    private val _accountInfo: MutableStateFlow<Pair<String?, String?>?> = MutableStateFlow(null)
    val accountInfo: StateFlow<Pair<String?, String?>?> = _accountInfo

    private var homeJob: Job? = null

    val showSnackBarErrorState = MutableSharedFlow<String>()

    private val _chart: MutableStateFlow<Chart?> = MutableStateFlow(null)
    val chart: StateFlow<Chart?> = _chart

    // Fork: Iran has no YouTube chart; it is built from the daily Spotify snapshot instead.
    private val _iranChart: MutableStateFlow<com.maxrave.simpmusic.chart.IranChart?> = MutableStateFlow(null)
    val iranChart: StateFlow<com.maxrave.simpmusic.chart.IranChart?> = _iranChart
    private val _newRelease: MutableStateFlow<List<HomeItem>> = MutableStateFlow(arrayListOf())
    val newRelease: StateFlow<List<HomeItem>> = _newRelease
    var regionCodeChart: MutableStateFlow<String?> = MutableStateFlow(null)

    val loading = MutableStateFlow<Boolean>(true)
    val loadingChart = MutableStateFlow<Boolean>(true)
    private var regionCode: String = ""
    private var language: String = ""

    private val _songEntity: MutableStateFlow<SongEntity?> = MutableStateFlow(null)
    val songEntity: StateFlow<SongEntity?> = _songEntity

    private var _params: MutableStateFlow<String?> = MutableStateFlow(null)
    val params: StateFlow<String?> = _params

    // For showing alert that should log in to YouTube
    private val _showLogInAlert: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val showLogInAlert: StateFlow<Boolean> = _showLogInAlert

    val dataSyncId =
        dataStoreManager
            .dataSyncId
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _mainHomeThumbnail: MutableStateFlow<String?> = MutableStateFlow(null)
    val mainHomeThumbnail: StateFlow<String?> = _mainHomeThumbnail

    // The page background image YouTube sends with Home, null when it sends none.
    private val _homeBackground: MutableStateFlow<String?> = MutableStateFlow(null)
    val homeBackground: StateFlow<String?> = _homeBackground

    init {
        if (runBlocking { dataStoreManager.cookie.first() }.isEmpty() &&
            runBlocking {
                dataStoreManager.shouldShowLogInRequiredAlert.first() == TRUE
            }
        ) {
            _showLogInAlert.update { true }
        }
        homeJob = Job()
        viewModelScope.launch {
            regionCodeChart.value = dataStoreManager.chartKey.first()
            exploreChart(regionCodeChart.value ?: "ZZ")
            language = dataStoreManager.getString(SELECTED_LANGUAGE).first()
                ?: SUPPORTED_LANGUAGE.codes.first()
            //  refresh when region change
            val job1 =
                launch {
                    dataStoreManager.location.distinctUntilChanged().collect {
                        regionCode = it
                        getHomeItemList(params.value)
                    }
                }
            //  refresh when language change
            val job2 =
                launch {
                    dataStoreManager.language.distinctUntilChanged().collect {
                        language = it
                        getHomeItemList(params.value)
                    }
                }
            val job3 =
                launch {
                    dataStoreManager.cookie.distinctUntilChanged().collect {
                        getHomeItemList(params.value)
                        _accountInfo.emit(
                            Pair(
                                dataStoreManager.getString("AccountName").first(),
                                dataStoreManager.getString("AccountThumbUrl").first(),
                            ),
                        )
                    }
                }
            val job4 =
                launch {
                    params.collectLatest {
                        getHomeItemList(it)
                    }
                }
            val job5 =
                launch {
                    dataStoreManager
                        .cookie
                        .distinctUntilChanged()
                        .collectLatest {
                            if (it.isNotEmpty()) {
                                Logger.w(tag, "Cookie changed, refreshing home")
                                loading.value = true
                                delay(1000) // To wait for the cookie to be saved properly
                                getHomeItemList(params.value)
                            }
                        }
                }
            val job6 =
                launch {
                    homeItemList.collectLatest { list ->
                        _mainHomeThumbnail.value =
                            list
                                .firstOrNull()
                                ?.contents
                                ?.firstOrNull()
                                ?.thumbnails
                                ?.lastOrNull()
                                ?.url
                    }
                }
            job1.join()
            job2.join()
            job3.join()
            job4.join()
            job5.join()
            job6.join()
        }
    }

    fun doneShowLogInAlert(neverShowAgain: Boolean = false) {
        viewModelScope.launch {
            _showLogInAlert.update { false }
            if (neverShowAgain) {
                dataStoreManager.setShouldShowLogInRequiredAlert(false)
            }
        }
    }

    fun getHomeItemList(params: String? = null) {
        loading.value = true
        _homeListState.value = ListState.LOADING
        language =
            runBlocking {
                dataStoreManager.getString(SELECTED_LANGUAGE).first()
                    ?: SUPPORTED_LANGUAGE.codes.first()
            }
        regionCode = runBlocking { dataStoreManager.location.first() }
        homeJob?.cancel()
        continuationJob?.cancel()
        homeJob =
            viewModelScope.launch {
                // Fork: every part of Home is fetched on its own and retried until it answers, and
                // none of them can overwrite another. These four used to sit in one combine(), so
                // the mood list's second emission (cached copy, then network) re-ran the whole
                // block: the list fell back to page one with page one's continuation, every shelf
                // already appended vanished, and the page never loaded more.
                launch {
                    val chartKey = dataStoreManager.chartKey.first()
                    if (chartKey == com.maxrave.simpmusic.chart.IRAN_CHART_KEY) {
                        loadIranChart()
                        regionCodeChart.value = chartKey
                        return@launch
                    }
                    val chart = retryHome("chart") { homeRepository.getChartData(chartKey) }
                    // Fork: no YouTube podcast shows in the chart either.
                    _chart.value = chart.data?.copy(podcasts = null)
                    regionCodeChart.value = chartKey
                }
                launch {
                    val newRelease =
                        retryHome("new releases") {
                            homeRepository.getNewRelease(getString(Res.string.new_release), getString(Res.string.music_video))
                        }
                    _newRelease.value = newRelease.data ?: arrayListOf()
                }
                launch {
                    // Cached copy first, then the network copy: each one replaces the last, and
                    // only the mood grid listens to it.
                    // The repository emits no error over a cached copy, so a run that ends with
                    // anything is enough; one that ends with nothing is tried again.
                    var attempt = 0
                    while (true) {
                        var gotAny = false
                        homeRepository.getMoodAndMomentsData().collect { mood ->
                            if (mood is Resource.Success) {
                                _exploreMoodItem.value = mood.data
                                gotAny = true
                            }
                        }
                        if (gotAny) break
                        attempt++
                        delay(retryDelayMs(attempt))
                    }
                }

                val home =
                    retryHome(
                        "first page",
                        onStillFailing = { failure ->
                            // Show the error state now; the request keeps going behind it.
                            _continuation.value = null
                            _homeItemList.value = listOf()
                            _homeListState.value = ListState.PAGINATION_EXHAUST
                            loading.value = false
                            showSnackBarErrorState.emit(failure.message ?: "Unknown error")
                        },
                    ) {
                        homeRepository.getHomeData(params, getString(Res.string.view_count), getString(Res.string.song))
                    }
                Logger.i("Home", "loaded: ${(home.data?.second?.size ?: 0)} shelves")
                _continuation.value = home.data?.first
                _homeItemList.value = (home.data?.second ?: listOf()).withoutYouTubePodcasts()
                _homeBackground.value = home.data?.third
                _homeListState.value = if (continuation.value.isNullOrEmpty()) ListState.PAGINATION_EXHAUST else ListState.IDLE
                dataStoreManager.cookie.first().let {
                    if (it != "") {
                        _accountInfo.emit(
                            Pair(
                                dataStoreManager.getString("AccountName").first(),
                                dataStoreManager.getString("AccountThumbUrl").first(),
                            ),
                        )
                    }
                }
                loading.value = false
                // The rest of Home is fetched now, page after page, instead of waiting for the
                // list to be scrolled to its end.
                loadRemainingHomePages()
            }
    }

    /**
     * Fork: one Home request, asked again with a growing wait (up to a minute) until it answers.
     * A flow that ends without emitting (an exception swallowed in the repository) counts as a
     * failure too. After [HOME_ATTEMPTS] failures [onStillFailing] runs once, so the screen can
     * show the error, and the request keeps being retried behind it. A refresh or leaving the
     * screen cancels it.
     */
    private suspend fun <T> retryHome(
        what: String,
        onStillFailing: suspend (Resource<T>) -> Unit = {},
        request: suspend () -> kotlinx.coroutines.flow.Flow<Resource<T>>,
    ): Resource.Success<T> {
        var attempt = 0
        var result: Resource<T>?
        do {
            if (attempt > 0) delay(retryDelayMs(attempt))
            result =
                try {
                    request().lastOrNull()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Resource.Error<T>(e.message ?: e::class.simpleName.orEmpty())
                }
            if (result !is Resource.Success) {
                val failure: Resource<T> = result ?: Resource.Error<T>("No answer")
                attempt++
                Logger.w("Home", "$what failed (try $attempt): ${failure.message}")
                if (attempt == HOME_ATTEMPTS) onStillFailing(failure)
            }
        } while (result !is Resource.Success)
        @Suppress("UNCHECKED_CAST")
        return result as Resource.Success<T>
    }

    private fun retryDelayMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(MAX_RETRY_DELAY_MS)

    private var continuationJob: Job? = null

    /** Fetches every remaining Home page, retrying each, until YouTube says there is no more. */
    private fun loadRemainingHomePages() {
        if (continuationJob?.isActive == true) return
        continuationJob =
            viewModelScope.launch {
                var pages = 0
                while (pages < MAX_HOME_PAGES) {
                    val token = continuation.value
                    if (token.isNullOrEmpty()) {
                        _homeListState.value = ListState.PAGINATION_EXHAUST
                        return@launch
                    }
                    _homeListState.value = ListState.PAGINATING
                    val page =
                        retryHome("page ${pages + 2}") {
                            homeRepository.getHomeDataContinue(token, getString(Res.string.view_count), getString(Res.string.song))
                        }
                    _homeItemList.update { it + (page.data?.second ?: listOf()).withoutYouTubePodcasts() }
                    _continuation.value = page.data?.first
                    pages++
                }
                _homeListState.value = ListState.PAGINATION_EXHAUST
            }
    }

    /** A shelf's "Play all": YouTube's own finite queue of that shelf's songs. */
    fun playAll(item: HomeItem) {
        item.playAllEndpoint?.let { playFromEndpoint(it, item.title) }
    }

    fun getContinueHomeItem(continuation: String?) {
        if (continuation.isNullOrEmpty()) {
            _homeListState.value = ListState.PAGINATION_EXHAUST
            return
        }
        loadRemainingHomePages()
    }

    fun exploreChart(region: String) {
        if (region == com.maxrave.simpmusic.chart.IRAN_CHART_KEY) {
            viewModelScope.launch {
                loadingChart.value = true
                regionCodeChart.value = region
                dataStoreManager.setChartKey(region)
                _chart.value = null
                loadIranChart()
                loadingChart.value = false
            }
            return
        }
        viewModelScope.launch {
            loadingChart.value = true
            _iranChart.value = null
            homeRepository
                .getChartData(
                    region,
                ).collect { values ->
                    regionCodeChart.value = region
                    dataStoreManager.setChartKey(region)
                    when (values) {
                        is Resource.Success -> {
                            _chart.value = values.data?.copy(podcasts = null)
                        }

                        else -> {
                            _chart.value = null
                        }
                    }
                    loadingChart.value = false
                }
        }
    }

    /** Reads the Spotify snapshot into the Iran chart, retrying a failed read a few times. */
    private suspend fun loadIranChart() {
        repeat(HOME_ATTEMPTS) { attempt ->
            val chart = com.maxrave.simpmusic.chart.loadSpotifySnapshot()?.toIranChart()
            if (chart != null) {
                _iranChart.value = chart
                return
            }
            Logger.w("Home", "Iran chart failed (try ${attempt + 1})")
            delay(retryDelayMs(attempt + 1))
        }
    }

    // Fork: "Same vibe, new artists" — see recommendation/DiscoverMix.kt.
    private val discoverMixLoader by lazy { DiscoverMixLoader(get(), get(), get(), dataStoreManager) }
    private val _discoverMix = MutableStateFlow<List<Track>>(emptyList())
    val discoverMix: StateFlow<List<Track>> = _discoverMix
    val discoverMixLoading = MutableStateFlow(false)
    private var discoverMixJob: Job? = null

    fun loadDiscoverMix(newVariant: Boolean = false) {
        if (!newVariant && (_discoverMix.value.isNotEmpty() || discoverMixJob?.isActive == true)) return
        discoverMixJob?.cancel()
        discoverMixJob =
            viewModelScope.launch {
                discoverMixLoading.value = true
                try {
                    _discoverMix.value = discoverMixLoader.load(newVariant)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w("DiscoverMix", "Mix failed: ${e.message}")
                } finally {
                    discoverMixLoading.value = false
                }
            }
    }

    fun playDiscoverMix(index: Int = 0) {
        val tracks = _discoverMix.value
        val first = tracks.getOrNull(index) ?: return
        viewModelScope.launch {
            setQueueData(
                QueueData.Data(
                    listTracks = ArrayList(tracks),
                    firstPlayedTrack = first,
                    playlistId = null,
                    playlistName = getString(Res.string.discover_mix_title),
                    playlistType = PlaylistType.RADIO,
                    continuation = null,
                ),
            )
            loadMediaItem(first, Config.PLAYLIST_CLICK, index)
        }
    }

    // Fork: "Like what you play" — Deezer related artists, see recommendation/SimilarTracksLoader.kt.
    private val similarLoader by lazy { SimilarTracksLoader(get(), get(), get(), dataStoreManager) }
    private val _similar = MutableStateFlow<List<Track>>(emptyList())
    val similar: StateFlow<List<Track>> = _similar
    val similarLoading = MutableStateFlow(false)
    private var similarJob: Job? = null

    fun loadSimilar(newVariant: Boolean = false) {
        if (!newVariant && (_similar.value.isNotEmpty() || similarJob?.isActive == true)) return
        similarJob?.cancel()
        similarJob =
            viewModelScope.launch {
                similarLoading.value = true
                try {
                    _similar.value = similarLoader.load(newVariant)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w("SimilarTracks", "Similar failed: ${e.message}")
                } finally {
                    similarLoading.value = false
                }
            }
    }

    fun playSimilar(index: Int = 0) {
        val tracks = _similar.value
        val first = tracks.getOrNull(index) ?: return
        viewModelScope.launch {
            setQueueData(
                QueueData.Data(
                    listTracks = ArrayList(tracks),
                    firstPlayedTrack = first,
                    playlistId = null,
                    playlistName = getString(Res.string.similar_mix_title),
                    playlistType = PlaylistType.RADIO,
                    continuation = null,
                ),
            )
            loadMediaItem(first, Config.PLAYLIST_CLICK, index)
        }
    }

    // Fork: Spotify-style history shelves — see recommendation/HomeShelvesLoader.kt.
    private val homeShelvesLoader by lazy { HomeShelvesLoader(get(), get(), dataStoreManager) }
    private val matcher by lazy { YouTubeMatcher(get()) }
    private val _homeShelves = MutableStateFlow(HomeShelves())
    val homeShelves: StateFlow<HomeShelves> = _homeShelves
    private var homeShelvesJob: Job? = null

    /** Key of the card being resolved on YouTube Music (it shows a spinner), or null. */
    val shelfBusy = MutableStateFlow<String?>(null)
    private var shelfJob: Job? = null

    fun loadHomeShelves() {
        if (homeShelvesJob != null) return
        homeShelvesJob =
            viewModelScope.launch {
                try {
                    _homeShelves.value = homeShelvesLoader.load()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w("HomeShelves", "Shelves failed: ${e.message}")
                    homeShelvesJob = null
                }
            }
    }

    /** One card at a time: a second tap while one is resolving replaces it. */
    private fun shelfAction(
        key: String,
        block: suspend () -> Unit,
    ) {
        shelfJob?.cancel()
        shelfJob =
            viewModelScope.launch {
                shelfBusy.value = key
                try {
                    block()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w("HomeShelves", "Card $key failed: ${e.message}")
                    makeToast(getString(Res.string.home_shelf_not_found))
                } finally {
                    if (shelfBusy.value == key) shelfBusy.value = null
                }
            }
    }

    /** Matches [tracks] on YouTube Music, four at a time, and plays them from [startIndex]. */
    private suspend fun playDeezerTracks(
        tracks: List<DeezerTrack>,
        startIndex: Int,
        name: String,
    ) {
        val limit = kotlinx.coroutines.sync.Semaphore(4)
        val matched =
            kotlinx.coroutines.coroutineScope {
                tracks
                    .map { t ->
                        async {
                            limit.withPermit { kotlinx.coroutines.withTimeoutOrNull(15_000) { runCatching { matcher.song(t.artist, t.title) }.getOrNull() } }
                        }
                    }.awaitAll()
            }
        // The tapped card keeps its place even when cards before it found no match.
        val startId = matched.getOrNull(startIndex)?.videoId
        val queue = matched.filterNotNull().distinctBy { it.videoId }
        val index = queue.indexOfFirst { it.videoId == startId }.coerceAtLeast(0)
        val first = queue.getOrNull(index)
        if (first == null) {
            makeToast(getString(Res.string.home_shelf_not_found))
            return
        }
        setQueueData(
            QueueData.Data(
                listTracks = ArrayList(queue),
                firstPlayedTrack = first,
                playlistId = null,
                playlistName = name,
                playlistType = PlaylistType.RADIO,
                continuation = null,
            ),
        )
        loadMediaItem(first, Config.PLAYLIST_CLICK, index)
    }

    fun playMix(card: MixCard) =
        shelfAction("mix:${card.artist.id}") {
            val station = DeezerCatalog.radio(card.artist.id).distinctBy { SimilarMix.trackKey(it.artist, it.title) }
            playDeezerTracks(station, 0, getString(Res.string.home_mix_name).replace("%1\$s", card.artist.name))
        }

    fun playMoreLike(index: Int) {
        val shelf = _homeShelves.value.moreLike ?: return
        shelfAction("more:$index") { playDeezerTracks(shelf.tracks, index, shelf.artist.name) }
    }

    fun openRelease(
        album: DeezerAlbum,
        onFound: (String) -> Unit,
    ) = shelfAction("release:${album.artist}:${album.title}") {
        val browseId = matcher.album(album.artist, album.title)
        if (browseId != null) onFound(browseId) else makeToast(getString(Res.string.home_shelf_not_found))
    }

    fun setParams(params: String?) {
        _params.value = params
    }

    override fun onCleared() {
        super.onCleared()
        homeJob?.cancel()
    }

    companion object {
        // Fork: tries before Home reports a failure (it keeps retrying after that), the longest
        // wait between two tries, and a ceiling on the pages fetched in one go.
        private const val HOME_ATTEMPTS = 4
        private const val MAX_RETRY_DELAY_MS = 60_000L
        private const val MAX_HOME_PAGES = 20

        // Home params
        const val HOME_PARAMS_RELAX = "ggM8SgQIBxADSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_SLEEP = "ggM8SgQIBxABSgQIBRADSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_ENERGIZE = "ggM8SgQIBxABSgQIBRABSgQICRADSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_SAD = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChADSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_ROMANCE = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRADSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_FEEL_GOOD = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBADSgQIBBABSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_WORKOUT = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBADSgQIDhABSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_PARTY = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhADSgQIAxABSgQIBhAB"
        const val HOME_PARAMS_COMMUTE = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxADSgQIBhAB"
        const val HOME_PARAMS_FOCUS = "ggM8SgQIBxABSgQIBRABSgQICRABSgQIChABSgQIDRABSgQICBABSgQIBBABSgQIDhABSgQIAxABSgQIBhAD"
    }
}