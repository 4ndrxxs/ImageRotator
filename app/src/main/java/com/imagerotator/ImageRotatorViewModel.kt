package com.imagerotator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImageItem(
    val uri: Uri,
    val rotation: Int = 0 // 누적 회전: 0, 90, 180, 270
)

class ImageRotatorViewModel(application: Application) : AndroidViewModel(application) {

    val images = mutableStateListOf<ImageItem>()
    val isSaving = mutableStateOf(false)
    val progress = mutableFloatStateOf(0f)
    val showConfirmDialog = mutableStateOf(false)
    val resultMessage = mutableStateOf<String?>(null)
    val savedCount = mutableIntStateOf(0)

    fun addImages(uris: List<Uri>) {
        val existingUris = images.map { it.uri }.toSet()
        val newImages = uris.filter { it !in existingUris }.map { ImageItem(it) }
        images.addAll(newImages)
    }

    // 전체 일괄 회전
    fun rotateAllClockwise() {
        val updated = images.map { it.copy(rotation = (it.rotation + 90) % 360) }
        images.clear()
        images.addAll(updated)
    }

    fun rotateAllCounterClockwise() {
        val updated = images.map { it.copy(rotation = (it.rotation + 270) % 360) }
        images.clear()
        images.addAll(updated)
    }

    // 개별 이미지 탭하면 시계방향 90° 회전
    fun rotateSingle(index: Int) {
        if (index in images.indices) {
            val item = images[index]
            images[index] = item.copy(rotation = (item.rotation + 90) % 360)
        }
    }

    fun removeImage(index: Int) {
        if (index in images.indices) {
            images.removeAt(index)
        }
    }

    fun clearAll() {
        images.clear()
        progress.floatValue = 0f
        resultMessage.value = null
    }

    fun requestSave() {
        val rotatedCount = images.count { it.rotation != 0 }
        if (rotatedCount == 0) {
            resultMessage.value = "회전된 이미지가 없습니다"
            return
        }
        showConfirmDialog.value = true
    }

    fun confirmSave() {
        showConfirmDialog.value = false
        saveAll()
    }

    private fun saveAll() {
        val toProcess = images.filter { it.rotation != 0 }
        if (toProcess.isEmpty() || isSaving.value) return

        viewModelScope.launch {
            isSaving.value = true
            progress.floatValue = 0f
            resultMessage.value = null
            var successCount = 0
            var failCount = 0

            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver

                toProcess.forEachIndexed { index, item ->
                    try {
                        // 읽기
                        val inputStream = resolver.openInputStream(item.uri)
                            ?: throw Exception("읽기 실패")
                        val original = BitmapFactory.decodeStream(inputStream)
                        inputStream.close()

                        if (original == null) {
                            failCount++
                            return@forEachIndexed
                        }

                        // 회전
                        val matrix = Matrix().apply { postRotate(item.rotation.toFloat()) }
                        val rotated = Bitmap.createBitmap(
                            original, 0, 0,
                            original.width, original.height,
                            matrix, true
                        )

                        // 원본 URI에 덮어쓰기 ("wt" = write+truncate)
                        val outputStream = resolver.openOutputStream(item.uri, "wt")
                            ?: throw Exception("쓰기 실패")
                        rotated.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                        outputStream.flush()
                        outputStream.close()

                        // EXIF orientation 리셋
                        try {
                            resolver.openFileDescriptor(item.uri, "rw")?.use { pfd ->
                                val exif = ExifInterface(pfd.fileDescriptor)
                                exif.setAttribute(
                                    ExifInterface.TAG_ORIENTATION,
                                    ExifInterface.ORIENTATION_NORMAL.toString()
                                )
                                exif.saveAttributes()
                            }
                        } catch (_: Exception) { }

                        if (rotated !== original) original.recycle()
                        rotated.recycle()
                        successCount++
                    } catch (_: Exception) {
                        failCount++
                    }

                    withContext(Dispatchers.Main) {
                        progress.floatValue = (index + 1f) / toProcess.size
                    }
                }
            }

            isSaving.value = false
            savedCount.intValue = successCount

            resultMessage.value = when {
                failCount == 0 -> "${successCount}개 저장 완료!"
                successCount == 0 -> "저장 실패. 파일 접근 권한을 확인하세요."
                else -> "${successCount}개 성공, ${failCount}개 실패"
            }

            // 저장된 이미지 목록에서 제거 (완료된 것은 치워줌)
            if (successCount > 0) {
                val processedUris = toProcess.map { it.uri }.toSet()
                val remaining = images.filter { it.uri !in processedUris || it.rotation != 0 }
                images.clear()
                images.addAll(remaining)
            }
        }
    }
}
