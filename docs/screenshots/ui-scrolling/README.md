# Scrolling and tab spacing QA

Screenshots use isolated fixture data. Light and dark appearances were checked in Chromium and WebKit.

- [Desktop dark](desktop-dark.png) and [light](desktop-light.png)
- [Mobile dark](mobile-dark.png) and [small mobile light](mobile-light.png)
- [Run detail](run-mobile-dark.png)
- [Landscape](landscape-light.png)
- [Expanded artifacts](artifacts-mobile-dark.png)
- [Agent dialog](dialog-mobile-dark.png)

Validation: 90 unit/integration tests and 15 browser journeys passed. After the final landscape spacing adjustment, all seven layout projects passed again. The browser checks verify root bounds, nested scroll containers, actual activity scrolling, visible content height, task actions, and tab padding.
