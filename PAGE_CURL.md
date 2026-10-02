# Page-curl reader change

This source change adds a finger-controlled cylindrical page mesh inspired by
Google Play Books. It is an original implementation, not Google's proprietary
renderer. It replaces the earlier clipped-fold approximation.

The fold tilts and bends with horizontal and vertical finger movement. After a
short blend from the edge into the gesture, the grabbed point follows the finger.
Release finishes the turn or returns the sheet to its flat state using the
reader's existing pager settlement. Taps and volume keys use an automatic curl
path. Forward and backward turns work in both horizontal reading directions.

The curled back uses the destination page's artwork with texture coordinates
chosen to keep it readable, rather than faded mirrored artwork from the current
page. The destination page is also visible underneath. A cylinder mesh, surface
lighting and a layered projected shadow provide depth.

Page textures are captured at the start of a turn and capped at 1600 pixels on
the longest edge. Animated images/loading indicators pause in those textures
until the turn ends. Two textures are reused while curl is enabled and released
when it is disabled or the pager detaches. Unsupported hardware-only image
captures or allocation failures fall back to the standard slide for that turn.

This is an unverified implementation: visual fidelity, touch handling and frame
rate still need evaluation on Android. It does not include a deformable-paper
physics engine, Google's exact lighting model, or a guarantee of identical feel.

Enable **Settings → Reader → Paged → Page transitions**, then **Page curl**.
The horizontal reader's settings dialog also exposes **Page curl** when page
transitions are enabled. The preference defaults to off. Vertical pagers and
webtoon modes continue using their existing rendering.

## Build status

No APK was produced in the editing workspace. The workspace has no Android SDK,
and Gradle's distribution download fails with `SocketException: Operation not
permitted`. `spotlessApply`, `spotlessCheck` and `assembleDebug` were attempted in
that order but could not start their tasks. These changes have not been compiled
or tested on a device. Geometry unit tests were added but could not run here. XML parsing and `git diff --check` pass.

## Build on an Android development machine

Install the SDK required by this repository, configure `local.properties` or
`ANDROID_HOME`, and use JDK 17 or newer. From the project directory, run:

```sh
bash ./gradlew spotlessApply
bash ./gradlew spotlessCheck
bash ./gradlew assembleDebug
bash ./gradlew :app:testDebugUnitTest --tests "*CurlGeometryTest"
```

The debug APK is under `app/build/outputs/apk/debug/`. This repository uses a
separate debug application ID; an ordinary debug build installs alongside the
release app.

## Device checks still required

- With curl off, check that the normal slide transition and zoom still work.
- In both left-to-right and right-to-left modes, swipe forward and backward,
  cancel a partial swipe, and turn pages with taps and volume buttons.
- After a turn finishes, zoom and long-press the visible page; verify it is the
  current page receiving the gesture rather than an overlapping neighbor.
- Toggle curl and page transitions while the reader is open. Disabling page
  transitions should disable curl and make tap turns immediate.
- Drag from top, middle and bottom; move vertically during a turn and check that
  the fold follows. Test both completion and cancellation after release.
- Check that text on the curled back stays readable when tilted.
- Check first/last pages, chapter transitions, loading/error pages, animated
  images, portrait/landscape rotation, double pages and split wide pages.
- Check white, black, gray and automatic backgrounds with letterboxed images.
- Confirm vertical and webtoon modes keep their usual behavior.
- Check frame rate and memory use on a real device with large manga pages.
