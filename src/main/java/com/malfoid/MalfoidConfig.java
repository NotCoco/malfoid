package com.malfoid;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup("malfoid")
public interface MalfoidConfig extends Config
{
    @ConfigItem(keyName = "showMalfoid", name = "Show Malfoid", position = 0,
        description = "Untick to hide her instantly. Tick to bring her back.")
    default boolean showMalfoid()
    {
        return true;
    }

    @ConfigItem(keyName = "showDialogue", name = "Show speech", position = 1,
        description = "Untick and she stays quiet but still follows and reacts.")
    default boolean showDialogue()
    {
        return true;
    }

    @Range(min = 90, max = 110)
    @Units(Units.PERCENT)
    @ConfigItem(keyName = "heightPercent", name = "Height", position = 2,
        description = "Adjust her height from 90% to 110%. She is hidden briefly while her poses rebuild.")
    default int heightPercent()
    {
        return 100;
    }

    @ConfigItem(keyName = "introShown", name = "Intro shown", description = "", hidden = true)
    default boolean introShown()
    {
        return false;
    }
}
