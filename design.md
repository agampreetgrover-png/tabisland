---
name: Tab Island
description: A true-black Dynamic Island for Android, and the black-and-basalt settings screen that is its fitting room.
colors:
  ink: "#000000"
  basalt: "#161618"
  seam: "#2A2A2E"
  track-grey: "#6E6E76"
  pumice: "#9E9EA6"
  bone: "#F4F4F1"
  white: "#FFFFFF"
  art-tile: "#2C2C2E"
  muted-white: "#FFFFFFB3"
  island-track: "#FFFFFF33"
  action-fill: "#FFFFFF26"
  press-ripple: "#FFFFFF24"
  signal-green: "#30D158"
  signal-orange: "#FF9F0A"
  signal-red: "#FF453A"
  signal-blue: "#2997FF"
  signal-yellow: "#FFD60A"
typography:
  display:
    fontFamily: "system-ui, sans-serif"
    fontSize: "30px"
    fontWeight: 600
    lineHeight: 1.15
    letterSpacing: "-0.02em"
  headline:
    fontFamily: "system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: 1.15
  title:
    fontFamily: "system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.15
  body:
    fontFamily: "system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.15
  label:
    fontFamily: "system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 500
    lineHeight: 1.15
  segment:
    fontFamily: "system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.15
  caption:
    fontFamily: "system-ui, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.15
  readout:
    fontFamily: "system-ui, sans-serif"
    fontSize: "13px"
    fontWeight: 500
    lineHeight: 1.15
    fontFeature: "tnum"
  island-name:
    fontFamily: "Google Sans, Roboto, sans-serif"
    fontSize: "21px"
    fontWeight: 600
    letterSpacing: "0.011em"
  island-title:
    fontFamily: "Google Sans, Roboto, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    letterSpacing: "-0.022em"
  island-body:
    fontFamily: "Google Sans, Roboto, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    letterSpacing: "-0.016em"
  island-compact:
    fontFamily: "Google Sans, Roboto, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    letterSpacing: "-0.016em"
  island-compact-value:
    fontFamily: "Google Sans, Roboto, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    letterSpacing: "-0.016em"
    fontFeature: "tnum"
  island-meta:
    fontFamily: "Google Sans, Roboto, sans-serif"
    fontSize: "13px"
    fontWeight: 400
  island-action:
    fontFamily: "Google Sans, Roboto, sans-serif"
    fontSize: "13px"
    fontWeight: 600
  island-time:
    fontFamily: "Google Sans, Roboto, sans-serif"
    fontSize: "12px"
    fontWeight: 400
    fontFeature: "tnum"
rounded:
  seek: "2px"
  art: "12px"
  switch: "16px"
  action: "18px"
  call-text: "22px"
  control: "24px"
  stage: "28px"
  island-card: "40px"
  full: "9999px"
spacing:
  hairline: "1px"
  track-inset: "4px"
  chip-gap: "8px"
  caption-gap: "10px"
  row-y: "12px"
  gutter: "16px"
  block: "24px"
  page-top: "28px"
  section: "36px"
