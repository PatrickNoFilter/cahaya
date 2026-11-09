package com.patrisius.cahaya.ui;

/**
 * v1.33.0: ytdlnis runtime for the bundled yt-dlp. Mirrors
 * deniscerri/ytdlnis (GPL-3.0; Cahaya is personal-use only): a
 * native CPython interpreter + the yt-dlp script, run as a
 * subprocess — no Chaquopy, no native yt-dlp binary.
 *
 * Layout (first launch, once):
 *   <nativeLibraryDir>/libpython.so          the interpreter (APK native lib, uncompressed)
 *   <nativeLibraryDir>/libpython.zip.so      the Python stdlib zip (APK native lib, uncompressed)
 *   noBackupFilesDir/ytdlnis/packages/python/   unzipped stdlib (PYTHONHOME = .../usr)
 *   noBackupFilesDir/ytdlnis/yt-dlp/yt-dlp      the yt-dlp script (from assets)
 *
 * The stdlib zip contains Unix symlinks (libz.so.1 -> libz.so.1.3.2).
 * Java's ZipInputStream writes them as plain files holding the
 * target name, which breaks the interpreter (zlib not available).
 * After extraction, fixUpSymlinks() replaces such placeholder files
 * with a copy of their target — same effect as the symlink for
 * loading .so files. Re-extracts only when the zip size changes
 * (stored in SharedPreferences, like YTDLnis's PackageBase).
 */
public class YtDlpRuntime {
    private static final String BASENAME = "ytdlnis";
    private static final String PY_DIR = "packages/python";
    private static final String YTDLP_DIR = "yt-dlp";
    private static final String YTDLP_BIN = "yt-dlp";
    private static final String SIZE_KEY = "ytdl_py_size";
    private static final String ASSET_NAME = "yt-dlp";

    private static volatile boolean ready = false;
    private static final Object LOCK = new Object();

    public static boolean isReady() {
        return ready;
    }

    /** Idempotent first-launch setup. Call on a worker thread. */
    public static void ensureReady(android.content.Context ctx) {
        if (ready) return;
        synchronized (LOCK) {
            if (ready) return;
            try {
                android.content.Context app = ctx.getApplicationContext();
                java.io.File base = new java.io.File(app.getNoBackupFilesDir(), BASENAME);
                java.io.File pyDir = new java.io.File(base, PY_DIR);
                java.io.File zip = new java.io.File(
                        app.getApplicationInfo().nativeLibraryDir, "libpython.zip.so");
                android.content.SharedPreferences prefs =
                        app.getSharedPreferences("cahaya_tv", android.content.Context.MODE_PRIVATE);
                String wantSize = zip.exists() ? String.valueOf(zip.length()) : "";
                String haveSize = prefs.getString(SIZE_KEY, "");
                if (!pyDir.exists() || !wantSize.equals(haveSize)) {
                    TvLog.log("ytdl extract python zip size=" + wantSize);
                    deleteQuietly(pyDir);
                    pyDir.mkdirs();
                    if (zip.exists()) {
                        unzip(zip, pyDir);
                        fixUpSymlinks(pyDir);
                        prefs.edit().putString(SIZE_KEY, wantSize).apply();
                    } else {
                        TvLog.log("ytdl MISSING libpython.zip.so in nativeLibraryDir");
                    }
                }
                // yt-dlp script from assets.
                java.io.File ytdlpDir = new java.io.File(base, YTDLP_DIR);
                ytdlpDir.mkdirs();
                java.io.File script = new java.io.File(ytdlpDir, YTDLP_BIN);
                long assetSize = -1;
                try {
                    android.content.res.AssetFileDescriptor afd =
                            app.getAssets().openFd(ASSET_NAME);
                    assetSize = afd.getLength();
                    afd.close();
                } catch (Throwable ignored) {}
                if (!script.exists() || (assetSize >= 0 && script.length() != assetSize)) {
                    copyAsset(app, ASSET_NAME, script);
                    script.setExecutable(true);
                    TvLog.log("ytdl copied yt-dlp script size=" + script.length());
                }
                // The interpreter must be executable.
                java.io.File exe = pythonExe(app);
                if (exe.exists() && !exe.canExecute()) exe.setExecutable(true);
                ready = true;
                TvLog.log("ytdl runtime ready py=" + pyDir.getAbsolutePath());
            } catch (Throwable t) {
                TvLog.log("ytdl runtime setup FAILED: " + t);
                ready = false;
            }
        }
    }

