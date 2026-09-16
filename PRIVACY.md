# Privacy Policy

**EmuBackup** — last updated 2026-09-16.

## Short version

EmuBackup has no servers, no accounts, and no analytics. Nothing about you is sent
anywhere except the backup files you ask it to upload, which go straight from your
device to your own Google Drive.

## What the app reads

EmuBackup reads emulator save data from your device. It only reads paths listed in its
own registry, [`src/main/assets/targets.json`](src/main/assets/targets.json) — a plain
JSON file shipped inside the APK and validated in CI. It does not scan your device
generally, and it does not read ROMs, photos, contacts, messages, call logs, or
clipboard.

Save data includes whatever the emulator itself wrote: save files, memory cards,
optional save states, and emulator profile names such as the name you gave a Switch
user account.

## What the app sends, and to whom

Nothing, unless you link a Google Drive account.

If you do, EmuBackup uploads backup archives to a folder it creates in **your** Drive.
Transfers go directly from your device to `googleapis.com` over HTTPS. The developer
never sees them, and there is no intermediate server.

## Google account data

EmuBackup requests a single OAuth scope:

`https://www.googleapis.com/auth/drive.file`

This scope grants access **only to files the app itself creates**. EmuBackup cannot
see, read, or delete any other file in your Drive. It does not request your name,
your email address, your profile picture, or your contacts.

EmuBackup's use of information received from Google APIs follows the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the Limited Use requirements.

## Where credentials live

The Google refresh token is stored on your device only, in the app's private storage,
encrypted at rest by Android. It is never uploaded, logged, or included in a backup.

## Retention and deletion

Backup archives stay in your Drive until you delete them, either from inside the app or
from Drive directly. Removing the app from Drive's
[connected apps](https://myaccount.google.com/permissions) revokes its access
immediately; your existing backup files remain yours and are unaffected.

Uninstalling EmuBackup deletes its local database and stored token.

## No children's data, no sale of data

EmuBackup is not directed at children, collects no personal information for the
developer, and sells nothing, because it collects nothing.

## Contact

Open an issue at <https://github.com/tarikbc/emubackup/issues>.
