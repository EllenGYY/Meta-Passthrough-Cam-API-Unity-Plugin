# Quest Camera Plugin for Unity

A Unity plugin that provides access to Meta Quest's passthrough cameras, enabling developers to capture stereo camera frames with calibration data for AR/MR applications.

![No more WebcamTextures!](https://github.com/EllenGYY/Meta-Passthrough-Cam-API-Unity-Plugin/blob/main/Media/screenshot.jpg)

*No more WebcamTextures!*

You can download the `.aar` plugin from the release section if you don't want to build it from scratch. If you want to download the code and build it yourself, see the build section below.

## Features

- **Dual Camera Access**: Simultaneous access to left and right passthrough cameras
- **Single Camera Access**: Individual left or right camera access for improved performance
- **Stereo Frame Combining**: Automatic side-by-side stereo frame combining with synchronized timestamps
- **Real-time Streaming**: Live camera frame delivery with timestamps
- **Camera Calibration**: Intrinsic parameters, distortion coefficients, and pose data
- **NV12 Format**: Optimized YUV format for efficient processing
- **Native Performance**: JNI bridge for high-performance frame delivery
- **Thread-safe**: Proper threading for camera operations and image processing
- **Memory Optimized**: Buffer pooling and zero-copy operations for optimal performance

## Requirements

- Meta Quest 3 / 3s device (Build v74 or later)
- Android Studio Koala or newer (if you want to build it yourself)
- Unity Engine (I use Unity 6, but 5 should also work)

## Build

1. Clone the repository to your computer.
2. Open the project in Android Studio.
3. Run the Gradle task: `gradle questcameraplugin:assembleRelease`
4. Find the output `.aar` file in `questcameraplugin/build/outputs/aar`.

## Installation

1. Add the `.aar` plugin to your Unity project's `Plugins/Android` folder.
2. Add the required permissions and features to your Android manifest (also in `Plugins/Android`):

```xml
<!-- Permissions -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="horizonos.permission.HEADSET_CAMERA" />
```

## API Reference

### Core Methods

#### Initialize
```csharp
// Initialize the camera plugin with Android context
bool QuestCameraPlugin.nativeInitialize(AndroidJavaObject context)
```

#### Start Camera Streaming
```csharp
// Start dual camera capture
bool QuestCameraPlugin.nativeStartDualCamera()

// Start single camera capture (true = left eye, false = right eye)
bool QuestCameraPlugin.nativeStartSingleCamera(bool isLeft)
```

#### Stop Camera Streaming
```csharp
// Stop dual camera capture
void QuestCameraPlugin.nativeStopDualCamera()

// Stop single camera capture (true = left eye, false = right eye)
void QuestCameraPlugin.nativeStopSingleCamera(bool isLeft)
```

### Callback Setup

Set up callbacks to receive frame data from Unity:

```csharp
// Set callback for left camera frames
QuestCameraPlugin.setLeftFrameCallback(IntPtr callback)

// Set callback for right camera frames  
QuestCameraPlugin.setRightFrameCallback(IntPtr callback)

// Set callback for stereo combined frames (NEW)
QuestCameraPlugin.setStereoFrameCallback(IntPtr callback)

// Set callback for camera errors
QuestCameraPlugin.setErrorCallback(IntPtr callback)
```

### Frame Data Structure

#### Individual Camera Frames
Each camera frame includes:
- **Frame Data**: NV12 format byte array
- **Dimensions**: Width and height in pixels (1280x960 per eye)
- **Timestamp**: Frame capture timestamp (nanoseconds)
- **Intrinsics**: 5-element camera intrinsic parameters array `[fx, fy, cx, cy, s]`
- **Distortion**: 6-element distortion coefficients array
- **Pose**: 7-element pose array `[tx, ty, tz, qx, qy, qz, qw]` (translation + quaternion)

#### Stereo Combined Frames (NEW)
Combined stereo frames include:
- **Frame Data**: Side-by-side NV12 format (2560x960 total)
- **Dimensions**: Combined width (2x camera width) and height
- **Timestamp**: Left camera timestamp
- **Stereo Metadata**: 38-element array containing:
  - Elements 0-17: Left camera metadata (intrinsics, distortion, pose)
  - Elements 18-35: Right camera metadata (intrinsics, distortion, pose)
  - Element 36: Time difference between frames (milliseconds)
  - Element 37: Interpupillary distance (IPD) in meters

## Usage Example

```csharp
using UnityEngine;
using System;
using System.Runtime.InteropServices;

public class QuestCameraManager : MonoBehaviour
{
    // Callback delegates
    private delegate void FrameCallback(IntPtr frameData, int dataSize, int width, int height, 
                                       long timestamp, IntPtr intrinsics, IntPtr distortion, 
                                       IntPtr pose, bool isLeft);
    private delegate void StereoFrameCallback(IntPtr frameData, int dataSize, int width, int height,
                                             long timestamp, IntPtr stereoMetadata, int metadataSize);
    private delegate void ErrorCallback(IntPtr errorMessage);

    private FrameCallback frameCallback;
    private StereoFrameCallback stereoFrameCallback;
    private ErrorCallback errorCallback;

    void Start()
    {
        // Initialize callbacks
        frameCallback = OnFrameReceived;
        stereoFrameCallback = OnStereoFrameReceived;
        errorCallback = OnCameraError;
        
        // Get Unity activity context
        AndroidJavaClass unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
        AndroidJavaObject activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
        
        // Request permissions here (both standard and Horizon OS camera permissions must be granted)
        
        // Initialize plugin
        using (AndroidJavaClass pluginClass = new AndroidJavaClass("com.meta.questcamera.plugin.QuestCameraPlugin"))
        {
            bool initialized = pluginClass.CallStatic<bool>("nativeInitialize", activity);
            
            if (initialized)
            {
                // Set callbacks
                IntPtr leftPtr = Marshal.GetFunctionPointerForDelegate(frameCallback);
                IntPtr rightPtr = Marshal.GetFunctionPointerForDelegate(frameCallback);
                IntPtr stereoPtr = Marshal.GetFunctionPointerForDelegate(stereoFrameCallback);
                IntPtr errorPtr = Marshal.GetFunctionPointerForDelegate(errorCallback);
                
                pluginClass.CallStatic("setLeftFrameCallback", leftPtr.ToInt64());
                pluginClass.CallStatic("setRightFrameCallback", rightPtr.ToInt64());
                pluginClass.CallStatic("setStereoFrameCallback", stereoPtr.ToInt64());
                pluginClass.CallStatic("setErrorCallback", errorPtr.ToInt64());
                
                // Start camera streaming
                bool started = pluginClass.CallStatic<bool>("nativeStartDualCamera");
                Debug.Log($"Camera streaming started: {started}");
                
                // Alternative: Start single camera (left eye only)
                // bool started = pluginClass.CallStatic<bool>("nativeStartSingleCamera", true);
                // Debug.Log($"Left camera streaming started: {started}");
            }
        }
    }

    private void OnFrameReceived(IntPtr frameData, int dataSize, int width, int height,
                                long timestamp, IntPtr intrinsics, IntPtr distortion, 
                                IntPtr pose, bool isLeft)
    {
        // Copy frame data
        byte[] frameBytes = new byte[dataSize];
        Marshal.Copy(frameData, frameBytes, 0, dataSize);
        
        // Copy camera parameters
        float[] intrinsicsArray = new float[5];
        float[] distortionArray = new float[6];
        float[] poseArray = new float[7];
        
        Marshal.Copy(intrinsics, intrinsicsArray, 0, 5);
        Marshal.Copy(distortion, distortionArray, 0, 6);
        Marshal.Copy(pose, poseArray, 0, 7);
        
        // Process frame (convert NV12, apply to texture, etc.)
        ProcessCameraFrame(frameBytes, width, height, timestamp, 
                          intrinsicsArray, distortionArray, poseArray, isLeft);
    }

    private void OnStereoFrameReceived(IntPtr frameData, int dataSize, int width, int height,
                                      long timestamp, IntPtr stereoMetadata, int metadataSize)
    {
        // Copy stereo frame data (side-by-side format)
        byte[] frameBytes = new byte[dataSize];
        Marshal.Copy(frameData, frameBytes, 0, dataSize);
        
        // Copy stereo metadata (38 elements)
        float[] metadata = new float[metadataSize];
        Marshal.Copy(stereoMetadata, metadata, 0, metadataSize);
        
        // Process stereo frame
        ProcessStereoFrame(frameBytes, width, height, timestamp, metadata);
    }

    private void OnCameraError(IntPtr errorMessage)
    {
        string error = Marshal.PtrToStringAnsi(errorMessage);
        Debug.LogError($"Camera Error: {error}");
    }

    private void ProcessCameraFrame(byte[] frameData, int width, int height, long timestamp,
                                   float[] intrinsics, float[] distortion, float[] pose, bool isLeft)
    {
        // Your frame processing logic here
        // - Convert NV12 to RGB
        // - Apply to Unity texture
        // - Use calibration data for AR tracking
        // - etc.
        
        Debug.Log($"{(isLeft ? "Left" : "Right")} frame: {width}x{height}, " +
                 $"timestamp: {timestamp}, pose: [{pose[0]}, {pose[1]}, {pose[2]}]");
    }

    private void ProcessStereoFrame(byte[] frameData, int width, int height, long timestamp, float[] metadata)
    {
        // Your stereo frame processing logic here
        // - frameData contains side-by-side NV12 data (2560x960)
        // - metadata[0-17]: left camera parameters
        // - metadata[18-35]: right camera parameters  
        // - metadata[36]: time difference in ms
        // - metadata[37]: IPD in meters
        
        float timeDiff = metadata[36];
        float ipd = metadata[37];
        
        Debug.Log($"Stereo frame: {width}x{height}, timestamp: {timestamp}, " +
                 $"time diff: {timeDiff}ms, IPD: {ipd}m");
    }

    void OnDestroy()
    {
        // Stop camera streaming
        using (AndroidJavaClass pluginClass = new AndroidJavaClass("com.meta.questcamera.plugin.QuestCameraPlugin"))
        {
            pluginClass.CallStatic("nativeStopDualCamera");
            // Or if using single camera: pluginClass.CallStatic("nativeStopSingleCamera", true); // Stop left camera
        }
    }
}
```

## Single Camera vs Dual Camera

### Performance Comparison
- **Single Camera**: ~50% reduction in resource usage (memory, processing, battery)
- **Dual Camera**: Full stereo capture for advanced AR/VR applications

### When to Use Single Camera
- Object detection and tracking
- Basic AR overlays
- Computer vision applications that don't require stereo
- Battery-sensitive applications

### When to Use Dual Camera
- Stereo depth estimation
- 3D reconstruction
- Advanced SLAM/tracking
- Full AR/VR passthrough

## Stereo Frame Combining (NEW Feature)

The plugin now includes automatic stereo frame combining that synchronizes left and right camera frames and delivers them as side-by-side combined frames.

### Features
- **Automatic Synchronization**: Frames are matched based on timestamps with 5ms tolerance
- **Side-by-side Layout**: Left and right frames combined horizontally (2560x960 total)
- **Memory Optimized**: Buffer pooling and efficient memory management
- **Rich Metadata**: Combined metadata includes both camera parameters plus IPD and timing info

### Usage
When dual camera mode is active, you automatically receive both individual camera callbacks AND combined stereo callbacks:

```csharp
// Individual frames (original behavior)
OnFrameReceived(frameData, width=1280, height=960, ..., isLeft=true/false)

// Combined stereo frames (NEW)
OnStereoFrameReceived(frameData, width=2560, height=960, ..., stereoMetadata[38])
```

### Stereo Frame Layout
```
Combined Frame (2560x960):
[Left Y Plane][Right Y Plane]    <- Y data side-by-side
[Left UV Plane][Right UV Plane]  <- UV data side-by-side
```

### Enabling/Disabling Stereo Combining
Stereo combining is enabled by default. To disable it:

```csharp
// Get plugin instance and disable stereo combining (optional)
using (AndroidJavaClass pluginClass = new AndroidJavaClass("com.meta.questcamera.plugin.QuestCameraPlugin"))
{
    AndroidJavaObject instance = pluginClass.CallStatic<AndroidJavaObject>("getInstance");
    instance.Call("setStereoCombiningEnabled", false);
}
```

### Single Camera Usage Example
```csharp
// Start left camera only
bool leftStarted = pluginClass.CallStatic<bool>("nativeStartSingleCamera", true);

// Start right camera only  
bool rightStarted = pluginClass.CallStatic<bool>("nativeStartSingleCamera", false);

// Stop left camera
pluginClass.CallStatic("nativeStopSingleCamera", true);

// Stop right camera
pluginClass.CallStatic("nativeStopSingleCamera", false);
```

## Frame Format Details

### NV12 Layout
The plugin delivers frames in NV12 format:
- **Y Plane**: Luminance data (width x height bytes)
- **UV Plane**: Interleaved chroma data (width x height/2 bytes)
- **Total Size**: width x height x 1.5 bytes
- Camera resolution is typically 1280x960 per eye

### Camera Calibration Parameters

**Intrinsics Array (5 elements):**
- `[0]` fx: Focal length X
- `[1]` fy: Focal length Y  
- `[2]` cx: Principal point X
- `[3]` cy: Principal point Y
- `[4]` s: Skew parameter

**Distortion Array (6 elements):**
- `[0-2]` k1, k2, k3: Radial distortion coefficients
- `[3-4]` p1, p2: Tangential distortion coefficients  
- `[5]` k4: Additional radial distortion

**Pose Array (7 elements):**
- `[0-2]` tx, ty, tz: Translation (meters)
- `[3-6]` qx, qy, qz, qw: Rotation quaternion

## License

Licensed under the Apache License, Version 2.0. See the LICENSE file for details. I forked this from the original Meta-Passthrough-Camera-API-Samples repository as an Android Studio project base, but didn't use any code from them for the plugin.
