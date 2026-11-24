package com.freereading.reader

import androidx.annotation.MainThread
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator

/**
 * 串行更新朗读句高亮并在允许时跳到原文页，合并快速到达的中间状态。
 * 每个已启动的导航视图使用独立实例，所有调用均在该视图的 UI 调度器执行。
 */
@MainThread
internal class AudioHighlightSynchronizer(
    scope: CoroutineScope,
    private val apply: suspend (List<Decoration>) -> Unit,
    private val navigate: (Locator) -> Unit,
    private val canNavigate: (Target) -> Boolean,
) {
    /** 高亮目标同时携带书籍 ID 与句子索引，供调用方校验它仍属于当前播放位置。 */
    data class Target(val bookId: String, val sentenceIndex: Int, val locator: Locator)

    private val session = nextSession.incrementAndGet()
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private var latestTarget: Target? = null
    private var revision = 0L
    private var pageEpoch = 0L
    private var ready = false
    private var pendingNavigation = false

    init {
        scope.launch {
            var clearedPageEpoch = 0L
            for (ignored in updates) {
                if (!ready) continue
                val renderPageEpoch = pageEpoch
                val clearForPage = clearedPageEpoch != renderPageEpoch
                if (clearForPage) {
                    // Readium 会在页面就绪前重放缓存，但跳过同期的差量更新。
                    // 缓存无法记录 DOM 里残留的旧标记，页面每次就绪都要整组清空。
                    apply(emptyList())
                    currentCoroutineContext().ensureActive()
                    clearedPageEpoch = renderPageEpoch
                    // 清理期间可能又有一页就绪。保留其通知，下一轮再清理。
                    if (renderPageEpoch != pageEpoch) continue
                    // 在清理完成后读取最新目标，合并期间到达的换句通知。
                    updates.tryReceive()
                }
                val target = latestTarget
                val requestRevision = revision
                val decorations = target?.let {
                    listOf(Decoration(
                        id = "speech-$session-${it.bookId}-${it.sentenceIndex}-$renderPageEpoch",
                        locator = it.locator,
                        style = Decoration.Style.Highlight(tint = 0xFFE4CB88.toInt()),
                    ))
                }.orEmpty()

                // Readium 在挂起计算差异前就更新缓存目标，因此每次 apply 都必须执行完毕。
                // 不能通过取消旧 apply 追赶新请求，否则缓存与实际发送的 DOM 脚本会不一致。
                if (target != null || !clearForPage) apply(decorations)
                currentCoroutineContext().ensureActive()
                if (requestRevision != revision || target == null || !pendingNavigation || !canNavigate(target)) continue

                // 先消费本次跳页意图：go() 可能同步触发页面加载回调，
                // navigate() 中产生的新请求也必须保留它自己的跳页意图。
                pendingNavigation = false
                navigate(target.locator)
            }
        }.invokeOnCompletion { updates.close() }
    }

    /** 更新最新目标；相同目标的纯重绘不会丢失尚未执行的跳页请求，null 用于清除高亮。 */
    fun request(target: Target?, navigate: Boolean = true) {
        pendingNavigation = target != null && (navigate || (target == latestTarget && pendingNavigation))
        latestTarget = target
        revision += 1
        if (ready) updates.trySend(Unit)
    }

    /** 页面加载后清理 Readium 残留高亮并重绘当前句，已有跳页意图保持不变。 */
    fun onPageLoaded() {
        ready = true
        pageEpoch += 1
        revision += 1
        updates.trySend(Unit)
    }

    private companion object {
        val nextSession = AtomicLong()
    }
}
