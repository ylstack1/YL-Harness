package com.androidharness.app.data.env

import com.androidharness.app.IHarnessService
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CancellableServiceCommandTest {
    private class Runner {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicInteger()
        val globalStops = AtomicInteger()
        val commands = ConcurrentHashMap<String, Thread>()
        private val ids = AtomicInteger()
        val service = Proxy.newProxyInstance(IHarnessService::class.java.classLoader,
            arrayOf(IHarnessService::class.java)) { _, method, args ->
            when (method.name) {
                "registerCommand" -> ids.incrementAndGet().toString()
                "execRegistered" -> {
                    val id = args!![0] as String
                    commands[id] = Thread.currentThread()
                    started.countDown()
                    try { release.await(10, TimeUnit.SECONDS); "exit=0\ntimeout=0\nok" }
                    catch (_: InterruptedException) { "exit=-130\ntimeout=0\ncancelled" }
                    finally { commands.remove(id); Thread.interrupted() }
                }
                "cancelCommand" -> { cancelled.incrementAndGet(); commands[args!![0]]?.interrupt(); null }
                "cancelActive" -> { globalStops.incrementAndGet(); 0 }
                else -> error("Unexpected service call: ${method.name}")
            }
        } as IHarnessService
    }

    @Test(timeout = 10_000) fun `cancellation interrupts a blocking service call without stopping other commands`() = runBlocking {
        val runner = Runner()
        val job = launch(Dispatchers.Default) {
            runner.service.execCancellable(arrayOf("sh"), null, null, 1024, 60_000)
        }
        try {
            assertTrue(runner.started.await(3, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(runner.commands.isEmpty())
            assertTrue(runner.cancelled.get() > 0)
            assertEquals(0, runner.globalStops.get())
        } finally { runner.release.countDown(); job.cancelAndJoin() }
    }

    @Test(timeout = 10_000) fun `successful service calls keep their result and release the reservation`() = runBlocking {
        val runner = Runner()
        runner.release.countDown()
        assertEquals("exit=0\ntimeout=0\nok", runner.service.execCancellable(arrayOf("sh"), null, null, 1024, 60_000))
        assertTrue(runner.commands.isEmpty())
        assertTrue(runner.cancelled.get() > 0)
        assertEquals(0, runner.globalStops.get())
    }
}
