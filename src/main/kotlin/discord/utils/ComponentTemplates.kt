package jp.simplespace.discord.utils

import dev.minn.jda.ktx.interactions.components.FileDisplay
import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.utils.FileUpload
import java.awt.Color

fun simpleTitleAndDescriptionContainer(title: String, description: String, color: Color? = null): Container {
    return Container.of(
        TextDisplay.of("## $title\n$description")
    ).withAccentColor(color)
}