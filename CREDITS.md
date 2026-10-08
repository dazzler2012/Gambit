# Gambit credits

Gambit is a private, family-only build of OwnTV for Android TV. It is released under the same
**GNU General Public License v3.0** as the projects it builds on — see [LICENSE](LICENSE). Every
original copyright notice in files taken from those projects is kept.

## Projects Gambit is built on

| Project | Author | Licence | How Gambit uses it |
|---|---|---|---|
| [OwnTV](https://github.com/ahXN00/OwnTV) | ahXN00 and contributors | GPL-3.0 | The base app: Gambit is a fork, restyled and extended |
| [OwnTV_Core](https://github.com/ahXN00/OwnTV_Core) | ahXN00 and contributors | GPL-3.0 | Database, sync, EPG, settings and both playback engines, used as a library |
| [TuvoraTV](https://github.com/paradox-kush/TuvoraTV) | paradox-kush and contributors | GPL-3.0 | Source for the Stremio addon, Trakt and poster-home features, adapted to OwnTV |
| [NuvioTV](https://github.com/tapframe/NuvioTV) | NuvioMedia (tapframe) and contributors | GPL-3.0 | The project TuvoraTV is built on |

OwnTV's own credits (translators on Weblate, TMDB, OpenSubtitles, mpv/libmpv, FFmpeg, Media3,
Jetpack Compose, Room, Koin, OkHttp, Coil, ZXing and the wider Kotlin/AndroidX ecosystem) apply to
Gambit too; they are listed in the [README](README.md#-credits).

## Icons and artwork

| Source | Licence | Where |
|---|---|---|
| OwnTV icon set (`ui/components/OwnTVIcon.kt`, drawn in code) | GPL-3.0 (part of OwnTV) | All in-app icons, including Gambit's screens |
| OwnTV brand artwork (app icon, banner, wordmark by [@m3th0d93](https://github.com/m3th0d93)) | GPL-3.0 (part of OwnTV) | Launcher icon, banner, About screen |
| [Material Icons](https://fonts.google.com/icons) (`androidx.compose.material:material-icons-core`) | Apache-2.0 | Available through OwnTV's existing dependency; not yet used by Gambit's own code |

Gambit adds no image files of its own so far. Any icon or image added later is listed here with its
source and licence.

## Design reference

Gambit's layout is inspired by the look and behaviour of TiviMate. Gambit is not affiliated with or
endorsed by TiviMate or its developers, and contains no TiviMate code, icons, logos, images, fonts or
other assets: every screen is drawn with original Compose code.

## Content

Like OwnTV, Gambit is a player only. It ships with no channels, playlists, subscriptions, addon
URLs or other content. Everything it plays comes from sources the user adds.
