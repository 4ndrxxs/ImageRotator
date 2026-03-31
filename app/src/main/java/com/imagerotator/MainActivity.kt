package com.imagerotator

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1976D2),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFBBDEFB),
                    secondary = Color(0xFF455A64),
                    surface = Color(0xFFFAFAFA),
                    background = Color(0xFFF5F5F5)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ImageRotatorScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageRotatorScreen(viewModel: ImageRotatorViewModel = viewModel()) {
    val context = LocalContext.current
    val images = viewModel.images
    val isSaving by viewModel.isSaving
    val progress by viewModel.progress
    val showConfirmDialog by viewModel.showConfirmDialog
    val resultMessage by viewModel.resultMessage

    // OTA 업데이트 체크
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val currentVersionCode = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: PackageManager.NameNotFoundException) { 1 }

        val info = UpdateChecker.checkForUpdate(currentVersionCode)
        if (info != null) {
            updateInfo = info
            showUpdateDialog = true
        }
    }

    // 업데이트 다이얼로그
    if (showUpdateDialog && updateInfo != null) {
        val info = updateInfo!!
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("새 버전 v${info.versionName}") },
            text = {
                Text(
                    if (info.changelog.isNotEmpty()) info.changelog
                    else "새로운 버전이 있습니다. 업데이트 하시겠습니까?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    UpdateChecker.downloadAndInstall(context, info)
                    Toast.makeText(context, "다운로드 시작...", Toast.LENGTH_SHORT).show()
                }) {
                    Text("업데이트", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("나중에")
                }
            }
        )
    }

    // SAF 문서 선택기 - read/write 권한 자동 부여
    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) { }
            }
            viewModel.addImages(uris)
        }
    }

    // 결과 메시지 Toast
    LaunchedEffect(resultMessage) {
        resultMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    // 저장 확인 다이얼로그
    if (showConfirmDialog) {
        val rotatedCount = images.count { it.rotation != 0 }
        AlertDialog(
            onDismissRequest = { viewModel.showConfirmDialog.value = false },
            title = { Text("원본 덮어쓰기") },
            text = { Text("${rotatedCount}개 이미지를 회전하여 원본에 저장합니다.\n되돌릴 수 없습니다. 계속할까요?") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSave() }) {
                    Text("저장", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showConfirmDialog.value = false }) {
                    Text("취소")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("이미지 회전기", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    if (images.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearAll() }) {
                            Icon(Icons.Default.Delete, "전체 삭제",
                                tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 사진 선택 버튼
            Button(
                onClick = { docPickerLauncher.launch(arrayOf("image/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Icon(Icons.Default.PhotoLibrary, null, Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("사진 선택하기", fontSize = 16.sp)
            }

            if (images.isEmpty()) {
                // 빈 상태 안내
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PhotoLibrary, null,
                            Modifier.size(80.dp), tint = Color.LightGray)
                        Spacer(Modifier.height(16.dp))
                        Text("회전할 사진을 선택해주세요",
                            color = Color.Gray, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("사진을 탭하면 개별 회전됩니다",
                            color = Color.LightGray, fontSize = 13.sp)
                    }
                }
            } else {
                // 상태 표시
                val rotatedCount = images.count { it.rotation != 0 }
                Text(
                    buildString {
                        append("${images.size}개 선택됨")
                        if (rotatedCount > 0) append(" · ${rotatedCount}개 회전 대기")
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )

                // 이미지 그리드 - 탭하면 개별 90° 회전
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(images) { index, item ->
                        ImageThumbnail(
                            item = item,
                            onClick = { viewModel.rotateSingle(index) },
                            onRemove = { viewModel.removeImage(index) },
                            enabled = !isSaving
                        )
                    }
                }

                // 진행률
                if (isSaving) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "저장 중... ${(progress * 100).toInt()}%",
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp
                        )
                    }
                }

                // 하단 컨트롤
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(Modifier.padding(16.dp).animateContentSize()) {
                        // 일괄 회전 버튼
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.rotateAllCounterClockwise() },
                                modifier = Modifier.weight(1f),
                                enabled = !isSaving,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.RotateLeft, null)
                                Spacer(Modifier.width(4.dp))
                                Text("전체 -90°")
                            }
                            OutlinedButton(
                                onClick = { viewModel.rotateAllClockwise() },
                                modifier = Modifier.weight(1f),
                                enabled = !isSaving,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.RotateRight, null)
                                Spacer(Modifier.width(4.dp))
                                Text("전체 +90°")
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 저장 버튼
                        Button(
                            onClick = { viewModel.requestSave() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving && images.any { it.rotation != 0 },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(vertical = 14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50)
                            )
                        ) {
                            Icon(Icons.Default.Save, null)
                            Spacer(Modifier.width(8.dp))
                            Text("원본에 저장하기", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImageThumbnail(
    item: ImageItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean
) {
    val hasRotation = item.rotation != 0

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (hasRotation) Modifier.border(2.dp, Color(0xFF4CAF50), RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable(enabled = enabled) { onClick() }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.uri)
                .crossfade(true)
                .size(300)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 회전 각도 뱃지
        if (hasRotation) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("${item.rotation}°", color = Color.White,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 삭제 버튼
        if (enabled) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .padding(2.dp)
                    .background(Color(0x88000000), CircleShape)
            ) {
                Icon(Icons.Default.Close, "삭제",
                    tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}
