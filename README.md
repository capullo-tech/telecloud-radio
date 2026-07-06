# Telecloud Radio

An Android audio player for Telegram: browse a chat's audio messages as a
playlist, play them with gapless prefetch, and broadcast to any number of
[Snapcast](https://github.com/badaix/snapcast) clients for synchronized
multiroom listening - with a built-in web player for browser clients.

Part of the **capullo-tech** audio platform. Telecloud Radio is the Telegram
front-end, being recomposed onto the platform's shared libraries:

- **[capullo-audio](https://github.com/capullo-tech/capullo-audio)** - the
  delivery engine (ExoPlayer → FIFO → Snapcast server/client) and multiroom
  control.
- **[capullo-source-telegram](https://github.com/capullo-tech/capullo-source-telegram)**
  - the Telegram source (TDLib client, download manager, playlist queue) behind
  the `capullo-audio-contracts` SPI.

## Building

The `:tdlib` module (TDLib Java API + prebuilt native binaries) is populated by
a script rather than committed:

```sh
./scripts/setup_tdlib.sh
./gradlew :app:assembleDebug
```

You need `git-lfs` installed (the script pulls the prebuilt `.so` binaries from
[TGX-Android/tdlib](https://github.com/TGX-Android/tdlib) via LFS).

## License

GPLv3 - see [LICENSE](LICENSE) and [NOTICE](NOTICE).
