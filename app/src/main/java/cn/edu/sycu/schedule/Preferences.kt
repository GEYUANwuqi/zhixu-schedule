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
    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean("auto_check_updates", false)
        set(value) { prefs.edit().putBoolean("auto_check_updates", value).apply() }
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
    var widgetOpacity: Int
        get() = prefs.getInt("widget_opacity", 100).coerceIn(0, 100)
        set(value) { prefs.edit().putInt("widget_opacity", value.coerceIn(0, 100)).apply() }
    var cardColors: Map<String, Int>
        get() {
            val json = org.json.JSONObject(prefs.getString("card_colors", "{}")!!)
            return json.keys().asSequence().associateWith { json.getInt(it) }
        }
        set(value) { prefs.edit().putString("card_colors", org.json.JSONObject(value).toString()).apply() }
    fun appearance() = Appearance(themeColor, widgetOpacity, cardColors)
    var rules: List<CourseRule>
        get() = runCatching { RuleCodec.decode(prefs.getString("course_rules", "[]")!!) }.getOrDefault(emptyList())
        set(value) { prefs.edit().putString("course_rules", RuleCodec.encode(value)).apply() }
}

fun courseLocation(c: Course, display: CardDisplay = CardDisplay()): String =
    listOfNotNull(
            roomLabel(c.room).takeIf { display.room && it.isNotBlank() },
            c.teacher.takeIf { display.teacher && it.isNotBlank() },
        )
        .joinToString("·")
