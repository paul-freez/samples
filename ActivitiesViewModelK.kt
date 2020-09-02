package com.project.main.viewmodel

import androidx.annotation.Nullable
import androidx.lifecycle.*
import com.project.main.models.json.params.SessionFilter
import com.project.main.models.session.info.ActivitiesArchiveInfo
import com.project.main.models.session.info.ActivitiesArchiveInfo.ArchiveItem
import com.project.main.providers.ActivitiesFilterProvider
import com.project.main.utils.DateUtils
import com.project.main.web.RequestListener
import com.project.main.web.RequestPerformer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

open class ActivitiesViewModelK(@JvmField protected val pageSize: Int) : LoadingViewModel() {

    @JvmField
    protected val MAX_THRESHOLD = 25

    @JvmField
    protected var currentPage = 0

    var isThresholdReached = false
        protected set(value) {
            _filteredThresholdArchive.value = null
            field = value
        }

    var itemsToShowList = mutableListOf<Long>()
        set(value) {
            field.addAll(value)
        }

    // Original archive info. Should not be modified
    @JvmField
    protected val allArchiveItems: MutableLiveData<List<ArchiveItem>> = MutableLiveData()
    val _allArchiveItems: LiveData<List<ArchiveItem>> = allArchiveItems

    // Archive info that was filtered
    @JvmField
    protected val mFilteredArchiveInfo = MediatorLiveData<List<ArchiveItem>>()

    @JvmField
    protected val thresholdArchiveItems: MutableLiveData<List<ArchiveItem>> = MutableLiveData()

    @JvmField
    protected val filterProvider: ActivitiesFilterProvider = ActivitiesFilterProvider(SessionFilter())

    private val _filteredThresholdArchive = MutableLiveData<List<ArchiveItem>>()
    private val filteredThresholdArchive: LiveData<List<ArchiveItem>> = Transformations.switchMap(thresholdArchiveItems) { threshold ->
        val filteredData = filterProvider.getFilteredData(threshold)
        if (filteredData.size >= MAX_THRESHOLD
                && (_filteredThresholdArchive.value == null || filteredData.size / MAX_THRESHOLD == 2)) {
            Timber.d("Threshold ready %d", filteredData.size)
            _filteredThresholdArchive.value = filteredData
        }
        _filteredThresholdArchive
    }

    init {
        mFilteredArchiveInfo.addSource(filteredThresholdArchive) { threshold ->
            if (threshold != null) {
                isThresholdReached = true
                currentPage = 0
                mFilteredArchiveInfo.value = threshold
            }
        }
    }

    fun isAllArchiveReady() : Boolean = allArchiveItems.value != null && allArchiveItems.value!!.isNotEmpty()

    @ExperimentalCoroutinesApi
    protected fun loadActivitiesArchive(@Nullable coachPupilsIds: List<Long>) {
        viewModelScope.launch {
            val archiveInfoList = mutableListOf<ActivitiesArchiveInfo>()
            val currentTime = System.currentTimeMillis() // Save current time to make sure it'd be within range

            loadAllArchiveAsync(coachPupilsIds)
                    .onCompletion {
                        Timber.d("All archive fetched")
                        allArchiveItems.value = transformArchiveItemsList(archiveInfoList)
                    }
                    .onEach {
                        archiveInfoList.add(it.info)
                        if (it.isWithinRange(currentTime)) {
                            Timber.d("Received archive within range")
                            thresholdArchiveItems.value = transformArchiveItemsList(mutableListOf(it.info))
                        }
                    }
                    .launchIn(this)

            loadFastArchives(coachPupilsIds)
        }
    }

    @ExperimentalCoroutinesApi
    private fun loadAllArchiveAsync(@Nullable coachPupilsIds: List<Long>): Flow<ActivityArchiveRange> = flow {
        // Getting dates in YEAR periods
        getPeriodDates(System.currentTimeMillis(), SessionFilter.DEFAULT_STARTTIME)
                .buffer() // Calculate dates independently
                .collect { dates ->
                    // Loading archives async to allow this flow to complete
                    flow {
                        emit(loadArchiveForDates(dates.first, dates.second, coachPupilsIds))
                    }.filterNotNull().collect { info ->
                        emit(ActivityArchiveRange(dates, info))
                    }
                }
    }

