package com.malfoid;

import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

/** Exercise RuneLite's subscriber registration and inactive-session dispatch contract. */
public class MalfoidEventBusRegistrationTest
{
    @Test public void everyPluginSubscriberRegistersAndPuzzleEventsAreSafeWithoutASession()
    {
        EventBus eventBus = new EventBus(error -> { throw new AssertionError("RuneLite event dispatch failed", error); });
        MalfoidPlugin plugin = new MalfoidPlugin();
        eventBus.register(plugin);
        eventBus.post(new ScriptPreFired(688));
        eventBus.post(new ScriptPostFired(688));
        eventBus.post(new WidgetClosed(306, 0, true));
        WidgetLoaded loaded = new WidgetLoaded(); loaded.setGroupId(306);
        eventBus.post(loaded);
        eventBus.unregister(plugin);
    }
}
