# v0.5 experimental

- Search modes now wired into scanner service.
- Candidate level detection, pinch-to-zoom, OCR nickname verification, pinch-back on mismatch.
- Four low-motion samples before declaring a horizontal edge.
- Candidate rejection for current route position.
- Found candidate triggers sound and stops. Tap on the player manually after checking; automatic tapping is intentionally disabled until coordinate validation is tested.

Known limitations: no reliable global map-coordinate tracking; zoom restoration and edge detection need live device testing; OCR may misassociate nicknames with other nearby cities. Do not leave unattended.
