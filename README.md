# SCP Night Reader

An Android reader for the SCP Wiki (scp-wiki.wikidot.com). It loads the live wiki
and gives it a dark reading theme.

## Build

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleRelease
    adb install app/build/outputs/apk/release/app-release.apk

The release APK is minified. It is signed with the release key when these are set in
`~/.gradle/gradle.properties` (outside the repo):

    GV_RELEASE_STORE_FILE=/path/to/release.jks
    GV_RELEASE_STORE_PASSWORD=...
    GV_RELEASE_KEY_ALIAS=...
    GV_RELEASE_KEY_PASSWORD=...

Without them it falls back to the debug key, so a fresh clone still builds an installable APK.

### Donate button

The ⋮ menu has a "Donate ❤️" item that opens the author's Ko-fi page. It is controlled by
`showDonate` in `gradle.properties`. To build without it (for example, for Google Play,
which restricts external payment links), set `showDonate=false` there, or override it for
one build:

    ./gradlew assembleRelease -PshowDonate=false

## Features

- Night themes: Dim, AMOLED, Sepia night, Red light, and Terminal. The CSS is injected
  at document start, so pages never flash white. Wiki menus, sidebars, footers and
  ads are hidden.
- Bottom bar: ◀ prev · ☰ list · 🎲 random · ★ bookmark · next ▶. Prev/next follow the
  official series order and skip unwritten slots. Joke SCPs work too.
- SCP list with tabs for Series I–X, Joke, All, Bookmarks and History. You can search
  by number or title, hide pages you've read, and see your completion % per series.
  Long-press an entry to mark it read or bookmark it.
- A "Continue reading" card at the end of each article.
- The toolbar shows the title, the object class (color-coded) and the rating.
- Read tracking: a page counts as read once you scroll past 90%. The app remembers
  where you stopped in each article and offers to resume there. On launch it reopens
  your last page.
- Fullscreen mode (in the ⋮ menu or settings) hides the system bars. A slim strip on the
  bottom bar then shows the time, battery level and how far you've read.
- Tables keep readable columns and scroll sideways on their own. Everything else fits
  the screen width, so the page itself never scrolls sideways.
- Text size zooms the whole article (text, spacing and boxes together), so page layouts
  stay intact. Fixed graphics such as the classification header keep their natural size.
- Tap paging: one tap near the left or right edge scrolls a screen back or forward. You can
  turn it off in settings and change how wide the tap areas are (10–45% of the screen each).
- Export / import progress (⋮ menu) saves bookmarks, history, read marks, scroll positions
  and settings to a JSON file. Importing merges progress with what's already on the device.
- Volume keys turn the page. Tap the toolbar to jump to the top. Tap hidden
  black-bar text to reveal it.
- Settings: text size, line spacing, font (Sans/Serif/Mono), dim images, force dark
  on themed pages, auto-expand collapsibles, keep screen on, block ads.
- Opens scp-wiki / scpwiki.com links from other apps. Links to other sites open in
  your browser.

## Testing the injected CSS/JS without a device

    ./gradlew testDebugUnitTest -PgvDumpDir=/some/dir --rerun-tasks

This writes the exact scripts the app injects into `/some/dir`. You can load them
into headless Chromium (for example as a Playwright init script) to preview pages.
