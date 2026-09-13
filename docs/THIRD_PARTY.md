# Third-party components

Vendored by `./vendor-libs.sh` into `libs/`, which is git-ignored. That script is
the authoritative record of which artifacts and versions the build expects.

| Component | Version | License | Why |
|---|---|---|---|
| androidx.recyclerview | 1.3.2 | Apache-2.0 | Every list in the app |
| androidx.core (+ core-ktx) | 1.13.1 | Apache-2.0 | RecyclerView transitive |
| androidx.annotation | 1.7.1 | Apache-2.0 | Shared transitive |
| androidx.collection | 1.1.0 | Apache-2.0 | RecyclerView transitive |
| androidx.lifecycle-common / -runtime | 2.6.2 | Apache-2.0 | RecyclerView transitive |
| androidx.arch.core-common / -runtime | 2.2.0 | Apache-2.0 | Lifecycle transitive |
| androidx.customview (+ poolingcontainer) | 1.0.0 | Apache-2.0 | RecyclerView transitive |
| androidx.interpolator | 1.0.0 | Apache-2.0 | RecyclerView transitive |
| kotlin-stdlib | 1.6.21 | Apache-2.0 | AndroidX runtime requirement |
| dev.rikka.shizuku:api / provider / aidl / shared | 13.1.5 | MIT | Optional app-private storage access |
| org.json | 20240303 | Public Domain | **Test only.** At runtime `android.jar` provides these classes |
| junit-platform-console-standalone | 1.10.2 | EPL-2.0 | **Test only.** Not shipped in the APK |

The four Shizuku artifacts total about 60 KB and carry no resources, so they add
no `--extra-packages` work to the build.

Fonts and icons: none bundled yet. Any added later must be listed here with a
license that permits redistribution.
