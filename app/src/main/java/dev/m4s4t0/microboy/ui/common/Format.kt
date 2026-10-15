package dev.m4s4t0.microboy.ui.common

import java.text.DateFormat
import java.util.Date

object Format {
    fun playtime(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            ms <= 0 -> "Not played yet"
            minutes < 1 -> "< 1 min"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    fun relative(time: Long): String {
        if (time <= 0) return "Never"
        val diff = System.currentTimeMillis() - time
        val min = diff / 60_000
        return when {
            min < 1 -> "Just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            min < 7 * 24 * 60 -> "${min / (24 * 60)} d ago"
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
        }
    }

    fun dateTime(time: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

    fun size(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
