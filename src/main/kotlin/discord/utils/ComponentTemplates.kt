package jp.simplespace.discord.utils

import net.dv8tion.jda.api.components.container.Container
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import java.awt.Color

fun simpleTitleAndDescriptionContainer(title: String, description: String, color: Color? = null): Container {
    return Container.of(
        TextDisplay.of("## $title\n$description")
    ).withAccentColor(color)
}