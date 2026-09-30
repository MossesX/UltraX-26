package com.ultrax26.recorder

import com.ultrax26.recorder.calls.MeetLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeetLauncherTest {
    @Test fun `meeting codes normalize to canonical links`() {
        assertEquals("https://meet.google.com/abc-defg-hij", MeetLauncher.normalizeMeetingLink("abc-defg-hij"))
        assertEquals("https://meet.google.com/abc-defg-hij", MeetLauncher.normalizeMeetingLink("  ABCDEFGHIJ "))
        assertEquals("https://meet.google.com/abc-defg-hij", MeetLauncher.normalizeMeetingLink("abcdefg-hij"))
    }

    @Test fun `meeting links keep the path and drop query and fragment`() {
        assertEquals("https://meet.google.com/abc-defg-hij", MeetLauncher.normalizeMeetingLink("https://meet.google.com/abc-defg-hij?authuser=0&hs=122#x"))
        assertEquals("https://meet.google.com/abc-defg-hij", MeetLauncher.normalizeMeetingLink("meet.google.com/abc-defg-hij"))
        assertEquals("https://meet.google.com/lookup/team-standup", MeetLauncher.normalizeMeetingLink("https://meet.google.com/lookup/team-standup"))
        assertEquals("https://meet.google.com/abc-defg-hij", MeetLauncher.normalizeMeetingLink("Join: <https://meet.google.com/abc-defg-hij>"))
        assertEquals("https://g.co/meet/standup", MeetLauncher.normalizeMeetingLink("g.co/meet/standup"))
    }

    @Test fun `nicknames open through lookup and junk is rejected`() {
        assertEquals("https://meet.google.com/lookup/standup", MeetLauncher.normalizeMeetingLink("Standup"))
        assertNull(MeetLauncher.normalizeMeetingLink(""))
        assertNull(MeetLauncher.normalizeMeetingLink("https://meet.google.com/"))
        assertNull(MeetLauncher.normalizeMeetingLink("https://meet.google.com/landing"))
        assertNull(MeetLauncher.normalizeMeetingLink("https://zoom.us/j/123"))
        assertNull(MeetLauncher.normalizeMeetingLink("hello world"))
    }

    @Test fun `phone numbers keep the plus and digits only`() {
        assertEquals("+15551234567", MeetLauncher.normalizeNumber(" +1 (555) 123-4567 "))
        assertEquals("5551234567", MeetLauncher.normalizeNumber("555.123.4567"))
        assertNull(MeetLauncher.normalizeNumber("+1"))
        assertNull(MeetLauncher.normalizeNumber("call me"))
    }
}
