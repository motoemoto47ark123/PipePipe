package org.schabi.newpipe.local.feed

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.functions.Function4
import io.reactivex.rxjava3.processors.BehaviorProcessor
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.local.feed.item.StreamItem
import org.schabi.newpipe.local.feed.service.FeedEventManager
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.ErrorResultEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.IdleEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.ProgressEvent
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.SuccessResultEvent
import org.schabi.newpipe.util.DEFAULT_THROTTLE_TIMEOUT
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class FeedViewModel(
    private val applicationContext: Context,
    groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
    initialFilters: FeedFilters = FeedFilters()
) : ViewModel() {
    private var feedDatabaseManager: FeedDatabaseManager = FeedDatabaseManager(applicationContext)

    private val filtersProcessor = BehaviorProcessor.create<FeedFilters>()
    private val filtersFlowable = filtersProcessor
        .startWithItem(initialFilters)
        .distinctUntilChanged()

    private val mutableStateLiveData = MutableLiveData<FeedState>()
    val stateLiveData: LiveData<FeedState> = mutableStateLiveData

    private var combineDisposable = Flowable
        .combineLatest(
            FeedEventManager.events(),
            filtersFlowable,
            feedDatabaseManager.notLoadedCount(groupId),
            feedDatabaseManager.oldestSubscriptionUpdate(groupId),

            Function4 { t1: FeedEventManager.Event, t2: FeedFilters,
                t3: Long, t4: List<OffsetDateTime> ->
                return@Function4 CombineResultEventHolder(t1, t2, t3, t4.firstOrNull())
            }
        )
        .throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)
        .subscribeOn(Schedulers.io())
        .observeOn(Schedulers.io())
        .map { (event, filters, notLoadedCount, oldestUpdate) ->
            val streamItems = if (event is SuccessResultEvent || event is IdleEvent)
                feedDatabaseManager
                    .getStreams(
                        groupId, filters.showPlayedItems, filters.showPaidItems,
                        filters.showLiveItems
                    )
                    .blockingGet(arrayListOf())
            else
                arrayListOf()

            CombineResultDataHolder(event, streamItems, notLoadedCount, oldestUpdate)
        }
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { (event, listFromDB, notLoadedCount, oldestUpdate) ->
            mutableStateLiveData.postValue(
                when (event) {
                    is IdleEvent -> FeedState.LoadedState(listFromDB.map { e -> StreamItem(e) }, oldestUpdate, notLoadedCount)
                    is ProgressEvent -> FeedState.ProgressState(event.currentProgress, event.maxProgress, event.progressMessage)
                    is SuccessResultEvent -> FeedState.LoadedState(listFromDB.map { e -> StreamItem(e) }, oldestUpdate, notLoadedCount, event.itemsErrors)
                    is ErrorResultEvent -> FeedState.ErrorState(event.error)
                }
            )

            if (event is ErrorResultEvent || event is SuccessResultEvent) {
                FeedEventManager.reset()
            }
        }

    override fun onCleared() {
        super.onCleared()
        combineDisposable.dispose()
    }

    private data class CombineResultEventHolder(
        val t1: FeedEventManager.Event,
        val t2: FeedFilters,
        val t3: Long,
        val t4: OffsetDateTime?
    )

    private data class CombineResultDataHolder(
        val t1: FeedEventManager.Event,
        val t2: List<StreamWithState>,
        val t3: Long,
        val t4: OffsetDateTime?
    )

    /** Which kinds of streams the feed shows. */
    data class FeedFilters(
        val showPlayedItems: Boolean = true,
        val showPaidItems: Boolean = true,
        val showLiveItems: Boolean = true
    )

    fun setFilters(filters: FeedFilters) {
        filtersProcessor.onNext(filters)
    }

    fun saveFiltersToPreferences(filters: FeedFilters) =
        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit {
            putBoolean(
                applicationContext.getString(R.string.feed_show_played_items_key),
                filters.showPlayedItems
            )
            putBoolean(
                applicationContext.getString(R.string.feed_show_paid_items_key),
                filters.showPaidItems
            )
            putBoolean(
                applicationContext.getString(R.string.feed_show_live_items_key),
                filters.showLiveItems
            )
        }

    fun getFiltersFromPreferences() = getFiltersFromPreferences(applicationContext)

    companion object {
        private fun getFiltersFromPreferences(context: Context): FeedFilters {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return FeedFilters(
                showPlayedItems = prefs.getBoolean(
                    context.getString(R.string.feed_show_played_items_key), true
                ),
                showPaidItems = prefs.getBoolean(
                    context.getString(R.string.feed_show_paid_items_key), true
                ),
                showLiveItems = prefs.getBoolean(
                    context.getString(R.string.feed_show_live_items_key), true
                )
            )
        }
    }

    class Factory(
        private val context: Context,
        private val groupId: Long = FeedGroupEntity.GROUP_ALL_ID
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FeedViewModel(
                context.applicationContext,
                groupId,
                // Read initial value from preferences
                getFiltersFromPreferences(context.applicationContext)
            ) as T
        }
    }
}
