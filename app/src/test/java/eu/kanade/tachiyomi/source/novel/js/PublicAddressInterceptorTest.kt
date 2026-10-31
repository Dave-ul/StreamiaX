package eu.kanade.tachiyomi.source.novel.js

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress

class PublicAddressInterceptorTest {

    @Test
    fun `local and private addresses are refused, public ones allowed`() {
        val refused = listOf(
            "127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1",
            "100.64.0.1", "100.127.255.254", "224.0.0.1", "::1", "::", "fe80::1", "fd00::1", "fc00::1",
            "::ffff:127.0.0.1",
        )
        val allowed = listOf("8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "2606:4700::1111")

        // Literals only: getByName does no DNS lookup for them
        assertEquals(emptyList<String>(), refused.filter { InetAddress.getByName(it).isPublic() })
        assertEquals(allowed, allowed.filter { InetAddress.getByName(it).isPublic() })
    }
}
