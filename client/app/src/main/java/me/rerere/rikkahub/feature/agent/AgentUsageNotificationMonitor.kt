package me.rerere.rikkahub.feature.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.utils.NotificationUtil
import me.rerere.rikkahub.utils.sendNotification
import kotlin.coroutines.coroutineContext

/** An event observer, never a task executor. No polling, model calls or automatic recovery. */
internal class AgentUsageNotificationMonitor(context: Context, appScope: AppScope, settings: SettingsStore) {
    private val sink=AgentUsageNotificationSink(context)
    private val foreground=MutableStateFlow(true)

    init {
        appScope.launch {
            val lifecycle=ProcessLifecycleOwner.get().lifecycle
            foreground.value=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                when(event) {
                    Lifecycle.Event.ON_START -> foreground.value=true
                    Lifecycle.Event.ON_STOP -> foreground.value=false
                    else -> Unit
                }
            })
        }
        appScope.launch(Dispatchers.IO) {
            AgentTaskStore(context).use { store ->
                AgentUsageNotificationJournal(context).use { journal ->
                    val dispatcher=AgentUsageNotificationDispatcher(store,journal)
                    var previousOwner: String?=null
                    combine(AgentTaskStore.usageChanges.onStart { emit(Unit) },foreground,settings.settingsFlow) { _,_,_ -> Unit }
                        .conflate().collect {
                            val current=settings.settingsFlow.value
                            val owner=current.activeProfileId
                            val jobContext=coroutineContext
                            val allowed={
                                jobContext.ensureActive()
                                val live=settings.settingsFlow.value
                                !foreground.value && !live.init && live.activeProfileId==owner &&
                                    live.learnerProfiles.any { it.id==owner } &&
                                    live.displaySetting.enableAgentUsageNotifications && sink.allowed()
                            }
                            if(previousOwner!=owner) sink.cancel()
                            previousOwner=owner
                            try {
                                if(!allowed() || !dispatcher.scan(owner,System.currentTimeMillis(),allowed) { sink.post() }) sink.cancel()
                            } catch(error: CancellationException) { throw error }
                            catch(_: Exception) {
                                // Advisory failure must never break generation or invent an all-clear.
                                sink.cancel()
                            }
                        }
                }
            }
        }
    }
}

internal class AgentUsageNotificationSink(private val context: Context,
    private val channel: String=CHANNEL, private val notificationId: Int=ID) {
    companion object {
        const val CHANNEL="agent_usage_review_v1"
        const val ID=0x485855
    }
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,"请求用量核对",NotificationManager.IMPORTANCE_DEFAULT).apply {
                description="请求结果未知、用量未报告或请求密集时提示核对；不代表已产生费用。"
                lockscreenVisibility=NotificationCompat.VISIBILITY_PRIVATE
            })
    }
    fun allowed(): Boolean = NotificationUtil.hasNotificationPermission(context) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel)?.importance?.let { it!=NotificationManager.IMPORTANCE_NONE }==true

    fun post() {
        // Opening the App revalidates the current profile; no owner or task deep-link is trusted.
        val intent=PendingIntent.getActivity(context,notificationId,Intent(context,StudyFlutterActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.sendNotification(channel,notificationId) {
            title="请求用量需要核对"
            content="请打开侧栏的请求用量，查看未知结果、未报告用量或密集请求记录。"
            autoCancel=true;onlyAlertOnce=true
            category=NotificationCompat.CATEGORY_STATUS
            contentIntent=intent
        }
    }
    fun cancel() { NotificationManagerCompat.from(context).cancel(notificationId) }
}
