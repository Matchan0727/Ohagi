package jp.simplespace.discord.commands.audio

import com.jagrosh.jdautilities.command.SlashCommand
import com.jagrosh.jdautilities.command.SlashCommandEvent
import dev.minn.jda.ktx.interactions.components.FileDisplay
import jp.simplespace.audio.RvcApiClient
import jp.simplespace.discord.utils.simpleTitleAndDescriptionContainer
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.utils.FileUpload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.dv8tion.jda.api.interactions.commands.Command
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.awt.Color
import kotlin.io.path.outputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.nameWithoutExtension

class RvcCommand(private val rvcApiClient: RvcApiClient) : SlashCommand() {
    init {
        name = "rvc"
        help = "RVC/UVRの音声処理を行います"
        children = arrayOf(
            ModelsCommand(rvcApiClient),
            SeparateCommand(rvcApiClient),
            ConvertCommand(rvcApiClient),
            CoverCommand(rvcApiClient),
        )
    }

    override fun execute(event: SlashCommandEvent) {}
}

private abstract class RvcChildCommand(
    protected val rvcApiClient: RvcApiClient,
) : SlashCommand() {

    protected fun requireAttachment(event: SlashCommandEvent): Message.Attachment {
        return event.optAttachment("file")
            ?: error("file option is required")
    }

    protected fun downloadAttachmentToTempFile(attachment: Message.Attachment): Path {
        val suffix = attachment.fileName.substringAfterLast('.', "")
        val tempFile = if (suffix.isBlank()) {
            Files.createTempFile("rvc-", "-upload")
        } else {
            Files.createTempFile("rvc-", ".${suffix}")
        }

        java.net.URI.create(attachment.url).toURL().openStream().use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        tempFile.toFile().deleteOnExit()
        return tempFile
    }

    protected fun withTempAttachment(
        event: SlashCommandEvent,
        block: (Path, Message.Attachment) -> Unit,
    ) {
        val attachment = requireAttachment(event)
        val file = downloadAttachmentToTempFile(attachment)
        try {
            block(file, attachment)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    protected fun deferAndQueue(
        event: SlashCommandEvent,
        block: (InteractionHook) -> Unit,
    ) {
        event.deferReply().queue { hook ->
            block(hook)
        }
    }

    protected fun sendError(event: SlashCommandEvent, message: String) {
        event.replyComponents(errorContainer(message))
            .useComponentsV2()
            .setEphemeral(true)
            .queue()
    }

    protected fun replyText(hook: InteractionHook, title: String, description: String, color: Color = Color(0x36B8FA)) {
        hook.editOriginalComponents(simpleTitleAndDescriptionContainer(title, description, color))
            .useComponentsV2()
            .queue()
    }

    protected fun replyFile(hook: InteractionHook, title: String, description: String, file: ByteArray, fileName: String) {
        hook.editOriginal("## $title\n$description")
            .setAttachments(FileUpload.fromData(file, fileName))
            .queue()
    }

    protected fun replyFiles(hook: InteractionHook, title: String, description: String, files: List<NamedFile>) {
        hook.editOriginal("## $title\n$description")
            .setAttachments(files.map { FileUpload.fromData(it.bytes, if (it.name.contains("Vocals")) "Vocals.wav" else "Instrumental.wav") })
            .queue()
    }

    protected fun errorContainer(message: String): Container {
        return Container.of(
            TextDisplay.of("## エラー\n$message")
        ).withAccentColor(Color.RED)
    }
}

private class SeparateCommand(
    rvcApiClient: RvcApiClient,
) : RvcChildCommand(rvcApiClient) {
    init {
        name = "separate"
        help = "UVRでボーカルとインストに分離します"
        options = listOf(
            OptionData(OptionType.ATTACHMENT, "file", "分離する音声ファイル", true),
            OptionData(OptionType.STRING, "model_name", "UVRモデル名", false),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        deferAndQueue(event) { hook ->
            withTempAttachment(event) { file, _ ->
                val modelName = event.optString("model_name", RvcApiClient.DEFAULT_UVR_MODEL)
                    ?: RvcApiClient.DEFAULT_UVR_MODEL
                val response = runBlocking {
                    rvcApiClient.separate(file, modelName)
                }
                val files = unzipFiles(response)
                if (files.isEmpty()) {
                    hook.editOriginalComponents(errorContainer("分離結果のZIPを展開できませんでした"))
                        .useComponentsV2()
                        .queue()
                    return@withTempAttachment
                }
                replyFiles(
                    hook = hook,
                    title = "ボーカルとインストの分離が完了",
                    description = "",
                    files = files,
                )
            }
        }
    }
}

private class ModelsCommand(
    rvcApiClient: RvcApiClient,
) : RvcChildCommand(rvcApiClient) {
    init {
        name = "models"
        help = "利用可能なモデル一覧を表示します"
    }

    override fun execute(event: SlashCommandEvent) {
        deferAndQueue(event) { hook ->
            val models = runBlocking {
                rvcApiClient.listModels()
            }
            val rvcModels = readModelArray(models, "rvc")
            val uvrModels = readModelArray(models, "uvr")
            replyModels(
                hook = hook,
                rvcModels = rvcModels,
                uvrModels = uvrModels,
            )
        }
    }
}

private class ConvertCommand(
    rvcApiClient: RvcApiClient,
) : RvcChildCommand(rvcApiClient) {
    init {
        name = "convert"
        help = "RVCで音声を変換します"
        options = listOf(
            OptionData(OptionType.ATTACHMENT, "file", "変換する音声ファイル", true),
            OptionData(OptionType.STRING, "model_name", "RVCモデル名", true),
            OptionData(OptionType.INTEGER, "pitch", "ピッチ補正", false),
            OptionData(OptionType.NUMBER, "index_rate", "index_rate", false),
            OptionData(OptionType.NUMBER, "protect", "protect", false),
            OptionData(OptionType.STRING, "rvc_version", "RVCのバージョン", false),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        deferAndQueue(event) { hook ->
            val modelName = event.optString("model_name")
            if (modelName.isNullOrBlank()) {
                hook.editOriginalComponents(errorContainer("model_name is required"))
                    .useComponentsV2()
                    .queue()
                return@deferAndQueue
            }
            val resolvedModelName = modelName

            withTempAttachment(event) { file, _ ->
                val response = runBlocking {
                    rvcApiClient.convert(
                        file = file,
                        modelName = resolvedModelName,
                        pitch = event.optLong("pitch", 0).toInt(),
                        indexRate = event.optDouble("index_rate", 0.75),
                        protect = event.optDouble("protect", 0.33),
                        rvcVersion = event.optString("rvc_version", RvcApiClient.DEFAULT_RVC_VERSION) ?: RvcApiClient.DEFAULT_RVC_VERSION,
                    )
                }
                replyFile(
                    hook = hook,
                    title = "RVC変換完了",
                    description = "",
                    file = response,
                    fileName = outputFileName("convert", file),
                )
            }
        }
    }
}

private class CoverCommand(
    rvcApiClient: RvcApiClient,
) : RvcChildCommand(rvcApiClient) {
    init {
        name = "cover"
        help = "好きな曲をUVR分離してRVC変換し、カバー曲を作ります"
        options = listOf(
            OptionData(OptionType.ATTACHMENT, "file", "処理する音声ファイル", true),
            OptionData(OptionType.STRING, "rvc_model", "RVCモデル名", true),
            OptionData(OptionType.STRING, "uvr_model", "UVRモデル名", false),
            OptionData(OptionType.INTEGER, "pitch", "ピッチ補正", false),
            OptionData(OptionType.NUMBER, "index_rate", "index_rate", false),
            OptionData(OptionType.NUMBER, "protect", "protect", false),
            OptionData(OptionType.STRING, "rvc_version", "RVCのバージョン", false),
            OptionData(OptionType.NUMBER, "vocal_volume_adjust", "ボーカル音量補正", false),
        )
    }

    override fun execute(event: SlashCommandEvent) {
        deferAndQueue(event) { hook ->
            val rvcModel = event.optString("rvc_model")
            if (rvcModel.isNullOrBlank()) {
                hook.editOriginalComponents(errorContainer("rvc_model is required"))
                    .useComponentsV2()
                    .queue()
                return@deferAndQueue
            }
            val resolvedRvcModel = rvcModel

            withTempAttachment(event) { file, _ ->
                val response = runBlocking {
                    rvcApiClient.createCover(
                        file = file,
                        rvcModel = resolvedRvcModel,
                        uvrModel = event.optString("uvr_model", RvcApiClient.DEFAULT_UVR_MODEL) ?: RvcApiClient.DEFAULT_UVR_MODEL,
                        pitch = event.optLong("pitch", 0).toInt(),
                        indexRate = event.optDouble("index_rate", 0.75),
                        protect = event.optDouble("protect", 0.33),
                        rvcVersion = event.optString("rvc_version", RvcApiClient.DEFAULT_RVC_VERSION) ?: RvcApiClient.DEFAULT_RVC_VERSION,
                        vocalVolumeAdjust = event.optDouble("vocal_volume_adjust", 7.0),
                    )
                }
                replyFile(
                    hook = hook,
                    title = "カバー生成完了",
                    description = "",
                    file = response,
                    fileName = outputFileName("cover", file),
                )
            }
        }
    }
}

private fun outputFileName(prefix: String, file: Path): String {
    val original = file.nameWithoutExtension
    return "$prefix-$original.wav"
}

private data class NamedFile(
    val name: String,
    val bytes: ByteArray,
)

private fun unzipFiles(zipBytes: ByteArray): List<NamedFile> {
    val files = mutableListOf<NamedFile>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                val normalizedName = entry.name.replace('\\', '/').substringAfterLast('/')
                val name = if (normalizedName.isBlank()) "separate-${files.size + 1}.wav" else normalizedName
                files.add(NamedFile(name, zip.readBytes()))
            }
            zip.closeEntry()
        }
    }
    return files
}

private fun replyModels(
    hook: InteractionHook,
    rvcModels: List<String>,
    uvrModels: List<String>,
) {
    val body = buildString {
        append(renderModelSection("RVC", rvcModels))
        append("\n\n")
        append(renderModelSection("UVR", uvrModels))
    }.trim()

    hook.editOriginalComponents(
        Container.of(
            TextDisplay.of(body),
        ).withAccentColor(Color(0x36B8FA))
    ).useComponentsV2().queue()
}

private fun renderModelSection(title: String, models: List<String>): String {
    val lines = if (models.isEmpty()) {
        listOf("- なし")
    } else {
        models.map { "- $it" }
    }
    return buildString {
        append("## ")
        append(title)
        append('\n')
        append(lines.joinToString("\n"))
    }
}

private fun readModelArray(models: JsonElement, key: String): List<String> {
    val array = (models as? JsonObject)?.get("models")?.jsonObject?.get(key) as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
        .map { stripExtension(it) }
        .filter { it.isNotBlank() }
}

private fun stripExtension(value: String): String {
    val trimmed = value.trim()
    val lastDot = trimmed.lastIndexOf('.')
    return if (lastDot > 0 && lastDot > trimmed.lastIndexOf('/') && lastDot > trimmed.lastIndexOf('\\')) {
        trimmed.substring(0, lastDot)
    } else {
        trimmed
    }
}
