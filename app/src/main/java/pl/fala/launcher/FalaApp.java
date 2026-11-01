package pl.fala.launcher;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Zapisuje raport, gdy aplikacja się zamknie z powodu błędu. */
public class FalaApp extends Application {

    static File crashFile(Context c) {
        return new File(c.getFilesDir(), "crash.txt");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String v = "";
                try {
                    v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                String text = "Fala " + v + " · Android " + Build.VERSION.RELEASE + " · " + Build.MODEL
                        + "\nWątek: " + t.getName() + "\n\n" + sw;
                try (FileOutputStream fo = new FileOutputStream(crashFile(this))) {
                    fo.write(text.getBytes("UTF-8"));
                }
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
