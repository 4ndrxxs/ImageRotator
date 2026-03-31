package com.imagerotator

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
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
                        AppRoot()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ImageRotator", "onCreate crash", e)
        }
    }
}

fun getImagePermission(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
}

fun hasImagePermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, getImagePermission()
    ) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val viewModel: ImageRotatorViewModel = viewModel()

    var permissionGranted by remember {
        mutableStateOf(hasImagePermission(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
    }

    // 권한이 있으면 로드 (권한 상태 변경 시 한 번만 실행)
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            try {
                viewModel.loadGalleryImages()
            } catch (e: Exception) {
                Log.e("ImageRotator", "loadGallery failed", e)
            }
        }
    }

    // OTA 업데이트
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        try {
            val versionCode = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }
            } catch (_: Exception) { 1 }

            val info = UpdateChecker.checkForUpdate(versionCode)
            if (info != null) {
                updateInfo = info
                showUpdateDialog = true
            }
        } catch (_: Exception) { }
    }

    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("새 버전 v${updateInfo!!.versionName}") },
            text = { Text(updateInfo!!.changelog.ifEmpty { "새로운 버전이 있습니다." }) },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    try {
                        UpdateChecker.downloadAndInstall(context, updateInfo!!)
                    } catch (_: Exception) { }
                }) { Text("업데이트", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("나중에") }
            }
        )
    }

    if (!permissionGranted) {
        PermissionScreen {
            permissionLauncher.launch(getImagePermission())
        }
    } else {
        val currentScreen by viewModel.screen
        when (currentScreen) {
            AppScreen.GALLERY -> GalleryScreen(viewModel)
            AppScreen.ROTATE -> RotateScreen(viewModel)
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PhotoLibrary, null, Modifier.size(80.dp), tint = Color.LightGray)
            Spacer(Modifier.height(16.dp))
            Text("사진 접근 권한이 필요합니다", fontSize = 16.sp, color = Color.Gray)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("권한 허용하기") }
        }
    }
}

// ━━━ 갤러리 화면 ━━━

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(vm: ImageRotatorViewModel) {
    val images = vm.allImages
    val selectedIds by vm.selectedIds
    val isLoading by vm.isLoading
    val isLoadingMore by vm.isLoadingMore
    val errorText by vm.errorText
    val selectedCount = selectedIds.size

    val gridState = rememberLazyGridState()

    // 스크롤 끝 감지 → 더 로드
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .collect { layoutInfo ->
                val total = layoutInfo.totalItemsCount
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (total > 0 && lastVisible >= total - 16) {
                    vm.loadMoreImages()
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedCount > 0) "${selectedCount}개 선택됨" else "이미지 회전기",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (selectedCount > 0) Color(0xFF455A64)
                    else MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                ),
                actions = {
                    if (selectedCount > 0) {
                        IconButton(onClick = {
                            if (selectedCount == images.size) vm.clearSelection()
                            else vm.selectAll()
                        }) {
                            Icon(
                                if (selectedCount == images.size) Icons.Default.DoneAll
                                else Icons.Default.Done,
                                "전체 선택", tint = Color.White
                            )
                        }
                        IconButton(onClick = { vm.clearSelection() }) {
                            Icon(Icons.Default.Close, "선택 해제", tint = Color.White)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = { vm.enterRotateMode() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.RotateRight, null)
                    Spacer(Modifier.width(8.dp))
                    Text("${selectedCount}개 회전하기")
                }
            }
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            errorText != null -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorText ?: "", color = Color.Red, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { vm.loadGalleryImages() }) {
                            Text("다시 시도")
                        }
                    }
                }
            }
            images.isEmpty() -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("사진이 없습니다", color = Color.Gray, fontSize = 16.sp)
                }
            }
            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(
                        items = images,
                        key = { it.id }
                    ) { image ->
                        GalleryThumbnail(
                            uri = image.uri,
                            isSelected = image.id in selectedIds,
                            onToggle = { vm.toggleSelection(image.id) }
                        )
                    }

                    // 하단 로딩 인디케이터
                    if (isLoadingMore) {
                        item(span = { GridItemSpan(4) }) {
                            Box(
                                Modifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GalleryThumbnail(
    uri: Uri,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (isSelected) Modifier.border(3.dp, Color(0xFF1976D2), RoundedCornerShape(4.dp))
                else Modifier
            )
            .clickable(onClick = onToggle)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(uri)
                .crossfade(true)
                .size(200)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .background(Color(0xFF1976D2), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ━━━ 회전/저장 화면 ━━━

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RotateScreen(vm: ImageRotatorViewModel) {
    val context = LocalContext.current
    val items = vm.rotateItems
    val isSaving by vm.isSaving
    val progress by vm.progress
    val showConfirmDialog by vm.showConfirmDialog
    val resultMessage by vm.resultMessage

    LaunchedEffect(resultMessage) {
        resultMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.resultMessage.value = null
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { vm.showConfirmDialog.value = false },
            title = { Text("원본 덮어쓰기") },
            text = {
                val cnt = items.count { it.rotation != 0 }
                Text("${cnt}개 이미지를 회전하여 원본에 저장합니다.\n되돌릴 수 없습니다.")
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmSave() }) {
                    Text("저장", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.showConfirmDialog.value = false }) { Text("취소") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${items.size}개 이미지 회전", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = { vm.backToGallery() }, enabled = !isSaving) {
                        Icon(Icons.Default.ArrowBack, "뒤로", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val rotatedCount = items.count { it.rotation != 0 }
            Text(
                "사진 탭 = 개별 90° 회전" +
                    if (rotatedCount > 0) " · ${rotatedCount}개 대기" else "",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(items) { index, item ->
                    RotateThumbnail(
                        uri = item.uri,
                        rotation = item.rotation,
                        onClick = { vm.rotateSingle(index) },
                        enabled = !isSaving
                    )
                }
            }

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
                        fontSize = 13.sp
                    )
                }
            }

            Surface(Modifier.fillMaxWidth(), shadowElevation = 8.dp) {
                Column(Modifier.padding(16.dp).animateContentSize()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { vm.rotateAllCounterClockwise() },
                            modifier = Modifier.weight(1f),
                            enabled = !isSaving,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.RotateLeft, null)
                            Spacer(Modifier.width(4.dp))
                            Text("전체 -90°")
                        }
                        OutlinedButton(
                            onClick = { vm.rotateAllClockwise() },
                            modifier = Modifier.weight(1f),
                            enabled = !isSaving,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.RotateRight, null)
                            Spacer(Modifier.width(4.dp))
                            Text("전체 +90°")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.requestSave() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving && items.any { it.rotation != 0 },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
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

@Composable
fun RotateThumbnail(
    uri: Uri,
    rotation: Int,
    onClick: () -> Unit,
    enabled: Boolean
) {
    val hasRotation = rotation != 0
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (hasRotation) Modifier.border(2.dp, Color(0xFF4CAF50), RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(uri)
                .crossfade(true)
                .size(300)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (hasRotation) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("${rotation}°", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
