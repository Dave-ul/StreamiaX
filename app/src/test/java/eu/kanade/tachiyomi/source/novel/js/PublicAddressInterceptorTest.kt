package eu.kanade.tachiyomi.source.novel.js

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
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

    @Test
    fun `inside ip literals are spotted in every numeric form, host names are left to dns`() {
        val inside = listOf(
            "http://127.0.0.1:8090/", "http://[::1]/", "http://2130706433/", "http://192.168.1.1/",
            "http://10.0.0.1.5/",
        )
        val outside = listOf("https://8.8.8.8/", "https://[2606:4700::1111]/", "https://localhost.example/")

        assertEquals(inside, inside.filter { it.toHttpUrl().isInsideLiteral() })
        assertEquals(emptyList<String>(), outside.filter { it.toHttpUrl().isInsideLiteral() })
    }

    @Test
    fun `dns answers lose their inside addresses`() {
        val local = InetAddress.getByName("192.168.1.1")
        val public = InetAddress.getByName("8.8.8.8")
        val dns = publicDns { host -> if (host == "mixed.example") listOf(local, public) else listOf(local) }

        assertEquals(listOf(public), dns.lookup("mixed.example"))
        assertThrows<IOException> { dns.lookup("rebound.example") }
    }

    @Test
    fun `an inside ip literal is refused before connecting`() {
        val chain = chain("http://127.0.0.1:8090/echo")

        val error = assertThrows<IOException> { PublicAddressInterceptor().intercept(chain) }
        verify(exactly = 0) { chain.proceed(any()) }
        assertFalse(error.message.orEmpty().contains("127.0.0.1"))
    }

    @Test
    fun `a redirect to an inside ip literal is refused before it is followed`() {
        val chain = chain("https://novels.example/a")
        every { chain.proceed(any()) } answers {
            response(firstArg(), 302, "http://192.168.1.1/admin")
        }

        assertThrows<IOException> { PublicAddressInterceptor().intercept(chain) }
    }

    @Test
    fun `public requests and redirects go through`() {
        val chain = chain("https://novels.example/a")
        every { chain.proceed(any()) } answers { response(firstArg(), 302, "/b") }

        assertTrue(PublicAddressInterceptor().intercept(chain).isRedirect)
    }

    private fun chain(url: String): Interceptor.Chain {
        val sent = Request.Builder().url(url).build()
        return mockk {
            every { request() } returns sent
            every { connection() } returns null
            every { proceed(any()) } answers { response(firstArg(), 200, null) }
        }
    }

    private fun response(request: Request, code: Int, location: String?) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .apply { if (location != null) header("Location", location) }
        .build()

    private fun publicDns(answer: (String) -> List<InetAddress>) = PublicDns(
        object : Dns {
            override fun lookup(hostname: String) = answer(hostname)
        },
    )
}
