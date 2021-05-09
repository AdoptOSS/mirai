/*
 * Copyright 2019-2021 Mamoe Technologies and contributors.
 *
 *  此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 *  Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 *  https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.internal.network.handler

import net.mamoe.mirai.internal.network.handler.NetworkHandler.State
import net.mamoe.mirai.internal.network.handler.selector.AbstractKeepAliveNetworkHandlerSelector
import net.mamoe.mirai.internal.test.runBlockingUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.seconds

private class TestSelector(val createInstance0: () -> NetworkHandler) :
    AbstractKeepAliveNetworkHandlerSelector<NetworkHandler>() {
    val createInstanceCount = AtomicInteger(0)
    override fun createInstance(): NetworkHandler {
        createInstanceCount.incrementAndGet()
        return this.createInstance0()
    }
}

internal class AbstractKeepAliveNetworkHandlerSelectorTest {

    private fun createHandler() = TestNetworkHandler(TestNetworkHandlerContext())

    @Test
    fun `can initialize instance`() {
        val selector = TestSelector { createHandler() }
        runBlockingUnit(timeout = 3.seconds) { selector.awaitResumeInstance() }
        assertNotNull(selector.getResumedInstance())
    }

    @Test
    fun `no redundant initialization`() {
        val selector = TestSelector {
            fail("initialize called")
        }
        val handler = createHandler()
        selector.setCurrent(handler)
        assertSame(handler, selector.getResumedInstance())
    }

    @Test
    fun `initialize another when closed`() {
        val selector = TestSelector {
            createHandler()
        }
        val handler = createHandler()
        selector.setCurrent(handler)
        assertSame(handler, selector.getResumedInstance())
        handler.setState(State.CLOSED)
        runBlockingUnit(timeout = 3.seconds) { selector.awaitResumeInstance() }
        assertEquals(1, selector.createInstanceCount.get())
    }
}