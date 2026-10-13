package cn.edu.sycu.schedule

import java.time.LocalTime

/** Fractional grid row; adjacent periods share a boundary throughout their break. */
internal fun currentTimeRow(times: List<Pair<String, String>>, now: LocalTime, classPeriods: Set<Int> = emptySet()): Float? {
    val seconds = now.toSecondOfDay()
    var previousEnd: Int? = null
    var previousRow = 0f
    times.forEachIndexed { index, (startText, endText) ->
        val start = runCatching { LocalTime.parse(startText).toSecondOfDay() }.getOrNull() ?: return@forEachIndexed
        val end = runCatching { LocalTime.parse(endText).toSecondOfDay() }.getOrNull() ?: return@forEachIndexed
        if (end <= start) return@forEachIndexed
        if (seconds < start) {
            val earlierEnd = previousEnd ?: return null
            return previousRow + (index - previousRow) * (seconds - earlierEnd).toFloat() / (start - earlierEnd)
        }
        if (seconds < end) {
            if (index + 1 in classPeriods) return null
            return index + (seconds - start).toFloat() / (end - start)
        }
        previousEnd = end
        previousRow = index + 1f
    }
    return previousRow.takeIf { seconds == previousEnd }
}
