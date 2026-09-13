# Using Google Drive

Drive is optional. With no client ID compiled in, EmuBackup writes to a local folder and is
fully functional; the Drive card in Settings simply reads "not configured in this build".
Released APKs from CI are built without secrets, so they are local-only by design.

To enable Drive you need your own Google Cloud OAuth client. This is unavoidable for an
open-source app: a shared client ID would put every user's quota and consent under one
project.

## Steps

1. Create a project at <https://console.cloud.google.com/>.
2. Enable the **Google Drive API**.
3. Configure the **OAuth consent screen**. User type *External* is fine.
4. Add the scope `https://www.googleapis.com/auth/drive.file` — and **only** that scope.
   It is classified non-sensitive, so it needs no verification and no security assessment.
   Do not request full `drive`; that is a restricted scope requiring an annual third-party
   security audit, which is absurd for a save backup tool.
5. **Set the publishing status to "In production".** See the warning below.
6. Create credentials → OAuth client ID → application type **TVs and Limited Input
   devices**. Note the client ID and client secret.
7. Build:

       EMUBACKUP_DRIVE_CLIENT_ID=xxxx.apps.googleusercontent.com \
       EMUBACKUP_DRIVE_CLIENT_SECRET=xxxx \
       ./build.sh

## Set publishing status to "In production"

**This is the single most likely cause of a silently broken backup.**

While the consent screen is in **Testing** with External user type, Google **revokes refresh
tokens after 7 days**. Scheduled backups then fail every week, and the failure looks like a
random authentication bug rather than a configuration choice.

Because `drive.file` is non-sensitive, switching to In production requires no verification
and no review. Do it before relying on scheduled backups.

If you see `invalid_grant`, this is almost certainly why. The app maps that error to this
explanation rather than a generic failure.

## Why the device-code flow

EmuBackup uses the OAuth 2.0 device authorization grant: the app shows a code, you enter it
at <https://google.com/device> on any other machine.

The alternative, an in-app browser flow with PKCE, requires an *Android* OAuth client, which
binds to your package name **plus the SHA-1 of the signing certificate**. `build.sh`
generates a throwaway debug keystore per clone, so every contributor and CI would need their
fingerprint registered on the client. Google also now disables custom URI schemes by default
and advises against them. The device flow needs no redirect URI, no custom scheme and no
fingerprint, so one client ID works everywhere — and on a handheld with no keyboard, typing
a short code elsewhere beats typing a Google password on a gamepad.

The client secret issued for this client type is embedded in the APK. That is public by
design for a device-flow client and is not treated as confidential; security rests on the
user-granted authorization, not on the secret.

## What the app stores in your Drive

A single folder, `EmuBackup/`, containing one directory per version. Because the scope is
`drive.file`, the app can only see files it created — it has no access to the rest of your
Drive. The files stay visible and downloadable at drive.google.com, so you can always
retrieve and unzip a backup by hand. See `docs/FORMAT.md`.

The refresh token is encrypted with an AES-256/GCM key held in the framework
`AndroidKeyStore` and stored as ciphertext in `SharedPreferences`. `android:allowBackup` is
`false`, partly because that key cannot be backed up and a restored ciphertext would be
undecryptable, and partly because a stale token in a cloud backup is a liability.
