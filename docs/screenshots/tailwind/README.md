# Tailwind refactor screenshots

Captured from the production build with synthetic browser fixtures. Reviewed in
Chromium and WebKit; the full matrix also covers 320, 390, 430, 844, and 1440 pixel
viewports, both appearances, editors, OAuth, artifacts, and fullscreen activity.

- [desktop light](desktop-light.png)
- [desktop dark](desktop-dark.png)
- [mobile dark](mobile-dark.png)
- [landscape light](landscape-light.png)
- [mobile select](mobile-select.png)
- [mobile artifacts](mobile-artifacts.png)
- [desktop settings](desktop-settings.png)
- [mobile mcps](mobile-mcps.png)

Validation: 91 unit tests and all 15 browser journeys pass, including icon
rendering, centered dialogs, and nested-scroll checks. Complete screenshots
are also retained in the CI browser evidence artifacts.
