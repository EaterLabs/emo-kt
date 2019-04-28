# Eater's Mod Organizer (backend)

This is the backend of Eater's Mod Organizer,

It handles installation, management of profiles, accounts, and modpacks.

All actions can be done through the [`EmoInstance`](https://eaterlabs.github.io/emo-kt/me.eater.emo/-emo-instance/index.html) class.

All docs are located at https://eaterlabs.github.io/emo-kt

# Building

```bash
./gradlew shadowJar
```

Because the installer sideloads the Forge installer,
and the Forge installer has a dependency that conflicts with
the emo backend, the `shadowJar` is the only supported library for this.
With shadowJar the conflicting dependency gets renamed. 
If you're planning to use Google Guave, please shadow `com.google` to a different namespace
to prevent this conflict.


# Usage and licensing

You're free to use this library to make your own project or launcher,
but only if said project and/or launcher is also open-source.

If you do, please send me a message!