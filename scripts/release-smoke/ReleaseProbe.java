import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs against the actual APK, without adding test entry points or keep rules to it. */
public final class ReleaseProbe {
    private static final String LOCAL = "com.androidharness.app.local.LocalNative";

    public static void main(String[] args) throws Exception {
        try {
            runChecks(args);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void runChecks(String[] args) throws Exception {
        for (String state : new String[] {"NotInstalled", "Preparing", "Downloading", "Installing", "Ready", "Failed"}) {
            Class<?> type = Class.forName("com.androidharness.app.data.env.EnvState$" + state);
            if (!type.getSimpleName().equals(state))
                throw new AssertionError("Environment label changed: " + state + " -> " + type.getSimpleName());
        }
        System.out.println("ENVIRONMENT_LABELS_OK");
        if (args.length > 1 && !args[1].isEmpty()) {
            Class<?> local = Class.forName(LOCAL);
            if (!(Boolean) local.getMethod("loadBackend", String.class).invoke(local.getField("INSTANCE").get(null), args[1]))
                throw new AssertionError("Downloaded GPU backend did not load");
            System.out.println("DOWNLOADED_GPU_BACKEND_OK");
        }
        checkPairing();
        checkSshKeys();
        checkNative(args[0], false);
        Class<?> local = Class.forName(LOCAL);
        String device = (String) local.getMethod("gpuDevice").invoke(local.getField("INSTANCE").get(null));
        if (device.trim().isEmpty()) {
            System.out.println("GPU_UNAVAILABLE: no Vulkan device on this test surface");
        } else {
            System.out.println("GPU_DEVICE: " + device);
            checkNative(args[0], true);
            checkNative(args[0], false);
        }
        System.out.println("RELEASE_PROBE_OK");
        System.out.flush();
        // Match Android's process lifecycle. This standalone app_process VM
        // must not run native backend shutdown hooks after unloading ART state.
        int pid = (Integer) Class.forName("android.os.Process").getMethod("myPid").invoke(null);
        Class.forName("android.system.Os").getMethod("kill", int.class, int.class).invoke(null, pid, 9);
    }

    private static void checkPairing() throws Exception {
        System.loadLibrary("harness_adb");
        Class<?> type = Class.forName("com.androidharness.app.data.env.adb.PairingContext");
        Method create = type.getDeclaredMethod("nativeConstructor", boolean.class, byte[].class);
        create.setAccessible(true);
        long pointer = (Long) create.invoke(null, true,
            "release-pairing-fixture".getBytes(StandardCharsets.UTF_8));
        if (pointer == 0) throw new AssertionError("SPAKE2 constructor failed");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        Object pairing = constructor.newInstance(pointer);
        try {
            byte[] message = (byte[]) type.getMethod("getMsg").invoke(pairing);
            if (message.length < 32) throw new AssertionError("Invalid SPAKE2 public message");
            System.out.println("PAIRING_NATIVE_OK bytes=" + message.length);
        } finally {
            type.getMethod("destroy").invoke(pairing);
        }
    }

    private static void checkSshKeys() throws Exception {
        Class<?> jsch = Class.forName("com.jcraft.jsch.JSch");
        Class<?> pair = Class.forName("com.jcraft.jsch.KeyPair");
        for (String algorithm : new String[] {"RSA", "ECDSA", "ED25519"}) {
            Object key = pair.getMethod("genKeyPair", jsch, int.class, int.class)
                .invoke(null, jsch.getConstructor().newInstance(), pair.getField(algorithm).getInt(null),
                    algorithm.equals("RSA") ? 2048 : 256);
            try {
                byte[] publicKey = (byte[]) pair.getMethod("getPublicKeyBlob").invoke(key);
                if (publicKey == null || publicKey.length == 0) throw new AssertionError("Missing SSH key");
                if (((String) pair.getMethod("getFingerPrint").invoke(key)).trim().isEmpty())
                    throw new AssertionError("Missing SSH fingerprint");
                System.out.println("SSH_KEY_OK algorithm=" + algorithm);
            } finally {
                pair.getMethod("dispose").invoke(key);
            }
        }
    }

    private static void checkNative(String modelPath, boolean gpu) throws Exception {
        Class<?> local = Class.forName(LOCAL);
        Class<?> callback = Class.forName(LOCAL + "$ChatCallback");
        Object instance = local.getField("INSTANCE").get(null);
        long handle = (Long) local.getMethod("create").invoke(instance);
        AtomicInteger events = new AtomicInteger();
        Object receiver = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[] {callback},
            (proxy, method, values) -> {
                if (method.getName().equals("onEvent")) { events.incrementAndGet(); return true; }
                if (method.getName().equals("onWarning")) return false;
                if (method.getName().equals("toString")) return "ReleaseProbeCallback";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == values[0];
                throw new AssertionError("Unknown callback " + method);
            });
        String request = "{\"messages\":[{\"role\":\"user\",\"content\":\"Tell a short story.\"}],\"tools\":[],\"enable_thinking\":false}";
        Method generate = local.getMethod("generateChat", long.class, byte[].class, byte[].class,
            byte[].class, byte[][].class, int.class, int.class, int.class, int.class, boolean.class, callback);
        long started = System.nanoTime();
        try {
            byte[] result = (byte[]) generate.invoke(instance, handle, modelPath.getBytes(StandardCharsets.UTF_8),
                new byte[0], request.getBytes(StandardCharsets.UTF_8), new byte[0][], 256, 192, 16, 2, gpu, receiver);
            String text = new String(result, StandardCharsets.UTF_8);
            if (!text.contains("\"backend\":\"" + (gpu ? "GPU" : "CPU") + "\""))
                throw new AssertionError("Unexpected backend: " + text);
            if (events.get() == 0) throw new AssertionError("Native callbacks were not delivered");
            System.out.println("LOCAL_NATIVE_OK backend=" + (gpu ? "GPU" : "CPU")
                + " events=" + events.get() + " ms=" + (System.nanoTime() - started) / 1_000_000);
        } finally {
            local.getMethod("destroy", long.class).invoke(instance, handle);
        }
    }
}
