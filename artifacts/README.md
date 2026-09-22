# Build artifacts

**Empty on purpose.** The two APKs built earlier (`nexus-1.0.0-debug.apk`, `nexus-1.0.0-release.apk`)
were removed from the workspace on request. Nothing else here is a release channel — `.gitignore`
excludes `*.apk`, so this folder is a delivery/inspection area only.

The build outputs under `app/build/` are not here either: that path is excluded from workspace
snapshots, so the only persisted copies were the two files above.

## Rebuilding

`scripts/build-apk.sh` reproduces both variants and copies them back here:

```bash
./scripts/build-apk.sh          # tests, then debug + release
./scripts/build-apk.sh debug    # debug only
```

It expects an Android SDK and a JDK 17+ (see the top-level README for the toolchain matrix), and it
passes the low-memory Gradle/Kotlin heap flags that a small machine needs. Once it finishes:

| File | Package | Size (last build) |
| --- | --- | --- |
| `nexus-1.0.0-debug.apk` | `com.nexus.aichat.debug` | ~34.0 MB |
| `nexus-1.0.0-release.apk` | `com.nexus.aichat` | ~8.6 MB, R8 full mode |

The release APK is signed with the debug key unless `NEXUS_KEYSTORE_*` is set — supply a real keystore
before distributing it.
