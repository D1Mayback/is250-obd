package com.d1mayback.is250obd;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Read-only client for the FYT head unit's com.syu.ms "toolkit" service, which already decodes
 * the CAN box. We only fetch modules and register/unregister callbacks. We never call the
 * module's cmd() transaction (code 1), so nothing is ever sent to the car.
 *
 * Interface layout (com.syu.ipc.*), as documented by other FYT projects:
 *   IRemoteToolkit  txn 1: getRemoteModule(int module) -> IBinder
 *   IRemoteModule   txn 3: register(IModuleCallback cb, int field, int notifyNow)
 *                   txn 4: unregister(IModuleCallback cb, int field)
 *   IModuleCallback txn 1: update(int field, int[] ints, float[] flts, String[] strs)
 */
public class FytCan {

    public static final String TOOLKIT = "com.syu.ipc.IRemoteToolkit";
    public static final String MODULE = "com.syu.ipc.IRemoteModule";
    public static final String CALLBACK = "com.syu.ipc.IModuleCallback";

    /** Modules to watch: 0 = main/system, 7 = canbus. */
    public static final int[] MODULES = {0, 7};
    /** Field ids to subscribe to in each module. 1000 on the canbus module is the decoder profile. */
    public static final int MAX_FIELD = 300;
    public static final int PROFILE_FIELD = 1000;

    public interface Listener {
        void onStatus(String text);
        void onUpdate(int module, int field, int[] ints, float[] flts, String[] strs);
    }

    private final Context ctx;
    private final Listener listener;
    private final IBinder[] modules = new IBinder[MODULES.length];
    private final IBinder[] callbacks = new IBinder[MODULES.length];
    private ServiceConnection conn;
    private volatile boolean closed;

    public FytCan(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        for (int i = 0; i < MODULES.length; i++) callbacks[i] = makeCallback(MODULES[i]);
    }

    public void connect() {
        Intent intent = new Intent("com.syu.ms.toolkit").setPackage("com.syu.ms");
        ComponentName target = new ComponentName("com.syu.ms", "app.ToolkitService");
        try {
            ResolveInfo ri = ctx.getPackageManager().resolveService(intent, 0);
            if (ri != null && ri.serviceInfo != null && "com.syu.ms".equals(ri.serviceInfo.packageName)) {
                target = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
            }
        } catch (RuntimeException ignored) { }
        intent.setComponent(target);
        listener.onStatus("Binding " + target.flattenToShortString() + " …");

        conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder toolkit) {
                new Thread(() -> subscribeAll(toolkit), "fyt-subscribe").start();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                listener.onStatus("Service disconnected (com.syu.ms restarted?) — tap Reconnect");
            }
            @Override public void onNullBinding(ComponentName name) {
                listener.onStatus("com.syu.ms refused the connection (null binding)");
            }
        };
        try {
            boolean ok = ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE);
            if (!ok) listener.onStatus("Could not bind to com.syu.ms toolkit service");
        } catch (RuntimeException e) {
            listener.onStatus("Bind denied: " + e.getMessage());
        }
    }

    private void subscribeAll(IBinder toolkit) {
        StringBuilder st = new StringBuilder("Connected. ");
        for (int i = 0; i < MODULES.length && !closed; i++) {
            int m = MODULES[i];
            try {
                IBinder mod = getModule(toolkit, m);
                if (mod == null) { st.append("M").append(m).append(": none. "); continue; }
                modules[i] = mod;
                int ok = 0, fail = 0;
                for (int f = 0; f <= MAX_FIELD && !closed; f++) {
                    try { register(mod, callbacks[i], f, true); ok++; } catch (Exception e) { fail++; }
                }
                if (m == 7) {
                    try { register(mod, callbacks[i], PROFILE_FIELD, true); ok++; } catch (Exception e) { fail++; }
                }
                st.append("M").append(m).append(": ").append(ok).append(" fields");
                if (fail > 0) st.append(" (").append(fail).append(" failed)");
                st.append(". ");
            } catch (Exception e) {
                st.append("M").append(m).append(": error ").append(e.getMessage()).append(". ");
            }
            listener.onStatus(st.toString() + (i < MODULES.length - 1 ? "…" : ""));
        }
        listener.onStatus(st.toString());
    }

    public void close() {
        closed = true;
        new Thread(() -> {
            for (int i = 0; i < MODULES.length; i++) {
                IBinder mod = modules[i];
                if (mod == null) continue;
                for (int f = 0; f <= MAX_FIELD; f++) {
                    try { register(mod, callbacks[i], f, false); } catch (Exception ignored) { }
                }
                try { register(mod, callbacks[i], PROFILE_FIELD, false); } catch (Exception ignored) { }
            }
        }, "fyt-unsubscribe").start();
        if (conn != null) {
            try { ctx.unbindService(conn); } catch (RuntimeException ignored) { }
            conn = null;
        }
    }

    // ------------------------------------------------------------------ binder plumbing

    private IBinder makeCallback(final int module) {
        return new Binder() {
            { attachInterface(null, CALLBACK); }

            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(CALLBACK);
                    return true;
                }
                if (code != 1) return super.onTransact(code, data, reply, flags);
                try {
                    data.enforceInterface(CALLBACK);
                    int field = data.readInt();
                    int[] ints = data.dataAvail() >= 4 ? data.createIntArray() : null;
                    float[] flts = data.dataAvail() >= 4 ? data.createFloatArray() : null;
                    String[] strs = data.dataAvail() >= 4 ? data.createStringArray() : null;
                    if (!closed) listener.onUpdate(module, field, ints, flts, strs);
                } catch (RuntimeException ignored) {
                    // Malformed/unknown layout: ignore rather than crash the binder thread.
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
        };
    }

    private static IBinder getModule(IBinder toolkit, int module) throws RemoteException {
        checkDescriptor(toolkit, TOOLKIT);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOOLKIT);
            data.writeInt(module);
            if (!toolkit.transact(1, data, reply, 0)) throw new RemoteException("toolkit txn 1 unsupported");
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void register(IBinder module, IBinder cb, int field, boolean add) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MODULE);
            data.writeStrongBinder(cb);
            data.writeInt(field);
            if (add) data.writeInt(1); // 1 = send the current value right away
            if (!module.transact(add ? 3 : 4, data, reply, 0)) throw new RemoteException("module txn unsupported");
            // Some com.syu.ms builds reply with an empty parcel for register/unregister.
            if (reply.dataSize() > 0 && reply.dataAvail() >= 4) reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void checkDescriptor(IBinder b, String expected) throws RemoteException {
        String d = b.getInterfaceDescriptor();
        if (!expected.equals(d)) throw new RemoteException("unexpected interface " + d);
    }
}
