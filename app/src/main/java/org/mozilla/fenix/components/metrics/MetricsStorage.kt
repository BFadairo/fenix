/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.metrics

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.support.utils.ext.getPackageInfoCompat
import org.mozilla.fenix.ext.settings
import org.mozilla.fenix.nimbus.FxNimbus
import org.mozilla.fenix.utils.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Interface defining functions around persisted local state for certain metrics.
 */
interface MetricsStorage {
    /**
     * Determines whether an [event] should be sent based on locally-stored state.
     */
    suspend fun shouldTrack(event: Event): Boolean

    /**
     * Updates locally-stored state for an [event] that has just been sent.
     */
    suspend fun updateSentState(event: Event)
}

internal class DefaultMetricsStorage(
    context: Context,
    private val settings: Settings,
    private val checkDefaultBrowser: () -> Boolean,
    private val shouldSendGenerally: () -> Boolean = { shouldSendGenerally(context) },
    private val getInstalledTime: () -> Long = { getInstalledTime(context) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MetricsStorage {

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * Checks local state to see whether the [event] should be sent.
     */
    override suspend fun shouldTrack(event: Event): Boolean =
        withContext(dispatcher) {
            // The side-effect of storing days of use needs to happen during the first two days after
            // install, which would normally be skipped by shouldSendGenerally.
            updateDaysOfUse()
            shouldSendGenerally() && when (event) {
                Event.GrowthData.SetAsDefault -> {
                    !settings.setAsDefaultGrowthSent && checkDefaultBrowser()
                }
                Event.GrowthData.FirstAppOpenForDay -> {
                    settings.resumeGrowthLastSent.hasBeenMoreThanDaySince()
                }
                Event.GrowthData.FirstUriLoadForDay -> {
                    settings.uriLoadGrowthLastSent.hasBeenMoreThanDaySince()
                }
                Event.GrowthData.FirstWeekSeriesActivity -> {
                    shouldTrackFirstWeekActivity()
                }
            }
        }

    override suspend fun updateSentState(event: Event) = withContext(dispatcher) {
        when (event) {
            Event.GrowthData.SetAsDefault -> {
                settings.setAsDefaultGrowthSent = true
            }
            Event.GrowthData.FirstAppOpenForDay -> {
                settings.resumeGrowthLastSent = System.currentTimeMillis()
            }
            Event.GrowthData.FirstUriLoadForDay -> {
                settings.uriLoadGrowthLastSent = System.currentTimeMillis()
            }
            Event.GrowthData.FirstWeekSeriesActivity -> {
                settings.firstWeekSeriesGrowthSent = true
            }
        }
    }

    private fun updateDaysOfUse() {
        val daysOfUse = settings.firstWeekDaysOfUseGrowthData
        val currentDate = Calendar.getInstance(Locale.US)
        val currentDateString = dateFormatter.format(currentDate.time)
        if (currentDate.timeInMillis.withinFirstWeek() && daysOfUse.none { it == currentDateString }) {
            settings.firstWeekDaysOfUseGrowthData = daysOfUse + currentDateString
        }
    }

    private fun shouldTrackFirstWeekActivity(): Boolean = Result.runCatching {
        if (!System.currentTimeMillis().withinFirstWeek() || settings.firstWeekSeriesGrowthSent) {
            return false
        }

        val daysOfUse = settings.firstWeekDaysOfUseGrowthData.map {
            dateFormatter.parse(it)
        }.sorted()

        // This loop will check whether the existing list of days of use, combined with the
        // current date, contains any periods of 3 days of use in a row.
        for (idx in daysOfUse.indices) {
            if (idx + 1 > daysOfUse.lastIndex || idx + 2 > daysOfUse.lastIndex) {
                continue
            }

            val referenceDate = daysOfUse[idx]!!.time.toCalendar()
            val secondDateEntry = daysOfUse[idx + 1]!!.time.toCalendar()
            val thirdDateEntry = daysOfUse[idx + 2]!!.time.toCalendar()
            val oneDayAfterReference = referenceDate.createNextDay()
            val twoDaysAfterReference = oneDayAfterReference.createNextDay()

            if (oneDayAfterReference == secondDateEntry && thirdDateEntry == twoDaysAfterReference) {
                return true
            }
        }
        return false
    }.getOrDefault(false)

    private fun Long.hasBeenMoreThanDaySince(): Boolean =
        System.currentTimeMillis() - this > dayMillis

    private fun Long.toCalendar(): Calendar = Calendar.getInstance(Locale.US).also { calendar ->
        calendar.timeInMillis = this
    }

    private fun Long.withinFirstWeek() = this < getInstalledTime() + fullWeekMillis

    private fun Calendar.createNextDay() = (this.clone() as Calendar).also { calendar ->
        calendar.add(Calendar.DAY_OF_MONTH, 1)
    }

    companion object {
        private const val dayMillis: Long = 1000 * 60 * 60 * 24
        private const val windowStartMillis: Long = dayMillis * 2
        private const val windowEndMillis: Long = dayMillis * 28

        // Note this is 8 so that recording of FirstWeekSeriesActivity happens throughout the length
        // of the 7th day after install
        private const val fullWeekMillis: Long = dayMillis * 8

        /**
         * Determines whether events should be tracked based on some general criteria:
         * - user has installed as a result of a campaign
         * - user is within 2-28 days of install
         * - tracking is still enabled through Nimbus
         */
        fun shouldSendGenerally(context: Context): Boolean {
            val installedTime = getInstalledTime(context)
            val timeDifference = System.currentTimeMillis() - installedTime
            val withinWindow = timeDifference in windowStartMillis..windowEndMillis

            return context.settings().adjustCampaignId.isNotEmpty() &&
                FxNimbus.features.growthData.value().enabled &&
                withinWindow
        }

        fun getInstalledTime(context: Context): Long = context.packageManager
            .getPackageInfoCompat(context.packageName, 0)
            .firstInstallTime
    }
}
