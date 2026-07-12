package life.fxs.purr.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext

@Composable
fun PurrAvatar(
    avatarUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (avatarUrl.isNullOrBlank()) {
                AvatarPlaceholder(contentDescription)
            } else {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(avatarUrl)
                        .size(256)
                        .crossfade(true)
                        .build(),
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                ) {
                    when (painter.state) {
                        is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                        is AsyncImagePainter.State.Loading -> CircularProgressIndicator(
                            modifier = Modifier.size(size * 0.3f),
                            strokeWidth = 2.dp,
                        )
                        else -> AvatarPlaceholder(contentDescription)
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarPlaceholder(contentDescription: String) {
    Icon(
        imageVector = Icons.Default.Person,
        contentDescription = contentDescription,
        modifier = Modifier.fillMaxSize(0.48f),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
