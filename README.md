# Books

A lightweight [Audiobookshelf](https://www.audiobookshelf.org/) player for Android TV, styled like [Home](https://github.com/AmalkrishnaAS/tv-detox/tree/main/home), the tvOS-style launcher it was made alongside.

- **Library:** the focused book large at the top, over a soft wash of its cover's colours. Below are rows from Audiobookshelf's home shelves: Continue Listening, Recently Added and the rest. Covers sit in Home's glass tiles. On focus they lift, catch a light sweep and glow in the cover's colour.
- **Player:** the cover, title, narrator and current chapter, with progress through the book. Pills skip back 30 s, play/pause, skip forward 30 s, pick a chapter, or change the speed (0.8–2×). The remote's media keys work anywhere.
- **Keeps your place:** progress is sent to Audiobookshelf every 15 seconds and on pause, so your phone and the web pick up where the TV left off. Audio keeps playing when you go back to Home, and stops after 15 minutes paused.
- **Lightweight:** about 60 KB, plain Java, no libraries. It uses Android's own media player with direct play.

## Build and install

You need Java 17 and the Android SDK (`platforms;android-34`, `build-tools;34.0.0`).

```sh
sh build.sh
adb install -r out/books.apk
```

## Sign in

When Books opens, it searches your network for Audiobookshelf servers. Audiobookshelf doesn't announce itself, so Books asks every address on the TV's network for `/status` on ports 13378 and 80, which takes a few seconds.

1. Pick your server; the first one found is filled in for you. You can also type the address.
2. Enter your username and password with the remote's keyboard.

Books keeps your login and renews it in the background: the access token lasts an hour and the refresh token 30 days. You stay signed in as long as you use it at least once a month. To sign out, use the account pill at the top of the library.

Use the server's IP address. Android TV 9 can't resolve `.local` names.

### Or use an API key

Use this if your server only allows single sign-on, or you'd rather not type a password on the TV. In Audiobookshelf on the web, open **Settings → API Keys → Add API Key**, choosing no expiry; you need an admin account to do this. Then, on the computer that has adb, run:

```sh
TV=<tv-ip>:5555 sh connect.sh http://<server-ip>:13378
```

It asks for the key without showing it, checks it against the server, and sends it to the TV.

## Remove

```sh
adb uninstall com.amalkrishna.books
```

## Credits

The side panel and `LineIcon` are adapted from TV Detox by Ansub Khan (MIT). See [NOTICE](NOTICE).
