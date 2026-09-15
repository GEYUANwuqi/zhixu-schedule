package cn.edu.sycu.schedule

import org.json.JSONArray
import org.json.JSONObject

data class CourseRule(val matchField:String, val matchValue:String, val name:String="", val teacher:String="", val room:String="", val weekday:Int?=null, val periods:String="", val weeks:String="", val note:String="")
val ruleFields = listOf("课程名", "教师", "教室", "星期", "周次", "节次", "备注")
fun CourseRule.matches(c: Course): Boolean {
    val actual = when(matchField){"课程名"->c.name;"教师"->c.teacher;"教室"->c.room;"星期"->c.weekday.toString();"周次"->c.weeks;"节次"->c.periods;"备注"->c.note;else->return false}
    return actual == matchValue
}
fun CourseRule.validate() {
    require(matchField in ruleFields && matchValue.isNotBlank()) { "请选择匹配字段并填写完整匹配值" }
    require(weekday == null || weekday in 1..7) { "星期应为 1–7" }
    if (periods.isNotBlank()) numbers(periods, 30)
    if (weeks.isNotBlank()) numbers(weeks, 64)
    require(listOf(name, teacher, room, periods, weeks, note).any { it.isNotBlank() } || weekday != null) { "请至少填写一个需要修改的字段" }
}
fun CourseRule.applyTo(c: Course): Course {
    if (!matches(c)) return c
    return replace(c)
}
private fun CourseRule.replace(c: Course): Course {
    return c.copy(name=name.ifBlank{c.name},teacher=teacher.ifBlank{c.teacher},room=room.ifBlank{c.room},weekday=weekday?:c.weekday,periods=periods.ifBlank{c.periods},weeks=weeks.ifBlank{c.weeks},note=if(note.isBlank()) c.note else note)
}
fun applyCourseRules(c: Course, rules: List<CourseRule>, table: Timetable): Course {
    // Every condition sees the original school data; later matching rules override only their filled fields.
    val updated = rules.filter { it.matches(c) }.fold(c) { current, rule -> rule.validate(); rule.replace(current) }
    require(updated.weekday in 1..7 && updated.name.isNotBlank()) { "规则生成了无效课程" }
    require(updated.weekList().all { it <= table.weekCount }) { "规则周次超出当前课表，请调整规则后再同步" }
    val times = timesFor(table)
    require(updated.periodList().all { it <= times.size && times[it - 1].first.isNotBlank() }) { "规则节次缺少上课时间，请调整规则后再同步" }
    return updated
}
object RuleCodec {
 fun encode(rules:List<CourseRule>)=JSONArray().apply{rules.forEach{put(JSONObject().put("matchField",it.matchField).put("matchValue",it.matchValue).put("name",it.name).put("teacher",it.teacher).put("room",it.room).put("weekday",it.weekday?:0).put("periods",it.periods).put("weeks",it.weeks).put("note",it.note))}}.toString()
 fun decode(text:String):List<CourseRule>{ val a=JSONArray(text); return (0 until a.length()).map{val o=a.getJSONObject(it);CourseRule(o.getString("matchField"),o.getString("matchValue"),o.optString("name"),o.optString("teacher"),o.optString("room"),o.optInt("weekday").takeIf{it in 1..7},o.optString("periods"),o.optString("weeks"),o.optString("note"))} }
}
