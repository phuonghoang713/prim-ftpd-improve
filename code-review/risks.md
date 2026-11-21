# Risks – what is fixed and what is left

Pass over **every risk which was still listed as open** in the review
documents, plus a search for further ones. Each was either fixed or is
listed below with the reason why it stays. Ids of this pass: `R3-`.

## Fixed in this pass

| # | Risk | Was listed as | Fix | Tested |
|---|------|---------------|-----|--------|
| R3-1 | **Weak storage of the password.** One round of SHA-512 with a salt which is the same for every installation: a stolen preferences file can be attacked with precomputed tables and billions of guesses per second. | O1 | PBKDF2 with HMAC-SHA256, 60,000 iterations, random salt per password (`pbkdf2-sha256$iterations$salt$hash`). Old hashes are still accepted, they are replaced when the password is set again. | Published test vectors for PBKDF2 with HMAC-SHA256, check of new and old format, logins by FTP and SFTP |
| R3-2 | **Unlimited guessing of passwords.** SFTP had no limit apart from the one per connection, FTP only per connection. | O7 | After 10 failed logins from an address, logins from it are rejected for 60 seconds without checking the password. One limiter for both servers (`LoginAttemptLimiter`). The SSH server passes the client address to the user manager for that. | Unit check, 10 wrong FTP logins block FTP and SFTP |
| R3-3 | **Weak SSH algorithms.** CBC ciphers allow recovery of plaintext, `hmac-sha1-96` is truncated. | O2 (partly) | Not offered anymore: `aes128-cbc`, `aes192-cbc`, `aes256-cbc`, `3des-cbc`, `blowfish-cbc`, `hmac-sha1-96`. Left: `aes128/192/256-ctr`, `hmac-sha2-256`, `hmac-sha2-512`, `hmac-sha1`. | Server proposal read with `ssh -vv`, CBC is refused |
| R3-4 | **SFTP write at offset 0 destroyed the rest of a file** (plain storage). | R2-O3 | SFTP and SCP writes of plain storage never truncate implicitly, truncation happens where the protocol asks for it (`truncate()`). FTP `STOR` still replaces the file. | In place write, append, truncate, overwrite of a larger file by a smaller one with FTP, SFTP and SCP |
| R3-5 | **Deleting a directory deleted everything in it** for SAF and root storage (`rm -rf` as root for a single FTP `RMD`). | R2-O4 | A directory is only deleted if it is empty (`rmdir` for root, check of children for SAF), like plain storage always did. | not tested (SAF, root) |
| R3-6 | **Incomplete files looked like complete ones** after a failed or cancelled download and after a failed copy of shared data. | R2-O5 | The incomplete file is deleted. | not tested (Android) |
| R3-7 | **Service without server.** After Android killed the process it restarted the services without the preferences. They stayed around doing nothing, widget, tile and app showed "running" although nothing was listening. | R2-O6 | Such a service stops itself, the state shown is the real one. The server is **not** restarted automatically, see below. | not tested (Android) |
| R3-8 | Service started without preferences stopped without `startForeground()` (crash by Android). | O8 | Foreground contract is fulfilled before stopping. | not tested (Android) |
| R3-9 | One thread stayed forever for every start/stop of a server. | R2-O1 | The thread ends when its service has stopped. Exception: the thread which the shared root shell is bound to stays as long as the other server uses the shell. | not tested (Android) |
| R3-10 | An SFTP read with a negative length ended the session with an exception, a client could open handles without limit. | new | Negative length is answered with an error, at most 1000 open handles per session. | compiled |
| R3-11 | Tasker configuration screens were drawn below the system bars on Android 15+. | A-O1 | Layout applies window insets. | not tested (Android) |
| R3-12 | Message of a failed server start could get lost because the thread showing it ended (found when reading R3-9 again). | new | Shown on the main thread. | not tested (Android) |

## SSH library replaced (branch `feature/replace-ssh-library`)

| # | Risk | Was listed as | Fix | Tested |
|---|------|---------------|-----|--------|
| R3-13 | **Outdated SSH library.** Apache SSHD 0.14 from 2014: key exchange with SHA-1, no strict key exchange, RSA host keys only with SHA-1 signatures, no way to update. | O2 | Replaced by Apache MINA SSHD 2.19.0 as a dependency. Offered now: post-quantum and curve25519 key exchange, chacha20-poly1305 / AES-GCM / AES-CTR, SHA-2 MACs, RSA with SHA-2, strict key exchange. CBC and everything with SHA-1 or MD5 for key exchange and MAC is not offered. See [../architecture/ssh-library.md](../architecture/ssh-library.md). | On a desktop JVM with OpenSSH 9.6, see [verification.md](verification.md). **Not on Android**, which is why it is on its own branch. |

## Not fixed, and why

| # | Risk | Why it stays | What you can do |
|---|------|--------------|-----------------|
| O3 | Tasker (and now PowerToggles) plugin: once enabled in the preferences, any app on the device can start and stop the server. | The plugin protocol has no authentication. Off by default, the preference links to the advisory. | Leave it disabled unless you need it. |
| O4 | The file provider of the directory picker covers the whole file system. | Not exported, only used inside the app. Changing it means changing the vendored picker. | – |
| O5 | FTP is not encrypted. Anonymous login gives full access. | Nature of FTP, option chosen by the user. | Use SFTP outside of a trusted network, keep anonymous login off. |
| O6 | Public key and anonymous SSH logins accept any user name. | By design of the app. | – |
| R2-O2 | Root storage: a failing `dd` is only logged, the upload is reported as successful. | Needs a rooted device to verify, exit codes of `su` implementations differ. A wrong fix would report every upload as failed. | Not relevant without root. |
| R2-O7 | SCP download with wild cards reads the file system directly. | Vendored code, rarely used, only reaches what the user may read anyway. | – |
| R2-O8 | Some screens do their "background" work while blocking the main thread, generating an RSA 4096 host key can take long enough for "app not responding". | Making it asynchronous changes the order of events in the UI and needs testing on a device. | Keep the default host key type (ed25519), which is fast. |
| R3-O1 | The server is not restarted automatically after Android killed the app. | Since Android 12 a service restarted in the background is not allowed to become a foreground service, so it would be killed again. Quick share mode would also be lost and the whole storage served instead. | Exclude the app from battery optimisation (see https://dontkillmyapp.com/). |
| A-O2 | Start by Tasker while the app is in the background can be refused by Android 12+. | Rule of Android. | Exclude the app from battery optimisation. |
| A-O3 | Progress dialog of a share copy can disappear on rotation on tablets (Android 16). | Cosmetic, the copy continues. | – |

## Behaviour changes of this pass

1. A password set from now on is stored in the new format. **An older
   version of the app can not check it**: after a downgrade the password has
   to be set again. Existing passwords keep working without any action.
2. After 10 wrong passwords from one address, logins from that address are
   refused for one minute, also with the right password.
3. SSH clients which only support CBC ciphers or `hmac-sha1-96` can not
   connect anymore (clients from before about 2010). With the replaced SSH
   library (R3-13) this extends to clients without SHA-2 key exchange and
   MAC, i.e. from before about 2014.
4. An SFTP client which writes into an existing file without asking for
   truncation keeps the rest of the file (POSIX behaviour).
5. FTP `RMD` on a directory which is not empty fails for SAF and root
   storage. FTP clients delete the content first, so "delete folder" in a
   client keeps working.
6. Incomplete downloads and incomplete copies of shared files are deleted.
7. After Android killed the app, widget and tile show "stopped" instead of
   a server which is not there.
