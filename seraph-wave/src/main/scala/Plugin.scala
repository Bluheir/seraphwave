package xyz.stopthis;

import org.bukkit.plugin.java.JavaPlugin;

class Plugin extends JavaPlugin {
    override def onEnable(): Unit = {
        // onEnable
        System.out.println("Hello world!")
    }
    override def onDisable(): Unit = {
        // onDisable
        System.out.println("Goodbye world!")
    }
}