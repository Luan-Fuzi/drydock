package dev.drydock.prototype

import android.app.Activity
import android.content.Intent
import java.io.File

/**
 * debug 构建专用的验收注入口（无视觉环境经 `am start --es` 驱动，night-b 等剧本使用）。
 * 只编进 debug 包；release 源集是空实现——发布包里不存在这些入口。
 */
object DebugHooks {
    const val WEBVIEW_DEBUGGING = true

    fun handle(activity: Activity, intent: Intent) {
        intent.getStringExtra("drydock_endpoint")?.takeIf { it.contains("|") }?.let { spec ->
            // D30 列表化：格式 "PROTOCOL|base_url|model|context[|provider|envvar[|output]]"，追加进端点列表
            val p = spec.split("|")
            runCatching { EndpointStore.Protocol.valueOf(p[0]) }.getOrNull()?.let { proto ->
                EndpointStore.add(activity, proto, p[1], p.getOrElse(2) { "" },
                    p.getOrElse(3) { "" }.trim().takeIf { it.isNotBlank() && it != "-" }?.toLongOrNull(),
                    p.getOrElse(6) { "" }.trim().takeIf { it.isNotBlank() && it != "-" }?.toLongOrNull(),
                    p.getOrElse(5) { "DRYDOCK_API_KEY" }, p.getOrElse(4) { "" })
            }
        }
        intent.getStringExtra("drydock_recipe")?.takeIf { it.isNotBlank() }?.let { ids ->
            val recipes = ids.split(",").mapNotNull { RecipeManager.byId(it.trim()) }
            Thread {
                val appCtx = activity.applicationContext
                if (!RootfsManager.isDeployed(appCtx)) {
                    RootfsManager.deploy(appCtx) { }
                    if (!RootfsManager.isDeployed(appCtx)) return@Thread
                }
                recipes.forEach { r ->
                    val res = RecipeManager.ensure(appCtx, r) { }
                    android.util.Log.i("DrydockRecipe", "ensure ${r.id} <${res.output.takeLast(300)}>")
                    if (res.output.contains("RECIPE_RC=0")) RecipeManager.markInstalled(appCtx, r.id)
                }
                android.util.Log.i("DrydockRecipe", "cfg <${RecipeManager.applyEndpointConfig(appCtx).output.takeLast(300)}>")
            }.start()
        }
        intent.getStringExtra("drydock_exec64")?.takeIf { it.isNotBlank() }?.let { b64 ->
            Thread {
                val cmd = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val r = RootfsManager.runInEnv(activity.applicationContext, cmd)
                try {
                    File(activity.applicationContext.filesDir, "exec-out.txt").writeText("EXEC_DONE exit=${r.exitCode}\n${r.output}")
                } catch (_: Exception) {
                }
                android.util.Log.i("DrydockExec", "EXEC_DONE exit=${r.exitCode} (full output in files/exec-out.txt)")
            }.start()
        }
        // 环境导出（夜批验收通道，产品入口在设置页「环境与备份」）
        if (intent.getStringExtra("drydock_export") != null) {
            Thread {
                val out = try {
                    "EXPORT_DONE " + RootfsManager.exportEnvTar(activity.applicationContext)
                } catch (e: Exception) {
                    "EXPORT_FAILED $e"
                }
                try {
                    File(activity.applicationContext.filesDir, "exec-out.txt").writeText(out)
                } catch (_: Exception) {
                }
                android.util.Log.i("DrydockExec", out.take(200))
            }.start()
        }
        // 环境救援转发（原 MainActivity 通道）：drydock_rescue → RescueActivity
        intent.getStringExtra("drydock_rescue")?.takeIf { it.isNotBlank() }?.let { rc ->
            activity.startActivity(
                android.content.Intent(activity, RescueActivity::class.java).putExtra("drydock_cmd", rc),
            )
        }
        // provider 全回路自测（写→读→改名→列举→删除），写→读经 activity.contentResolver 走
        // grant 免权限路径，与 DocumentsUI 同口径；结果落 files/exec-out.txt
        if (intent.getStringExtra("drydock_provider_test") != null) {
            Thread {
                val sb = StringBuilder()
                try {
                    val resolver = activity.contentResolver
                    val uri = android.provider.DocumentsContract.buildDocumentUri(
                        WorkspaceProvider.authority(activity), "/zz-provider-test.txt")
                    resolver.openOutputStream(uri, "w")!!.use { it.write("PROBE_WRITE_OK\n".toByteArray()) }
                    sb.append("write=ok\n")
                    val txt = resolver.openInputStream(uri)!!.bufferedReader().readText()
                    sb.append("read_ok=").append(txt.contains("PROBE_WRITE_OK")).append('\n')
                    val renamed = android.provider.DocumentsContract.renameDocument(
                        resolver, uri, "zz-provider-renamed.txt")
                    sb.append("rename_uri=").append(renamed != null).append('\n')
                    val kids = android.provider.DocumentsContract.buildChildDocumentsUri(
                        WorkspaceProvider.authority(activity), "/")
                    var listed = false
                    resolver.query(
                        kids, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null, null, null,
                    )?.use { c ->
                        while (c.moveToNext()) if (c.getString(0) == "zz-provider-renamed.txt") listed = true
                    }
                    sb.append("children_listed=").append(listed).append('\n')
                    android.provider.DocumentsContract.deleteDocument(resolver, renamed ?: uri)
                    val gone = !File(RootfsManager.rootfsDir(activity.applicationContext), "root/zz-provider-renamed.txt").exists() &&
                        !File(RootfsManager.rootfsDir(activity.applicationContext), "root/zz-provider-test.txt").exists()
                    sb.append("delete_gone=").append(gone).append('\n')
                    sb.append("PROVIDER_TEST_RC=0")
                } catch (e: Exception) {
                    sb.append("EXCEPTION ").append(e).append("\nPROVIDER_TEST_RC=1")
                }
                try {
                    File(activity.applicationContext.filesDir, "exec-out.txt").writeText(sb.toString())
                } catch (_: Exception) {
                }
                android.util.Log.i("DrydockExec", "provider selftest done")
            }.start()
        }
    }
}
