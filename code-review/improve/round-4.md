# Replacement of the SSH library

Branch `feature/replace-ssh-library`. Reasons, candidates and design:
[../../architecture/ssh-library.md](../../architecture/ssh-library.md).
Finding: R3-13 in [../risks.md](../risks.md).

| Improvement | Files |
|-------------|-------|
| Apache MINA SSHD 2.19.0 (`sshd-core`, `sshd-sftp`, `sshd-scp`) is a dependency. | `gradle/libs.versions.toml`, `primitiveFTPd/build.gradle` |
| The copy of Apache SSHD 0.14 is removed (268 files). | `sshd-core-0.14.0/` (deleted), `settings.gradle` |
| File interfaces of the old library live in the app now, the storage classes are independent of the SSH library. | `primitiveFTPd/.../filesystem/SshFile.java`, `SshFileSystemView.java`, `SshSession.java` (new), all `*Ssh*` classes of `filesystem` |
| Adapter from `java.nio.file`, which the new library works on, to the storage types of the app. | `primitiveFTPd/.../sftp/` (new package, 7 classes) |
| Server setup with the API of the new library: algorithms, IP filter, authentication, host keys, timeouts. | `primitiveFTPd/.../services/SshServerService.java`, `PubKeyAuthenticator.java` |
| ed25519 is handled by the library with BouncyCastle, the app's own signature class is removed. | `primitiveFTPd/.../crypto/SignatureEd25519.java` (deleted) |
| The library is told that it runs on Android and where its home and working directory are. | `primitiveFTPd/.../PftpdApp.java` |
| Extended command `check-file` of prim-sync is kept with its old reply format. | `primitiveFTPd/.../sftp/PftpdSftpSubsystemFactory.java` |

## Behaviour changes

1. Algorithms: current ones are offered, nothing based on SHA-1 or MD5 for
   key exchange and MAC, no CBC. Clients from before about 2014 can not
   connect.
2. RSA host keys are used with SHA-2 signatures (`rsa-sha2-512`).
3. A client which is not allowed by the IP pattern is disconnected right
   after connecting, as before.
4. SFTP is about twice as fast in the tests on a desktop (5 GiB in about
   30 s instead of 75 s).
5. `rename` can replace an existing file if the client asks for it
   (`posix-rename`), listings contain `.` and `..`.
