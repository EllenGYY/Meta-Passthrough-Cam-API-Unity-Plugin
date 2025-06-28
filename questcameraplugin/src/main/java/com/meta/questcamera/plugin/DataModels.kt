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

enum class Position {
    Left,
    Right,
    Unknown;

    companion object {
        fun fromInt(value: Int?): Position =
            when (value) {
                0 -> Left
                1 -> Right
                else -> Unknown
            }
    }
}

data class CameraInfo(
    val id: String,
    val width: Int,
    val height: Int,
    val position: Position,
    val intrinsics: FloatArray,
    val distortion: FloatArray,
    val pose: FloatArray,
    val isPassthrough: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CameraInfo

        if (id != other.id) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (position != other.position) return false
        if (!intrinsics.contentEquals(other.intrinsics)) return false
        if (!distortion.contentEquals(other.distortion)) return false
        if (!pose.contentEquals(other.pose)) return false
        if (isPassthrough != other.isPassthrough) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + position.hashCode()
        result = 31 * result + intrinsics.contentHashCode()
        result = 31 * result + distortion.contentHashCode()
        result = 31 * result + pose.contentHashCode()
        result = 31 * result + isPassthrough.hashCode()
        return result
    }
}