    @ExperimentalCoroutinesApi
    private suspend fun loadFastArchives(@Nullable coachPupilsIds: List<Long>) {
        val archiveForPeriod = fun(period: Int) = flow {
            val archivesForPeriod = mutableListOf<ArchiveItem>()
            var counter = 0

            getPeriodDates(System.currentTimeMillis(), period = period, endTime = SessionFilter.DEFAULT_STARTTIME)
                    .filter { !isThresholdReached }
                    .map { dates -> loadArchiveForDates(dates.first, dates.second, coachPupilsIds) }
                    .filterNotNull()
                    .map { info -> transformArchiveItemsList(mutableListOf(info)) }
                    .collect {
                        archivesForPeriod.addAll(it)
                        emit(archivesForPeriod)
                    }
        }

        val collector: (List<ArchiveItem>) -> Unit = {
            if (!isThresholdReached) thresholdArchiveItems.value = it
        }

        fun Flow<List<ArchiveItem>>.prepareFlowChain() {
            this
                    .onEach { collector(it) }
                    .launchIn(viewModelScope)
        }
        archiveForPeriod(Calendar.MONTH).prepareFlowChain()
        archiveForPeriod(Calendar.WEEK_OF_MONTH).prepareFlowChain()
    }


    /**
     * Actual request to load #ArchiveItem from server
     */
    private suspend fun loadArchiveForDates(from: Long, to: Long, @Nullable coachPupilsIds: List<Long>): ActivitiesArchiveInfo? {
        return suspendCoroutine { continuation ->
            val format: (Long) -> String = { DateUtils.formatDate(DateUtils.DATE_TIME_FORMAT, it) }

            RequestPerformer.getActivitiesArchive(format(to), format(from),
                    coachPupilsIds, this, object : RequestListener<ActivitiesArchiveInfo?>() {
                override fun onSuccess(response: ActivitiesArchiveInfo?) {
                    super.onSuccess(response)

                    continuation.resume(response)
                }

                override fun onFailure(err: String?): Boolean {
                    continuation.resume(null)
//                    continuation.resumeWithException(Exception(err))

                    return super.onFailure(err)
                }
            })
        }
    }

    /**
     * Transforms [ActivitiesArchiveInfo] to @[ActivitiesArchiveInfo.ArchiveItem]
     */
    private fun transformArchiveItemsList(archiveList: List<ActivitiesArchiveInfo>): List<ArchiveItem> {
        return archiveList
                .flatMap { it.archiveItemList }
                .filter { itemsToShowList.isEmpty() || itemsToShowList.contains(it.sessionId) }
                .sortedWith(Comparator { o1, o2 -> o2.orderDate.compareTo(o1.orderDate) })
    }

    private fun getPeriodDates(startTime: Long, endTime: Long = 0, period: Int = Calendar.YEAR): Flow<Pair<Long, Long>> =
            flow {
                var currentTime = startTime
                val calendar = Calendar.getInstance().apply { timeInMillis = currentTime }
                val _endTime = if (endTime == 0L) calendar.apply { add(period, -1) }.timeInMillis else endTime

                while (currentTime >= _endTime) {
                    val lastTime = currentTime
                    currentTime = calendar.apply {
                        timeInMillis = currentTime
                        add(period, -1)
                    }.timeInMillis

                    emit(Pair(lastTime, currentTime))
                }
            }
}

private class ActivityArchiveRange(private val from: Long, private val to: Long, val info: ActivitiesArchiveInfo) {
    constructor(dates: Pair<Long, Long>, info: ActivitiesArchiveInfo) : this(dates.first, dates.second, info)

    fun isWithinRange(date: Long) = (date in to..from)
}