package com.imagerotator

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GalleryImage(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateModified: Long
)

data class RotateItem(
    val id: Long,
    val uri: Uri,
    val rotation: Int = 0
)

enum class AppScreen { GALLERY, ROTATE }

class ImageRotatorViewModel(application: Application) : AndroidViewModel(application) {

    // 갤러리 전체 이미지
    val allImages = mutableStateListOf<GalleryImage>()
    val isLoading = mutableStateOf(true)

    // 선택 상태
    val selectedIds = mutableStateOf(setOf<Long>())

    // 회전 화면
    val screen = mutableStateOf(AppScreen.GALLERY)
    val rotateItems = mutableStateListOf<RotateItem>()

    // 저장 상태
    val isSaving = mutableStateOf(false)
    val progress = mutableFloatStateOf(0f)
    val showConfirmDialog = mutableStateOf(false)
    val resultMessage = mutableStateOf<String?>(null)

    fun loadGalleryImages() {
        viewModelScope.launch {
            isLoading.value = true
            val images = withContext(Dispatchers.IO) { queryMediaStore() }
            allImages.clear()
            allImages.addAll(images)
            isLoading.value = false
        }
    }

    private fun queryMediaStore(): List<GalleryImage> {
        val resolver = getApplication<Application>().contentResolver
        val images = mutableListOf<GalleryImage>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        resolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: ""
                val date = cursor.getLong(dateCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                images.add(GalleryImage(id, uri, name, date))
            }
        }
        return images
    }

    // 선택 토글
    fun toggleSelection(id: Long) {
        val current = selectedIds.value.toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        selectedIds.value = current
    }

    fun selectAll() {
        selectedIds.value = allImages.map { it.id }.toSet()
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    // 선택 확정 → 회전 화면 전환
    fun enterRotateMode() {
        val selected = allImages.filter { it.id in selectedIds.value }
        rotateItems.clear()
        rotateItems.addAll(selected.map { RotateItem(it.id, it.uri) })
        screen.value = AppScreen.ROTATE
    }

    fun backToGallery() {
        screen.value = AppScreen.GALLERY
        rotateItems.clear()
    }

    // 회전
    fun rotateAllClockwise() {
        val updated = rotateItems.map { it.copy(rotation = (it.rotation + 90) % 360) }
        rotateItems.clear()
        rotateItems.addAll(updated)
    }

    fun rotateAllCounterClockwise() {
        val updated = rotateItems.map { it.copy(rotation = (it.rotation + 270) % 360) }
        rotateItems.clear()
        rotateItems.addAll(updated)
    }

    fun rotateSingle(index: Int) {
        if (index in rotateItems.indices) {
            val item = rotateItems[index]
            rotateItems[index] = item.copy(rotation = (item.rotation + 90) % 360)
        }
    }

    // 저장
    fun requestSave() {
        val rotatedCount = rotateItems.count { it.rotation != 0 }
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
        val toProcess = rotateItems.filter { it.rotation != 0 }
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

                        // API 29+: IS_PENDING 사용
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // 먼저 IS_PENDING=1로 설정
                            val cv = ContentValues().apply {
                                put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                            resolver.update(item.uri, cv, null, null)

                            // 쓰기
                            val out = resolver.openOutputStream(item.uri, "wt")
                                ?: throw Exception("쓰기 실패")
                            rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
                            out.flush()
                            out.close()

                            // IS_PENDING=0으로 해제
                            val cv2 = ContentValues().apply {
                                put(MediaStore.Images.Media.IS_PENDING, 0)
                            }
                            resolver.update(item.uri, cv2, null, null)
                        } else {
                            val out = resolver.openOutputStream(item.uri, "wt")
                                ?: throw Exception("쓰기 실패")
                            rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
                            out.flush()
                            out.close()
                        }

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

            resultMessage.value = when {
                failCount == 0 -> "${successCount}개 저장 완료!"
                successCount == 0 -> "저장 실패. 파일 접근 권한을 확인하세요."
                else -> "${successCount}개 성공, ${failCount}개 실패"
            }

            if (successCount > 0) {
                // 저장 완료 후 갤러리로 복귀
                selectedIds.value = emptySet()
                rotateItems.clear()
                screen.value = AppScreen.GALLERY
                loadGalleryImages() // 갤러리 새로고침
            }
        }
    }
}
