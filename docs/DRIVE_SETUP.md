# Using Google Drive

Drive is optional. With no client ID compiled in, EmuBackup writes to a local folder and is
fully functional; the Drive card in Settings simply reads "not configured in this build".
Released APKs from CI are built without secrets, so they are local-only by design.

To enable Drive you need your own Google Cloud OAuth client. This is unavoidable for an
open-source app: a shared client ID would put every user's quota and consent under one
project.

## Steps

The console calls this area **Google Auth Platform**. Menu labels below are the English
ones; the pages are the same in any locale.

1. Create a project at <https://console.cloud.google.com/>.
2. Enable the **Google Drive API**.
3. **Google Auth Platform → Overview** → run the OAuth setup wizard. App name, support
   email, audience **External**, contact email.
4. **Clients → Create client** → application type **TVs and Limited Input devices**.
   The client ID and client secret are shown once, in a dialog. **Copy the secret now**;
   the console will not show it again, and a lost one has to be rotated.
5. **Data access → Add or remove scopes**. The scope is not in the picker list until the
   Drive API has finished enabling, so paste it into *Add scopes manually* instead:

       https://www.googleapis.com/auth/drive.file

   Add that scope and **only** that scope. It is classified non-sensitive — it appears
   under *Your non-sensitive scopes* — so it needs no verification and no security
   assessment. Do not request full `drive`; that is a restricted scope requiring an annual
   third-party security audit, which is absurd for a save backup tool. Click **Save**.
6. **Branding**. "Publish app" stays greyed out until this page is complete, and the only
   hint the console gives is a one-line "to publish your app, complete the configuration
   on the branding page". Fill in:
   - **Application home page**, **Privacy policy link**, **Terms of service link** — for
     a fork, your own repository URLs will do.
   - **Authorized domains** — add the bare domain of those URLs, for example
     `github.com`. Leave it out and the page shows "missing domain" and refuses to save.
     A domain you do not own is accepted here because the scope is non-sensitive; a
     sensitive or restricted scope would require Search Console ownership.

   Click **Save**.
7. **Audience → Publish app → Confirm.** The status must read **In production**. See the
   warning below.
8. Build:

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
