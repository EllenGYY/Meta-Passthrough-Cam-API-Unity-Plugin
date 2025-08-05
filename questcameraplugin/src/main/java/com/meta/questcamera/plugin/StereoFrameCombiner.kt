/*
 * Quest Camera Plugin for Unity - Stereo Frame Combiner
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

import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.abs

class StereoFrameCombiner {
    companion object {
        private const val TAG = "StereoFrameCombiner"
        private const val SYNC_TOLERANCE_NS = 5_000_000L // 5ms
        private const val FRAME_WIDTH = 1280
        private const val FRAME_HEIGHT = 960
        private const val Y_SIZE = FRAME_WIDTH * FRAME_HEIGHT
        private const val UV_SIZE = Y_SIZE / 2
        private const val FRAME_SIZE = Y_SIZE + UV_SIZE
        private const val COMBINED_WIDTH = FRAME_WIDTH * 2
        private const val COMBINED_Y_SIZE = COMBINED_WIDTH * FRAME_HEIGHT
        private const val COMBINED_UV_SIZE = COMBINED_Y_SIZE / 2
        private const val COMBINED_FRAME_SIZE = COMBINED_Y_SIZE + COMBINED_UV_SIZE
    }
    
    data class FrameData(
        val data: ByteArray,
        val timestamp: Long,
        val intrinsics: FloatArray,
        val distortion: FloatArray,
        val pose: FloatArray
    )
    
    // Direct ByteBuffer for zero-copy operation
    private val combinedDirectBuffer = ByteBuffer.allocateDirect(COMBINED_FRAME_SIZE)
    private var combinedByteArray: ByteArray? = null
    
    private var leftFrameData: FrameData? = null
    private var rightFrameData: FrameData? = null
    private val frameLock = Object()
    
    // Buffer pool for memory reuse
    private val bufferPool = mutableListOf<ByteArray>()
    private val poolLock = Object()
    
    fun acquireBuffer(size: Int): ByteArray {
        synchronized(poolLock) {
            val buffer = bufferPool.removeFirstOrNull()
            return buffer?.takeIf { it.size == size } ?: ByteArray(size)
        }
    }
    
    fun releaseBuffer(buffer: ByteArray) {
        synchronized(poolLock) {
            if (bufferPool.size < 4) { // Keep max 4 buffers
                bufferPool.add(buffer)
            }
        }
    }
    
    fun onFrameAvailable(isLeft: Boolean, frameData: FrameData): ByteArray? {
        synchronized(frameLock) {
            if (isLeft) {
                // Release old left frame buffer if exists
                leftFrameData?.let { releaseBuffer(it.data) }
                leftFrameData = frameData
            } else {
                // Release old right frame buffer if exists
                rightFrameData?.let { releaseBuffer(it.data) }
                rightFrameData = frameData
            }
            
            val left = leftFrameData
            val right = rightFrameData
            
            if (left != null && right != null && 
                abs(left.timestamp - right.timestamp) < SYNC_TOLERANCE_NS) {
                
                // Combine frames
                val combined = combineFramesOptimized(left, right)
                
                // Clear references
                leftFrameData = null
                rightFrameData = null
                
                return combined
            }
            
            return null
        }
    }
    
    private fun combineFramesOptimized(left: FrameData, right: FrameData): ByteArray {
        // Reuse or allocate combined buffer
        if (combinedByteArray?.size != COMBINED_FRAME_SIZE) {
            combinedByteArray = ByteArray(COMBINED_FRAME_SIZE)
        }
        
        val combined = combinedByteArray!!
        
        // Copy Y planes side by side
        for (row in 0 until FRAME_HEIGHT) {
            val srcOffset = row * FRAME_WIDTH
            val dstOffset = row * COMBINED_WIDTH
            
            // Left Y
            System.arraycopy(left.data, srcOffset, combined, dstOffset, FRAME_WIDTH)
            // Right Y
            System.arraycopy(right.data, srcOffset, combined, dstOffset + FRAME_WIDTH, FRAME_WIDTH)
        }
        
        // Copy UV planes side by side
        val uvHeight = FRAME_HEIGHT / 2
        for (row in 0 until uvHeight) {
            val srcOffset = Y_SIZE + row * FRAME_WIDTH
            val dstOffset = COMBINED_Y_SIZE + row * COMBINED_WIDTH
            
            // Left UV
            System.arraycopy(left.data, srcOffset, combined, dstOffset, FRAME_WIDTH)
            // Right UV  
            System.arraycopy(right.data, srcOffset, combined, dstOffset + FRAME_WIDTH, FRAME_WIDTH)
        }
        
        // Create combined metadata
        val stereoMetadata = createStereoMetadata(left, right)
        
        // Call JNI callback
        QuestCameraPlugin.onStereoFrameAvailable(
            combined,
            COMBINED_WIDTH,
            FRAME_HEIGHT,
            left.timestamp,
            stereoMetadata
        )
        
        // Release source buffers back to pool
        releaseBuffer(left.data)
        releaseBuffer(right.data)
        
        return combined
    }
    
    private fun createStereoMetadata(left: FrameData, right: FrameData): FloatArray {
        val metadata = FloatArray(36) // Only camera metadata, no time diff or IPD
        
        // Left camera metadata (indices 0-17)
        System.arraycopy(left.intrinsics, 0, metadata, 0, 5)
        System.arraycopy(left.distortion, 0, metadata, 5, 6)
        System.arraycopy(left.pose, 0, metadata, 11, 7)
        
        // Right camera metadata (indices 18-35)
        System.arraycopy(right.intrinsics, 0, metadata, 18, 5)
        System.arraycopy(right.distortion, 0, metadata, 23, 6)
        System.arraycopy(right.pose, 0, metadata, 29, 7)
        
        return metadata
    }
    
    fun clear() {
        synchronized(frameLock) {
            leftFrameData?.let { releaseBuffer(it.data) }
            rightFrameData?.let { releaseBuffer(it.data) }
            leftFrameData = null
            rightFrameData = null
        }
        
        synchronized(poolLock) {
            bufferPool.clear()
        }
    }
}