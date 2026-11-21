package org.primftpd.services;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down guessing of passwords: after too many failed logins from an address,
 * logins from that address are rejected for a while, without checking the password.
 * Shared by ftp and sftp server.
 */
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 10;
    static final long BLOCK_MILLIS = 60_000;
    private static final int MAX_ENTRIES = 1000;

    private static final class Entry {
        int failures;
        long lastFailure;
    }

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private static String key(String address) {
        return address != null ? address : "unknown";
    }

    public static boolean isBlocked(String address) {
        return isBlocked(address, System.currentTimeMillis());
    }

    static boolean isBlocked(String address, long now) {
        Entry entry = ENTRIES.get(key(address));
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (now - entry.lastFailure >= BLOCK_MILLIS) {
                // block (or series of failures) is over
                ENTRIES.remove(key(address), entry);
                return false;
            }
            return entry.failures >= MAX_FAILURES;
        }
    }

    public static void loginFailed(String address) {
        loginFailed(address, System.currentTimeMillis());
    }

    static void loginFailed(String address, long now) {
        if (ENTRIES.size() >= MAX_ENTRIES) {
            // do not let the map grow without limit
            for (Iterator<Entry> it = ENTRIES.values().iterator(); it.hasNext(); ) {
                Entry old = it.next();
                synchronized (old) {
                    if (now - old.lastFailure >= BLOCK_MILLIS) {
                        it.remove();
                    }
                }
            }
        }
        Entry entry = ENTRIES.get(key(address));
        if (entry == null) {
            Entry created = new Entry();
            entry = ENTRIES.putIfAbsent(key(address), created);
            if (entry == null) {
                entry = created;
            }
        }
        synchronized (entry) {
            if (now - entry.lastFailure >= BLOCK_MILLIS) {
                entry.failures = 0;
            }
            entry.failures++;
            entry.lastFailure = now;
        }
    }

    public static void loginSucceeded(String address) {
        ENTRIES.remove(key(address));
    }

    static void reset() {
        ENTRIES.clear();
    }
}
