package com.dayoudu.app.data

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.dayoudu.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderScheduler(private val context: Context) {
    private val alarm = context.getSystemService(AlarmManager::class.java)

    fun sync(plan: PlanEntity) {
        cancel(plan.id)
        if (plan.deletedAtMillis != null) return
        val minutes = plan.reminderMinutesBefore ?: return
        if (plan.status in setOf("CANCELLED", "COMPLETE")) return
        val trigger = plan.scheduledStartMillis - minutes * 60_000L
        if (trigger <= System.currentTimeMillis()) return
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending(plan.id, false))
    }

    fun cancel(planId: String) {
        listOf(false, true).forEach { snoozed ->
            val intent = pending(planId, snoozed)
            alarm.cancel(intent)
            intent.cancel()
        }
        context.getSystemService(NotificationManager::class.java).cancel(planId.hashCode())
    }

    fun snooze(planId: String) {
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10 * 60_000L,
            pending(planId, true))
        context.getSystemService(NotificationManager::class.java).cancel(planId.hashCode())
    }

    private fun pending(planId: String, snoozed: Boolean): PendingIntent {
        val intent = Intent(context, PlanReminderReceiver::class.java).apply {
            action = "com.dayoudu.app.PLAN_REMINDER"
            data = Uri.parse("dayoudu://plan-reminder/$planId/${if (snoozed) "snoozed" else "scheduled"}")
            putExtra("planId", planId)
        }
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class PlanReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("planId") ?: return
        if (intent.action == "com.dayoudu.app.SNOOZE_REMINDER") {
            ReminderScheduler(context).snooze(id)
            return
        }
        if (intent.action != "com.dayoudu.app.PLAN_REMINDER") return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val plan = NutritionDatabase.get(context).planDao().get(id)
                if (plan != null && plan.deletedAtMillis == null && plan.reminderMinutesBefore != null && plan.status !in setOf("CANCELLED", "COMPLETE")) {
                    showNotification(context, plan)
                }
            } finally { result.finish() }
        }
    }

    private fun showNotification(context: Context, plan: PlanEntity) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = if (plan.reminderSilent) "plan_silent" else "plan_reminders"
        manager.createNotificationChannel(NotificationChannel("plan_reminders", "计划提醒", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("plan_silent", "静音计划提醒", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(context, 0,
            Intent(context, PlanReminderReceiver::class.java).apply {
                action = "com.dayoudu.app.SNOOZE_REMINDER"
                data = Uri.parse("dayoudu://plan-reminder/${plan.id}/action-snooze")
                putExtra("planId", plan.id)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("计划提醒")
            .setContentText(plan.title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "延后 10 分钟", snooze).build())
            .build()
        manager.notify(plan.id.hashCode(), notice)
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED)) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val plans = NutritionDatabase.get(context).planDao().futureReminders(System.currentTimeMillis())
                val scheduler = ReminderScheduler(context)
                plans.forEach { scheduler.sync(it) }
            } finally { result.finish() }
        }
    }
}
