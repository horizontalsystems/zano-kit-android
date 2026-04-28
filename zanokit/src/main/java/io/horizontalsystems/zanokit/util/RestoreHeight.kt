package io.horizontalsystems.zanokit.util

import java.util.Calendar
import java.util.Date
import java.util.TimeZone

object RestoreHeight {
    private const val DIFFICULTY_TARGET = 60 // seconds per block (hybrid PoW/PoS)

    // Monthly checkpoints: first-of-month UTC date → block height.
    // Data retrieved from Zano daemon on 2026-02-18.
    private val checkpoints: Map<String, Long> = mapOf(
        "2019-05-01" to 0L,
        "2019-06-01" to 33753L,
        "2019-07-01" to 76449L,
        "2019-08-01" to 120919L,
        "2019-09-01" to 164985L,
        "2019-10-01" to 207604L,
        "2019-11-01" to 252030L,
        "2019-12-01" to 295035L,
        "2020-01-01" to 339494L,
        "2020-02-01" to 383940L,
        "2020-03-01" to 425579L,
        "2020-04-01" to 470098L,
        "2020-05-01" to 513253L,
        "2020-06-01" to 557689L,
        "2020-07-01" to 600704L,
        "2020-08-01" to 645191L,
        "2020-09-01" to 689563L,
        "2020-10-01" to 732593L,
        "2020-11-01" to 777139L,
        "2020-12-01" to 820149L,
        "2021-01-01" to 864507L,
        "2021-02-01" to 908839L,
        "2021-03-01" to 949403L,
        "2021-04-01" to 993618L,
        "2021-05-01" to 1036823L,
        "2021-06-01" to 1081230L,
        "2021-07-01" to 1124184L,
        "2021-08-01" to 1168666L,
        "2021-09-01" to 1213065L,
        "2021-10-01" to 1256083L,
        "2021-11-01" to 1300706L,
        "2021-12-01" to 1343923L,
        "2022-01-01" to 1388500L,
        "2022-02-01" to 1432998L,
        "2022-03-01" to 1473418L,
        "2022-04-01" to 1517892L,
        "2022-05-01" to 1561036L,
        "2022-06-01" to 1605647L,
        "2022-07-01" to 1648647L,
        "2022-08-01" to 1693251L,
        "2022-09-01" to 1737858L,
        "2022-10-01" to 1781224L,
        "2022-11-01" to 1825835L,
        "2022-12-01" to 1869049L,
        "2023-01-01" to 1913644L,
        "2023-02-01" to 1958240L,
        "2023-03-01" to 1998574L,
        "2023-04-01" to 2043191L,
        "2023-05-01" to 2086475L,
        "2023-06-01" to 2131038L,
        "2023-07-01" to 2174187L,
        "2023-08-01" to 2218818L,
        "2023-09-01" to 2263535L,
        "2023-10-01" to 2306751L,
        "2023-11-01" to 2351357L,
        "2023-12-01" to 2394645L,
        "2024-01-01" to 2439151L,
        "2024-02-01" to 2483864L,
        "2024-03-01" to 2525559L,
        "2024-04-01" to 2569856L,
        "2024-05-01" to 2613175L,
        "2024-06-01" to 2657650L,
        "2024-07-01" to 2700899L,
        "2024-08-01" to 2745562L,
        "2024-09-01" to 2790360L,
        "2024-10-01" to 2833504L,
        "2024-11-01" to 2878227L,
        "2024-12-01" to 2921353L,
        "2025-01-01" to 2966161L,
        "2025-02-01" to 3010703L,
        "2025-03-01" to 3051042L,
        "2025-04-01" to 3095283L,
        "2025-05-01" to 3138566L,
        "2025-06-01" to 3183222L,
        "2025-07-01" to 3226424L,
        "2025-08-01" to 3270981L,
        "2025-09-01" to 3315720L,
        "2025-10-01" to 3358971L,
        "2025-11-01" to 3403521L,
        "2025-12-01" to 3446760L,
        "2026-01-01" to 3491296L,
        "2026-02-01" to 3536021L,
        "2026-03-01" to 3576257L,
        "2026-04-01" to 3620877L,
    )

    fun getHeight(timestamp: Long): Long = getHeight(Date(timestamp * 1000L))

    fun getHeight(date: Date): Long = getHeightOrEstimate(date)

    fun maximumEstimatedHeight(): Long = getHeight(Date(System.currentTimeMillis() + 4L * 86400 * 1000))

    fun getDate(height: Long): Date {
        val sorted = checkpoints.entries.sortedBy { it.value }
        val prev = sorted.lastOrNull { it.value <= height }
        val next = sorted.firstOrNull { it.value > height }

        return when {
            prev != null && next != null -> {
                val prevDate = parseDate(prev.key)
                val nextDate = parseDate(next.key)
                val timeDiff = nextDate.time - prevDate.time
                val heightDiff = next.value - prev.value
                val heightOffset = height - prev.value
                if (heightDiff > 0) {
                    Date(prevDate.time + timeDiff * heightOffset / heightDiff)
                } else {
                    prevDate
                }
            }
            prev != null -> {
                val prevDate = parseDate(prev.key)
                val heightOffset = height - prev.value
                val dailyBlocks = 86400.0 / DIFFICULTY_TARGET
                val daysOffset = heightOffset / dailyBlocks
                Date(prevDate.time + (daysOffset * 86400_000L).toLong())
            }
            else -> parseDate(sorted.first().key)
        }.let { estimated -> if (estimated.after(Date())) Date() else estimated }
    }

    private fun parseDate(dateStr: String): Date {
        val parts = dateStr.split("-")
        val utc = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(utc)
        cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    private fun getHeightOrEstimate(date: Date): Long {
        val utc = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(utc)
        cal.time = date

        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1 // 1-based

        if (year < 2019 || (year == 2019 && month < 5)) return 0L

        // Find first-of-month checkpoint on or before the date
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var prevMonthDate = cal.time

        var prevHeight: Long? = checkpoints[formatDate(prevMonthDate)]
        while (prevHeight == null) {
            cal.add(Calendar.MONTH, -1)
            if (cal.get(Calendar.YEAR) < 2019) return 0L
            prevMonthDate = cal.time
            prevHeight = checkpoints[formatDate(prevMonthDate)]
        }

        val queryDate = date
        if (formatDate(queryDate) == formatDate(prevMonthDate)) return prevHeight

        // Try to interpolate using next month's checkpoint
        val nextMonthCal = Calendar.getInstance(utc)
        nextMonthCal.time = prevMonthDate
        nextMonthCal.add(Calendar.MONTH, 1)
        val nextMonthDate = nextMonthCal.time
        val nextHeight = checkpoints[formatDate(nextMonthDate)]

        return if (nextHeight != null) {
            val diff = nextHeight - prevHeight
            val totalMs = nextMonthDate.time - prevMonthDate.time
            val offsetMs = queryDate.time - prevMonthDate.time
            val days = offsetMs / 86400000L
            val totalDays = totalMs / 86400000L
            val blocks = diff.toDouble() * (days.toDouble() / totalDays.toDouble())
            (prevHeight + blocks.toLong())
        } else {
            val days = (queryDate.time - prevMonthDate.time) / 86400000L
            val dailyBlocks = (24.0 * 60 * 60) / DIFFICULTY_TARGET
            (prevHeight + (days * dailyBlocks).toLong())
        }
    }

    private fun formatDate(date: Date): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.time = date
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(y, m, d)
    }
}
