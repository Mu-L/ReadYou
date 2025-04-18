package me.ash.reader.ui.page.home.reading

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalPullToSwitchArticle
import me.ash.reader.infrastructure.preference.LocalReadingAutoHideToolbar
import me.ash.reader.infrastructure.preference.LocalReadingBionicReading
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.infrastructure.preference.not
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.motion.materialSharedAxisY
import me.ash.reader.ui.page.home.HomeViewModel
import me.ash.reader.ui.page.home.Diff
import kotlin.math.abs

private const val UPWARD = 1
private const val DOWNWARD = -1

@OptIn(
    ExperimentalFoundationApi::class, ExperimentalMaterialApi::class
)
@Composable
fun ReadingPage(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    readingViewModel: ReadingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val isPullToSwitchArticleEnabled = LocalPullToSwitchArticle.current.value
    val readingUiState = readingViewModel.readingUiState.collectAsStateValue()
    val readerState = readingViewModel.readerStateStateFlow.collectAsStateValue()
    val homeUiState = homeViewModel.homeUiState.collectAsStateValue()
    val bionicReading = LocalReadingBionicReading.current

    var isReaderScrollingDown by remember { mutableStateOf(false) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }

    var currentImageData by remember { mutableStateOf(ImageData()) }

    val isShowToolBar = if (LocalReadingAutoHideToolbar.current.value) {
        readerState.articleId != null && !isReaderScrollingDown
    } else {
        true
    }

    var showTopDivider by remember {
        mutableStateOf(false)
    }

    var bringToTop by remember { mutableStateOf(false) }

    val pagingItems = homeUiState.pagingData.collectAsLazyPagingItems().itemSnapshotList

    LaunchedEffect(Unit) {
        navController.currentBackStackEntryFlow.collect {
            it.arguments?.getString("articleId")?.let { articleId ->
                if (readerState.articleId != articleId) {
                    readingViewModel.initData(articleId)
                }
            }
        }
    }

    LaunchedEffect(readerState.articleId, pagingItems.isNotEmpty()) {
        if (pagingItems.isNotEmpty() && readerState.articleId != null) {
//            Log.i("RLog", "ReadPage: ${readingUiState.articleWithFeed}")
            readingViewModel.prefetchArticleId(pagingItems)
            val article = readingUiState.articleWithFeed?.article
            if (article?.isUnread == true) {
                homeViewModel.diffMap[article.id] = Diff(false)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        content = { paddings ->
            Log.i("RLog", "TopBar: recomposition")

            Box(modifier = Modifier.fillMaxSize()) {
                if (readerState.articleId != null) {
                    TopBar(
                        navController = navController,
                        isShow = isShowToolBar,
                        isScrolled = showTopDivider,
                        title = readerState.title,
                        link = readerState.link,
                        onClick = { bringToTop = true },
                        onClose = {
                            navController.popBackStack()
                        },
                    )
                }

                val isNextArticleAvailable = !readerState.nextArticleId.isNullOrEmpty()
                val isPreviousArticleAvailable = !readerState.previousArticleId.isNullOrEmpty()


                if (readerState.articleId != null) {
                    // Content
                    AnimatedContent(
                        targetState = readerState,
                        contentKey = { it.articleId + it.content.text },
                        transitionSpec = {
                            val direction = when {
                                initialState.nextArticleId == targetState.articleId -> UPWARD
                                initialState.previousArticleId == targetState.articleId -> DOWNWARD
                                initialState.articleId == targetState.articleId -> {
                                    when (targetState.content) {
                                        is ReaderState.Description -> DOWNWARD
                                        else -> UPWARD
                                    }
                                }

                                else -> UPWARD
                            }
                            materialSharedAxisY(
                                initialOffsetY = { (it * 0.1f * direction).toInt() },
                                targetOffsetY = { (it * -0.1f * direction).toInt() },
                                durationMillis = 400
                            )
                        }, label = ""
                    ) {

                        remember { it }.run {
                            val state =
                                rememberPullToLoadState(
                                    key = content,
                                    onLoadNext = readingViewModel::loadNext,
                                    onLoadPrevious = readingViewModel::loadPrevious
                                )

                            val listState = rememberSaveable(
                                inputs = arrayOf(content),
                                saver = LazyListState.Saver
                            ) { LazyListState() }

                            val scrollState = rememberScrollState()

                            val scope = rememberCoroutineScope()

                            LaunchedEffect(bringToTop) {
                                if (bringToTop) {
                                    scope.launch {
                                        if (scrollState.value != 0) {
                                            scrollState.animateScrollTo(0)
                                        } else if (listState.firstVisibleItemIndex != 0) {
                                            listState.animateScrollToItem(0)
                                        }
                                    }.invokeOnCompletion { bringToTop = false }
                                }
                            }


                            showTopDivider = snapshotFlow {
                                scrollState.value >= 120 || listState.firstVisibleItemIndex != 0
                            }.collectAsStateValue(initial = false)

                            CompositionLocalProvider(
                                LocalOverscrollConfiguration provides
                                        if (isPullToSwitchArticleEnabled) null else LocalOverscrollConfiguration.current,
                                LocalTextStyle provides LocalTextStyle.current.run {
                                    merge(lineHeight = if (lineHeight.isSpecified) (lineHeight.value * LocalReadingTextLineHeight.current).sp else TextUnit.Unspecified)
                                }
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Content(
                                        modifier = Modifier
                                            .pullToLoad(
                                                state = state,
                                                density = LocalDensity.current,
                                                onScroll = { f ->
                                                    if (abs(f) > 2f)
                                                        isReaderScrollingDown = f < 0f
                                                },
                                                enabled = isPullToSwitchArticleEnabled
                                            ),
                                        contentPadding = paddings,
                                        content = content.text ?: "",
                                        feedName = feedName,
                                        title = title.toString(),
                                        author = author,
                                        link = link,
                                        publishedDate = publishedDate,
                                        isLoading = content is ReaderState.Loading,
                                        scrollState = scrollState,
                                        listState = listState,
                                        onImageClick = { imgUrl, altText ->
                                            currentImageData = ImageData(imgUrl, altText)
                                            showFullScreenImageViewer = true
                                        }
                                    )
                                    PullToLoadIndicator(
                                        state = state,
                                        canLoadPrevious = isPreviousArticleAvailable,
                                        canLoadNext = isNextArticleAvailable
                                    )
                                }
                            }
                        }
                    }
                }
                // Bottom Bar
                if (readerState.articleId != null) {
                    BottomBar(
                        isShow = isShowToolBar,
                        isUnread = homeViewModel.diffMap[readerState.articleId]?.isUnread
                            ?: readingUiState.isUnread, // fixme
                        isStarred = readingUiState.isStarred,
                        isNextArticleAvailable = isNextArticleAvailable,
                        isFullContent = readerState.content is ReaderState.FullContent,
                        isBionicReading = bionicReading.value,
                        onUnread = { isUnread ->
                            val id = readerState.articleId

                            with(homeViewModel.diffMap) {
                                if (contains(id)) remove(id)
                                else put(id, Diff(isUnread = isUnread))
                            }
                        },
                        onStarred = {
                            readingViewModel.updateStarredStatus(it)
                        },
                        onNextArticle = {
                            readingViewModel.loadNext()
                        },
                        onFullContent = {
                            if (it) readingViewModel.renderFullContent()
                            else readingViewModel.renderDescriptionContent()
                        },
                        onBionicReading = {
                            (!bionicReading).put(context, homeViewModel.viewModelScope)
                        },
                        onReadAloud = {
                            context.showToast(context.getString(R.string.coming_soon))
                        }
                    )
                }
            }
        }
    )
    if (showFullScreenImageViewer) {

        ReaderImageViewer(
            imageData = currentImageData,
            onDownloadImage = {
                readingViewModel.downloadImage(
                    it,
                    onSuccess = { context.showToast(context.getString(R.string.image_saved)) },
                    onFailure = {
                        // FIXME: crash the app for error report
                            th ->
                        throw th
                    }
                )
            },
            onDismissRequest = { showFullScreenImageViewer = false }
        )
    }
}
