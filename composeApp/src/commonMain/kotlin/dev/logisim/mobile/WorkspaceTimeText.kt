package dev.logisim.mobile

import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.WorkspaceProjectInfo
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/**
 * How long ago [thenMillis] was, in the app language: "just now", "5 minutes ago", "3 days ago", "2 months ago".
 * Pure arithmetic on the two instants, so it needs no calendar or time-zone library; a time in the future (another
 * device's clock) reads as just now.
 */
internal fun relativeTimeText(nowMillis: Long, thenMillis: Long): LocalizedText {
    val elapsed = (nowMillis - thenMillis).coerceAtLeast(0)
    return when {
        elapsed < MINUTE -> message("time_just_now")
        elapsed < HOUR -> LocalizedText("time_minutes_ago", quantity = elapsed / MINUTE)
        elapsed < DAY -> LocalizedText("time_hours_ago", quantity = elapsed / HOUR)
        elapsed < 30 * DAY -> LocalizedText("time_days_ago", quantity = elapsed / DAY)
        elapsed < 365 * DAY -> LocalizedText("time_months_ago", quantity = elapsed / (30 * DAY))
        else -> LocalizedText("time_years_ago", quantity = elapsed / (365 * DAY))
    }
}

/**
 * Session tab labels: the project name, and where open tabs share a name, a detail that tells them apart: the linked
 * file's name, else when the project was created, else the start of its ID.
 */
internal fun workspaceTabLabels(
    projects: List<WorkspaceProjectInfo>, files: Map<WorkspaceProjectId, String>, nowMillis: Long,
): Map<WorkspaceProjectId, LocalizedText> {
    val labels = mutableMapOf<WorkspaceProjectId, LocalizedText>()
    for (group in projects.groupBy { it.name.trim().lowercase() }.values) {
        if (group.size == 1) { labels[group.single().id] = LocalizedText.literal(group.single().name); continue }
        fun distinct(details: List<LocalizedText?>) = details.all { it != null } && details.toSet().size == details.size
        val byFile = group.map { project -> files[project.id]?.let(LocalizedText::literal) }
        val byAge = group.map { project -> message("workspace_tab_created", "time" to relativeTimeText(nowMillis, project.createdAtMillis)) }
        val details = when {
            distinct(byFile) -> byFile
            distinct(byAge) -> byAge
            else -> group.map { LocalizedText.literal(it.id.value.take(6)) }
        }
        group.zip(details).forEach { (project, detail) ->
            labels[project.id] = message("workspace_tab_disambiguated", "name" to project.name, "detail" to checkNotNull(detail))
        }
    }
    return labels
}

/**
 * A project row's detail line: when its content or name last changed. The storage ID is shown only to tell apart
 * projects that share a name.
 */
internal fun projectEditedText(project: WorkspaceProjectInfo, nowMillis: Long, sharesName: Boolean): LocalizedText {
    val time = relativeTimeText(nowMillis, project.updatedAtMillis)
    return if (sharesName) message("workspace_edited_with_id", "time" to time, "id" to project.id.value.take(8))
    else message("workspace_edited", "time" to time)
}
