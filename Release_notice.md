<!--
  RELEASE NOTICE - shown by the in-app updater.
  Edit this file BEFORE you run the Release workflow. Everything below this comment is what people
  see under "What's new" when they update. (This comment itself is never shown.)
  Supported: # headings, - bullets, 1. numbered lists, **bold**, *italic*, `code`, [links](url), > quotes.
-->

# SHARE64 1.2.0

## What's new

**A new look for the phone app**
- A floating bar at the bottom switches between **Send** and **Receive**.
- **Send**: a card shows the files you picked (open it to see them, remove one, or clear all). The devices around you are right below. Tap one to send.
- **Receive**: your device name with the sharing switch, a **Scan QR code** button, and the devices waiting for your OK, with Accept and Decline on each card.
- The QR code now opens from the bottom. A floating switch picks what it is for: **Via SHARE64** or **Share with anyone**.
- Files shared into SHARE64 from other apps wait on the Send tab until you pick a device.
- Settings stays at the top, as before.

**TV app**
- **Built-in file browser** made for the remote. Pick files from internal storage, USB drives and SD cards, even on TVs and Fire TV boxes that have no system file picker.
- **Share with anyone on the TV**: it shows a QR code, a phone scans it with its normal camera and downloads in the browser. Allow or Decline with the remote.

**Also**
- Release notes now come from `Release_notice.md` (the old misspelt name still works).
- New `docs/ARCHITECTURE.md` explains the modules, discovery and the wire protocol.

## Good to know

- On Android 11 and newer TVs, the file browser asks for "All files access" once.
- "Share with anyone" pages are not encrypted. Allow only people you know.
