# Hawkeye Viewer - Android

Professional borescope camera viewer for Android devices (Mobile & TV)

## Features

- ✅ USB UVC camera support
- ✅ Real-time camera preview
- ✅ Image capture (coming soon)
- ✅ Video recording (coming soon)
- ✅ Image controls (brightness, contrast, saturation, etc.)
- ✅ Multiple aspect ratios (16:9, 4:3, 1:1)
- ✅ Per-camera settings persistence
- ✅ Android TV optimized interface

## Requirements

- Android 7.0 (API 24) or higher
- USB OTG support (for USB cameras)
- Camera permission
- Storage permission (for saving images/videos)

## Build Variants

### Mobile
Standard Android phone/tablet interface
```bash
./gradlew assembleMobileDebug
```

### TV
Android TV optimized interface with leanback support
```bash
./gradlew assembleTvDebug
```

## Development Setup

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Kotlin 1.9.20+

### Opening in Android Studio

1. Open Android Studio
2. Select **File → Open**
3. Navigate to this directory and click **OK**
4. Wait for Gradle sync to complete
5. Connect your Android device or start an emulator
6. Click **Run** (green play button)

### USB Camera Testing

For testing with actual USB cameras:
1. Use a physical Android device (emulators don't support USB)
2. Connect camera via USB OTG adapter
3. Grant USB permission when prompted
4. Camera should appear in device list

## Project Structure

```
app/
├── src/
│   ├── main/           # Shared code (mobile + TV)
│   ├── mobile/         # Mobile-specific resources
│   └── tv/             # TV-specific resources
├── build.gradle.kts    # App-level build configuration
└── proguard-rules.pro  # ProGuard rules
```

## Dependencies

- **androidx.camera**: Camera2 API support
- **com.serenegiant:libuvccamera**: USB UVC camera library
- **androidx.leanback**: Android TV UI components

## Configuration

### Adding Your Camera's Vendor ID

Edit `app/src/main/res/xml/device_filter.xml` and add your camera's USB vendor/product ID:

```xml
<usb-device vendor-id="XXXX" product-id="XXXX" />
```

To find your camera's IDs:
1. Connect camera to computer
2. Use `lsusb` (Linux/Mac) or Device Manager (Windows)
3. Look for vendor and product IDs (format: XXXX:XXXX)

## Roadmap

- [ ] Implement image capture with filters applied
- [ ] Implement video recording
- [ ] Add zoom and pan controls
- [ ] Add measurement overlay/grid
- [ ] Add image rotation and flip
- [ ] Implement per-camera presets
- [ ] Add TV remote control support
- [ ] Implement gallery viewer

## License

© 2024 HawkeyeBorescopes. All rights reserved.

## Version

1.0.0
