package npdf.app

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The app cannot reach any network: it does not ask for the permission, and
 * Android therefore refuses it a socket and a name lookup. Runs in the app's
 * own process, as the installed app does.
 */
@RunWith(AndroidJUnit4::class)
class NoNetworkTest {

    @Test
    fun noNetworkPermissionIsRequested() {
        val ctx = TestKit.ctx
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val requested = pi.requestedPermissions?.toList() ?: emptyList()
        Log.i("NPDF-NET", "requested permissions: $requested")
        for (p in listOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_NETWORK_STATE, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA)) {
            assertFalse("$p must not be requested", requested.contains(p))
        }
        assertEquals(PackageManager.PERMISSION_DENIED, ctx.checkSelfPermission(Manifest.permission.INTERNET))
    }

    @Test
    fun androidRefusesSocketsAndLookups() {
        val sock = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 80), 3000) } }.exceptionOrNull()
        assertNotNull("a socket connection must be refused", sock)
        val msg = sock.toString()
        Log.i("NPDF-NET", "socket attempt: $msg")
        assertTrue("refused by Android, not by the network: $msg", sock is SecurityException || msg.contains("EACCES") || msg.contains("Permission denied"))
        val dns = runCatching { InetAddress.getByName("example.com") }.exceptionOrNull()
        assertNotNull("a name lookup must fail", dns)
        Log.i("NPDF-NET", "lookup attempt: $dns")
    }

    @Test
    fun backupAndCleartextAreOff() {
        val ai = TestKit.ctx.applicationInfo
        assertEquals("cloud backup off", 0, ai.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertFalse("no plain-text traffic", android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
    }
}
