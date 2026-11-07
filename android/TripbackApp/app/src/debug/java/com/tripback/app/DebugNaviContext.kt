package com.tripback.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder

/** Android binding observer, not a fabricated NaviHelper API. Fail closed after disconnect.
 * Local 3.0.5 BaseServiceConnector uses the three-argument bindService overload.
 * Keeps the SDK from silently reconnecting and replaying cached operations in this trial.
 */
internal class DebugNaviContext(base: Context, private val main: Handler, private val lost: () -> Unit) : ContextWrapper(base) {
    private val bindings = mutableMapOf<ServiceConnection, ServiceConnection>()
    @Volatile private var binder: IBinder? = null
    @Volatile private var stopped = false
    val live: Boolean get() = !stopped && binder?.isBinderAlive == true && binder?.pingBinder() == true
    private val death = IBinder.DeathRecipient { failClosed() }
    override fun getApplicationContext(): Context = this
    private fun failClosed() {
        stopped = true
        binder = null
        main.post(lost)
    }
    @Synchronized override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        if (stopped) return false
        val wrapped = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (stopped) return
                try { service.linkToDeath(death, 0) } catch (_: Exception) { failClosed(); return }
                binder = service
                connection.onServiceConnected(name, service)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                failClosed()
                connection.onServiceDisconnected(name)
            }
            override fun onBindingDied(name: ComponentName) {
                // Do not forward onBindingDied: SDK reconnects there; release on main instead.
                failClosed()
            }
            override fun onNullBinding(name: ComponentName) {
                failClosed()
                connection.onNullBinding(name)
            }
        }
        bindings[connection] = wrapped
        return super.bindService(intent, wrapped, flags)
    }
    @Synchronized override fun unbindService(connection: ServiceConnection) {
        binder?.let { try { it.unlinkToDeath(death, 0) } catch (_: Exception) { } }
        binder = null
        val wrapped = bindings.remove(connection) ?: return
        super.unbindService(wrapped)
    }
}
