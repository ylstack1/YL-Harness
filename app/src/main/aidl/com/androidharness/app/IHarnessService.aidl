package com.androidharness.app;

/**
 * Runs inside the Shizuku server process with ADB-shell (or root) privileges.
 * The Shizuku server instantiates {@link com.androidharness.app.data.env.HarnessUserService}
 * reflectively, so the app needs only the IHarnessService class + a bind via
 * Shizuku.bindUserService — no <service> entry in the manifest.
 */
interface IHarnessService {

    // Reserved method + transaction id: the Shizuku server calls this when it
    // wants to stop running this user service.
    void destroy() = 16777114;

    // Tells the in-server service to exit its process (it will be restarted
    // on the next bind).
    void exit() = 1;

    /**
     * Executes a command as the Shizuku server process uid (shell 2000, or
     * root when started via Sui/root).
     *
     * @param cmd argv, e.g. ["sh", "-c", "ls -l /sdcard"]
     * @param env "KEY=VALUE" entries, may be null
     * @param dir working directory, may be null
     * @param maxBytes cap on the combined stdout+stderr returned (bounded well
     *                 below the binder transaction limit)
     * @param timeoutMs hard kill timeout in milliseconds
     * @return "exit=N\ntimeout=0|1\n<combined output>" (N=-1 when launch failed)
     */
    String exec(in String[] cmd, in String[] env, in String dir, int maxBytes, int timeoutMs) = 2;

    /**
     * Starts a command as a detached child of the Shizuku server process,
     * output appended to logPath. Because it lives in the server process it
     * survives the app being killed. Returns the pid, or -1 on failure.
     */
    int spawnDetached(in String[] cmd, in String[] env, in String dir, in String logPath) = 3;

    /** kill(pid, 0)-style liveness probe. */
    boolean isProcessAlive(int pid) = 4;

    /** SIGKILL a process by pid. */
    boolean killProcess(int pid) = 5;

    /** Interrupt only commands started by this service. */
    int cancelActive() = 6;
    int activeCommandCount() = 7;
    int getUid() = 8;
    String rootList(in String path, int offset) = 9;
    String rootRead(in String path) = 10;
    String rootWrite(in String path, in String expectedHash, in String text) = 11;
    void resumeCommands() = 12;
    /** Stop a recovered job only after verifying it still owns its unique log. */
    boolean killTrackedProcess(int pid, in String logPath) = 13;
    /** Reserve before dispatch so cancellation can also prevent a queued command from starting. */
    String registerCommand() = 14;
    String execRegistered(in String id, in String[] cmd, in String[] env, in String dir, int maxBytes, int timeoutMs) = 15;
    /** Cancels only this reservation/command; other chats and detached jobs keep running. */
    void cancelCommand(in String id) = 16;
}
