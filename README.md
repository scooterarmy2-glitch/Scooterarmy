# #scooterarmy Android App

GPS performance meter for scooters — standalone Android APK.

## How to build

1. Push this repo to GitHub (any branch named `main`)
2. GitHub Actions builds the APK automatically
3. Go to **Actions** tab → latest run → **Artifacts** → download `scooterarmy-debug-apk`
4. Side-load the APK or submit to Google Play

## What's baked in

- GPS permissions (ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION)
- Wake lock (screen stays on mid-run)
- Hardware acceleration (smooth canvas gauge)
- Portrait lock
- Fullscreen immersive mode (no status/nav bar)
- Google Fonts embedded offline by CI pipeline
- localStorage for run history
- Back button intercepted (no accidental exit mid-run)

## First-time side loading

Enable *Install from unknown sources* in Android Settings,
then open the downloaded APK file.
