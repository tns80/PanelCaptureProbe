# Third-party notices

## Shizuku API

- Project: Shizuku API
- Source: <https://github.com/RikkaApps/Shizuku-API>
- Dependency: `dev.rikka.shizuku:api:13.1.5`,
  `dev.rikka.shizuku:provider:13.1.5`
- License: MIT License

The packaged Maven artifacts retain their own license metadata.

## scrcpy display-control approach

- Project: scrcpy
- Source: <https://github.com/Genymobile/scrcpy>
- Relevant upstream area:
  `server/src/main/java/com/genymobile/scrcpy/device/Device.java` and display wrappers
- License: Apache License 2.0

`PhysicalDisplayController.kt` contains a new, reduced implementation adapted from the
documented scrcpy approach: obtain physical display tokens through hidden `SurfaceControl`
or Android 14+ `DisplayControl`, then request `setDisplayPowerMode`.

This project is not affiliated with Shizuku, scrcpy, REDMAGIC, Smilegate, Stove, or Epic Seven.
