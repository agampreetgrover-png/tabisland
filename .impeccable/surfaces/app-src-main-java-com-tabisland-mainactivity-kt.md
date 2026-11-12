---
version: 1
slug: "app-src-main-java-com-tabisland-mainactivity-kt"
primary_target: "app/src/main/java/com/tabisland/MainActivity.kt"
related_targets: []
---

# Surface: settings screen (MainActivity)

Mode: operate. Audience: the developer on their Pixel 9a. Task: check setup, play any style, choose what the island shows, fit it to the camera.

## Direction contract

THESIS: The settings screen is the island's fitting room: a live, real-scale preview you drag to fit, with every style playable on it. It refuses the usual long list of switches with a separate grid of test buttons.

OWN-WORLD: True-black ground (#000000), basalt groups (#161618) with seam hairlines (#2A2A2E), bone text (#F4F4F1), pumice secondary text (#9E9EA6). The island itself (the real IslandView, pure black, spring motion) is the only thing that moves. Green (#30D158) means "on/done" and nothing else.

STORY: You see whether setup is complete, watch any style play on the island (in the preview even before setup), flip what it shows, and fit it to the camera by dragging.

FIRST VIEWPORT: Title (30sp) and a status line with a state dot. A ~200dp stage: the camera ring at its real position and the real IslandView at 1:1 scale, draggable to move and pinched (two fingers) to widen, with an x/y/w/h readout under it; the empty stage carries a quiet "Tap a style below to play it here." (Changed from "pull an end to widen" during the build: the idle pill is about 38dp wide, too small for end handles.) A segmented control (one basalt track, the selected tab a bone segment: Calls, Messages, Live, System) over wrapping chips that play each style; the playing chip is outlined until its demo ends. The first switch group peeks in below the demos as a scroll cue.

FORM: "Drag the island to fit", 4th of 7 on the ranked list. seed:c21e65d3aa586eb2

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
