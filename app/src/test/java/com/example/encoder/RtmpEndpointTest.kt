package com.example.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpEndpointTest {
    private fun ok(server: String, key: String) =
        (RtmpEndpoint.build(server, key) as RtmpEndpoint.Result.Ok).endpoint

    @Test fun joinsWithSingleSlash() {
        assertEquals("rtmp://a.example/live/KEY", ok("rtmp://a.example/live", "KEY"))
        assertEquals("rtmp://a.example/live/KEY", ok("rtmp://a.example/live/", "KEY"))
    }

    @Test fun keyIsNotModified() {
        val key = "stream-123?expire=1&sign=a%2Fb%3D"
        assertEquals("rtmps://h/app/$key", ok("rtmps://h/app", key))
    }

    @Test fun trimsPastedWhitespaceOnly() {
        assertEquals("rtmp://h/app/KEY", ok("  rtmp://h/app \n", " KEY\n"))
    }

    @Test fun rejectsBadInput() {
        assertTrue(RtmpEndpoint.build("http://h/app", "K") is RtmpEndpoint.Result.Invalid)
        assertTrue(RtmpEndpoint.build("rtmp://", "K") is RtmpEndpoint.Result.Invalid)
        assertTrue(RtmpEndpoint.build("rtmp://h/app", "") is RtmpEndpoint.Result.Invalid)
        assertTrue(RtmpEndpoint.build("rtmp://h/app", "a b") is RtmpEndpoint.Result.Invalid)
        assertTrue(RtmpEndpoint.build("rtmp://h/app", "live_stream_key_sample") is RtmpEndpoint.Result.Invalid)
    }
}
