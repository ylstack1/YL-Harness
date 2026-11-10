package com.androidharness.app.data.env

import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class TemporaryRootAllowanceTest {
    @Test fun rootStartsDisabledAndAnAppSessionDoesNotCarryToANewManager() {
        val grant = TemporaryRootAllowance()
        assertFalse(grant.allows("chat", RootLease()))
        grant.grant(RootUse.SESSION, null)
        assertTrue(grant.claim("chat", RootLease()))
        assertFalse(TemporaryRootAllowance().allows("chat", RootLease()))
    }
    @Test fun chatScopeDoesNotLeakAcrossChats() {
        val grant = TemporaryRootAllowance()
        grant.grant(RootUse.CHAT, "a")
        assertTrue(grant.claim("a", RootLease()))
        assertFalse(grant.claim("b", RootLease()))
        assertFalse(grant.claim(null, RootLease()))
    }
    @Test(expected = IllegalArgumentException::class) fun chatScopeNeedsAChat() {
        TemporaryRootAllowance().grant(RootUse.CHAT, null)
    }
    @Test fun nextActionAllowsInternalCallsButNotTheNextTool() {
        val grant = TemporaryRootAllowance()
        grant.grant(RootUse.NEXT_ACTION, null)
        val tool = RootLease()
        assertTrue(grant.claim("a", tool))
        assertTrue(grant.claim("a", tool))
        assertFalse(grant.claim("a", RootLease()))
    }
    @Test fun revokeInvalidatesAnAlreadyClaimedAction() {
        val grant = TemporaryRootAllowance()
        grant.grant(RootUse.SESSION, null)
        val tool = RootLease()
        assertTrue(grant.claim("a", tool))
        grant.revoke()
        assertFalse(grant.claim("a", tool))
        grant.grant(RootUse.NEXT_ACTION, null)
        assertTrue(grant.claim("a", RootLease()))
        assertFalse(grant.claim("a", tool))
    }
    @Test fun uiActivityTracksTheScopeWithoutConsumingAnAllowance() {
        val grant = TemporaryRootAllowance()
        assertFalse(grant.activeFor("a"))
        grant.grant(RootUse.SESSION, null)
        assertTrue(grant.activeFor("a")); assertTrue(grant.activeFor(null))
        grant.grant(RootUse.CHAT, "a")
        assertTrue(grant.activeFor("a")); assertFalse(grant.activeFor("b")); assertFalse(grant.activeFor(null))
        grant.grant(RootUse.NEXT_ACTION, null)
        assertTrue(grant.activeFor("a"))
        assertTrue(grant.claim("a", RootLease()))
        assertFalse(grant.activeFor("a"))
    }
    @Test fun concurrentToolsCannotBothConsumeNextAction() {
        val grant = TemporaryRootAllowance()
        grant.grant(RootUse.NEXT_ACTION, null)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((1..32).map { java.util.concurrent.Callable { grant.claim("chat", RootLease()) } })
            assertEquals(1, results.count { it.get() })
        } finally { pool.shutdownNow() }
    }
}