components:
  group:
    backgroundColor: "{colors.basalt}"
    rounded: "{rounded.control}"
  list-row:
    textColor: "{colors.bone}"
    typography: "{typography.title}"
    padding: "12px 16px"
    height: "64px"
  switch-row:
    textColor: "{colors.bone}"
    typography: "{typography.title}"
    padding: "8px 16px"
    height: "56px"
  switch-track-on:
    backgroundColor: "{colors.bone}"
    rounded: "{rounded.switch}"
    width: "52px"
    height: "32px"
  switch-track-off:
    backgroundColor: "{colors.track-grey}"
    rounded: "{rounded.switch}"
    width: "52px"
    height: "32px"
  switch-thumb:
    backgroundColor: "{colors.ink}"
    rounded: "{rounded.full}"
    size: "24px"
  chip:
    backgroundColor: "{colors.basalt}"
    textColor: "{colors.bone}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "10px 18px"
    height: "48px"
  chip-playing:
    backgroundColor: "{colors.seam}"
    textColor: "{colors.bone}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "10px 18px"
    height: "48px"
  segment-track:
    backgroundColor: "{colors.basalt}"
    rounded: "{rounded.stage}"
    padding: "4px"
  segment:
    textColor: "{colors.pumice}"
    typography: "{typography.segment}"
    rounded: "{rounded.control}"
    height: "48px"
  segment-selected:
    backgroundColor: "{colors.bone}"
    textColor: "{colors.ink}"
    typography: "{typography.segment}"
    rounded: "{rounded.control}"
    height: "48px"
  stage:
    backgroundColor: "{colors.basalt}"
    rounded: "{rounded.stage}"
    height: "208px"
  step-mark:
    textColor: "{colors.pumice}"
    rounded: "{rounded.full}"
    size: "28px"
  step-mark-done:
    backgroundColor: "{colors.signal-green}"
    textColor: "{colors.ink}"
    rounded: "{rounded.full}"
    size: "28px"
  status-dot:
    rounded: "{rounded.full}"
    size: "8px"
  island-pill:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.white}"
    typography: "{typography.island-compact}"
    rounded: "{rounded.full}"
  island-card:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.white}"
    rounded: "{rounded.island-card}"
    padding: "0 16px"
  call-button-answer:
    backgroundColor: "{colors.signal-green}"
    textColor: "{colors.white}"
    rounded: "{rounded.full}"
    size: "44px"
  call-button-decline:
    backgroundColor: "{colors.signal-red}"
    textColor: "{colors.white}"
    rounded: "{rounded.full}"
    size: "44px"
  island-action-chip:
    backgroundColor: "{colors.action-fill}"
    textColor: "{colors.white}"
    typography: "{typography.island-action}"
    rounded: "{rounded.action}"
    padding: "0 10px"
    height: "36px"
---

# Design System: Tab Island

Units: this is a native Android app. In the tokens above, `px` stands for Android **dp** (lengths) and **sp** (text sizes), one to one. Colour alpha is written CSS-style (`#RRGGBBAA`); the Kotlin source writes the same values as `0xAARRGGBB`. Sources: `MainActivity.kt` (settings), `IslandView.kt` (the island), `Island.kt` (signal colours, priority), `Spring.kt` (motion).

## Overview

**Creative North Star: "The Fitting Room"**

Tab Island is one material in two places. On top of any app, it is a true-black pill grown out of the front camera, measured from the real cutout, that swells into cards for music, calls and messages and shrinks back into the hole on a spring. In the settings app, the same black becomes the page, and grey basalt slabs (each group "a small island") hold the switches. The settings screen is the island's fitting room: the real `IslandView` sits on a stage at 1:1 scale under a drawn camera ring, you drag it to fit, and every style plays on it from a chip.

