# Quest Camera Plugin for Unity

A Unity plugin that provides access to Meta Quest's passthrough cameras, enabling developers to capture stereo camera frames with calibration data for AR/MR applications.

![No more WebcamTextures!](https://github.com/EllenGYY/Meta-Passthrough-Cam-API-Unity-Plugin/blob/main/Media/screenshot.jpg)

*No more WebcamTextures!*

You can download the `.aar` plugin from the release section if you don't want to build it from scratch. If you want to download the code and build it yourself, see the build section below.

## Features

- **Dual Camera Access**: Simultaneous access to left and right passthrough cameras
- **Single Camera Access**: Individual left or right camera access for improved performance
- **Real-time Streaming**: Live camera frame delivery with timestamps
- **Camera Calibration**: Intrinsic parameters, distortion coefficients, and pose data
- **NV12 Format**: Optimized YUV format for efficient processing
- **Native Performance**: JNI bridge for high-performance frame delivery
- **Thread-safe**: Proper threading for camera operations and image processing

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

// Set callback for camera errors
QuestCameraPlugin.setErrorCallback(IntPtr callback)
```

### Frame Data Structure

Each camera frame includes:
- **Frame Data**: NV12 format byte array
- **Dimensions**: Width and height in pixels
- **Timestamp**: Frame capture timestamp (nanoseconds)
- **Intrinsics**: 5-element camera intrinsic parameters array `[fx, fy, cx, cy, s]`
- **Distortion**: 6-element distortion coefficients array
- **Pose**: 7-element pose array `[tx, ty, tz, qx, qy, qz, qw]` (translation + quaternion)

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
    private delegate void ErrorCallback(IntPtr errorMessage);

    private FrameCallback frameCallback;
    private ErrorCallback errorCallback;

    void Start()
    {
        // Initialize callbacks
        frameCallback = OnFrameReceived;
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
                IntPtr errorPtr = Marshal.GetFunctionPointerForDelegate(errorCallback);
                
                pluginClass.CallStatic("setLeftFrameCallback", leftPtr.ToInt64());
                pluginClass.CallStatic("setRightFrameCallback", rightPtr.ToInt64());
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