    public static java.io.File pythonExe(android.content.Context ctx) {
        return new java.io.File(
                ctx.getApplicationContext().getApplicationInfo().nativeLibraryDir,
                "libpython.so");
    }

    public static java.io.File ytdlpScript(android.content.Context ctx) {
        return new java.io.File(
                new java.io.File(ctx.getApplicationContext().getNoBackupFilesDir(), BASENAME),
                YTDLP_DIR + "/" + YTDLP_BIN);
    }

    /** Environment for the yt-dlp subprocess (mirrors
     *  RuntimeManager.getEnvironment in YTDLnis). */
    public static java.util.Map<String, String> envMap(android.content.Context ctx) {
        android.content.Context app = ctx.getApplicationContext();
        java.io.File pyDir = new java.io.File(
                new java.io.File(app.getNoBackupFilesDir(), BASENAME), PY_DIR);
        java.io.File usr = new java.io.File(pyDir, "usr");
        java.io.File usrLib = new java.io.File(usr, "lib");
        String ld = usrLib.getAbsolutePath() + ":"
                + app.getApplicationInfo().nativeLibraryDir;
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("LD_LIBRARY_PATH", ld);
        env.put("PYTHONHOME", usr.getAbsolutePath());
        env.put("HOME", usr.getAbsolutePath());
        env.put("SSL_CERT_FILE", new java.io.File(usr, "etc/tls/cert.pem").getAbsolutePath());
        env.put("OPENSSL_CONF", "");
        env.put("TMPDIR", app.getCacheDir().getAbsolutePath());
        env.put("PATH", app.getApplicationInfo().nativeLibraryDir + ":/system/bin");
        return env;
    }

    // ------------------------------------------------------------------

    private static void unzip(java.io.File zip, java.io.File target) throws Exception {
        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(zip)));
        java.util.zip.ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zis.getNextEntry()) != null) {
            java.io.File out = new java.io.File(target, e.getName());
            if (e.isDirectory()) {
                out.mkdirs();
            } else {
                out.getParentFile().mkdirs();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                int n;
                while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
            }
            zis.closeEntry();
        }
        zis.close();
    }

    /** Replace symlink placeholder files with a copy of their target.
     *  A placeholder is a tiny file under usr/lib whose whole content
     *  is a filename that exists in the same directory (e.g. the 13
     *  bytes "libz.so.1.3.2" in libz.so.1). Copying is equivalent to
     *  the symlink for loading shared libraries. */
    private static void fixUpSymlinks(java.io.File root) {
        java.io.File lib = new java.io.File(new java.io.File(root, "usr"), "lib");
        if (!lib.isDirectory()) return;
        java.io.File[] files = lib.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (!f.isFile() || f.length() > 256) continue;
            try {
                byte[] b = readAll(f);
                String target = new String(b, "UTF-8").trim();
                if (target.isEmpty() || target.contains("\n") || target.contains("\0")) continue;
                if (!target.matches("[A-Za-z0-9._\\-+]+")) continue;
                java.io.File t = new java.io.File(f.getParentFile(), target);
                if (!t.isFile()) continue;
                byte[] tb = readAll(t);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
                fos.write(tb);
                fos.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] readAll(java.io.File f) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
        fis.close();
        return bos.toByteArray();
    }

    private static void copyAsset(android.content.Context app, String name,
                                  java.io.File out) throws Exception {
        java.io.InputStream in = app.getAssets().open(name);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        in.close();
        fos.close();
    }

    private static void deleteQuietly(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) for (java.io.File k : kids) deleteQuietly(k);
        }
        f.delete();
    }
}
