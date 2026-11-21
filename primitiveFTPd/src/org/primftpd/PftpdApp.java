package org.primftpd;

import android.app.Application;
import android.util.Log;

import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.common.util.io.PathUtils;
import org.primftpd.log.LogController;

import java.io.File;
import java.nio.file.Path;
import java.security.Security;

public class PftpdApp extends Application {

    static {
        Security.removeProvider("BC");
        Security.insertProviderAt(new org.bouncycastle.jce.provider.BouncyCastleProvider(), 1);

        // The ssh library (Apache SSHD) has some code paths for android,
        // e.g. it does not touch classes of javax.management then. Do not rely on its detection.
        OsUtils.setAndroid(Boolean.TRUE);
        OsUtils.setCurrentUser("pftpd");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        initSshLibrary();

        LogController.init(getApplicationContext());
    }

    /**
     * Android has no system properties 'user.home' and 'user.dir', the ssh library asks for them.
     * See docs/android.md of Apache SSHD.
     */
    private void initSshLibrary() {
        try {
            File filesDir = getFilesDir();
            if (filesDir != null) {
                final Path filesPath = filesDir.toPath();
                PathUtils.setUserHomeFolderResolver(() -> filesPath);
                OsUtils.setCurrentWorkingDirectoryResolver(() -> filesPath);
            }
        } catch (Throwable e) {
            // logging is not initialised yet
            Log.w("PftpdApp", "could not init ssh library", e);
        }
    }
}
