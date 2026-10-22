package com.dayoudu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudEndpointTest {
    @Test fun explicitHttpsOverridesDebugRelayOnPhoneAndEmulator() {
        for (debug in listOf(true,false)) for (emulator in listOf(true,false)) {
            assertEquals("https://api.example.com",cloudEndpoint("https://api.example.com/",debug,emulator))
        }
    }
    @Test fun unconfiguredDebugKeepsExistingDevelopmentConnection() {
        assertEquals("http://127.0.0.1:18080",cloudEndpoint("",true,false))
        assertEquals("http://10.0.2.2:18080",cloudEndpoint("",true,true))
        assertThrows(IllegalArgumentException::class.java) {cloudEndpoint("",false,false)}
    }
    @Test fun publicIpHttpsIsUsedDirectlyWithoutLocalhostOrUsb() {
        assertEquals("https://43.142.121.249",cloudEndpoint("https://43.142.121.249",true,false))
        assertEquals("https://43.142.121.249",cloudEndpoint("https://43.142.121.249",false,false))
    }
    @Test fun configuredAddressCannotDowngradeOrIncludeCredentialsOrPaths() {
        for (origin in listOf("http://api.example.com","http://127.0.0.1:18080","https://user:password@api.example.com",
            "https://api.example.com/api","https://api.example.com?token=secret","https://api.example.com#part",
            "https://api.example.com:70000","https://api.example.com:0")) {
            assertThrows(origin,IllegalArgumentException::class.java) {cloudEndpoint(origin,true,false)}
        }
    }
}
