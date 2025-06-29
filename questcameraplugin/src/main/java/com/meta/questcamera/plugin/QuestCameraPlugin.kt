/*
 * Quest Camera Plugin for Unity
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.meta.questcamera.plugin

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.concurrent.Executors

class QuestCameraPlugin private constructor() {
    companion object {
        private const val TAG = "QuestCameraPlugin"
        private const val IMAGE_BUFFER_SIZE = 3
        
        @JvmStatic
        val instance: QuestCameraPlugin by lazy { QuestCameraPlugin() }
        
        // JNI Methods - called from C++
        @JvmStatic
        external fun onLeftFrameAvailable(
            frameData: ByteArray,
            width: Int, 
            height: Int,
            timestamp: Long,
            intrinsics: FloatArray,
            distortion: FloatArray,
            pose: FloatArray
        )
        
        @JvmStatic
        external fun onRightFrameAvailable(
            frameData: ByteArray,
            width: Int,
            height: Int, 
            timestamp: Long,
            intrinsics: FloatArray,
            distortion: FloatArray,
            pose: FloatArray
        )
        
        @JvmStatic
        external fun onCameraError(errorMessage: String)
        
        // JNI callback setters - called from Unity
        @JvmStatic
        external fun setLeftFrameCallback(callback: Long)
        
        @JvmStatic
        external fun setRightFrameCallback(callback: Long)
        
        @JvmStatic
        external fun setErrorCallback(callback: Long)
        
        // Camera control methods - called from Unity via JNI
        @JvmStatic
        external fun nativeInitialize(context: Context): Boolean
        
        @JvmStatic
        external fun nativeStartDualCamera(): Boolean
        
        @JvmStatic
        external fun nativeStopDualCamera()
        
        @JvmStatic
        external fun nativeStartSingleCamera(isLeft: Boolean): Boolean
        
        @JvmStatic
        external fun nativeStopSingleCamera(isLeft: Boolean)
        
        init {
            try {
                System.loadLibrary("questcameraplugin")
                Log.d(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
            }
        }
    }
    
    private lateinit var context: Context
    private lateinit var cameraManager: CameraManager
    
    private var leftCamera: CameraDevice? = null
    private var rightCamera: CameraDevice? = null
    private var leftImageReader: ImageReader? = null
    private var rightImageReader: ImageReader? = null
    private var leftSession: CameraCaptureSession? = null
    private var rightSession: CameraCaptureSession? = null
    
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageThread = HandlerThread("ImageThread").apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    private val sessionExecutor = Executors.newSingleThreadExecutor()
    
    private var leftCameraInfo: CameraInfo? = null
    private var rightCameraInfo: CameraInfo? = null
    
    private var isLeftCameraActive = false
    private var isRightCameraActive = false
    
    // Called from JNI
    fun initialize(context: Context): Boolean {
        Log.d(TAG, "Initializing QuestCameraPlugin")
        this.context = context
        this.cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return discoverCameras()
    }
    
    // Called from JNI
    @RequiresPermission(Manifest.permission.CAMERA)
    fun startDualCamera(): Boolean {
        Log.d(TAG, "Starting dual camera")
        val leftInfo = leftCameraInfo ?: run {
            Log.e(TAG, "Left camera info not available")
            return false
        }
        val rightInfo = rightCameraInfo ?: run {
            Log.e(TAG, "Right camera info not available")
            return false
        }
        
        return try {
            val success = openCamera(leftInfo, true) && openCamera(rightInfo, false)
            if (success) {
                isLeftCameraActive = true
                isRightCameraActive = true
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start cameras: ${e.message}")
            onCameraError("Failed to start cameras: ${e.message}")
            false
        }
    }
    
    // Called from JNI
    fun stopDualCamera() {
        Log.d(TAG, "Stopping dual camera")
        
        leftSession?.stopRepeating()
        rightSession?.stopRepeating()
        leftSession?.close()
        rightSession?.close()
        leftCamera?.close()
        rightCamera?.close()
        leftImageReader?.close()
        rightImageReader?.close()
        
        leftSession = null
        rightSession = null
        leftCamera = null
        rightCamera = null
        leftImageReader = null
        rightImageReader = null
        
        isLeftCameraActive = false
        isRightCameraActive = false
    }
    
    // Called from JNI
    @RequiresPermission(Manifest.permission.CAMERA)
    fun startSingleCamera(isLeft: Boolean): Boolean {
        Log.d(TAG, "Starting ${if (isLeft) "left" else "right"} camera only")
        
        val cameraInfo = if (isLeft) {
            leftCameraInfo ?: run {
                Log.e(TAG, "Left camera info not available")
                return false
            }
        } else {
            rightCameraInfo ?: run {
                Log.e(TAG, "Right camera info not available")
                return false
            }
        }
        
        return try {
            val success = openCamera(cameraInfo, isLeft)
            if (success) {
                if (isLeft) {
                    isLeftCameraActive = true
                } else {
                    isRightCameraActive = true
                }
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ${if (isLeft) "left" else "right"} camera: ${e.message}")
            onCameraError("Failed to start ${if (isLeft) "left" else "right"} camera: ${e.message}")
            false
        }
    }
    
    // Called from JNI
    fun stopSingleCamera(isLeft: Boolean) {
        Log.d(TAG, "Stopping ${if (isLeft) "left" else "right"} camera")
        
        if (isLeft && isLeftCameraActive) {
            leftSession?.stopRepeating()
            leftSession?.close()
            leftCamera?.close()
            leftImageReader?.close()
            
            leftSession = null
            leftCamera = null
            leftImageReader = null
            isLeftCameraActive = false
            Log.d(TAG, "Left camera stopped")
        } else if (!isLeft && isRightCameraActive) {
            rightSession?.stopRepeating()
            rightSession?.close()
            rightCamera?.close()
            rightImageReader?.close()
            
            rightSession = null
            rightCamera = null
            rightImageReader = null
            isRightCameraActive = false
            Log.d(TAG, "Right camera stopped")
        } else {
            Log.w(TAG, "${if (isLeft) "Left" else "Right"} camera is not active, nothing to stop")
        }
    }
    
    private fun discoverCameras(): Boolean {
        Log.d(TAG, "Discovering cameras")
        try {
            cameraManager.cameraIdList.forEach { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val position = characteristics.get(KEY_POSITION)
                val cameraSource = characteristics.get(KEY_SOURCE)
                val intrinsics = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                val distortion = characteristics.get(CameraCharacteristics.LENS_DISTORTION)
                val poseTranslation = characteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
                val poseRotation = characteristics.get(CameraCharacteristics.LENS_POSE_ROTATION)
                
                if (pixelSize != null && position != null && cameraSource == CAMERA_SOURCE_PASSTHROUGH) {
                    // Combine translation and rotation into 7-element pose array [tx, ty, tz, qx, qy, qz, qw]
                    val combinedPose = FloatArray(7)
                    poseTranslation?.let { 
                        System.arraycopy(it, 0, combinedPose, 0, minOf(3, it.size))
                    }
                    poseRotation?.let { 
                        System.arraycopy(it, 0, combinedPose, 3, minOf(4, it.size))
                    }
                    
                    val cameraInfo = CameraInfo(
                        id = cameraId,
                        width = pixelSize.width,
                        height = pixelSize.height,
                        position = Position.fromInt(position),
                        intrinsics = intrinsics ?: FloatArray(5),
                        distortion = distortion ?: FloatArray(6),
                        pose = combinedPose,
                        isPassthrough = true
                    )
                    
                    when (Position.fromInt(position)) {
                        Position.Left -> {
                            leftCameraInfo = cameraInfo
                            Log.d(TAG, "Found left camera: $cameraId (${pixelSize.width}x${pixelSize.height})")
                        }
                        Position.Right -> {
                            rightCameraInfo = cameraInfo
                            Log.d(TAG, "Found right camera: $cameraId (${pixelSize.width}x${pixelSize.height})")
                        }
                        Position.Unknown -> {
                            Log.w(TAG, "Unknown camera position for $cameraId")
                        }
                    }
                }
            }
            
            val bothFound = leftCameraInfo != null && rightCameraInfo != null
            Log.d(TAG, "Camera discovery complete. Both cameras found: $bothFound")
            return bothFound
        } catch (e: Exception) {
            Log.e(TAG, "Camera discovery failed: ${e.message}")
            onCameraError("Camera discovery failed: ${e.message}")
            return false
        }
    }
    
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera(cameraInfo: CameraInfo, isLeft: Boolean): Boolean {
        Log.d(TAG, "Opening ${if (isLeft) "left" else "right"} camera: ${cameraInfo.id}")
        
        val imageReader = ImageReader.newInstance(
            cameraInfo.width, 
            cameraInfo.height, 
            ImageFormat.YUV_420_888, 
            IMAGE_BUFFER_SIZE
        )
        
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            image?.let {
                processImage(it, cameraInfo, isLeft)
                it.close()
            }
        }, imageHandler)
        
        if (isLeft) {
            leftImageReader = imageReader
        } else {
            rightImageReader = imageReader
        }
        
        try {
            cameraManager.openCamera(cameraInfo.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "${if (isLeft) "Left" else "Right"} camera opened: ${cameraInfo.id}")
                    if (isLeft) {
                        leftCamera = camera
                    } else {
                        rightCamera = camera
                    }
                    createCaptureSession(camera, imageReader, isLeft, cameraInfo.id)
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera ${cameraInfo.id} disconnected")
                    camera.close()
                    if (isLeft) {
                        leftCamera = null
                    } else {
                        rightCamera = null
                    }
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    val errorMsg = "Camera ${cameraInfo.id} error: $error"
                    Log.e(TAG, errorMsg)
                    onCameraError(errorMsg)
                    camera.close()
                    if (isLeft) {
                        leftCamera = null
                    } else {
                        rightCamera = null
                    }
                }
            }, cameraHandler)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera ${cameraInfo.id}: ${e.message}")
            onCameraError("Failed to open camera ${cameraInfo.id}: ${e.message}")
            return false
        }
    }
    
    private fun createCaptureSession(camera: CameraDevice, imageReader: ImageReader, isLeft: Boolean, cameraId: String) {
        Log.d(TAG, "Creating capture session for ${if (isLeft) "left" else "right"} camera")
        
        val outputConfig = OutputConfiguration(imageReader.surface)
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(outputConfig),
            sessionExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Capture session configured for ${if (isLeft) "left" else "right"} camera")
                    if (isLeft) {
                        leftSession = session
                    } else {
                        rightSession = session
                    }
                    startRepeatingRequest(camera, imageReader.surface)
                }
                
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val errorMsg = "Session configuration failed for ${if (isLeft) "left" else "right"} camera"
                    Log.e(TAG, errorMsg)
                    onCameraError(errorMsg)
                }
            }
        )
        
        camera.createCaptureSession(sessionConfig)
    }
    
    private fun startRepeatingRequest(camera: CameraDevice, surface: android.view.Surface) {
        Log.d(TAG, "Starting repeating request")
        
        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
        }
        
        val session = if (camera == leftCamera) leftSession else rightSession
        session?.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
    }
    
    private fun processImage(image: Image, cameraInfo: CameraInfo, isLeft: Boolean) {
        try {
            val planes = image.planes
            val yPlane = planes[0]
            val uvPlane1 = planes[1]  // Interleaved UV data (mostly complete)
            val uvPlane2 = planes[2]  // Contains the last V byte
            
            val width = cameraInfo.width
            val height = cameraInfo.height
            val uvPixelStride = uvPlane1.pixelStride
            
            // Get buffer sizes
            val ySize = yPlane.buffer.remaining()
            val uv1Size = uvPlane1.buffer.remaining()
            val uv2Size = uvPlane2.buffer.remaining()
            
            // Complete NV12: Y + UV plane 1 + last byte from UV plane 2
            val frameData = ByteArray(ySize + uv1Size + 1)  // +1 for the missing byte
            
            Log.d(TAG, "${if (isLeft) "LEFT" else "RIGHT"} Camera: ${width}x${height}")
            Log.d(TAG, "Y: $ySize, UV1: $uv1Size, UV2: $uv2Size, Total: ${frameData.size}")
            
            // Copy Y plane
            yPlane.buffer.get(frameData, 0, ySize)
            
            // Copy UV plane 1 (interleaved UVUVUV... but missing last V)
            uvPlane1.buffer.get(frameData, ySize, uv1Size)
            
            // Append the LAST V byte from plane 2
            if (uv2Size > 0) {
                val uv2Buffer = uvPlane2.buffer
                val lastVByte = uv2Buffer.get(uv2Size - 1)  // THE LAST BYTE
                frameData[ySize + uv1Size] = lastVByte
                Log.d(TAG, "Added LAST V byte: $lastVByte from plane 2 (position ${uv2Size - 1})")
            }
            
            if (isLeft) {
                onLeftFrameAvailable(
                    frameData,
                    width,
                    height,
                    image.timestamp,
                    cameraInfo.intrinsics,
                    cameraInfo.distortion,
                    cameraInfo.pose
                )
            } else {
                onRightFrameAvailable(
                    frameData,
                    width,
                    height,
                    image.timestamp,
                    cameraInfo.intrinsics,
                    cameraInfo.distortion,
                    cameraInfo.pose
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing ${if (isLeft) "LEFT" else "RIGHT"} image: ${e.message}")
            e.printStackTrace()
        }
    }
    

}