The palette is monochrome on purpose. Black, basalt, bone and pumice do all the structural work; colour appears only as signal (green, orange, red, yellow, blue), each tied to a meaning, plus colour borrowed from content (album art, contact photos, an app's brand colour). Density is relaxed and thumb-first: 48dp chips and tabs, 56 to 64dp rows, a single column with a 16dp gutter.

Motion belongs to the island. It is the only thing that springs; the settings chrome moves only in short 150ms fades (the stage hint and the centre guide); chips swap in place and setup marks change without a pop. Every animation checks the system "Remove animations" setting.

**Key Characteristics:**
- True black (#000000) island and page, never tinted at rest, never shadowed.
- Basalt groups with inset seam hairlines instead of cards with shadows.
- Monochrome controls: bone is "on/selected", ink is the thumb, pumice is secondary.
- Pills everywhere: every tappable chip and tab is fully rounded.
- Spring motion for the island's size, scale-and-blur materialising for its content.
- Colour is signal or borrowed from content; never decoration.

## Colors

A monochrome black-to-bone scale with five semantic signal colours that each mean one thing.

### Primary
- **Bone** (#F4F4F1): the one bright tone in settings. Primary text, the selected segment's fill, the switch's on track, the slider's progress and thumb, the stage's centre guide when the island is centred on the camera. Warm off-white, so it reads softer than pure white against black.

### Secondary (signal colours, shared by both surfaces)
- **Signal Green** (#30D158): on, done, go. Granted setup step, "island is on" status dot, answer button, ringing-call ripple and caption, call chronometer, charging.
- **Signal Orange** (#FF9F0A): partial or time-bound. Status dot for "enabled but not finished", timers and stopwatch, alarm, vibrate mode.
- **Signal Red** (#FF453A): stop or warning. Decline/hang-up button, low battery, silent mode, status dot for "island is off".
- **Signal Yellow** (#FFD60A): the flashlight live activity only.
- **Signal Blue** (#2997FF): download progress ring, and the fallback tint of a notification card when the app gives no colour and its icon has none.

### Neutral
- **Ink** (#000000): the island, the settings page ground, the switch thumb, text on a selected segment, the check on a granted step.
- **Basalt** (#161618): groups, chips, the segmented control's track, the stage.
- **Seam** (#2A2A2E): 1px hairlines between rows in a group, inset 16dp from the start; the fill of the chip whose demo is playing.
- **Track Grey** (#6E6E76): the switch's off track (3.2:1 on basalt), the camera ring and the guide line when off-centre.
- **Pumice** (#9E9EA6): secondary text in settings (status, captions, hints, unselected tab labels), the numbered ring of an unfinished step. Chosen to keep at least 4.5:1 on basalt.
- **White** (#FFFFFF): all primary text and glyphs on the island.
- **Muted White** (#FFFFFFB3, white at 70%): secondary copy on the island (artist, call caption, app name, times). Translucent on purpose, so on a tinted card it takes the card's hue instead of sitting on it as grey.
- **Island Track** (#FFFFFF33): the unfilled part of the music seek bar.
- **Action Fill** (#FFFFFF26): the fill of notification action chips.
- **Art Tile** (#2C2C2E): the placeholder behind album art and round images while they load. Glyphs and app icons in the compact pill sit straight on black, without it.
- **Press Ripple** (#FFFFFF24): the ripple on every pressable settings surface.

### Borrowed colour (computed, not tokens)
- **Music accent:** the album art averaged to one pixel, brightened to HSV value at least 0.9. Colours the waveform and the seek bar fill. White when there is no art.
- **Notification card:** a top-to-bottom gradient from black (under the camera band) into the app's colour (`Notification.color`, else the icon's dominant colour, else Signal Blue), with saturation clamped to 0.45–0.85 and HSV value 0.22 then 0.48, so white text stays legible on yellow and green brands.
- **Card glow:** album art or caller photo, blurred 48dp at 60% opacity, under a black scrim gradient (20% → 67% → 90% black, top to bottom).

### Named Rules
**The True Black Rule.** The island is #000000 on screen, always (a brand commitment in PRODUCT.md). It may carry borrowed colour inside a card, but the pill itself is never tinted, lightened or given a border.

**The Signal Rule.** Each signal colour means one thing, on both surfaces. In settings, green appears only when something is on or done; a control is never green just to look active.

**The Monochrome Control Rule.** Settings controls are bone on basalt with ink thumbs. Selected and on are shown by bone fill, not by an accent colour.

## Typography

**Island Font:** Google Sans (requested by family name `google-sans`; falls back to Roboto where it does not exist)
**Settings Font:** the system default sans (`Typeface.DEFAULT`) at explicit weights
**Numerals:** tabular figures (`tnum`) wherever numbers change in place

**Character:** One quiet grotesque at three weights (400, 500, 600) and no display face. Hierarchy comes from size and weight steps, never from colour beyond bone/pumice or white/muted-white.

### Hierarchy (settings)
- **Display** (600, 30sp, -0.02em): the "Tab Island" title only. An accessibility heading.
- **Headline** (600, 17sp): section headings ("Set up", "What the island shows", "Fit to your camera"). 36dp above, 10dp below.
- **Title** (400, 16sp): switch and row labels. Step titles use the same size at 500.
- **Body** (400, 15sp): the status line beside the status dot.
- **Label** (500 for chips and slider readouts, 600 for row actions like "Allow", "Open", "Reset"; 15sp).
- **Segment** (600, 14sp): tab labels; at a large font size a label wraps to a second line rather than being cut.
- **Caption** (400, 13sp): hints and explanations in pumice. The stage readout (x/y/w/h) uses 500 with tabular figures; the step number uses 600.

All settings text uses a 1.15 line-height multiplier and scales with the system font size.

### Hierarchy (island)
Tracking is set by size: +0.011em at 20sp and up, -0.022em at 17–19sp, -0.016em at 14–16sp, none below.
- **Caller name** (600, 21sp): the incoming call card.
- **Card title** (600, 17sp): song title, notification sender (up to 2 lines).
- **Card body** (400, 15sp): artist (muted white), notification text (up to 2 lines).
- **Compact** (400, 14sp left label; 600, 14sp right value with tabular figures, in the item's signal colour): the pill's two halves, and the call caption.
- **Meta** (400, 13sp, muted white): the app name on a notification card.
- **Action** (600, 13sp): notification action chips.
- **Time** (400, 12sp, tabular, muted white): elapsed and remaining time on the music card.

Island text is single-line and ellipsized unless noted.

### Named Rules
**The Tabular Rule.** Any number that changes in place (chronometers, seek times, slider readouts, the x/y/w/h readout) uses tabular figures, so the pill and the readouts never jitter as digits change.

## Layout

**Settings** is a single scrolling column on ink, edge to edge: the window draws behind transparent system bars, the scroll view pads its top by the status bar and cutout inset, and content stops there instead of scrolling under the clock and the island. The column has a 16dp gutter (plus any side inset), 28dp above the title and 32dp plus the navigation inset at the bottom.

First viewport, top to bottom: title; status line (8dp dot, 8dp gap, pumice text); the stage (208dp tall, 18dp below); a caption row with the x/y/w/h readout (10dp below); the segmented control (one basalt track with a 4dp inset holding four segments at least 48dp tall, each its label's width plus an equal share of the rest, 24dp below); wrapping chips (at least 48dp tall, 8dp gaps both ways, 12dp below). The first switch group peeks in below the chips as a scroll cue. Then section headings with their groups: Set up (moves to the end once setup is complete), What the island shows, System events, Behaviour, Fit to your camera.

Inside a group, rows are 64dp minimum with 16dp × 12dp padding; switch rows are 56dp minimum with 16dp × 8dp padding; slider rows have 14dp top and 6dp bottom padding. Hint text sits outside groups with 16dp side padding and 10dp above.

**The stage** places the camera ring at the phone's real cutout size, 44dp from the stage top, horizontally where the real camera sits relative to the screen centre. The island is laid out exactly as the service lays it out on screen. A 1px vertical guide through the camera centre fades in (150ms) while a finger is on the stage.

**The island** is measured from the camera cutout: pill height = cutout height + 2 × 5dp (+ the user's h offset); the gap left for the camera = cutout width + 2 × 5dp; the idle pill is as wide as the larger of the pill height and the gap (+ the user's w offset); cards span the screen width minus 16dp, minus twice any horizontal camera offset. The compact pill keeps both halves equal width so the camera stays centred. Card content starts below the camera band: music rows ride up beside the camera (top padding = pill height − 10dp, at least 16dp); call and message rows start under it (pill height − 4dp, at least 20dp). Cards keep a design height (music 148dp, call 52dp, message 68dp below that padding) and grow when a large font needs it.

## Elevation & Depth

Flat. No surface in either place has a shadow. Depth is tonal: ink ground, basalt slabs, seam hairlines. The island deliberately has no drop shadow: its margin would create a touchable dead zone around cards where taps cannot reach the app below. Inside island cards, depth comes from content: a 48dp blur of the album art or caller photo at 60% opacity, under a black scrim, and the notification card's black-to-brand gradient. Blur (up to 16px) is also used in motion, never at rest on chrome.

### Named Rules
**The Flat Basalt Rule.** Settings surfaces separate by tone only (ink → basalt → seam). Never add a shadow, border or elevation to a group, chip or the stage.

## Shapes

Everything rounded, nothing boxy.

- **Pills:** chips, segments, the switch track, notification action chips and call text buttons all use a radius of half their height (24dp on 48dp, 16dp on 32dp, 18dp on 36dp, 22dp on 44dp). Circles: status dot, step mark, call buttons, contact photos, app badge.
- **Slabs:** groups 24dp, the stage 28dp. Both clip their children to the outline, so ripples and rows follow the corner.
- **The island:** a true pill when compact (radius = half the height); as it grows into a card the radius keeps rising with the height up to 40dp (or half the pill height if that is larger), so cards stay soft. Interpolated every frame from the spring's current height.
- **Concentric corners:** the icon in the compact pill is inset so it stays concentric with the pill's round end: its radius is the pill radius minus the inset (at least 6dp). Album art uses 12dp.
- **Hairlines:** 1px seams; the seek bar is 4dp tall with 2dp radius, centred in a 22dp touch strip.

### Named Rules
**The Concentric Rule.** Anything sitting in the island's round end takes the pill's radius minus its inset, never a fixed radius of its own.

## Components

### Buttons
Settings has no filled buttons. Actions are rows: a group row whose trailing word is the action ("Allow", "Copy", "Open", "Try", "Reset") in bone at 15sp/600. Pressed state is the white ripple (14%) clipped to the group's corners. Disabled rows (a child setting whose parent switch is off) drop to 40% opacity.

On the island:
- **Call buttons** (an answer/decline pair): 44dp circles, green answer with a handset glyph, red decline with the hang-up glyph, 12dp apart. The answer glyph rocks like a ringing handset: a 500ms burst of up to 14° every 1.8s.
- **Call text buttons** (any other count of actions): 44dp tall pills (22dp radius), 16dp side padding, 14sp/600, green unless the title reads as hang-up, decline, end, cancel or dismiss. They share the row and shrink when there are many, always leaving 96dp for the caller's name.
- **Media controls:** previous, play/pause, next as white glyphs in 64 × 48dp slots, centred, with the app badge (24dp circle) at the start.
- **Press feedback:** glyph buttons scale to 0.9 and 70% opacity in 80ms, release in 200ms; the whole pill scales to 0.97 in 90ms and back in 220ms.

### Chips
- **Style chips (settings):** basalt pill, bone 15sp/500 label, 18dp × 10dp padding, at least 48dp tall (taller when a large font needs it). Tapping one plays that style on the stage (and on the real island when it is running); while it plays, the chip fills with seam and takes a 1.5dp bone outline, cleared when the demo ends. They wrap like words in a flow layout, mirrored in right-to-left layouts, and swap in place on a tab change: only the island moves.
- **Action chips (island notification card):** up to three, sharing the row equally with 6dp gaps, 36dp tall, white text 13sp/600 on 15% white, 18dp radius. An action with only an icon shows the icon, tinted white.

### Cards / Containers
- **Group:** basalt, 24dp corners, no border or shadow, rows separated by 1px seam lines inset 16dp from the start.
- **Stage:** basalt, 28dp corners, 208dp tall; shows "Tap a style below to play it here." in pumice when the island has nothing to show (fades 150ms).
- **Island cards** (music, incoming call, message) grow out of the pill; see the signature component below.

### Inputs / Fields
- **Switch:** custom drawables. Track 52 × 32dp, 16dp radius, bone when on, track grey when off; thumb a 24dp ink circle inset 4dp, black on both tracks so it never sinks into the lighter off track. Label 16sp bone.
- **Slider:** label (16sp bone) and a signed readout ("+4 dp", 15sp/500 pumice, tabular) above a framework seek bar tinted bone for progress and thumb, pumice for the track. The bar has 12dp side padding so the thumb is whole at either end, pulled out by −12dp margins so the track lines up with the labels. TalkBack reads the readout, not a percentage. "Reset" sits at 40% and does nothing while every value is already 0.
- **Stage fitting:** drag moves x and y; pinch changes w. Values are clamped to each slider's range, and a clock-tick haptic fires when a value crosses or lands on 0 (centred on the camera) or hits either end. The centre guide turns bone when x is 0, track grey otherwise. With "Show the pill when idle" off, the stage still shows the idle pill at 40% opacity, so there is always something to drag.

### Navigation
- **Segmented tabs** (Calls, Messages, Live, System): one basalt track (28dp corners, 4dp inset) holding four segments with no gaps between them, at least 48dp tall, each as wide as its label (12dp side padding) plus an equal share of the spare width, so a long label never crowds its neighbour. Selected: a bone pill (24dp radius) with an ink label. Unselected: the track shows through, pumice label. 14sp/600. Announced to TalkBack as buttons with selected state.

### Setup Steps
A row with a 28dp step mark, a two-line title (16sp/500 bone) and subtitle (13sp pumice), and a trailing action. Unfinished: a 2dp pumice ring with the number inside, action in bone. Granted: a filled green circle with an ink check, action reads "On" in pumice. Once granted, the subtitle says what the step now does instead of what to do (step 3: "The island shows pop-ups instead of the system"). When step 3 is granted, the setup section also shows how to undo it before uninstalling: the command in monospace 13sp, in a group row with a "Copy" action. The status line's 8dp dot is red (off), orange (enabled but not running, or setup unfinished) or green (on).

### The Island (signature component)
A black pill anchored to the camera, sized by two springs (width and height), whose content materialises out of the hole.

- **Priority:** ringing call > message or system flash > call in progress > timer > navigation/flashlight > music > download progress > idle pill.
- **Compact anatomy:** [icon + label] [camera gap] [value | ring gauge | waveform | chronometer]. The left icon is 22dp (28dp for the unlock padlock); the right value is in the item's signal colour. The ring gauge is 18dp with a stroke of 16% of its size, its track at ~27% alpha, filling from empty over 700ms (150ms delay, decelerate). The waveform is four round-capped bars in a 20 × 14dp box, bouncing while music plays, flat at 15% when paused.
- **Music card:** 52dp art (12dp radius), title and artist, a waveform top right (level with the title, where the pill's waveform was), a seek row with times, and the controls row. Dragging down on the music pill pulls it open 1:1 with the finger; dragging up on the card closes it.
- **Incoming call card:** a 48dp round photo with two green rings rippling out of it every 1.8s, a muted caption above the 21sp name, and the call buttons. Hidden from screen sharing.
- **Message card:** 48dp round photo or icon, the sender (up to 2 lines) with the app name beside it, the message (up to 2 lines), then up to three action chips, on the black-to-brand gradient. Hidden from screen sharing. Flick up to dismiss.
- **Motion:** see the sidecar for exact values. Growing is bouncy (damping 0.8, about 1.5% overshoot); shrinking is critically damped (damping 1). Growing, the pill widens first and then drops; shrinking, it pulls up first and then narrows. Content fades in while scaling from 0.9 and unblurring from 14px over 240ms; the compact halves slide outward 14dp from the camera; card rows rise 8dp one after another (40ms apart). On the way out, content fades, shrinks to 0.94 and blurs to 16px in 120ms, and the pill waits 70ms before shrinking so it never closes over visible content. A drag hands the finger's velocity to the springs. Interruptions keep the current size and velocity.
- **Haptics:** a context click when the island grows, a long-press buzz only when there is an app to open, a confirm tick as the unlock padlock opens.

## Do's and Don'ts

### Do:
- **Do** keep the island and the settings ground at true black (#000000), and build settings structure from basalt slabs and seam hairlines only.
- **Do** make every chip and tab a full pill at 48dp tall, and every list row at least 56dp.
- **Do** use one meaning per signal colour: green on/done/answer/charging, orange timers and partial states, red decline and warnings, yellow flashlight, blue downloads and the notification fallback.
- **Do** show selection and "on" in bone, with ink text or thumbs on it.
- **Do** use tabular figures for every number that updates in place.
- **Do** keep content in the island concentric with the pill's round end, and start card text below the camera band.
- **Do** put new island motion on the springs (grow: responses 0.36s/0.46s, damping 0.8; shrink: 0.40s/0.30s, damping 1) and keep velocity across interruptions.
- **Do** check the system "Remove animations" setting: springs snap, blurs are skipped, the waveform holds still and the ripple rings disappear.
- **Do** give custom controls a TalkBack role and a state ("Calls, selected, button"; "+4 dp").

### Don't:
- **Don't** give the island a drop shadow, border or tint at rest; a shadow's margin becomes a dead touch zone over the app below.
- **Don't** add shadows or elevation to settings groups, chips or the stage.
- **Don't** colour a settings control with an accent to make it look active; green there means granted or running, nothing else.
- **Don't** use a grey switch thumb; it sinks into the lighter off track. The thumb stays ink on both tracks.
- **Don't** animate settings chrome with springs or long transitions; the island is the only thing that springs. Settings motion is limited to 150ms fades; chips and setup marks change in place.
- **Don't** let a long label push the camera off-centre in the compact pill; both halves stay equal width and the text ellipsizes.
