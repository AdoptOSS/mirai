/*
 * Copyright 2019-2021 Mamoe Technologies and contributors.
 *
 *  此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 *  Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 *  https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:Suppress(
    "EXPERIMENTAL_API_USAGE",
    "DEPRECATION_ERROR",
    "OverridingDeprecatedMember",
    "INVISIBLE_REFERENCE",
    "INVISIBLE_MEMBER"
)

package net.mamoe.mirai.internal

import kotlinx.coroutines.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.Mirai
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.event.EventChannel
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.BotEvent
import net.mamoe.mirai.internal.contact.info.FriendInfoImpl
import net.mamoe.mirai.internal.contact.info.StrangerInfoImpl
import net.mamoe.mirai.internal.contact.uin
import net.mamoe.mirai.internal.network.component.ConcurrentComponentStorage
import net.mamoe.mirai.internal.network.handler.NetworkHandler
import net.mamoe.mirai.supervisorJob
import net.mamoe.mirai.utils.*
import kotlin.coroutines.CoroutineContext

/**
 * Protocol-irrelevant implementations
 */
internal abstract class AbstractBot constructor(
    final override val configuration: BotConfiguration,
    final override val id: Long,
) : Bot, CoroutineScope {
    ///////////////////////////////////////////////////////////////////////////
    // lifecycle
    ///////////////////////////////////////////////////////////////////////////

    // FASTEST INIT
    final override val logger: MiraiLogger by lazy { configuration.botLoggerSupplier(this) }

    final override val coroutineContext: CoroutineContext = configuration.parentCoroutineContext.childScopeContext(
        configuration.parentCoroutineContext.getOrElse(CoroutineExceptionHandler) {
            CoroutineExceptionHandler { _, e ->
                logger.error("An exception was thrown under a coroutine of Bot", e)
            }
        } + CoroutineName("Mirai Bot")
    )

    abstract val components: ConcurrentComponentStorage

    init {
        @Suppress("LeakingThis")
        Bot._instances[this.id] = this
        supervisorJob.invokeOnCompletion {
            Bot._instances.remove(id)
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // overrides
    ///////////////////////////////////////////////////////////////////////////

    final override val isOnline: Boolean get() = network.isOk()
    final override val eventChannel: EventChannel<BotEvent> =
        GlobalEventChannel.filterIsInstance<BotEvent>().filter { it.bot === this@AbstractBot }

    final override val otherClients: ContactList<OtherClient> = ContactList()
    final override val friends: ContactList<Friend> = ContactList()
    final override val groups: ContactList<Group> = ContactList()
    final override val strangers: ContactList<Stranger> = ContactList()

    final override val asFriend: Friend by lazy { Mirai.newFriend(this, FriendInfoImpl(uin, nick, "")) }
    final override val asStranger: Stranger by lazy { Mirai.newStranger(bot, StrangerInfoImpl(bot.id, bot.nick)) }

    ///////////////////////////////////////////////////////////////////////////
    // network
    ///////////////////////////////////////////////////////////////////////////

    val network: NetworkHandler by lazy { createNetworkHandler() }

    final override suspend fun login() {
        if (!isActive) error("Bot is already closed and cannot relogin. Please create a new Bot instance then do login.")
        network.resumeConnection()
    }

    protected abstract fun createNetworkHandler(): NetworkHandler
    protected abstract suspend fun sendLogout()

    // endregion


    init {
        coroutineContext[Job]!!.invokeOnCompletion { throwable ->
            logger.info { "Bot cancelled" + throwable?.message?.let { ": $it" }.orEmpty() }

            kotlin.runCatching {
                network.close(throwable)
            }

            // help GC release instances
            groups.forEach {
                it.members.delegate.clear()
            }
            groups.delegate.clear() // job is cancelled, so child jobs are to be cancelled
            friends.delegate.clear()
            strangers.delegate.clear()
        }
    }

    override fun close(cause: Throwable?) {
        if (!this.isActive) {
            // already cancelled
            return
        }

        this.network.close(cause)

        if (supervisorJob.isActive) {
            if (cause == null) {
                supervisorJob.cancel()
            } else {
                supervisorJob.cancel(CancellationException("Bot closed", cause))
            }
        }
    }

    final override fun toString(): String = "Bot($id)"
}

private val Throwable.rootCause: Throwable
    get() {
        var depth = 0
        var rootCause: Throwable? = this
        while (rootCause?.cause != null) {
            rootCause = rootCause.cause
            if (depth++ == 20) break
        }
        return rootCause ?: this
    }