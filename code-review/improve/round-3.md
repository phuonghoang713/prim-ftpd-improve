# Improvements of the pass over open risks

Findings and reasons are in [../risks.md](../risks.md).

| Improvement | Files | Finding |
|-------------|-------|---------|
| Password is stored as PBKDF2 hash with random salt, old hashes are still accepted. | `primitiveFTPd/.../util/EncryptionUtil.java`, `services/AndroidPrefsUserManager.java` | R3-1 |
| Failed logins are limited per client address for FTP and SFTP. | `primitiveFTPd/.../services/LoginAttemptLimiter.java` (new), `AndroidPrefsUserManager.java`, `SshServerService.java` | R3-2 |
| CBC ciphers and `hmac-sha1-96` are not offered by the SSH server. | `primitiveFTPd/.../services/SshServerService.java` | R3-3 |
| SFTP / SCP writes of plain storage do not truncate implicitly. | `primitiveFTPd/.../filesystem/FsFile.java`, `FsSshFile.java` | R3-4 |
| Directories are only deleted if they are empty (SAF, root). | `primitiveFTPd/.../filesystem/SafFile.java`, `RootFile.java` | R3-5 |
| Incomplete downloads and incomplete copies of shared files are deleted. | `primitiveFTPd/.../services/DownloadsService.java`, `share/TargetDir.java`, `share/AbstractReceiveShareActivity.java` | R3-6 |
| A service restarted without its start intent stops itself, a service without preferences fulfils the foreground contract before stopping. | `primitiveFTPd/.../services/AbstractServerService.java` | R3-7, R3-8 |
| Thread of a server service ends with the service. | `primitiveFTPd/.../services/ServerServiceHandler.java` | R3-9 |
| SFTP: negative read length is rejected, open handles per session are limited. | `sshd-core-0.14.0/.../server/sftp/SftpSubsystem.java`, `primitiveFTPd/.../services/SshServerService.java` | R3-10 |
| Tasker configuration screens apply window insets. | `primitiveFTPd/res/layout/tasker_edit_activity.xml` | R3-11 |
| Messages of the server services are shown on the main thread. | `primitiveFTPd/.../services/AbstractServerService.java`, `SshServerService.java` | R3-12 |
