# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
One person, the developer, on their own Pixel 9a. They set the app up once, then come back to check a style, adjust the island to fit the camera, or switch a feature on or off.

## Product Purpose
Tab Island is a Dynamic Island for Android. It draws a black pill around the front camera that shows music, calls, messages, timers, navigation, downloads and system events, and it replaces the system's heads-up pop-ups. The settings app sets it up, shows every style on demand, picks which features appear, and fits the pill to the camera.

## Positioning
An accessibility overlay measured from the phone's real camera cutout, with spring motion, so it sits exactly on the hole instead of floating near it.

## Operating Context
- Setup needs an Accessibility grant, Notification access and, optionally, one adb command (WRITE_SECURE_SETTINGS) so it can turn off the system pop-ups.
- Until step 1 is done, nothing appears on screen; demos today just say "Turn on step 1 first".
- Fit sliders move the real island live while you drag them.

## Capabilities and Constraints
- Native Android Views written in code (no XML layouts, no Compose, no Material library), minSdk 31, targetSdk 36.
- The island's priority order: ringing call > message > call in progress > timer > navigation/flashlight > music > download > idle.
- 21 demo styles (Demo.styles) and 15 feature switches (Prefs.groups) share the island's state in `Island`.
- Never force-stop the app: that switches off its accessibility service.

## Brand Commitments
- The name "Tab Island".
- The island is pure black (#000000) on the screen.

## Evidence on Hand
- `app/src/main/res/drawable-nodpi/demo_caller.jpg`: a stock caller photo (Unsplash licence), used only in demos.
- No real users, testimonials or store listing. Do not make any up.

## Product Principles
1. Show it working, don't describe it: every setting can be seen on the island straight away.
2. Honest state: say exactly what is missing from setup and how to fix it.
3. The island is the hero; the settings screen is its stage, not a second product.

## Accessibility & Inclusion
48dp touch targets, TalkBack labels on custom controls, text that scales with the system font size, and system Remove animations respected.
