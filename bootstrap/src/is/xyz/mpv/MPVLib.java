package is.xyz.mpv;

import android.content.Context;
import android.view.Surface;

/**
 * JNI bridge to libmpv via libplayer.so (from mpv-android).
 * Method signatures must match the native implementations.
 */
public class MPVLib {
    static {
        // libmpv.so has DT_NEEDED on the FFmpeg libs; load them first
        // so the linker resolves them reliably on all devices/ABIs.
        System.loadLibrary("c++_shared");
        System.loadLibrary("avutil");
        System.loadLibrary("swresample");
        System.loadLibrary("swscale");
        System.loadLibrary("avcodec");
        System.loadLibrary("avformat");
        System.loadLibrary("avfilter");
        System.loadLibrary("avdevice");
        System.loadLibrary("mpv");
        System.loadLibrary("player");
    }

    public static native void create(Context context);
    public static native void init();
    public static native void destroy();

    public static native void command(String[] cmd);

    public static native void setOptionString(String name, String value);

    public static native String getPropertyString(String name);
    public static native int getPropertyInt(String name);
    public static native double getPropertyDouble(String name);
    public static native boolean getPropertyBoolean(String name);

    public static native void setPropertyString(String name, String value);
    public static native void setPropertyInt(String name, int value);
    public static native void setPropertyDouble(String name, double value);
    public static native void setPropertyBoolean(String name, boolean value);

    public static native void observeProperty(String name, int format);
    public static native void attachSurface(Surface surface);
    public static native void detachSurface();
    public static native byte[] grabThumbnail(String url, int width, int height);

    // Event observer interface
    public interface EventObserver {
        void eventProperty(String property);
        void eventProperty(String property, long value);
        void eventProperty(String property, double value);
        void eventProperty(String property, boolean value);
        void eventProperty(String property, String value);
        void event(int eventId);
    }

    private static EventObserver observer;

    public static void setEventObserver(EventObserver o) {
        observer = o;
    }

    // Called from native code
    @SuppressWarnings("unused")
    public static void eventProperty(String property) {
        if (observer != null) observer.eventProperty(property);
    }

    @SuppressWarnings("unused")
    public static void eventProperty(String property, long value) {
        if (observer != null) observer.eventProperty(property, value);
    }

    @SuppressWarnings("unused")
    public static void eventProperty(String property, double value) {
        if (observer != null) observer.eventProperty(property, value);
    }

    @SuppressWarnings("unused")
    public static void eventProperty(String property, boolean value) {
        if (observer != null) observer.eventProperty(property, value);
    }

    @SuppressWarnings("unused")
    public static void eventProperty(String property, String value) {
        if (observer != null) observer.eventProperty(property, value);
    }

    @SuppressWarnings("unused")
    public static void event(int eventId) {
        if (observer != null) observer.event(eventId);
    }

    // Called from native code for logging
    @SuppressWarnings("unused")
    public static void logMessage(String prefix, int level, String text) {
        // Log to Android logcat; level is mpv log level
        android.util.Log.d("mpv", prefix + ": " + text);
    }
}
