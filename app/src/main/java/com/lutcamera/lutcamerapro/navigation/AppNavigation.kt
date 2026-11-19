package com.lutcamera.lutcamerapro.navigation

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lutcamera.lutcamerapro.ui.screens.CameraScreen
import com.lutcamera.lutcamerapro.model.BorderStateRepository
import com.lutcamera.lutcamerapro.ui.screens.LutLibraryScreen
import com.lutcamera.lutcamerapro.ui.screens.LutPreviewScreen
import com.lutcamera.lutcamerapro.ui.screens.SettingsScreen
import com.lutcamera.lutcamerapro.ui.screens.watermark.WatermarkScreen
import com.lutcamera.lutcamerapro.ui.theme.AppleTheme
import com.lutcamera.lutcamerapro.ui.screens.connect.nikon.NikonViewModel
import com.lutcamera.lutcamerapro.ui.screens.connect.wired.SaveMode
import com.lutcamera.lutcamerapro.ui.viewmodel.LutViewModel
import com.lutcamera.lutcamerapro.ui.viewmodel.SettingsViewModel

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val apple = AppleTheme.colors
    val lutViewModel: LutViewModel = viewModel()
    // Keep the connection and its settings alive across camera, LUT, border and settings tabs.
    val settingsViewModel: SettingsViewModel = viewModel()
    val nikonViewModel: NikonViewModel = viewModel()
    var selectedBorder by remember(context) {
        mutableStateOf(BorderStateRepository.loadBorderState(context))
    }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("lut_camera_border_prefs", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            selectedBorder = BorderStateRepository.loadBorderState(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Read these states in composition so updates also apply while the camera tab is hidden.
    val currentLut = lutViewModel.currentLutData
    val currentIntensity = lutViewModel.lutIntensity
    val currentXmp = lutViewModel.currentXmpData
    val currentBorder = selectedBorder
    val autoApplyLut = settingsViewModel.autoApplyLut
    val autoApplyBorder = settingsViewModel.autoApplyBorder
    val currentSaveMode = runCatching { SaveMode.valueOf(settingsViewModel.saveModeName) }.getOrDefault(SaveMode.DUAL)
    SideEffect {
        nikonViewModel.configurePhotoProcessing(
            context = context.applicationContext,
            lut = currentLut,
            intensity = currentIntensity,
            autoApplyLut = autoApplyLut,
            autoApplyBorder = autoApplyBorder,
            saveMode = currentSaveMode,
            borderState = currentBorder,
            xmp = currentXmp
        )
    }

    val navItems = listOf(
        BottomNavItem("相机", "camera", Icons.Default.CameraAlt, apple.accentGreen),
        BottomNavItem("调色", "lut_preview", Icons.Default.ColorLens, apple.accent),
        BottomNavItem("边框", "frame", Icons.Default.FilterFrames, apple.accentOrange),
        BottomNavItem("设置", "settings", Icons.Default.Settings, apple.accentOrange)
    )

    Scaffold(
        containerColor = apple.background,
        bottomBar = {
            // ?? 多邻国风格圆角实心描边底栏
                Surface(
                color = apple.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, apple.border, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination

                    navItems.forEach { item ->
                        val isSelected = currentDestination?.hierarchy?.any { it.route == item.route } == true

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) item.activeColor.copy(alpha = 0.15f) else Color.Transparent)
                                .then(
                                    if (isSelected) Modifier.border(2.dp, item.activeColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                    else Modifier
                                )
                                .clickable {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.title,
                                    tint = if (isSelected) item.activeColor else apple.textSecondary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.title,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                    color = if (isSelected) item.activeColor else apple.textSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "camera",
            modifier = Modifier.padding(innerPadding)
        ) {
            // 1. 相机工作台
                composable("camera") {
                CameraScreen(
                    lutViewModel = lutViewModel,
                    settingsViewModel = settingsViewModel,
                    nikonViewModel = nikonViewModel,
                    borderState = currentBorder
                )
            }

            // 2. LUT 调色工作台
                composable("lut_preview") {
                LutPreviewScreen(
                    viewModel = lutViewModel,
                    onNavigateToLibrary = { navController.navigate("lut_library") }
                )
            }

            // 3. LUT 滤镜库管理
                composable("lut_library") {
                LutLibraryScreen(
                    viewModel = lutViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // 4. 30 款专业边框装裱工坊 (直接对接 WatermarkScreen，消除 Screens.kt 冗余)
                composable("frame") {
                WatermarkScreen()
            }

            // 5. 系统设置
                composable("settings") {
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }
}

data class BottomNavItem(
    val title: String,
    val route: String,
    val icon: ImageVector,
    val activeColor: Color
)


