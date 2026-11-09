package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.podcast.OpenPodcastDirectory
import com.maxrave.simpmusic.podcast.OpenPodcastEpisode
import com.maxrave.simpmusic.podcast.OpenPodcastFeed
import com.maxrave.simpmusic.podcast.OpenPodcastPlayer
import com.maxrave.simpmusic.podcast.OpenPodcastPlayerState
import com.maxrave.simpmusic.podcast.OpenPodcastShow
import com.maxrave.simpmusic.podcast.OpenPodcastSubscriptions
import com.maxrave.simpmusic.podcast.openPodcastPlayer
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Loading state of one list or feed. */
sealed interface OpenPodcastLoad<out T> {
    data object Idle : OpenPodcastLoad<Nothing>

    data object Loading : OpenPodcastLoad<Nothing>

    data class Ready<T>(
        val value: T,
    ) : OpenPodcastLoad<T>

    data object Failed : OpenPodcastLoad<Nothing>
}

/** Home's Podcasts tab and the show page. Nothing here talks to YouTube. */
class OpenPodcastViewModel(
    dataStoreManager: DataStoreManager,
) : BaseViewModel() {
    private val subscriptionStore = OpenPodcastSubscriptions(dataStoreManager)
    val player: OpenPodcastPlayer = openPodcastPlayer()
    val playerState: StateFlow<OpenPodcastPlayerState> = player.state

    val subscriptions: StateFlow<List<OpenPodcastShow>> =
        subscriptionStore.shows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _trending = MutableStateFlow<OpenPodcastLoad<List<OpenPodcastShow>>>(OpenPodcastLoad.Idle)
    val trending: StateFlow<OpenPodcastLoad<List<OpenPodcastShow>>> = _trending

    private val _searchResults = MutableStateFlow<OpenPodcastLoad<List<OpenPodcastShow>>>(OpenPodcastLoad.Idle)
    val searchResults: StateFlow<OpenPodcastLoad<List<OpenPodcastShow>>> = _searchResults
    private var searchJob: Job? = null

    private val _feed = MutableStateFlow<OpenPodcastLoad<OpenPodcastFeed>>(OpenPodcastLoad.Idle)
    val feed: StateFlow<OpenPodcastLoad<OpenPodcastFeed>> = _feed

    fun loadTrending(force: Boolean = false) {
        if (!force && (_trending.value is OpenPodcastLoad.Ready || _trending.value is OpenPodcastLoad.Loading)) return
        _trending.value = OpenPodcastLoad.Loading
        viewModelScope.launch {
            _trending.value =
                try {
                    OpenPodcastLoad.Ready(OpenPodcastDirectory.trending())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Trending failed: ${e.message}")
                    OpenPodcastLoad.Failed
                }
        }
    }

    /** Debounced: typing does not fire a request per key. */
    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = OpenPodcastLoad.Idle
            return
        }
        _searchResults.value = OpenPodcastLoad.Loading
        searchJob =
            viewModelScope.launch {
                delay(400)
                _searchResults.value =
                    try {
                        OpenPodcastLoad.Ready(OpenPodcastDirectory.search(query))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "Search failed: ${e.message}")
                        OpenPodcastLoad.Failed
                    }
            }
    }

    fun loadFeed(show: OpenPodcastShow) {
        _feed.value = OpenPodcastLoad.Loading
        viewModelScope.launch {
            _feed.value =
                try {
                    OpenPodcastLoad.Ready(OpenPodcastDirectory.feed(show.feedUrl, show))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Feed failed for ${show.feedUrl}: ${e.message}")
                    OpenPodcastLoad.Failed
                }
        }
    }

    fun toggleSubscription(show: OpenPodcastShow) {
        viewModelScope.launch { subscriptionStore.toggle(show) }
    }

    fun play(
        show: OpenPodcastShow,
        episode: OpenPodcastEpisode,
    ) {
        val state = playerState.value
        if (state.episodeId == episode.id) player.togglePlayPause() else player.play(show, episode)
    }

    private companion object {
        const val TAG = "OpenPodcast"
    }
}
