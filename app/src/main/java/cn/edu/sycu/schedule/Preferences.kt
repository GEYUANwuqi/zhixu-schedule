package cn.edu.sycu.schedule

import android.content.Context

data class CardDisplay(
    val room: Boolean = true,
    val teacher: Boolean = true,
    val weeks: Boolean = true,
    val periods: Boolean = false,
    val note: Boolean = false,
)

class SchedulePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)
    var activeId: String?
        get() = prefs.getString("active_table", null)
        set(value) {
            prefs.edit().putString("active_table", value).apply()
        }

    var widgetLarge: Boolean
        get() = prefs.getBoolean("widget_large", false)
        set(value) {
            prefs.edit().putBoolean("widget_large", value).apply()
        }

    fun read() =
        CardDisplay(
            prefs.getBoolean("room", true),
            prefs.getBoolean("teacher", true),
            prefs.getBoolean("weeks", true),
            prefs.getBoolean("periods", false),
            prefs.getBoolean("note", false),
        )

    fun save(value: CardDisplay) {
        prefs
            .edit()
            .putBoolean("room", value.room)
            .putBoolean("teacher", value.teacher)
            .putBoolean("weeks", value.weeks)
            .putBoolean("periods", value.periods)
            .putBoolean("note", value.note)
            .apply()
    }

    var themeColor: Int
        get() = prefs.getInt("theme_color", 0xff52754f.toInt())
        set(value) { prefs.edit().putInt("theme_color", value).apply() }
}

fun courseLocation(c: Course, display: CardDisplay = CardDisplay()): String =
    listOfNotNull(
            roomLabel(c.room).takeIf { display.room && it.isNotBlank() },
            c.teacher.takeIf { display.teacher && it.isNotBlank() },
        )
        .joinToString("·")
