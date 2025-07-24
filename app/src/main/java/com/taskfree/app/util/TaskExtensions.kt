import com.taskfree.app.data.entities.Task
import java.time.LocalDateTime
import java.time.ZoneId

fun Task.isNotificationPassed(): Boolean {
    return reminderTime != null && due != null &&
            due.atTime(reminderTime.atZone(ZoneId.systemDefault()).toLocalTime())
                .isBefore(LocalDateTime.now())
}