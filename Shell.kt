package app.soundpad

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

object Shell {
    @Volatile
    var injector: IInjector? = null
    private var binding = false
    private val pending = ArrayList<(IInjector) -> Unit>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val i = IInjector.Stub.asInterface(binder)
            injector = i
            binding = false
            val list = synchronized(pending) {
                ArrayList(pending).also { pending.clear() }
            }
            list.forEach { it(i) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            injector = null
            binding = false
            Engine.injectorLost()
        }
    }

    fun alive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun granted(): Boolean = try {
        alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun request(code: Int) {
        try {
            Shizuku.requestPermission(code)
        } catch (e: Throwable) {
            Engine.note = "Shizuku: ${e.message}"
        }
    }

    fun withService(ctx: Context, cb: (IInjector) -> Unit) {
        injector?.let {
            cb(it)
            return
        }
        synchronized(pending) { pending.add(cb) }
        if (binding) return
        binding = true
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, Injector::class.java.name))
                .daemon(false)
                .processNameSuffix("injector")
                .version(1)
            Shizuku.bindUserService(args, conn)
        } catch (e: Throwable) {
            binding = false
            Engine.note = "bindUserService: ${e.message}"
        }
    }
}
