package com.imagerotator

import android.app.Application
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GalleryImage(
    val id: Long,
    val uri: Uri,
    val displayName: String
)

data class RotateItem(
    val id: Long,
    val uri: Uri,
    val rotation: Int = 0
)

enum class AppScreen { GALLERY, ROTATE }

class ImageRotatorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ImageRotator"
        private const val PAGE_SIZE = 80
    }

    val allImages = mutableStateListOf<GalleryImage>()
    val isLoading = mutableStateOf(false)
    val isLoadingMore = mutableStateOf(false)
    val hasMore = mutableStateOf(true)
    val errorText = mutableStateOf<String?>(null)

    private var currentOffset = 0

    val selectedIds = mutableStateOf(emptySet<Long>())

    val screen = mutableStateOf(AppScreen.GALLERY)
    val rotateItems = mutableStateListOf<RotateItem>()

    val isSaving = mutableStateOf(false)
    val progress = mutableFloatStateOf(0f)
    val showConfirmDialog = mutableStateOf(false)
    val resultMessage = mutableStateOf<String?>(null)

    fun loadGalleryImages() {
        if (isLoading.value) return
        viewModelScope.launch {
            try {
                isLoading.value = true
                errorText.value = null
                currentOffset = 0

                val images = withContext(Dispatchers.IO) {
                    queryPage(PAGE_SIZE, 0)
                }

                allImages.clear()
                allImages.addAll(images)
                currentOffset = images.size
                hasMore.value = images.size == PAGE_SIZE
                Log.d(TAG, "First page loaded: ${images.size} images")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "loadGalleryImages failed", e)
                errorText.value = "오류: ${e::class.simpleName}: ${e.message}"
            } finally {
                isLoading.value = false
            }
        }
    }

    fun loadMoreImages() {
        if (isLoadingMore.value || !hasMore.value || isLoading.value) return
        viewModelScope.launch {
            try {
                isLoadingMore.value = true
                val images = withContext(Dispatchers.IO) {
                    queryPage(PAGE_SIZE, currentOffset)
                }
                allImages.addAll(images)
                currentOffset += images.size
                hasMore.value = images.size == PAGE_SIZE
                Log.d(TAG, "More loaded: ${images.size}, total=${allImages.size}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "loadMoreImages failed", e)
            } finally {
                isLoadingMore.value = false
            }
        }
    }

    private fun queryPage(limit: Int, offset: Int): List<GalleryImage> {
        val resolver = getApplication<Application>().contentResolver
        val images = mutableListOf<GalleryImage>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )

        try {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+: Bundle 방식 - MediaStore에서 실제로 LIMIT/OFFSET 적용됨
                val queryArgs = Bundle().apply {
                    putStringArray(
                        android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(MediaStore.Images.Media.DATE_MODIFIED)
                    )
                    putInt(
                        android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                    )
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                    putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset)
                }
                resolver.query(collection, projection, queryArgs, null)
            } else {
                // API 24-25 fallback: sortOrder에 LIMIT 직접 추가
                val sortOrder =
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC LIMIT $limit OFFSET $offset"
                resolver.query(collection, projection, null, null, sortOrder)
            }

            cursor?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (idCol < 0) {
                    Log.e(TAG, "MediaStore: _ID column missing")
                    return images
                }
                while (c.moveToNext()) {
                    try {
                        val id = c.getLong(idCol)
                        val name = if (nameCol >= 0) (c.getString(nameCol) ?: "") else ""
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                        images.add(GalleryImage(id, uri, name))
                    } catch (e: Exception) {
                        Log.w(TAG, "Skip row: ${e.message}")
                    }
                }
            }
            Log.d(TAG, "queryPage($limit, $offset) => ${images.size} rows")
        } catch (e: Exception) {
            Log.e(TAG, "queryPage failed", e)
        }
        return images
    }

    fun toggleSelection(id: Long) {
        val current = selectedIds.value.toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        selectedIds.value = current.toSet()
    }

    fun selectAll() {
        selectedIds.value = allImages.map { it.id }.toSet()
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun enterRotateMode() {
        val ids = selectedIds.value
        val selected = allImages.filter { it.id in ids }
        rotateItems.clear()
        rotateItems.addAll(selected.map { RotateItem(it.id, it.uri) })
        screen.value = AppScreen.ROTATE
    }

    fun backToGallery() {
        screen.value = AppScreen.GALLERY
        rotateItems.clear()
    }

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
                        val inputStream = resolver.openInputStream(item.uri)
                            ?: throw Exception("읽기 실패")
                        val original = BitmapFactory.decodeStream(inputStream)
                        inputStream.close()

                        if (original == null) {
                            failCount++
                            return@forEachIndexed
                        }

                        val matrix = Matrix().apply { postRotate(item.rotation.toFloat()) }
                        val rotated = Bitmap.createBitmap(
                            original, 0, 0,
                            original.width, original.height,
                            matrix, true
                        )

                        val out = resolver.openOutputStream(item.uri, "wt")
                            ?: throw Exception("쓰기 실패")
                        rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        out.flush()
                        out.close()

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
                    } catch (e: Exception) {
                        Log.e(TAG, "Save failed ${item.uri}: ${e.message}")
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
                selectedIds.value = emptySet()
                rotateItems.clear()
                screen.value = AppScreen.GALLERY
                loadGalleryImages()
            }
        }
    }
}
