package com.tiktokvpn.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tiktokvpn.app.R
import com.tiktokvpn.app.core.ConnectionState
import com.tiktokvpn.app.core.TunnelPhase
import com.tiktokvpn.app.core.statusText
import com.tiktokvpn.app.ui.AppViewModel
import com.tiktokvpn.app.ui.components.CreditLine
import com.tiktokvpn.app.ui.theme.Cyan
import com.tiktokvpn.app.ui.theme.Ink
import com.tiktokvpn.app.ui.theme.MutedText
import com.tiktokvpn.app.ui.theme.Panel
import com.tiktokvpn.app.ui.theme.Pink
import com.tiktokvpn.app.ui.theme.PanelRaised
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onSettings: () -> Unit,
    onAbout: () -> Unit
) {
    val context = LocalContext.current
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val endpoint by viewModel.endpoint.collectAsStateWithLifecycle()
    val optimizing by viewModel.optimizing.collectAsStateWithLifecycle()

    val connected = connection.connected
    val tunnelUp = connection.phase.tunnelUp
    val status = statusText(context, connection)
    val statusColor = when (connection.phase) {
        TunnelPhase.Connected -> Cyan
        TunnelPhase.Failed -> Pink
        else -> MaterialTheme.colorScheme.onBackground
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Cyan
            )
            Row {
                IconButton(onClick = onAbout) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.nav_about),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.nav_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleLarge,
                    color = statusColor
                )
                if (connection.phase == TunnelPhase.Failed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = connection.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            ConnectButton(
                connected = connected,
                busy = connection.phase.busy && !optimizing,
                enabled = !optimizing && connection.phase != TunnelPhase.Disconnecting,
                onClick = viewModel::toggleConnection
            )

            if (connected) {
                StatsRow(connection = connection)
            }

            RouteCard(
                viewModel = viewModel,
                warpInWarp = settings.warpInWarp,
                endpoint = connection.endpoint.ifBlank { endpoint?.endpoint.orEmpty() },
                tunnelUp = tunnelUp,
                optimizing = optimizing,
                busy = connection.phase.busy
            )
        }

        CreditLine()
    }
}

@Composable
private fun ConnectButton(
    connected: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (connected) Cyan else PanelRaised,
        label = "connectBackground"
    )
    val ringAlpha = if (busy) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "ringAlpha"
        ).value
    } else {
        0.5f
    }
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(224.dp)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = Cyan.copy(alpha = ringAlpha),
                    shape = CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(196.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(196.dp),
                    color = if (connected) Ink else Cyan,
                    strokeWidth = 3.dp
                )
            }
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = if (connected) {
                    stringResource(R.string.action_disconnect)
                } else {
                    stringResource(R.string.action_connect)
                },
                tint = if (connected) Color.Black else Cyan,
                modifier = Modifier.size(78.dp)
            )
        }
    }
}

@Composable
private fun StatsRow(connection: ConnectionState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.stat_download),
            value = formatBytes(connection.rxBytes)
        )
        StatCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.stat_upload),
            value = formatBytes(connection.txBytes)
        )
    }
}

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String) {
    Surface(
        modifier = modifier,
        color = Panel,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MutedText
            )
        }
    }
}

@Composable
private fun RouteCard(
    viewModel: AppViewModel,
    warpInWarp: Boolean,
    endpoint: String,
    tunnelUp: Boolean,
    optimizing: Boolean,
    busy: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.warp_in_warp_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.warp_in_warp_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = warpInWarp,
                    onCheckedChange = viewModel::setWarpInWarp,
                    enabled = !tunnelUp
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = MaterialTheme.colorScheme.outline
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.stat_route),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = endpoint.ifBlank { stringResource(R.string.route_not_set) },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (endpoint.isBlank()) MutedText else Cyan,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = viewModel::optimize,
                    enabled = !busy && !optimizing
                ) {
                    if (optimizing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = if (optimizing) {
                            stringResource(R.string.action_optimizing)
                        } else {
                            stringResource(R.string.action_optimize)
                        }
                    )
                }
            }
        }
    }
}

private fun formatBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    if (value < 1024L) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = value.toDouble() / 1024.0
    var index = 0
    while (size >= 1024.0 && index < units.lastIndex) {
        size /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", size, units[index])
}
