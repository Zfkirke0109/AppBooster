/** Live, shell-only configuration for the disposable API 37 CI emulator. */
public final class DisableCiSnapshots {
    private DisableCiSnapshots() {}

    public static void main(String[] args) throws Exception {
        int sdk = Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null);
        String hardware = (String) Class.forName("android.os.Build").getField("HARDWARE").get(null);
        int uid = (Integer) Class.forName("android.os.Process").getMethod("myUid").invoke(null);
        if (args.length != 0 || sdk != 37 || uid != 2000 ||
                !("ranchu".equals(hardware) || "goldfish".equals(hardware))) {
            throw new IllegalStateException("This helper requires the API 37 CI emulator shell.");
        }
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "window");
        if (binder == null) throw new IllegalStateException("WindowManager is unavailable.");
        Object window = Class.forName("android.view.IWindowManager$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        Class.forName("android.view.IWindowManager")
                .getMethod("setTaskSnapshotEnabled", boolean.class).invoke(window, false);
        System.out.println("WindowManager accepted setTaskSnapshotEnabled(false) for CI.");
    }
}
