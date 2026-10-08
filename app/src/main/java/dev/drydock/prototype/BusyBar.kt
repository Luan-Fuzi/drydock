package dev.drydock.prototype

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 长操作统一进度反馈（R11 用户定调）：busy 文字行紧邻一条小进度条（3dp 高，
 *  indeterminate 从左到右滚动动画，无进度数字）；不弹独立对话框。新建/接回/
 *  关闭会话、部署、导出、镜像应用、表单保存、向导安装等接线点共用。 */
@Composable
internal fun BusyBar(text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .height(3.dp),
        )
    }
}
