# v0.8.9 test — OCR candidate improvements

- 2x screenshot upscaling before ML Kit OCR on screens narrower than 1200 px.
- Convert OCR coordinates back to original screenshot pixels.
- Find target-level badges even when the name is unreadable, and verify after zoom.
- Accept names above/below/alongside a level badge.
- Require matching level for immediate name+level match.
- Keep v0.8.8 navigation unchanged.

LIMITATION: Google ML Kit Latin recognizer does NOT reliably recognize Cyrillic. This update does not add a Russian OCR engine. Cyrillic targets require a separate compatible OCR engine and trained Russian model.
